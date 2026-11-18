// npm run stats [-- fixture.jsonl ...]   -> validation result + DAG stats per fixture (all fixtures if none given)
import { readdirSync } from "node:fs";
import { resolve, basename } from "node:path";
import { FIXTURES_DIR, readJsonl, checkStream, dagStats, resolveFixture } from "./schema.ts";

const files = process.argv.slice(2).length
  ? process.argv.slice(2).map(resolveFixture)
  : readdirSync(FIXTURES_DIR).filter((f) => f.endsWith(".jsonl")).sort().map((f) => resolve(FIXTURES_DIR, f));
let bad = 0;
for (const f of files) {
  const evs = readJsonl(f);
  const probs = checkStream(evs);
  if (probs.length) bad++;
  console.log(JSON.stringify({ fixture: basename(f), valid: probs.length === 0, ...dagStats(evs), problems: probs.slice(0, 5) }));
}
process.exit(bad ? 1 : 0);
