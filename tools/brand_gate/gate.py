"""Brand gate (#879, rules s.4): fail the release when text a judge can see names a vendor, product or model.

    python tools/brand_gate/gate.py [--capture DIR] [--src native/overhand/app/src/main] [--apk APK]
                                    [--self-test] [--out runs/brand_gate/release.json]

Three scopes, each reported on its own:
  brand_hits_rendered  every string the layout lint capture rendered (LayoutLintRenderer writes each window's and each
                       state's `strings`: TextView text, hints, content descriptions, hidden text; plus `text` fields).
  brand_hits_assets    the source copy shipped as data: res/values*/strings.xml, res/layout and res/xml text
                       attributes, res/raw and assets text (JSON string values, JSONL, JS, HTML, TXT).
  brand_hits_apk       the built APK's text resources: assets/**, res/raw/** and the resources.arsc string pool.
                       (classes.dex is code: package names such as com.<vendor>.* are identifiers, not copy.)

Words: tools/brand_gate/words.txt (the one list). Allowlist: tools/brand_gate/allowlist.json; every entry carries a
reason and must say "shown": false. An entry that claims shown=true is refused, so nothing on screen can be waved through.
--self-test plants a name in a copy of every scope (a rendered window, an asset, an APK entry) and fails unless each is
caught, so a gate that stopped matching cannot pass silently.
"""
import argparse, fnmatch, json, pathlib, re, shutil, struct, sys, tempfile, zipfile

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
WORDS = HERE / 'words.txt'
ALLOW = HERE / 'allowlist.json'
TEXT_EXT = {'.json', '.jsonl', '.js', '.mjs', '.html', '.htm', '.txt', '.md', '.csv', '.xml', '.properties'}
PLANT = 'Ask Claude'


def load_words(path=WORDS) -> re.Pattern:
    words, regexes = [], []
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('re:'):
            regexes.append(line[3:])
        else:
            parts = [re.escape(p) for p in re.split(r'[-_ ]+', line) if p]
            words.append('(?i:' + r'[-_ ]?'.join(parts) + ')')
    alts = sorted(words, key=len, reverse=True) + regexes
    return re.compile(r'(?<![A-Za-z0-9])(?:' + '|'.join(alts) + r')(?![A-Za-z0-9])')


def load_allow(path=ALLOW) -> list[dict]:
    if not path.is_file():
        return []
    entries = json.loads(path.read_text(encoding='utf-8'))
    for e in entries:
        if not e.get('reason') or e.get('shown') is not False:
            raise SystemExit(f'allowlist entry needs a reason and "shown": false (nothing on screen may be allowlisted): {e}')
    return entries


def allowed(allow, scope, file, match, text) -> dict | None:
    for e in allow:
        if e.get('scope', scope) != scope or not fnmatch.fnmatch(file, e.get('file', '*')):
            continue
        if e.get('match') and e['match'].lower() != match.lower():
            continue
        if e.get('text') and not re.search(e['text'], text):
            continue
        return e
    return None


class Scan:
    def __init__(self, rx, allow):
        self.rx, self.allow = rx, allow
        self.hits = {'rendered': [], 'assets': [], 'apk': []}
        self.allowed = []

    def check(self, scope, file, where, text):
        if not text:
            return
        for m in self.rx.finditer(text):
            ctx = text[max(0, m.start() - 60):m.end() + 60].replace('\n', ' ')
            h = {'scope': scope, 'file': file, 'at': where, 'match': m.group(), 'text': ctx}
            e = allowed(self.allow, scope, file, m.group(), text)
            if e:
                self.allowed.append({**h, 'reason': e['reason']})
            elif h not in self.hits[scope]:
                self.hits[scope].append(h)


def strings_of(v, path='$'):
    """Every string VALUE in a JSON document (keys are identifiers, never shown)."""
    if isinstance(v, str):
        yield path, v
    elif isinstance(v, list):
        for i, x in enumerate(v):
            yield from strings_of(x, f'{path}[{i}]')
    elif isinstance(v, dict):
        for k, x in v.items():
            yield from strings_of(x, f'{path}.{k}')


def scan_text(s: Scan, scope, file, name, data: str):
    suffix = pathlib.PurePosixPath(name).suffix.lower()
    if suffix == '.json':
        try:
            for p, v in strings_of(json.loads(data)):
                s.check(scope, file, p, v)
            return
        except ValueError:
            pass
    if suffix == '.jsonl':
        for i, line in enumerate(data.splitlines(), 1):
            try:
                for p, v in strings_of(json.loads(line)):
                    s.check(scope, file, f'line {i} {p}', v)
            except ValueError:
                s.check(scope, file, f'line {i}', line)
        return
    if suffix == '.xml':
        data = re.sub(r'<!--[\s\S]*?-->', '', data)
        for m in re.finditer(r'>([^<]+)<', data):
            s.check(scope, file, f'line {data.count(chr(10), 0, m.start()) + 1}', m[1])
        for m in re.finditer(r'(?:android:)?(?:text|hint|label|contentDescription|title|summary)="([^"]*)"', data):
            s.check(scope, file, f'line {data.count(chr(10), 0, m.start()) + 1}', m[1])
        return
    if scope in ('assets', 'apk') and file == 'assets/windows.js':
        data = classify_windows_css(data)
    for i, line in enumerate(data.splitlines(), 1):
        s.check(scope, file, f'line {i}', line)


def classify_windows_css(data):
    """Mask only non-display syntax in windows.js's stylesheet templates.

    Quoted CSS values and JS interpolations stay visible to the scanner. No
    exception applies to content, other JS strings, rendered text or code diffs.
    """
    templates = re.compile(r'(?:const CSS\s*=|theme\.textContent\s*=)\s*`([^`]*)`')
    tokens = re.compile(
        r'''"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|\$\{[^}]*\}|'''
        r'(?P<syntax>\.panel \.meta(?=\s*\{)|'
        r'(?<=[;{])\s*cursor\s*:\s*pointer(?=\s*[;}])|'
        r'(?<=[;{])\s*display\s*:\s*-webkit-box(?=\s*[;}])|'
        r'(?<=[;{])\s*-webkit-line-clamp\s*:\s*[23](?=\s*[;}])|'
        r'(?<=[;{])\s*-webkit-box-orient\s*:\s*vertical(?=\s*[;}]))')

    def template(m):
        body = tokens.sub(lambda t: re.sub(r'[^\n]', ' ', t[0]) if t.group('syntax') else t[0], m[1])
        return m[0][:m.start(1) - m.start()] + body + '`'

    return templates.sub(template, data)


def scan_capture(s: Scan, cap: pathlib.Path) -> int:
    n = 0
    for f in sorted(cap.glob('*.json')):
        try:
            d = json.loads(f.read_text(encoding='utf-8'))
        except ValueError:
            continue
        if not isinstance(d, dict) or 'window' not in d:
            continue
        n += 1
        seen = set()
        for i, t in enumerate(d.get('strings', [])):
            seen.add(t); s.check('rendered', d['window'], f'strings[{i}]', t)
        for el in d.get('elements', []):
            for k in ('text', 'hidden_text'):
                t = el.get(k)
                if isinstance(t, str) and t not in seen:
                    seen.add(t); s.check('rendered', d['window'], el.get('name', '?'), t)
    return n


def scan_src(s: Scan, main: pathlib.Path) -> int:
    n = 0
    for f in sorted(main.rglob('*')):
        rel = f.relative_to(main).as_posix()
        if not f.is_file() or not (rel.startswith('assets/') or rel.startswith('res/')):
            continue
        if f.suffix.lower() not in TEXT_EXT:
            continue  # wav, png, jpg: no copy (spoken clips are covered by res/raw/voice_answers.json)
        if rel.startswith('res/') and f.suffix.lower() == '.xml' and not re.match(r'res/(values[^/]*|layout[^/]*|xml|menu)/', rel):
            continue
        n += 1
        scan_text(s, 'assets', rel, rel, f.read_text(encoding='utf-8', errors='replace'))
    return n


def arsc_strings(data: bytes) -> list[str]:
    """The global string pool of resources.arsc (every string resource value, any library's included)."""
    out = []
    off = 12  # ResTable header
    if len(data) < off + 28 or struct.unpack_from('<H', data, off)[0] != 0x0001:
        return out
    _, hsz, _, count, _, flags, sstart, _ = struct.unpack_from('<HHIIIIII', data, off)
    utf8 = bool(flags & 0x100)
    offs = struct.unpack_from(f'<{count}I', data, off + hsz)
    base = off + sstart
    for o in offs:
        p = base + o
        try:
            if utf8:
                n = data[p]; p += 2 if n & 0x80 else 1
                b = data[p]
                if b & 0x80:
                    b = ((b & 0x7f) << 8) | data[p + 1]; p += 2
                else:
                    p += 1
                out.append(data[p:p + b].decode('utf-8', 'replace'))
            else:
                n = struct.unpack_from('<H', data, p)[0]; p += 2
                if n & 0x8000:
                    n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', data, p)[0]; p += 2
                out.append(data[p:p + 2 * n].decode('utf-16le', 'replace'))
        except (IndexError, struct.error):
            break
    return out


def scan_apk(s: Scan, apk: pathlib.Path) -> int:
    n = 0
    with zipfile.ZipFile(apk) as z:
        entries = set(z.namelist())
        for info in z.infolist():
            name = info.filename
            if info.is_dir():
                continue
            if name == 'resources.arsc':
                n += 1
                for i, t in enumerate(arsc_strings(z.read(info))):
                    if t in entries:
                        continue  # a file path inside the APK (R8 shortens them, e.g. res/O1.xml); never rendered
                    s.check('apk', name, f'string #{i}', t)
            elif (name.startswith('assets/') or name.startswith('res/raw/')) and pathlib.PurePosixPath(name).suffix.lower() in TEXT_EXT:
                n += 1
                scan_text(s, 'apk', name, name, z.read(info).decode('utf-8', 'replace'))
    return n


def run(rx, allow, capture=None, src=None, apk=None) -> tuple[Scan, dict]:
    s = Scan(rx, allow)
    counts = {}
    if capture:
        counts['windows'] = scan_capture(s, capture)
    if src:
        counts['asset_files'] = scan_src(s, src)
    if apk:
        counts['apk_entries'] = scan_apk(s, apk)
    return s, counts


def self_test(rx, allow, capture, src, apk) -> dict:
    """Plant PLANT in a copy of each scope given; each must be caught at the planted file."""
    caught = {}
    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        if capture:
            c = tmp / 'cap'; c.mkdir()
            (c / 'zz_planted.json').write_text(json.dumps({'window': 'zz_planted', 'strings': [PLANT]}))
            s, _ = run(rx, allow, capture=c)
            caught['rendered'] = any(h['file'] == 'zz_planted' for h in s.hits['rendered'])
        if src:
            m = tmp / 'main' / 'assets'; m.mkdir(parents=True)
            (m / 'zz_planted.json').write_text(json.dumps({'title': PLANT}))
            s, _ = run(rx, allow, src=tmp / 'main')
            caught['assets'] = any(h['file'] == 'assets/zz_planted.json' for h in s.hits['assets'])
        if apk:
            copy = tmp / 'planted.apk'
            shutil.copyfile(apk, copy)
            with zipfile.ZipFile(copy, 'a') as z:
                z.writestr('assets/zz_planted.json', json.dumps({'title': PLANT}))
            s, _ = run(rx, allow, apk=copy)
            caught['apk'] = any(h['file'] == 'assets/zz_planted.json' for h in s.hits['apk'])
    return {'planted': PLANT, 'caught': caught, 'ok': all(caught.values()) and bool(caught)}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--capture', type=pathlib.Path, help='layout lint capture dir (<window>.json)')
    ap.add_argument('--src', type=pathlib.Path, help='app src/main (res/ and assets/)')
    ap.add_argument('--apk', type=pathlib.Path)
    ap.add_argument('--out', type=pathlib.Path)
    ap.add_argument('--self-test', action='store_true')
    a = ap.parse_args()
    rx, allow = load_words(), load_allow()
    s, counts = run(rx, allow, a.capture, a.src, a.apk)
    if a.capture and counts.get('windows', 0) == 0:
        print(f'FAIL: no captured windows in {a.capture}; the rendered scan saw nothing')
        return 1
    rc = 0
    result = {'words': str(WORDS.relative_to(ROOT)).replace('\\', '/'), 'counts': counts}
    for scope in ('rendered', 'assets', 'apk'):
        if scope == 'rendered' and not a.capture or scope == 'assets' and not a.src or scope == 'apk' and not a.apk:
            continue
        hits = s.hits[scope]
        result[f'brand_hits_{scope}'] = len(hits)
        result[f'hits_{scope}'] = hits
        for h in hits:
            print(f"  HIT {scope:8} {h['file']} {h['at']}: {h['match']!r} in {h['text']!r}")
        rc |= bool(hits)
    result['allowlisted'] = s.allowed
    # #1015 (R212): the retired product name, in the same scopes plus speech and the judge-facing files.
    import retired
    rs, rcounts = retired.run(a.capture, a.src, a.apk, speech=True, judge=True)
    rres = retired.report(rs, rcounts)
    result['retired_name_hits'] = rres['retired_name_hits']
    result['retired'] = rres
    for h in rres['hits']:
        print(f"  RETIRED {h['scope']:8} {h['file']} {h['at']}: {h['match']!r} in {h['text']!r}")
    rc |= bool(rres['retired_name_hits'])
    if a.self_test:
        st = self_test(rx, allow, a.capture, a.src, a.apk)
        result['self_test'] = st
        print(f"  self-test: planted {st['planted']!r}, caught {st['caught']}")
        if not st['ok']:
            print('FAIL: the planted control was not caught; the gate is broken')
            rc = 1
    if a.out:
        a.out.parent.mkdir(parents=True, exist_ok=True)
        a.out.write_text(json.dumps(result, indent=1, ensure_ascii=False) + '\n', encoding='utf-8')
    print(' '.join(f'{k}={v}' for k, v in result.items() if k.startswith('brand_hits_')) + f' allowlisted={len(s.allowed)}'
          + f" retired_name_hits={result['retired_name_hits']}")
    if rc:
        print('FAIL: a vendor, product or model name, or the retired name Overhand, in app text (rules s.4, R212). Fix the copy.')
    return 1 if rc else 0


if __name__ == '__main__':
    sys.exit(main())
