"""Retired-names gate (#1015, R212): the app is Flowstate; its old name must not come back where it is seen, heard or read.

    python tools/brand_gate/retired.py [--capture DIR] [--src SRC_MAIN] [--apk APK] [--speech] [--judge] [--out JSON]

Called by tools/brand_gate/gate.py (every build card's brand gate, and the release build's :app:brandGate) and by
scripts/brand_grep.mjs, so no card has to change anything. Scopes, each reported on its own:
  rendered  every string the layout lint capture drew (same reader as the brand gate)
  label     `aapt2 dump badging` application-label* lines of the built APK
  apk       the APK's text resources: resources.arsc string pool, assets/**, res/raw/** text
  src       the shipped copy in src/main res/ + assets/ (for runs without an APK)
  speech    every string handed to speech: each answer's text in res/raw/voice_answers.json and the respelled text
            the clips are rendered from (tools/voice_judges/voicec.py tts_text)
  judge     every file listed in scripts/judge_facing_files.txt (one path or glob per line)
Matches "overhand" in any case, also split ("Over hand", "over-hand"). Exceptions come only from
tools/brand_gate/retired_exceptions.json: each needs scope, file (glob), a reason, and "shown": false; an optional
`text` regex must match the hit's line, and an optional
`token` regex must fullmatch the run of non-space characters holding the hit (a path such as native/overhand/...). Nothing displayed, spoken or read by a judge may be excepted.
"""
import argparse, fnmatch, glob, json, os, pathlib, re, subprocess, sys

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
import gate as brand  # noqa: E402  (same capture/APK/asset readers as the brand gate)

RX = re.compile(r'(?<![A-Za-z0-9])over[-_ ]?hand(?![A-Za-z0-9])', re.I)
SUB = re.compile(r'over[-_ ]?hand', re.I)  # inside identifiers too, for the label and judge text
_TOK = r"""[^\s`'"()<>\[\]]*"""
TOKEN_END, TOKEN_START = re.compile(_TOK + '$'), re.compile(_TOK)
EXCEPTIONS = HERE /'retired_exceptions.json'
JUDGE_LIST = ROOT / 'scripts/judge_facing_files.txt'
SCOPES = ('rendered', 'label', 'apk', 'src', 'speech', 'judge')


def load_exceptions(path=EXCEPTIONS):
    entries = json.loads(path.read_text(encoding='utf-8'))['gate']
    for e in entries:
        if not e.get('reason') or e.get('shown') is not False or e.get('scope') not in SCOPES or not e.get('file'):
            raise SystemExit(f'retired_exceptions.json entry needs scope, file, reason and "shown": false: {e}')
    return entries


class Scan(brand.Scan):
    """brand.Scan with the retired-name pattern and its own exception file."""
    def __init__(self, exceptions, rx=RX):
        super().__init__(rx, [])
        self.exceptions = exceptions
        self.hits = {s: [] for s in SCOPES}

    def check(self, scope, file, where, text):
        if not text:
            return
        scope = 'src' if scope == 'assets' else scope  # brand.scan_src's scope name
        for m in self.rx.finditer(text):
            ctx = text[max(0, m.start() - 60):m.end() + 60].replace('\n', ' ')
            h = {'scope': scope, 'file': file, 'at': where, 'match': m.group(), 'text': ctx}
            token = TOKEN_END.search(text[:m.start()]).group() + m.group() + TOKEN_START.match(text[m.end():]).group()
            e = next((e for e in self.exceptions if e['scope'] == scope and fnmatch.fnmatch(file, e['file'])
                      and (not e.get('text') or re.search(e['text'], text))
                      and (not e.get('token') or re.fullmatch(e['token'], token))), None)
            if e:
                self.allowed.append({**h, 'reason': e['reason']})
            elif h not in self.hits[scope]:
                self.hits[scope].append(h)


def aapt2():
    sdk = os.environ.get('ANDROID_HOME') or os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android/Sdk')
    found = sorted(glob.glob(os.path.join(sdk, 'build-tools', '*', 'aapt2*')))
    found = [f for f in found if pathlib.Path(f).stem == 'aapt2']
    if not found:
        raise SystemExit(f'retired gate: aapt2 not found under {sdk}/build-tools (needed for the APK label)')
    return found[-1]


def scan_label(s, apk):
    out = subprocess.run([aapt2(), 'dump', 'badging', str(apk)], capture_output=True, text=True, encoding='utf-8', errors='replace')
    labels = [l for l in out.stdout.splitlines() if l.startswith('application-label') or l.startswith('launchable-activity')]
    for l in labels:
        if l.startswith('launchable-activity'):  # name= is a class (kept); only label= is shown
            l = ' '.join(re.findall(r"label='[^']*'", l))
        s.rx, rx = SUB, s.rx
        s.check('label', 'AndroidManifest.xml', 'badging', l)
        s.rx = rx
    return [l for l in labels if l.startswith('application-label:')]


def speech_strings():
    """Answer texts, and the respelled text the clips are rendered from (voicec.RESPELL, read without importing numpy)."""
    import ast
    src = (ROOT / 'tools/voice_judges/voicec.py').read_text(encoding='utf-8')
    node = next(n for n in ast.parse(src).body if isinstance(n, ast.Assign) and getattr(n.targets[0], 'id', '') == 'RESPELL')
    respell = ast.literal_eval(node.value)
    data = json.loads((ROOT / 'native/overhand/app/src/main/res/raw/voice_answers.json').read_text(encoding='utf-8'))
    for a in data['answers']:
        t = a['text']
        yield a['id'], t
        for x, y in respell: t = re.sub(x, y, t)
        yield a['id'] + ' (as rendered)', t
    # The first-minute greeting: its cue texts are what the clips say, its cards are what the brief speaks from.
    for name in ('first60_cues.json', 'first60_spoken_cards.json'):
        f = ROOT / 'native/overhand/app/src/main/res/raw' / name
        if f.is_file():
            for where, t in brand.strings_of(json.loads(f.read_text(encoding='utf-8'))):
                yield f'{name} {where}', t


def judge_files():
    for line in JUDGE_LIST.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if line and not line.startswith('#'):
            hits = sorted(glob.glob(str(ROOT / line), recursive=True))
            if not hits:
                raise SystemExit(f'retired gate: {JUDGE_LIST.name} lists {line!r}, which matches no file')
            yield from (pathlib.Path(h) for h in hits)


def run(capture=None, src=None, apk=None, speech=False, judge=False, exceptions=None):
    s = Scan(load_exceptions() if exceptions is None else exceptions)
    counts = {}
    if capture:
        counts['windows'] = brand.scan_capture(s, pathlib.Path(capture))
    if apk:
        counts['labels'] = scan_label(s, apk)
        counts['apk_entries'] = brand.scan_apk(s, pathlib.Path(apk))
    if src:
        counts['src_files'] = brand.scan_src(s, pathlib.Path(src))
    if speech:
        n = 0
        for where, t in speech_strings():
            n += 1; s.check('speech', 'res/raw/voice_answers.json', where, t)
        counts['speech_strings'] = n
    if judge:
        n = 0
        for f in judge_files():
            n += 1; rel = f.relative_to(ROOT).as_posix()
            for i, line in enumerate(f.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
                s.check('judge', rel, f'line {i}', line)
        counts['judge_files'] = n
    return s, counts


def report(s, counts):
    total = sum(len(s.hits[k]) for k in SCOPES)
    res = {'retired_name_hits': total, 'counts': counts}
    for k in SCOPES:
        res[f'retired_hits_{k}'] = len(s.hits[k])
    res['hits'] = [h for k in SCOPES for h in s.hits[k]]
    res['excepted'] = len(s.allowed)
    return res


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--capture'); ap.add_argument('--src'); ap.add_argument('--apk')
    ap.add_argument('--speech', action='store_true'); ap.add_argument('--judge', action='store_true')
    ap.add_argument('--out', type=pathlib.Path)
    ap.add_argument('--root', type=pathlib.Path, help='scan speech/judge files of another checkout (e.g. main)')
    ap.add_argument('--no-exceptions', action='store_true', help='report every use (inventory)')
    a = ap.parse_args()
    if a.root:
        global ROOT, JUDGE_LIST
        ROOT = a.root.resolve(); JUDGE_LIST = pathlib.Path(__file__).resolve().parents[2] / 'scripts/judge_facing_files.txt'
    s, counts = run(a.capture, a.src, a.apk, a.speech, a.judge, [] if a.no_exceptions else None)
    res = report(s, counts)
    for h in res['hits']:
        print(f"  RETIRED {h['scope']:8} {h['file']} {h['at']}: {h['match']!r} in {h['text']!r}")
    if a.out:
        a.out.parent.mkdir(parents=True, exist_ok=True)
        a.out.write_text(json.dumps(res, indent=1, ensure_ascii=False) + '\n', encoding='utf-8')
    print(f"retired_name_hits={res['retired_name_hits']} " + ' '.join(f'{k}={res[f"retired_hits_{k}"]}' for k in SCOPES)
          + f" excepted={res['excepted']} counts={json.dumps({k: v for k, v in counts.items() if k != 'labels'})}")
    if res['retired_name_hits']:
        print('FAIL: the retired name "Overhand" is visible, spoken or read by a judge. Say Flowstate (R212).')
    return 1 if res['retired_name_hits'] else 0


if __name__ == '__main__':
    sys.exit(main())
