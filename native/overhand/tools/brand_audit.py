"""Conservative literal audit for native copy and bundled WebView assets (rubric v1.1).

Checks all string literals, including logs, except explicit machine-only identifiers
and the denylist declarations themselves. Dynamic server text still needs the runtime
scrubbers and a rendered headset audit; this is the source regression gate.
"""
import json
import pathlib
import re
import sys
import wave

ROOT = pathlib.Path(__file__).resolve().parents[3]
MAIN = ROOT / 'native/overhand/app/src/main'
shared = (ROOT / 'agents/bridge/src/brands.ts').read_text(encoding='utf-8')
words = re.findall(r'"([^"]+)"', shared.split('BRAND_DENYLIST = [')[1].split('] as const')[0])
shell = (MAIN / 'assets/windows.js').read_text(encoding='utf-8')
words += re.findall(r"'([^']+)'", shell.split('const deny = [')[1].split('];')[0])
words += ['tailscale']
pattern = re.compile(r'\b(?:' + '|'.join(re.escape(w) for w in sorted(set(words), key=len, reverse=True)) + r')\b', re.I)
# Lex strings before comments, so URLs and escaped quotes are preserved.
tokens = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*')
def audit(main=MAIN):
    hits = []
    files = 0
    for file in sorted(main.rglob('*')):
        if not file.is_file() or file.name == '.gitattributes':
            continue
        files += 1
        if file.suffix == '.wav' and file.parent == main / 'assets/audio':
            with wave.open(str(file), 'rb') as audio:
                assert (audio.getnchannels(), audio.getsampwidth(), audio.getframerate()) == (1, 2, 48000)
                assert 0 < audio.getnframes() < 14400
            continue
        # Spoken answers (t756): each res/raw/voice_<id>.wav is the pre-render of answer <id> in voice_answers.json,
        # whose text this audit scans like any other copy. A clip with no scanned transcript stays unreviewed.
        if file.suffix == '.wav' and file.parent == main / 'res/raw':
            spoken = {a['id']: a.get('text', '') for a in json.loads((main / 'res/raw/voice_answers.json').read_text(encoding='utf-8'))['answers']}
            if file.stem.removeprefix('voice_') in spoken and spoken[file.stem.removeprefix('voice_')].strip():
                continue
            raise ValueError(f'Spoken clip with no scanned transcript: {file}')
        # Place sky images (t753): pixels, no copy. A logo in them is a visual check, not a text one.
        if file.suffix in {'.jpg', '.png'} and file.parent.name.startswith('drawable'):
            continue
        if file.suffix not in {'.kt', '.java', '.js', '.xml', '.html', '.json', '.jsonl', '.txt'}:
            raise ValueError(f'Unreviewed native asset type: {file}')
        source = file.read_text(encoding='utf-8')
        # Provenance source paths are machine metadata, never rendered as app copy.
        if file == main / 'assets/judge/manifest.json':
            manifest = json.loads(source)
            for item in manifest.get('sources', []):
                item['path'] = ''
            source = json.dumps(manifest, indent=2)
        # Denylist data is never displayed. Keep line offsets for diagnostics.
        source = re.sub(r'const deny = \[[\s\S]*?\];|val EXTRA_DENY=listOf\([\s\S]*?\)',
                        lambda m: '\n' * m[0].count('\n'), source)
        if file.suffix == '.xml':
            source = re.sub(r'<!--[\s\S]*?-->', '', source)
            # Resource body text and all visible/accessibility attributes; do not
            # scan platform namespace, manifest metadata or component identifiers.
            candidates = [(m.start(), m[1]) for m in re.finditer(r'>([^<]+)<', source)]
            candidates += [(m.start(), m[1]) for m in re.finditer(
                r'(?:android:)?(?:text|hint|label|contentDescription|title|summary)="([^"]*)"', source)]
        else:
            candidates = [(m.start(), m[0].strip('\"\'')) for m in tokens.finditer(source)
                          if not m[0].startswith(('//', '/*'))]
        for offset, value in candidates:
            # Exact internal API identifiers and one project ID; never UI phrases.
            if value == 'example-project' or re.fullmatch(r'com\.oculus\.[\w.]+', value):
                continue
            if value.startswith("<html>"):
                value = re.sub(r"<[^>]*>", " ", value)
            # Kotlin interpolation expressions are API code, not emitted copy.
            value = re.sub(r'\$\{[^}]*\}', '', value)
            if file.name == 'RecordedText.kt' and value == 'e4b': continue
            for hit in pattern.findall(value):
                hits.append(dict(file=str(file.relative_to(ROOT)), line=source.count('\n', 0, offset)+1,
                                 brand=hit, text=value))
    return dict(hits=len(hits), files=files, findings=hits,
                scope='native source literals, XML copy and bundled assets; eval/RUBRIC.md v1.1 branding gate')

if __name__ == '__main__':
    result = audit(pathlib.Path(sys.argv[1]) if len(sys.argv)>1 else MAIN)
    print(json.dumps(result, indent=2))
    sys.exit(bool(result['hits']))
