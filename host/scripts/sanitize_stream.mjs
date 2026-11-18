// Turn a raw CLI capture into a committed fixture: project path -> {{CWD}}, home -> ~, drop hook/rate-limit noise,
// blank signatures. Usage: node scripts/sanitize_stream.mjs <raw.jsonl> <project dir> <out.jsonl> [--permission-before "node hello.js"]
import fs from 'node:fs'; import os from 'node:os';
const [raw, proj, out, flag, before] = process.argv.slice(2);
const BS = String.fromCharCode(92);
const variants = p => [p, p.split(BS).join('/')];
const esc = s => s.split(BS).join(BS + BS); // the JSON-escaped spelling
const home = os.homedir();
const lines = [];
for (const l of fs.readFileSync(raw, 'utf8').split('\n')) {
  if (!l.trim() || l.startsWith('>> ') || l.startsWith('[stderr]')) continue;
  let m = JSON.parse(l);
  if (m.type === 'system' && /^hook_/.test(m.subtype)) continue;
  if (m.type === 'rate_limit_event') continue;
  if (m.type === 'control_response' && m.response?.request_id === 'init_1') continue; // lists the user's local skills
  if (m.type === 'system' && m.subtype === 'init') m = { type: 'system', subtype: 'init', cwd: proj, session_id: m.session_id, tools: ['Bash', 'Edit', 'Read', 'Write'], model: '(model)' };
  if (m.type === 'item.completed' && m.item?.type === 'error') continue; // local config warnings
  let s = JSON.stringify(m).replace(/"signature":"[^"]*"/g, '"signature":""');
  for (const v of variants(proj)) s = s.split(esc(v)).join('{{CWD}}').split(v).join('{{CWD}}');
  for (const v of variants(home)) s = s.split(esc(v)).join('~').split(v).join('~');
  s = s.replace(new RegExp(os.userInfo().username, 'gi'), 'user');
  const o = JSON.parse(s);
  if (flag === '--permission-before' && o.type === 'assistant' && o.message.content.some(c => c.type === 'tool_use' && c.name === 'Bash' && c.input?.command === before))
    lines.push(JSON.stringify({ type: 'control_request', request_id: 'perm-fixture-1', request: { subtype: 'can_use_tool', tool_name: 'Bash', display_name: 'Bash', input: { command: before } } }));
  lines.push(JSON.stringify(o));
}
fs.writeFileSync(out, lines.join('\n') + '\n'); console.log(out, lines.length);
