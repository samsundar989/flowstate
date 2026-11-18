// Displayed-text gate; copied from agents/bridge/src/brands.ts. Keep the list here only.
(() => {
  const deny = [
    'redis','milvus','qdrant','pinecone','weaviate','chroma','github',
    'gitlab','slack','aws','s3','azure','gcp','google','vercel','netlify',
    'openai','gpt','claude','anthropic','gemini','docker','kubernetes',
    'stripe','datadog','sentry','notion','jira','supabase','firebase',
    'cloudflare','heroku','elasticsearch','kafka','snowflake','databricks',
    'huggingface','llama','mistral','meta','oculus','quest','horizon',
    'pgvector','lancedb','excel','firefox','chromium','webkit',
    'gh','gh-pages','gh pages','devpost','ngrok','fly.io','itch.io',
    // Dashboard-specific aliases in addition to the shared gate.
    'tailscale','codex','chatgpt','vscode','vs code','gemma','gemma4','fable','opus','sonnet','haiku','kaggle','unity','vllm','todowrite','android','31b','12b','e4b','5090','rtx','nvidia'
  ];
  const pattern = '\\b(?:' + [...deny].sort((a,b) => b.length-a.length)
    .map(s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|') + ')\\b';
  const matches = text => [...String(text).matchAll(new RegExp(pattern, 'gi'))].map(m => m[0]);
  const clean = text => String(text).replace(new RegExp(pattern, 'gi'), 'agent');
  const audit = window.afWindowAudit = {deny, matches, before: [], replaced: 0, frozen: false};
  function scrub(root) {
    const elements = root.nodeType === 1 ? [root, ...root.querySelectorAll('*')] : [];
    for (const e of elements) {
      if (['SCRIPT','STYLE','NOSCRIPT'].includes(e.tagName)) continue;
      const labels = ['alt','title','aria-label','placeholder'];
      if (e.matches('img,svg,image,use') && matches(labels.map(a => e.getAttribute(a)||'')
          .concat([e.getAttribute('src')||'',e.getAttribute('href')||'',e.getAttribute('class')||'',e.id||'',
            e.querySelector(':scope > title')?.textContent||'']).join(' ')).length) {
        e.style.setProperty('display','none','important');
      }
      for (const a of labels) {
        const v = e.getAttribute(a);
        if (v && matches(v).length) e.setAttribute(a, clean(v));
      }
      // Value properties are displayed but must preserve an in-progress user draft.
      if (e.matches('input[type=button],input[type=submit]') && matches(e.value).length) e.value = clean(e.value);
    }
    const nodes = root.nodeType === 3 ? [root] : [];
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) nodes.push(walker.currentNode);
    for (const n of nodes) {
      if (n.parentElement?.closest('script,style,noscript,textarea,input')) continue;
      const hits = matches(n.nodeValue);
      if (hits.length) {
        if(n.parentElement?.tagName.toLowerCase()==='title' && n.parentElement.parentElement?.tagName.toLowerCase()==='svg')
          n.parentElement.parentElement.style.setProperty('display','none','important');
        if(audit.before.length<256) audit.before.push(...hits);
        audit.replaced += hits.length; n.nodeValue = clean(n.nodeValue);
      }
    }
  }
  // Installed at document start. MutationObserver runs before the next paint, including SSE updates.
  const observer = new MutationObserver(records => {
    observer.disconnect();
    for (const r of records) {
      if (r.type === 'childList') r.addedNodes.forEach(scrub);
      else scrub(r.target);
    }
    observe();
  });
  const observe = () => observer.observe(document, {subtree:true,childList:true,characterData:true,
    attributes:true,attributeFilter:['title','alt','aria-label','placeholder','src','href','class']});
  observe();
  // Freeze only this WebView's timers and stream. Do not use pauseTimers(): it pauses ALL WebViews.
  const intervals = new Set(), timeouts = new Set(), streams = new Set();
  let frozen = false;
  const nativeInterval = window.setInterval.bind(window), nativeTimeout = window.setTimeout.bind(window);
  window.setInterval = (...a) => { if(frozen)return 0; const id = nativeInterval(...a); intervals.add(id); return id; };
  window.setTimeout = (...a) => { if(frozen)return 0; const id = nativeTimeout(...a); timeouts.add(id); return id; };
  const Source = window.EventSource;
  // Display vocabulary only: leave upstream wire states and decision IDs unchanged.
  const statusWords = window.afStatusWords = Object.freeze({working:'working', attached:'working',
    waiting:'queued', rotating:'queued', parked:'queued', idle:'done', needs:'needs you',
    failed:'failed', canceled:'canceled', cancelled:'canceled'});
  const isFixture = row => /^\s*\[Overhand test \d+\]/i.test(row.question || row.title || '');
  // Each shared window belongs to the focused station. Filter both initial state and SSE.
  const projectState = state => {
    state = {...state};
    if (Array.isArray(state.decisions)) state.decisions = state.decisions.filter(row => !isFixture(row));
    if (Array.isArray(state.agents)) state.agents = state.agents.map(a => ({...a,
      summary: /^waiting in .+ for its next wake(?: \(no Enter needed\))?(?:\. To type to it there, press Esc in its tab first, or message it from here)?$/i.test(a.summary || '')
        ? 'queued' : a.summary}));
    const project = window.AF_PROJECT;
    if (!project || !Array.isArray(state.projects)) return state;
    const copy = {...state};
    copy.projects = state.projects.filter(p => p.id === project);
    for (const key of ['agents','tasks','decisions','events'])
      if (Array.isArray(state[key])) copy[key] = state[key].filter(row => row.project === project);
    for (const key of ['build','plans'])
      if (state[key]) copy[key] = {[project]: state[key][project]};
    return copy;
  };
  const fetchState = window.fetch.bind(window);
  window.fetch = async (...args) => {
    const response = await fetchState(...args);
    const url = new URL(typeof args[0] === 'string' ? args[0] : args[0].url, location.href);
    if (url.origin === location.origin && url.pathname === '/api/state' && response.ok)
      return new Response(JSON.stringify(projectState(await response.json())),
        {status:response.status, headers:response.headers});
    return response;
  };
  window.EventSource = class extends Source {
    constructor(...a) { super(...a); if(frozen)this.close(); else streams.add(this); }
    set onmessage(handler) {
      super.onmessage = handler ? event => {
        let data = event.data;
        try { data = JSON.stringify(projectState(JSON.parse(data))); } catch {}
        handler.call(this, new MessageEvent('message', {data, lastEventId:event.lastEventId, origin:event.origin}));
      } : null;
    }
    get onmessage() { return super.onmessage; }
  };
  window.afFreeze = () => {
    frozen = true;
    audit.frozen = true;
    intervals.forEach(clearInterval); timeouts.forEach(clearTimeout); streams.forEach(s => s.close());
    document.getAnimations().forEach(a => a.pause());
    const style = document.createElement('style');
    style.textContent = '* { animation-play-state:paused!important; transition:none!important; }';
    document.head.append(style);
  };
  document.addEventListener('DOMContentLoaded', () => {
    document.documentElement.dataset.afWindow = window.AF_WINDOW;
    const style = document.createElement('style');
    style.textContent = `
      body { zoom:${window.AF_ZOOM || 3.5}; min-width:0!important; }
      body * { font-size:${28/(window.AF_ZOOM || 3.5)}px!important; }
      h1,h2,h3 { font-size:${36/(window.AF_ZOOM || 3.5)}px!important; }
      small,.chip { font-size:${22/(window.AF_ZOOM || 3.5)}px!important; }
      button,input,textarea { font-size:${32/(window.AF_ZOOM || 3.5)}px!important; }
      button:hover,button:focus-visible { outline:2px solid #9fcfff; background:#2b4e67!important; }
      button { transition:background-color 120ms,transform 120ms; }
      button:active { transform:scale(.98); }
      body { overflow-wrap:anywhere; }
      .grid { display:block!important; padding:8px!important; }
      .bar,.side,.log,.seg { display:none!important; }
      .grid>section { min-width:0!important; }
      .grid>section:first-child { display:${window.AF_WINDOW === 'chat' ? 'block' : 'none'}!important; }
      .grid>section:nth-child(2) { display:${window.AF_WINDOW === 'chat' ? 'none' : 'block'}!important; }
      button,input,textarea { min-height:${128/(window.AF_ZOOM || 3.5)}px; }
      button { margin:${16/(window.AF_ZOOM || 3.5)}px!important; }
      button { white-space:normal!important; padding:3px 6px!important; line-height:1.2!important; }
      .drawer { display:flex!important; flex-direction:column!important; }
      .dh { flex:none; max-height:28%; overflow:auto; padding:6px!important; }
      .dh>.row:not(:first-child) { display:none!important; }
      .dh .row { gap:4px!important; }
      .dh .chip { display:none!important; }
      .db { flex:1; min-height:0; padding:6px!important; }
      .df { flex:none; max-height:30%; overflow:auto; flex-wrap:wrap; padding:4px!important; gap:4px!important; }
      .df textarea { height:24px!important; }
      #af-heads { padding:8px; display:flex; flex-wrap:wrap; gap:6px; }
      #af-heads button { max-width:100%; }
    `;
    document.head.append(style);
    // Optional look from Theme.kt (window.AF_THEME, dp values). Absent = page exactly as before.
    const T = window.AF_THEME;
    if (T) {
      const px = dp => (dp/(window.AF_ZOOM || 3.5)).toFixed(3)+'px';
      const theme = document.createElement('style'); theme.id = 'af-theme';
      theme.textContent = `
      :root { --r:${px(T.controlRadius)}; --void:transparent; --hull:${T.panelFill}; --deck:${T.raisedFill};
        --deck-hi:${T.liftedFill}; --seam:${T.hairlineColor}; --seam-hi:${T.hairlineColor}; --ion:${T.accent};
        --ink:${T.text}; --af-glow:${px(T.glow)}; --af-rim:${px(T.rim)}; --af-hover:${T.hoverGlow};
        --af-selected:${T.selectedGlow}; --af-pressed:${T.pressedFill}; --af-panel-r:${px(T.panelRadius)};
        --af-edge:${px(T.edge||1)}; --af-edge-c:${T.edgeColor||T.hairlineColor}; --af-edge-top:${T.edgeTop||T.hairlineColor};
        --af-tile-r:${px(T.tileRadius||T.controlRadius)}; --af-caption:${T.captionText||T.text}; }
      html,body,main,.grid { background:transparent!important; }
      body * { font-size:${px(T.body)}!important; }
      h1,h2,h3 { font-size:${px(T.title)}!important; }
      small,.chip { font-size:${px(T.caption)}!important; }
      button,input,textarea { font-size:${px(T.control)}!important; }
      .panel,.card,.lane,.job,.gamemenu,.drawer { border-radius:var(--af-panel-r)!important; overflow:hidden; }
      .panel::before,.panel::after { display:none!important; }
      .opt.rec::after { content:none!important; }
      .opt.rec { box-shadow:0 0 var(--af-glow) rgba(255,181,71,.35),inset 0 0 0 1px rgba(255,181,71,.6); }
      .panel { border:var(--af-edge) solid var(--af-edge-c)!important; border-top-color:var(--af-edge-top)!important;
        background:var(--hull)!important; color:var(--ink)!important; }
      .panel small,.panel .chip,.panel .sub,.panel .meta { color:var(--af-caption)!important; }
      #af-heads button,.btn { background:var(--deck); border:1px solid var(--seam); color:var(--ink); }
      .tile,.crew,.opt,.chip,.btn,.modebtn,button,input,textarea { border-radius:var(--r)!important; }
      .tile,.crew,textarea { border-radius:var(--af-tile-r)!important; }
      .grid>section { padding:${px(T.spaceM)}!important; }
      .tiles,.stack,.opts,#af-heads { gap:${px(T.spaceS)}!important; }
      button,.btn,.tile,.crew,.opt,.modebtn { outline:none!important;
        transition:background-color ${T.hoverOut}ms,box-shadow ${T.hoverOut}ms,transform ${T.release}ms!important; }
      button:hover,button:focus-visible,.btn:hover,.tile:hover,.crew:hover,.opt:hover,.modebtn:hover {
        outline:none!important; background:${T.raisedFill}!important;
        box-shadow:0 0 var(--af-glow) var(--af-hover),inset 0 0 0 1px var(--af-hover)!important;
        transition-duration:${T.hoverIn}ms!important; }
      [aria-pressed="true"],[aria-selected="true"],.cp-chip.on {
        background:var(--deck-hi)!important; color:var(--ink)!important;
        box-shadow:0 0 var(--af-glow) var(--af-selected),inset 0 0 0 var(--af-rim) var(--ion)!important; }
      .cpm.sel .frame { stroke:var(--ion)!important; filter:drop-shadow(0 0 2px var(--ion)); }
      button:active,.btn:active,.opt:active { transform:scale(.98); background:var(--af-pressed)!important;
        transition-duration:${T.press}ms!important; }
      `;
      document.head.append(theme);
    }
    const view =window.AF_WINDOW === 'chat' ? 'fleet' : window.AF_WINDOW;
    document.querySelector('[data-view="'+view+'"]')?.click();
    document.title = window.AF_WINDOW;
    if (window.AF_WINDOW === 'chat') {
      const heads = document.createElement('div'); heads.id = 'af-heads';
      document.querySelector('main').prepend(heads);
      fetch('/api/state').then(r => {if(!r.ok)throw Error();return r.json();}).then(s => {
        for (const a of s.agents.filter(a => a.role === 'head' || a.id.endsWith(':head'))) {
          const b = document.createElement('button'); b.type = 'button'; b.dataset.agent = a.id;
          b.textContent = a.project || a.id.split(':')[0]; heads.append(b);
        }
        window.afChatReady = true;
      }).catch(() => { heads.textContent='Head list unavailable; reconnect in Settings'; window.afChatReady=true; });
    }
    scrub(document.documentElement);
  });
})();
/* look K3 */
// #1374: Fleet rows and Blueprint tiles (look_nov17 s5) drawn from the recorded day's snapshot and architecture.
// Sizes are dp (the page is 1280 css px wide = the 1280 dp window). Values cite look_nov17 s2 and Fleet/Blueprint.dc.html.
(() => {
  const C = {cyan:'#5CE1E6', amber:'#FFB547', green:'#45D99A', red:'#FF6B6B', text:'#F2F6FA', dark:'#06222A', // s2 table
    cap:'rgba(255,255,255,.7)', plan:'rgba(255,255,255,.55)', edge:'rgba(255,255,255,.22)', rule:'rgba(255,255,255,.1)',
    tile:'rgba(255,255,255,.07)', tileEdge:'rgba(255,255,255,.12)', pick:'rgba(92,225,230,.35)', needTint:'rgba(255,181,71,.1)'};
  // Wire states (architecture `shown`/`status`) -> one of the s2 status marks. `dropped` has no mark of its own in s2:
  // it draws as Planned (not built) with its own word.
  const STATE = {planned:'planned', dropped:'planned', ready:'planned', building:'building', built:'built',
    verified:'done', done:'done', needs:'needs', failed:'error', error:'error', blocked:'error'};
  const WORD = {planned:'Planned', building:'Building', built:'Built', needs:'Needs you', done:'Done', error:'Error'};
  const ESC = {'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'};
  const esc = s => String(s).replace(/[&<>"']/g, c => ESC[c]);
  const D = (s, cls = '', tag = 'span') => `<${tag} class="${cls}" data-d>${esc(s)}</${tag}>`; // text from data
  let uid = 0;
  /** The seven s2 marks as inline SVG: planned, building, built, needs, done, error, worker (n = slot number). */
  function mark(kind, w = 30, h = w, n = '') {
    const open = `<svg class="k3-mark" data-mark="${kind}" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}" aria-hidden="true">`;
    const r = Math.min(8, h / 3), box = `x="1" y="1" width="${w - 2}" height="${h - 2}" rx="${r}"`;
    if (kind === 'planned') return open + `<rect ${box} fill="none" stroke="${C.plan}" stroke-width="2" stroke-dasharray="5 4"/></svg>`;
    if (kind === 'built') return open + `<rect ${box} fill="${C.cyan}"/></svg>`;
    if (kind === 'building') { const id = 'k3h' + (++uid);
      return open + `<defs><pattern id="${id}" width="8" height="8" patternUnits="userSpaceOnUse" patternTransform="rotate(135)">` +
        `<rect width="3" height="8" fill="${C.cyan}"/></pattern></defs><rect ${box} fill="url(#${id})" stroke="${C.cyan}" stroke-width="2"/></svg>`; }
    if (kind === 'needs') return open + `<path d="M${w / 2} 2L${w - 1} ${h - 2}H1Z" fill="${C.amber}"/>` +
      `<path d="M${w / 2} ${h * .38}V${h * .62}M${w / 2} ${h * .76}v.5" stroke="${C.dark}" stroke-width="3" stroke-linecap="round"/></svg>`;
    if (kind === 'done') return open + `<circle cx="${w / 2}" cy="${h / 2}" r="${Math.min(w, h) / 2 - 1}" fill="${C.green}"/>` +
      `<path d="M${w * .3} ${h * .52}l${w * .14} ${h * .14} ${w * .26}-${h * .3}" fill="none" stroke="${C.dark}" stroke-width="3" stroke-linecap="round"/></svg>`;
    if (kind === 'error') { const a = Math.min(w, h), k = a * .29, p = [[k, 1], [a - k, 1], [a - 1, k], [a - 1, a - k], [a - k, a - 1], [k, a - 1], [1, a - k], [1, k]];
      return open + `<path d="M${p.map(q => q.join(' ')).join('L')}Z" fill="${C.red}"/></svg>`; }
    if (kind === 'worker') return open + `<path d="M${w / 2} 0L${w} ${h / 4}V${h * 3 / 4}L${w / 2} ${h}L0 ${h * 3 / 4}V${h / 4}Z" fill="${C.cyan}"/>` +
      `<text x="${w / 2}" y="${h / 2}" text-anchor="middle" dominant-baseline="central" fill="${C.dark}" class="k3-n">${esc(n)}</text></svg>`;
    throw Error('no mark for ' + kind);
  }
  /** The recorded day as the two views read it. */
  function model(snapshot, arch) {
    const plans = Object.fromEntries((arch?.projects || []).map(p => [p.id, p.components || []]));
    const open = (snapshot.decisions || []).filter(d => d.status === 'open');
    return (snapshot.projects || []).map(p => {
      const comps = plans[p.id] || [], ids = new Set(comps.map(c => c.id));
      const waiting = new Set(open.filter(d => d.project === p.id).map(d => d.component || d.part).filter(Boolean));
      const parts = comps.map(c => {
        const raw = c.shown || c.status || 'planned', state = waiting.has(c.id) ? 'needs' : STATE[raw];
        if (!state) throw Error('unmapped state ' + raw);
        return {id:c.id, name:c.name || c.id, label:c.name || c.id, raw, state, word:raw === 'dropped' ? 'Dropped' : WORD[state],
          after:(c.after || []).filter(a => ids.has(a)), desc:c.desc || '', pct:typeof c.pct === 'number' ? c.pct : null};
      });
      const workers = (snapshot.tasks || []).filter(t => t.project === p.id && t.status === 'claimed').map((t, i) => ({slot:i + 1, task:t.id}));
      const current = parts.find(x => x.state === 'building') || null;
      return {id:p.id, name:p.name || p.id, parts, workers, current, needs:open.some(d => d.project === p.id)};
    });
  }
  // Blueprint.dc.html: tile 220 x 96; window 1280 - 2 x 40 padding = 1200; right tile 320 and a 28 gap leave 852.
  // Height 776 - 2 x 36 padding - 84 header - 12 gap = 608.
  const TILE = {w:220, h:96}, GRAPH = {w:852, h:608, left:24};
  /** Tiles left to right by dependency depth; runs of depths fold into as many columns as fit. {id: {x,y,w,h,col}}. */
  function layout(parts, W = GRAPH.w, H = GRAPH.h) {
    const by = Object.fromEntries(parts.map(p => [p.id, p])), depth = {};
    const dep = (id, seen) => {
      if (depth[id] !== undefined) return depth[id];
      if (seen.has(id)) return 0;
      seen.add(id);
      return depth[id] = Math.max(-1, ...by[id].after.map(a => dep(a, seen))) + 1;
    };
    parts.forEach(p => dep(p.id, new Set()));
    const levels = parts.length ? Math.max(...Object.values(depth)) + 1 : 0, minGap = 56;
    const fit = Math.max(1, Math.floor((W - GRAPH.left + minGap) / (TILE.w + minGap)));
    const counts = Array.from({length:levels}, (_, d) => parts.filter(p => depth[p.id] === d).length);
    const cols = Math.max(1, Math.min(fit, levels));
    // Contiguous split of the levels into at most `cols` groups with the smallest largest group.
    const split = cap => { const g = []; let n = 0, c = 0;
      for (const k of counts) { if (n + k > cap && n > 0) { c++; n = 0; } g.push(c); n += k; }
      return c < cols ? g : null; };
    let lo = Math.max(0, ...counts), hi = Math.max(lo, parts.length);
    while (lo < hi) { const m = (lo + hi) >> 1; if (split(m)) hi = m; else lo = m + 1; }
    const group = split(lo) || counts.map(() => 0);
    const used = Math.max(0, ...group) + 1, gx = used > 1 ? Math.min(120, (W - GRAPH.left - used * TILE.w) / (used - 1)) : 0;
    const out = {};
    for (let c = 0; c < used; c++) {
      const col = parts.filter(p => group[depth[p.id]] === c).sort((a, b) => depth[a.id] - depth[b.id]);
      const gy = col.length > 1 ? Math.min(40, (H - col.length * TILE.h) / (col.length - 1)) : 0;
      const top = (H - (col.length * TILE.h + (col.length - 1) * gy)) / 2;
      col.forEach((p, i) => { out[p.id] = {x:GRAPH.left + c * (TILE.w + gx), y:top + i * (TILE.h + gy), w:TILE.w, h:TILE.h, col:c}; });
    }
    return out;
  }
  // Fleet.dc.html / Blueprint.dc.html: the round 72 dp icon button, top right (#1382).
  const close = view => `<div class="k3-close" role="button" data-act="close" data-id="${view}" aria-label="Close ${view}">` +
    `<svg width="30" height="30" viewBox="0 0 30 30" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" aria-hidden="true"><path d="M8 8l14 14M22 8 8 22"/></svg></div>`;
  const monogram = name => { const w = name.trim().split(/\s+/); return (w.length > 1 ? w[0][0] + w[1][0] : name.slice(0, 2)).toUpperCase(); };
  const CSS = `
    #af-k3{width:1200px;height:740px;margin:18px 40px;color:${C.text};font-family:Roboto,sans-serif;display:flex;flex-direction:column}
    #af-k3,#af-k3 *{font-size:30px!important;font-weight:500;line-height:1.2;box-sizing:border-box}
    #af-k3 *{margin:0}
    #af-k3 .k3-head{display:flex;gap:20px;height:84px;align-items:center;flex:none}
    #af-k3 .k3-title{font-size:40px!important;font-weight:600}
    #af-k3 .k3-cap{color:${C.cap}}
    #af-k3 .k3-close{width:72px;height:72px;border-radius:999px;border:2px solid ${C.edge};display:flex;align-items:center;justify-content:center;
      margin-left:auto;flex:none;cursor:pointer;line-height:0}
    #af-k3 .k3-row{display:grid;grid-template-columns:64px 290px 300px minmax(0,1fr) 150px 44px;align-items:center;gap:24px;height:92px;
      border-top:2px solid ${C.rule};cursor:pointer;flex:none}
    #af-k3 .k3-row.need{background:${C.needTint}}
    #af-k3 .k3-mo{width:64px;height:64px;border-radius:999px;border:2px solid ${C.edge};display:flex;align-items:center;justify-content:center;font-weight:600}
    #af-k3 .k3-row.need .k3-mo{border-color:${C.amber};color:${C.amber}}
    #af-k3 .k3-strip,#af-k3 .k3-crew{display:flex;gap:6px;align-items:center}
    #af-k3 .k3-crew{gap:8px}
    #af-k3 .k3-clip{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-width:0}
    #af-k3 .k3-n{font-weight:600}
    #af-k3 .k3-body{display:flex;gap:28px;flex:1;min-height:0}
    #af-k3 .k3-graph{position:relative;width:${GRAPH.w}px;height:${GRAPH.h}px;flex:none}
    #af-k3 .k3-graph>svg.k3-lines{position:absolute;left:0;top:0;overflow:visible}
    #af-k3 .k3-t{position:absolute;width:${TILE.w}px;height:${TILE.h}px;border-radius:20px;padding:6px 14px;display:flex;align-items:center;justify-content:center;
      text-align:center;cursor:pointer;overflow:hidden}
    #af-k3 .k3-t>span{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;line-height:1.1;overflow-wrap:normal}
    #af-k3 .k3-t.built,#af-k3 .k3-t.done{border:2px solid ${C.cyan};background:rgba(92,225,230,.2)}
    #af-k3 .k3-t.building{border:2px solid ${C.cyan};background:repeating-linear-gradient(135deg,rgba(92,225,230,.5) 0 3px,transparent 3px 10px)}
    #af-k3 .k3-t.planned{border:2px dashed ${C.plan}}
    #af-k3 .k3-t.needs{border:2px solid ${C.amber}}
    #af-k3 .k3-t.error{border:2px solid ${C.red}}
    #af-k3 .k3-t.picked{box-shadow:0 0 0 6px ${C.pick}}
    #af-k3 .k3-on{position:absolute;pointer-events:none;line-height:0}
    #af-k3 .k3-side{flex:1;min-width:0;background:${C.tile};border:2px solid ${C.tileEdge};border-radius:20px;padding:28px 24px;display:flex;flex-direction:column;gap:16px}
    #af-k3 .k3-side .k3-title{display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:normal}
    #af-k3 .k3-state{display:flex;align-items:center;gap:14px}
    #af-k3 .k3-side .k3-cap{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
    #af-k3 .k3-btns{display:flex;gap:16px;margin-top:auto}
    #af-k3 .k3-pill{height:72px;padding:0 24px;border-radius:999px;border:2px solid ${C.edge};display:flex;align-items:center;cursor:pointer}
    #af-k3 .k3-pill.fill{background:${C.cyan};border-color:${C.cyan};color:${C.dark};font-weight:600}`;
  function fleetHtml(projects) {
    const rows = projects.map(p => {
      const n = p.parts.length, seg = n ? Math.max(8, Math.min(44, Math.floor((300 - 6 * (n - 1)) / n))) : 0;
      const allDone = n > 0 && p.parts.every(x => x.state === 'built' || x.state === 'done' || x.raw === 'dropped');
      const caption = p.current ? D(p.current.name, 'k3-cap k3-clip') :
        `<span class="k3-cap k3-clip">${!n ? 'No plan' : allDone ? 'Built' : 'Planned'}</span>`;
      const glyph = p.needs ? mark('needs', 40, 36) : allDone ? mark('done', 40) : '';
      return `<div class="k3-row${p.needs ? ' need' : ''}" role="button" data-act="open_project" data-id="${esc(p.id)}">` +
        `${D(monogram(p.name), 'k3-mo')}${D(p.name, 'k3-clip')}<span class="k3-strip">${p.parts.map(x => mark(x.state, seg, 22)).join('')}</span>` +
        `${caption}<span class="k3-crew">${p.workers.slice(0, 3).map(w => mark('worker', 44, 48, w.slot)).join('')}</span><span>${glyph}</span></div>`;
    }).join('');
    return `<div id="af-k3" data-view="fleet"><div class="k3-head"><span class="k3-title">Fleet</span>` +
      `<span class="k3-cap">${projects.length} projects</span>${close('fleet')}</div>${rows}</div>`;
  }
  function blueprintHtml(projects, projectId, pickedId) {
    const p = projects.find(x => x.id === projectId) || projects[0];
    const at = layout(p.parts), mid = r => r.y + r.h / 2;
    const picked = p.parts.find(x => x.id === pickedId) || p.current || p.parts[0];
    const lines = p.parts.flatMap(t => t.after.map(a => {
      const s = at[a], e = at[t.id], style = t.state === 'building' ? `stroke="${C.cyan}" stroke-dasharray="2 10"` :
        t.state === 'built' || t.state === 'done' ? `stroke="${C.cyan}"` : `stroke="${C.plan}" stroke-dasharray="8 8"`;
      const d = s.col === e.col ? `M${s.x} ${mid(s)}C${s.x - 22} ${mid(s)} ${e.x - 22} ${mid(e)} ${e.x} ${mid(e)}`
        : `M${s.x + s.w} ${mid(s)}C${s.x + s.w + 40} ${mid(s)} ${e.x - 40} ${mid(e)} ${e.x} ${mid(e)}`;
      return `<path d="${d}" ${style} data-edge="${esc(a)} ${esc(t.id)}"/>`;
    })).join('');
    const worker = p.workers[0];
    const tiles = p.parts.map(t => { const r = at[t.id];
      return `<div class="k3-t ${t.state}${t === picked ? ' picked' : ''}" role="button" data-act="pick" data-id="${esc(t.id)}" ` +
        `style="left:${r.x}px;top:${r.y}px" data-rect="${r.x},${r.y},${r.w},${r.h}">${D(t.label)}</div>` +
        (worker && t === p.current ? `<span class="k3-on" style="left:${r.x + 193}px;top:${r.y - 24}px">${mark('worker', 44, 48, worker.slot)}</span>` : '') +
        (t.state === 'needs' ? `<span class="k3-on" style="left:${r.x - 14}px;top:${r.y - 20}px">${mark('needs', 40, 36)}</span>` : ''); }).join('');
    const side = picked ? `<div class="k3-side">${D(picked.name, 'k3-title', 'div')}` +
      `<div class="k3-state">${mark(picked.state, 30)}<span>${picked.word}</span></div>` +
      (picked.desc ? D(picked.desc, 'k3-cap', 'div') : '') +
      (picked.pct != null ? `<div class="k3-cap">${Math.round(picked.pct * 100)}% built</div>` : '') +
      `<div class="k3-btns"><div class="k3-pill fill" role="button" data-act="code" data-id="${esc(picked.id)}">Code</div>` +
      `<div class="k3-pill" role="button" data-act="ask" data-id="${esc(picked.id)}">Ask</div></div></div>`
      : `<div class="k3-side"><span class="k3-cap">No plan</span></div>`;
    return `<div id="af-k3" data-view="blueprint" data-project="${esc(p.id)}"><div class="k3-head"><span class="k3-title">Blueprint</span>${D(p.name, 'k3-cap k3-clip')}${close('blueprint')}</div>` +
      `<div class="k3-body"><div class="k3-graph"><svg class="k3-lines" width="${GRAPH.w}" height="${GRAPH.h}" fill="none" stroke-width="3" stroke-linecap="round" aria-hidden="true">${lines}</svg>${tiles}</div>${side}</div></div>`;
  }
  /** Renders window.AF_K3 = {view, project, snapshot, architecture} into the page; actions go to AfK3.act(name, id). */
  function mount() {
    const k = window.AF_K3, projects = model(k.snapshot, k.architecture);
    let project = k.project || (projects[0] && projects[0].id), picked = null;
    const style = document.createElement('style'); style.id = 'af-k3-style';
    style.textContent = 'html,body{margin:0;background:transparent!important;overflow:hidden;zoom:1!important}' + CSS;
    document.head.append(style);
    const draw = () => { document.body.innerHTML = k.view === 'fleet' ? fleetHtml(projects) : blueprintHtml(projects, project, picked); };
    const act = (name, id) => { const area = name === 'open_project' || k.view === 'fleet' ? 'fleet' : 'blueprint', key = area === 'fleet' ? 'project' : 'part';
      if (window.AfK3) window.AfK3.act(name, id); else console.log(`${area} event=${name} ${key}=${id}`); };
    document.addEventListener('click', e => { const b = e.target.closest('[data-act]'); if (!b) return;
      const name = b.dataset.act, id = b.dataset.id; if (name === 'pick') { picked = id; draw(); } act(name, id); });
    window.afLookK3.show = id => { if (id && id !== project) { project = id; picked = null; draw(); } };
    draw();
  }
  window.afLookK3 = {C, CSS, STATE, WORD, mark, model, layout, fleetHtml, blueprintHtml, mount, TILE, GRAPH};
  if (typeof document !== 'undefined' && window.AF_K3) {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount); else mount();
  }
})();
/* end look K3 */
/* look K8 */
// #1379: the main window's default content is the briefing "While you were away" (look_nov17 s4; Room.dc.html and
// InVR.dc.html, scaled 480 -> 1280 dp: title 24 -> Large 40 semibold, rows 19 -> Regular 30 medium, s2 Type).
// Counts come from the same data Fleet (afLookK3.model) and the question card (open decisions) read.
(() => {
  const K = () => window.afLookK3;
  const ESC = {'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'};
  const esc = s => String(s).replace(/[&<>"']/g, c => ESC[c]);
  /** {needs, needsProject, building, buildingProjects, done} for the recorded day.
   *  needs = decisions with status "open" (practice answers mark theirs "answered"); needsProject = the first by
   *  judge_order; building = parts whose mark is Building (architecture `shown`/`status`) and the projects holding them;
   *  done = the day's `done` events (finished work). */
  function counts(snapshot, arch, decisions, events) {
    const open = (decisions || snapshot.decisions || []).filter(d => d.status === 'open')
      .sort((a, b) => (a.judge_order ?? 1e9) - (b.judge_order ?? 1e9));
    const names = Object.fromEntries((snapshot.projects || []).map(p => [p.id, p.name || p.id]));
    const projects = K().model({...snapshot, decisions: open}, arch);
    const busy = projects.filter(p => p.parts.some(x => x.state === 'building'));
    return {needs: open.length, needsProject: open[0] ? (open[0].project_name || names[open[0].project] || '') : '',
      building: busy.reduce((n, p) => n + p.parts.filter(x => x.state === 'building').length, 0), buildingProjects: busy.length,
      done: (events || []).filter(e => e.kind === 'done').length};
  }
  // look_nov17 s2: inner tile white 7% / edge 12% / radius 20; text #F2F6FA; caption white 70%; target >= 64 dp.
  const CSS = `
    #af-k8{width:1280px;height:100%;box-sizing:border-box;padding:64px 75px;color:#F2F6FA;font-family:Roboto,sans-serif;
      display:flex;flex-direction:column;gap:24px}
    #af-k8,#af-k8 *{font-size:30px!important;font-weight:500;line-height:1.2;box-sizing:border-box;margin:0}
    #af-k8 .k8-title{font-size:40px!important;font-weight:600;height:72px;display:flex;align-items:center}
    #af-k8 .k8-row{display:flex;align-items:center;gap:32px;height:112px;padding:0 32px;border-radius:20px;cursor:pointer;
      background:rgba(255,255,255,.07);border:2px solid rgba(255,255,255,.12)}
    #af-k8 .k8-row .k8-mark{width:40px;display:flex;justify-content:center;flex:none}
    #af-k8 .k8-cap{color:rgba(255,255,255,.7);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;min-width:0}
    #af-k8 .k8-row.calm .k8-word{color:rgba(255,255,255,.7)}
    #af-k8 .k8-foot{margin-top:auto;display:flex}
    #af-k8 .k8-pill{height:72px;padding:0 32px;border-radius:999px;border:2px solid rgba(255,255,255,.22);display:flex;align-items:center;cursor:pointer}`;
  /** The briefing. Text from data carries data-d; the view's own words are the title, the row words and "History". */
  function html(c) {
    const m = K().mark, n = (v, one, many) => v === 1 ? one : many;
    const needs = c.needs ? `<span class="k8-mark">${m('needs', 40, 36)}</span><span class="k8-word"><span data-d>${c.needs}</span> needs you</span>` +
        (c.needsProject ? `<span class="k8-cap" data-d>${esc(c.needsProject)}</span>` : '')
      : `<span class="k8-mark">${m('planned', 36)}</span><span class="k8-word">All clear</span>`;
    const building = `<span class="k8-mark">${m('building', 36)}</span><span class="k8-word"><span data-d>${c.building}</span> building</span>` +
      `<span class="k8-cap"><span data-d>${c.buildingProjects}</span> ${n(c.buildingProjects, 'project', 'projects')}</span>`;
    const done = `<span class="k8-mark">${m('done', 36)}</span><span class="k8-word"><span data-d>${c.done}</span> done</span>`;
    const row = (key, cls, body) => `<div class="k8-row${cls}" role="button" data-act="open" data-row="${key}">${body}</div>`;
    return `<div id="af-k8" data-view="briefing"><div class="k8-title">While you were away</div>` +
      row('needs', c.needs ? ' need' : ' calm', needs) + row('building', '', building) + row('done', '', done) +
      `<div class="k8-foot"><div class="k8-pill" role="button" data-act="history">History</div></div></div>`;
  }
  /** window.AF_K8 = {snapshot, architecture, decisions, events}; presses go to AfK8.act(name, row). */
  function mount() {
    const k = window.AF_K8;
    const style = document.createElement('style'); style.id = 'af-k8-style';
    style.textContent = 'html,body{margin:0;height:100%;background:transparent!important;overflow:hidden;zoom:1!important}' + CSS;
    document.head.append(style);
    const draw = () => { document.body.innerHTML = html(counts(k.snapshot, k.architecture, k.decisions, k.events)); };
    document.addEventListener('click', e => { const b = e.target.closest('[data-act]'); if (!b) return;
      const name = b.dataset.act, row = b.dataset.row || '';
      if (window.AfK8) window.AfK8.act(name, row); else console.log(name === 'open' ? `briefing event=open row=${row}` : 'briefing event=history'); });
    window.afLookK8.update = decisions => { k.decisions = decisions; draw(); };
    draw();
  }
  window.afLookK8 = {CSS, counts, html, mount};
  if (typeof document !== 'undefined' && window.AF_K8) {
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount); else mount();
  }
})();
/* end look K8 */
