#!/usr/bin/env node
// Layout lint (#773), Live mode's WebView windows. Two steps around the Gradle capture:
//   node tools/layout_lint/web_capture.mjs --fetch  <out>   saves the server's dashboard to <out>/web/classic.html
//   node tools/layout_lint/web_capture.mjs --render <out>   renders <out>/web/<view>.html (built by LayoutLintCapture
//                                                            exactly like Dashboards.kt injects it) in headless Chromium
// at the panel's 1280x800 dp and 1.8 device pixel ratio (288 dpi), against the same server, and writes
// <out>/live_<view>.png + .json in the lint's element schema. Content is live, so these windows are reported, not gated.
import { createRequire } from 'node:module';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
const mode = args[0], out = args[1];
const server = (args[2] || process.env.LAYOUT_LINT_SERVER || 'http://127.0.0.1:8790').replace(/\/$/, '');
const web = path.join(out, 'web');
mkdirSync(web, { recursive: true });

if (mode === '--fetch') {
  try {
    const r = await fetch(server + '/classic', { signal: AbortSignal.timeout(5000) });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    writeFileSync(path.join(web, 'classic.html'), await r.text());
    console.log('web_fetch=ok ' + server);
  } catch (e) {
    console.log(`web_fetch=skipped (${server} unreachable: ${e.message}); live WebView windows will not be rendered`);
  }
  process.exit(0);
}

const listing = path.join(web, 'windows.json');
if (!existsSync(listing)) { console.log('web_render=skipped (no documents)'); process.exit(0); }
const NM = '/opt/flowstate/Documents/example-project/web/agentdeck/node_modules';
const { chromium } = createRequire(`${NM}/playwright/package.json`)('playwright');
const browser = await chromium.launch({ headless: true });
let rendered = 0;
for (const w of JSON.parse(readFileSync(listing, 'utf8'))) {
  const ctx = await browser.newContext({ viewport: { width: w.width_dp, height: w.height_dp }, deviceScaleFactor: w.density });
  const page = await ctx.newPage();
  const doc = readFileSync(path.join(web, `${w.view}.html`), 'utf8');
  await page.route('**/*', (route) => {
    const req = route.request();
    if (req.isNavigationRequest() && req.url().startsWith(server + '/classic')) return route.fulfill({ contentType: 'text/html', body: doc });
    return route.continue();
  });
  try {
    await page.goto(server + '/classic', { waitUntil: 'load', timeout: 15000 });
    await page.waitForFunction(() => window.afWindowAudit && document.documentElement.dataset.afWindow, null, { timeout: 8000 }).catch(() => {});
    await page.waitForTimeout(2500);
    const name = `live_${w.view}`;
    await page.screenshot({ path: path.join(out, name + '.png') });
    const elements = await page.evaluate(extract, w.density);
    writeFileSync(path.join(out, name + '.json'), JSON.stringify({
      window: name, kind: 'live-webview', source: 'Dashboards.kt WebView (/classic + windows.js), headless Chromium',
      width_px: Math.round(w.width_dp * w.density), height_px: Math.round(w.height_dp * w.density), width_dp: w.width_dp,
      height_dp: w.height_dp, density: w.density, width_m: w.width_m, m_per_px: w.width_m / (w.width_dp * w.density),
      distance_m: w.distance_m, grid: false, elements }, null, 1));
    rendered++;
  } catch (e) { console.log(`web_render ${w.view} failed: ${e.message}`); }
  await ctx.close();
}
await browser.close();
console.log(`web_rendered=${rendered}`);

// Runs in the page. Device-pixel geometry for every visible element, in LayoutLintCapture's schema.
function extract(dpr) {
  const els = [], ids = new Map();
  const px = (v) => Math.round(v * dpr);
  const rect = (r) => [px(r.left), px(r.top), px(r.right), px(r.bottom)];
  const view = [0, 0, px(innerWidth), px(innerHeight)];
  const hex = (c) => { const m = c.match(/[\d.]+/g) || [0, 0, 0, 1]; const a = m.length > 3 ? +m[3] : 1;
    return '#' + [a * 255, m[0], m[1], m[2]].map((v) => Math.round(+v).toString(16).padStart(2, '0')).join('').toUpperCase(); };
  const isAlnum = (c) => /[\p{L}\p{N}]/u.test(c || '');
  const walk = (node, parent, clip) => {
    const cs = getComputedStyle(node);
    if (cs.display === 'none' || cs.visibility === 'hidden' || +cs.opacity === 0) return;
    const r = node.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) { for (const c of node.children) walk(c, parent, clip); return; }
    const box = rect(r);
    const vis = [Math.max(box[0], clip[0]), Math.max(box[1], clip[1]), Math.min(box[2], clip[2]), Math.min(box[3], clip[3])];
    const shown = vis[2] > vis[0] && vis[3] > vis[1];
    if (!shown) return;
    const index = els.length;
    const scroll = /(auto|scroll)/.test(cs.overflowX + cs.overflowY);
    const tag = node.tagName.toLowerCase();
    const clickable = !!(node.closest('a,button,select,input,textarea,[role=button],[onclick]') === node || node.onclick || cs.cursor === 'pointer');
    const e = { index, parent, name: `${tag}${node.id ? '#' + node.id : ''}${node.className && typeof node.className === 'string' ? '.' + node.className.trim().split(/\s+/).slice(0, 2).join('.') : ''}@${index}`,
      class: 'dom:' + tag, tag: node.getAttribute('data-lint') || null, rect: box, visible_rect: vis, clickable,
      scroll_container: scroll, alpha: +cs.opacity,
      orientation: cs.display.includes('flex') ? (cs.flexDirection.startsWith('row') ? 'horizontal' : 'vertical') : (cs.display === 'block' ? 'vertical' : undefined) };
    const own = [...node.childNodes].filter((n) => n.nodeType === 3 && n.textContent.trim());
    if (own.length) {
      const text = own.map((n) => n.textContent).join('');
      const zoom = node.currentCSSZoom || 1;
      e.text = text.replace(/\s+/g, ' ').trim().slice(0, 400);
      e.name = `${e.name}'${e.text.slice(0, 40)}'`;
      e.text_size_px = parseFloat(cs.fontSize) * zoom * dpr;
      e.text_color = hex(cs.color);
      e.gravity_h = cs.textAlign === 'center' ? 'center' : (cs.textAlign === 'right' || cs.textAlign === 'end') ? 'end' : 'start';
      // Lines and mid-word breaks, from per-character client rects of each own text node.
      const lines = []; const breaks = []; let offset = 0;
      for (const n of own) {
        const s = n.textContent; let top = null, line = null;
        for (let i = 0; i < Math.min(s.length, 600); i++) {
          if (!s[i].trim()) continue;
          const rg = document.createRange(); rg.setStart(n, i); rg.setEnd(n, i + 1);
          const cr = rg.getClientRects()[0]; if (!cr) continue;
          if (top === null || Math.abs(cr.top - top) > cr.height / 2) {
            if (line && isAlnum(s[i - 1]) && isAlnum(s[i])) breaks.push(i + offset);
            line = { start: i + offset, end: i + offset + 1, left: cr.left * dpr, right: cr.right * dpr, top: cr.top * dpr, bottom: cr.bottom * dpr, baseline: cr.bottom * dpr, ellipsis: 0 };
            lines.push(line); top = cr.top;
          } else { line.end = i + offset + 1; line.right = Math.max(line.right, cr.right * dpr); }
        }
        offset += s.length;
      }
      e.lines = lines; e.web_breaks = breaks;
      const clipReasons = [];
      if (cs.textOverflow === 'ellipsis' && node.scrollWidth > node.clientWidth + 1) clipReasons.push('text-overflow: ellipsis is active');
      if (/(hidden|clip)/.test(cs.overflowX) && node.scrollWidth > node.clientWidth + 1) clipReasons.push(`${node.scrollWidth - node.clientWidth} css px cut off horizontally`);
      if (/(hidden|clip)/.test(cs.overflowY) && node.scrollHeight > node.clientHeight + 1) clipReasons.push(`${node.scrollHeight - node.clientHeight} css px cut off vertically`);
      if (+cs.webkitLineClamp > 0 && node.scrollHeight > node.clientHeight + 1) clipReasons.push('line-clamp truncates');
      if (clipReasons.length) e.clip_reasons = clipReasons;
    }
    els.push(e);
    const childClip = /(hidden|clip|auto|scroll)/.test(cs.overflowX + cs.overflowY) ? vis : clip;
    for (const c of node.children) walk(c, index, childClip);
  };
  walk(document.body, -1, view);
  return els;
}
