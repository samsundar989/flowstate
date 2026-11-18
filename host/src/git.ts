// Git for the host: one repository per project on branch main, one worktree + branch per task, merge on success.
import { spawnSync } from "node:child_process";
import { existsSync, appendFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";

export interface GitResult { status: number | null; stdout: string; stderr: string }
export function git(cwd: string, ...args: string[]): GitResult {
  // A stranger's machine may have no git identity; only then use a neutral one for host-made commits.
  const id = hasIdentity() ? [] : ["-c", "user.name=Flowstate host", "-c", "user.email=host@flowstate.invalid"];
  const r = spawnSync("git", [...id, ...args], { cwd, encoding: "utf8", windowsHide: true });
  return { status: r.status, stdout: (r.stdout ?? "").trim(), stderr: (r.stderr ?? "").trim() };
}
let identity: boolean | undefined;
function hasIdentity() {
  if (identity === undefined) identity = spawnSync("git", ["config", "user.email"], { encoding: "utf8", windowsHide: true }).stdout?.trim() !== "";
  return identity;
}

export const WORKTREES = ".worktrees";
export const branchOf = (task_id: string) => `task/${task_id}`;
export const worktreeRel = (task_id: string) => `${WORKTREES}/${task_id}`;

/** New project folder: a git repo on main with one empty commit (worktrees need a base), worktrees folder ignored. */
export function initProject(dir: string) {
  mkdirSync(dir, { recursive: true });
  if (!existsSync(join(dir, ".git"))) {
    git(dir, "init", "-q", "-b", "main");
    appendFileSync(join(dir, ".git", "info", "exclude"), `\n${WORKTREES}/\n`);
    git(dir, "commit", "-q", "--allow-empty", "-m", "Project created");
  }
}

/** The task's worktree, created off main if missing (or re-attached to its branch after a restart). */
export function ensureWorktree(projectDir: string, task_id: string): string {
  const wt = join(projectDir, WORKTREES, task_id), br = branchOf(task_id);
  if (existsSync(join(wt, ".git"))) return wt;
  git(projectDir, "worktree", "prune");
  const hasBranch = git(projectDir, "rev-parse", "--verify", "--quiet", br).status === 0;
  const r = hasBranch ? git(projectDir, "worktree", "add", "-q", wt, br) : git(projectDir, "worktree", "add", "-q", "-b", br, wt, "main");
  if (r.status !== 0) throw new Error(`worktree add failed: ${r.stderr}`);
  return wt;
}

/** If the agent left changes uncommitted (one CLI's sandbox keeps .git read-only), the host commits them. */
export function commitIfDirty(cwd: string, message: string): { commit?: string; host_committed: boolean } {
  let host_committed = false;
  if (git(cwd, "status", "--porcelain").stdout) { git(cwd, "add", "-A"); host_committed = git(cwd, "commit", "-q", "-m", message).status === 0; }
  const head = git(cwd, "rev-parse", "--short", "HEAD");
  return { commit: head.status === 0 ? head.stdout : undefined, host_committed };
}

export type MergeResult = { merged: true; commit: string; files: string[] } | { merged: false; reason: "no_changes" | "merge_conflict"; commit?: string; files: string[] };
/** Bring the task branch into main: fast-forward when possible, else a merge commit; on conflict, abort and leave main as it was. */
export function mergeTask(projectDir: string, task_id: string): MergeResult {
  const br = branchOf(task_id);
  const files = git(projectDir, "diff", "--name-only", `main...${br}`).stdout.split("\n").filter(Boolean);
  const ahead = Number(git(projectDir, "rev-list", "--count", `main..${br}`).stdout || "0");
  const head = () => git(projectDir, "rev-parse", "--short", "HEAD").stdout;
  if (ahead === 0) return { merged: false, reason: "no_changes", commit: head(), files };
  if (git(projectDir, "merge", "-q", "--ff-only", br).status === 0) return { merged: true, commit: head(), files };
  if (git(projectDir, "merge", "-q", "--no-edit", br).status === 0) return { merged: true, commit: head(), files };
  git(projectDir, "merge", "--abort");
  return { merged: false, reason: "merge_conflict", files };
}

export function removeWorktree(projectDir: string, task_id: string) {
  git(projectDir, "worktree", "remove", "--force", join(projectDir, WORKTREES, task_id));
}

// ---- #972 preview files: read one tracked file at one commit on main, never the working folder ----------------------
export type FileAt = { ok: true; commit: string; bytes: Buffer } | { ok: false; code: "not_found" | "too_large" | "bad_path" };
/** The file `path` as tracked at `commit`, where `commit` is hex and reachable from main. Symlinks, folders and anything
 *  untracked are refused; the content comes from the object store (`git cat-file`), so the worktree is never read. */
export function fileAtCommit(projectDir: string, commit: string, path: string, maxBytes: number, safe: RegExp): FileAt {
  if (!safe.test(path) || path.length > 200) return { ok: false, code: "bad_path" };
  if (!/^[0-9a-f]{7,40}$/.test(commit)) return { ok: false, code: "not_found" };
  const full = git(projectDir, "rev-parse", "--verify", "--quiet", `${commit}^{commit}`);
  if (full.status !== 0 || !/^[0-9a-f]{40}$/.test(full.stdout)) return { ok: false, code: "not_found" };
  if (git(projectDir, "merge-base", "--is-ancestor", full.stdout, "main").status !== 0) return { ok: false, code: "not_found" };
  // `--full-tree` and `--` keep the lookup at the repository root and stop the path being read as an option.
  const row = git(projectDir, "ls-tree", "--full-tree", full.stdout, "--", path).stdout.split("\n").filter(Boolean);
  const m = row.length === 1 ? row[0].match(/^(100644|100755) blob ([0-9a-f]{40})\t(.+)$/) : null;
  if (!m || m[3] !== path) return { ok: false, code: "not_found" };
  const size = Number(git(projectDir, "cat-file", "-s", m[2]).stdout);
  if (!(size <= maxBytes)) return { ok: false, code: "too_large" };
  const r = spawnSync("git", ["cat-file", "blob", m[2]], { cwd: projectDir, windowsHide: true, maxBuffer: maxBytes + 1024 });
  if (r.status !== 0) return { ok: false, code: "not_found" };
  return { ok: true, commit: full.stdout, bytes: r.stdout as Buffer };
}

// ---- #1543 an existing repository, added where it is -----------------------------------------------------------------
// The person's repository is never moved, copied or changed in place: its checked-out branch, index and working files stay
// as they were. A task gets a new branch, checked out in a separate worktree outside the repository, and is never merged,
// pushed or forced; the branch is left for the person to review.

/** The repository's top folder and HEAD commit, or why it cannot be added. */
export function repoInfo(path: string): { ok: true; top: string; head: string } | { ok: false; why: string } {
  if (!existsSync(path)) return { ok: false, why: `${path} does not exist.` };
  const top = git(path, "rev-parse", "--show-toplevel");
  if (top.status !== 0 || !top.stdout) return { ok: false, why: `${path} is not inside a git repository (with a working folder). Run git init there first, or pick the repository's folder.` };
  const head = git(path, "rev-parse", "--verify", "--quiet", "HEAD^{commit}");
  if (head.status !== 0) return { ok: false, why: `${top.stdout} has no commits yet. Make a first commit there, then add it again.` };
  return { ok: true, top: top.stdout, head: head.stdout };
}

/** The last `n` commits on HEAD, newest first: short hash, unix time, subject. */
export function recentCommits(repo: string, n: number): { commit: string; short: string; time: number; subject: string }[] {
  return git(repo, "log", `-n${n}`, "--format=%H%x09%h%x09%ct%x09%s", "HEAD").stdout.split("\n").filter(Boolean)
    .map(l => { const [commit, short, time, ...s] = l.split("\t"); return { commit, short, time: Number(time), subject: s.join("\t") }; });
}

/** A branch name for a task that no branch in the repository uses yet: flowstate/<task>, then flowstate/<task>-2, ... */
export function freeBranch(repo: string, task_id: string): string {
  const exists = (b: string) => git(repo, "rev-parse", "--verify", "--quiet", `refs/heads/${b}`).status === 0;
  let b = `flowstate/${task_id}`, i = 2;
  while (exists(b)) b = `flowstate/${task_id}-${i++}`;
  return b;
}

/** The task's worktree at `wt` on `branch` (new, off `base`), or re-attached to that branch after a restart. No prune, no force. */
export function ensureRepoWorktree(repo: string, wt: string, branch: string, base: string): string {
  if (existsSync(join(wt, ".git"))) return wt;
  const has = git(repo, "rev-parse", "--verify", "--quiet", `refs/heads/${branch}`).status === 0;
  const r = has ? git(repo, "worktree", "add", "-q", wt, branch) : git(repo, "worktree", "add", "-q", "-b", branch, wt, base);
  if (r.status !== 0) throw new Error(`worktree add failed: ${r.stderr}`);
  return wt;
}
