"""Run capture.cmd through workerq and wait for it (run.cmd's Gradle step). Exit code is the job's."""
import json
import subprocess
import sys
from pathlib import Path

out = str(Path(sys.argv[1]).resolve())
root = Path(__file__).resolve().parent.parent.parent
cmd = ["workerq", "submit", "--project", "example-project", "--ram", "6", "--cpus", "4", "--gpus", "0", "--json",
       "--describe", "layout lint capture (Robolectric, every window)", "--eta", "5m",
       "--", "cmd.exe", "/c", r"tools\layout_lint\capture.cmd", out]
res = subprocess.run(cmd, cwd=root, capture_output=True, text=True)
print(res.stdout.strip())
if res.returncode:
    print(res.stderr, file=sys.stderr)
    sys.exit(res.returncode)
job = json.loads(res.stdout)
jid = str(job.get("id") or job.get("job_id"))
rc = subprocess.run(["workerq", "wait", jid], cwd=root).returncode
subprocess.run(["workerq", "logs", jid], cwd=root, stdout=open(Path(out) / "capture.log", "w", encoding="utf-8"))
sys.exit(rc)
