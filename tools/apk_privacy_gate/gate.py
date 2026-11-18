"""APK privacy gate (#808): fail when a built APK carries private names, the user name, the PC name or home paths.

    python tools/apk_privacy_gate/gate.py <apk|folder> [--self-test] [--out runs/apk_privacy_gate/release.json]

A folder (#1074, e.g. the fetched public Web entry or web/agentdeck/dist) is read file by file like APK entries.

What is read: every zip entry name; text entries (json, js, html, txt, ...) line by line; every other entry
(classes*.dex string table, resources.arsc, .so, media) as printable ASCII and UTF-16LE runs, reported by byte offset.

The private names list is NOT committed. It lives in data/apk_privacy/private_names.txt (gitignored; one name per
line, '#' comments) or the file named by APK_PRIVACY_NAMES. Without it (a clean clone) the gate checks the generic
patterns only (home paths, this machine's user name and PC name) and says so. Output never prints a private name in
clear: matches are masked as <name#N> (position in the list), <user>, <pc> or <home>.

Hashes (#1068): an unsalted hash of a name is as good as the name to anyone with a guess list. The gate hashes a
dictionary (the private names, plus this machine's project names from ~/.autoflow/autoflow.db and its folder names under
~/Documents, built at run time and never written out; each also as '<word>:<role>' agent handles) with md5, sha1 and
sha256, and FAILS on any 16-64 hex token whose
first 16 digits match one of them (masked <hash>). Bundle ids must be random, not derived from a name.

Opaque map (#1085): a dictionary finds only the names it was given. So every 's<16 hex>' id must also be in the opaque
map (export.py MAPPING, tools/judge_bundle/web_id_map.json, and public_ids.json's re-hashed public demo ids); any other
id fails as unmapped_id (masked <unmapped_id>), whatever it was derived from.

Literal slugs (#1096): a project slug compiled into code (a default id, say) is a string in classes*.dex. Every
slug-shaped word ('a-b', 'a_b') of the private names and the local dictionary is matched in dex strings only
(masked <slug#N>, its position in the sorted slug list), so a name the list lacks is still caught there.

--self-test copies the APK, plants one hit per rule (text asset, dex-like binary, UTF-16 run) and fails unless every
planted hit is caught at its file, so a gate that stopped matching cannot pass silently.
"""
import argparse, getpass, hashlib, json, os, pathlib, re, secrets, shutil, socket, sqlite3, sys, tempfile, zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
NAMES_FILE = pathlib.Path(os.environ.get('APK_PRIVACY_NAMES') or ROOT / 'data/apk_privacy/private_names.txt')
TEXT_EXT = {'.json', '.jsonl', '.js', '.mjs', '.html', '.htm', '.css', '.txt', '.md', '.csv', '.properties', '.svg'}
B = r'(?<![A-Za-z0-9])'
E = r'(?![A-Za-z0-9])'


def name_rx(name: str) -> str:
    # 'example-project' also matches 'example-project', 'example-project' and 'example-project'.
    parts = [re.escape(p) for p in re.split(r'[-_ ]+', name.strip()) if p]
    return B + r'[-_ ]?'.join(parts) + E


def load_pairs(path: pathlib.Path) -> list[tuple[str, str]] | None:
    """Lines 'name' or 'name = neutral replacement' (the bundle builder applies the replacement)."""
    if not path.is_file():
        return None
    out = []
    for l in path.read_text(encoding='utf-8-sig').splitlines():
        if l.strip() and not l.lstrip().startswith('#'):
            name, _, rep = l.partition('=')
            out.append((name.strip(), rep.strip() or 'another project'))
    return out


def load_names(path: pathlib.Path) -> list[str] | None:
    pairs = load_pairs(path)
    return None if pairs is None else [n for n, _ in pairs]


def user() -> str:
    return (os.environ.get('USERNAME') or getpass.getuser() or '').strip()


def host() -> str:
    return (os.environ.get('COMPUTERNAME') or socket.gethostname() or '').strip()


def local_dictionary() -> list[str]:
    """Names a reader could guess: this machine's autoflow project names and folder names under ~/Documents."""
    out = set()
    try:
        db = sqlite3.connect(pathlib.Path.home() / '.autoflow/autoflow.db')
        for table in ('tasks', 'agents'):
            out |= {r[0] for r in db.execute(f'select distinct project from {table}') if r[0]}
        # Agent handles such as '<project>:w1' (#1074: the Web recording's agent ids hashed these).
        out |= {r[0] for r in db.execute('select distinct id from agents') if r[0]}
        for col in ('created_by', 'claimed_by'):
            out |= {r[0] for r in db.execute(f'select distinct {col} from tasks') if r[0]}
        db.close()
    except Exception:
        pass
    try:
        out |= {d.name for d in (pathlib.Path.home() / 'Documents').iterdir() if d.is_dir()}
    except OSError:
        pass
    return sorted(out)


ROLES = ['head', 'chief', 'operator', 'worker'] + [f'w{i}' for i in range(1, 13)]


def with_handles(words) -> list[str]:
    """Each word plus '<word>:<role>' and '<word>/<role>' agent handles: hashing a handle leaks its project (#1074)."""
    words = list(words)
    return words + [f'{w}{sep}{r}' for w in words if ':' not in w for sep in (':', '/') for r in ROLES]


def hash_prefixes(words) -> set[str]:
    """First 16 hex digits of md5/sha1/sha256 of each word and its common spellings."""
    out = set()
    for w in words:
        w = w.strip()
        for v in {w, w.lower(), w.lower().replace('_', '-'), w.lower().replace('-', '_'), w.lower().replace(' ', '-')}:
            for fn in (hashlib.md5, hashlib.sha1, hashlib.sha256):
                out.add(fn(v.encode('utf-8')).hexdigest()[:16])
    return out


class HashRx:
    """Regex-like (finditer/search/sub): a 16-64 hex token, optionally 's'-prefixed, whose first 16 digits are a known hash."""
    rx = re.compile(B + r's?([0-9a-fA-F]{16,64})' + E)

    def __init__(self, prefixes: set[str]):
        self.prefixes = prefixes

    def _hit(self, m):
        return m.group(1)[:16].lower() in self.prefixes

    def finditer(self, text):
        return (m for m in self.rx.finditer(text) if self._hit(m))

    def search(self, text):
        return next(self.finditer(text), None)

    def sub(self, rep, text):
        return self.rx.sub(lambda m: rep if self._hit(m) else m.group(0), text)


SID = re.compile(B + r's[0-9a-f]{16}' + E)
PUBLIC_IDS = ROOT / 'tools/apk_privacy_gate/public_ids.json'


def sid(text: str) -> str:
    return 's' + hashlib.sha256(text.encode('utf-8')).hexdigest()[:16]


def opaque_ids(words=()) -> set[str]:
    """#1085: the ids a bundle may carry. Random ones: export.py MAPPING's project ids and every id in
    tools/judge_bundle/web_id_map.json (agent ids, #1074). Public ones: public_ids.json lists ids the demo builder
    derives from public text ('demo:<key>[:<agent name>]'); each is re-hashed here, and kept only if its source text
    holds no dictionary word, so the list cannot hide a name-derived id."""
    out = set()
    export = ROOT / 'tools/judge_bundle/export.py'
    if export.is_file():
        src = export.read_text(encoding='utf-8')
        out |= set(re.findall(r"\('[a-z0-9-]+','(s[0-9a-f]{16})','", src.split('MAPPING = [', 1)[-1].split(']\n', 1)[0]))
    table = ROOT / 'tools/judge_bundle/web_id_map.json'
    if table.is_file():
        out |= {v for field in json.loads(table.read_text(encoding='utf-8')).values() for v in field.values()}
    if PUBLIC_IDS.is_file():
        rxs = [re.compile(name_rx(w), re.I) for w in words if len(w.strip()) >= 3]
        for source in json.loads(PUBLIC_IDS.read_text(encoding='utf-8'))['sources']:
            if not any(rx.search(source) for rx in rxs):
                out.add(sid(source))
    return out


class UnmappedRx:
    """Regex-like: an 's<16 hex>' id that is not in the opaque map (#1085). Bundle ids must come from the map."""

    def __init__(self, allowed: set[str]):
        self.allowed = allowed

    def finditer(self, text):
        return (m for m in SID.finditer(text) if m.group(0) not in self.allowed)

    def search(self, text):
        return next(self.finditer(text), None)

    def sub(self, rep, text):
        return SID.sub(lambda m: m.group(0) if m.group(0) in self.allowed else rep, text)


SLUG = re.compile(r'^[a-z0-9]+(?:[-_][a-z0-9]+)+$')
DEX = re.compile(r'(?:^|/)classes\d*\.dex$')


def slugs(words) -> list[str]:
    """#1096: the slug-shaped words (lowercase, hyphen or underscore joined) a dex string must not carry."""
    return sorted({w.strip().lower() for w in words if SLUG.match(w.strip().lower())})


def slug_rules(words) -> list[tuple[str, str, re.Pattern]]:
    """#1096: one dex-only rule per slug word; scan() applies them to classes*.dex entries alone."""
    return [('literal_slug', f'slug#{i}', re.compile(name_rx(w), re.I)) for i, w in enumerate(slugs(words), 1)]


def build_rules(names: list[str] | None, dictionary: list[str] | None = None,
                opaque: set[str] | None = None) -> list[tuple[str, str, re.Pattern]]:
    """(rule, mask id, regex). Generic rules come from this machine, so nothing personal is committed."""
    u, h = user(), host()
    sep = r'[\\/]{1,2}'
    # Any Windows profile path counts; a Unix home only when it is this user's (vendor libraries carry
    # CI paths such as /home/runner/..., which are not private).
    home = r'(?i)(?<![A-Za-z])[a-z]:' + sep + 'users' + sep + r'[A-Za-z0-9._-]+|/c/users/[A-Za-z0-9._-]+'
    if len(u) >= 3:
        home += r'|/(?:home|Users)/' + re.escape(u) + E
    rules = [('home_path', 'home', re.compile(home))]
    if len(u) >= 3:
        rules.append(('user_name', 'user', re.compile(B + re.escape(u) + E, re.I)))
    if len(h) >= 3:
        rules.append(('pc_name', 'pc', re.compile(name_rx(h), re.I)))
    for i, n in enumerate(names or [], 1):
        # Masks are list positions: a hash of the name would let a reader confirm a guess.
        rules.append(('private_name', f'name#{i}', re.compile(name_rx(n), re.I)))
    words = (names or []) + (dictionary or [])
    if words:
        rules.append(('hashed_name', 'hash', HashRx(hash_prefixes(with_handles(words)))))
    if opaque:
        rules.append(('unmapped_id', 'unmapped_id', UnmappedRx(opaque)))
    return rules


def runs(data: bytes) -> list[tuple[int, str]]:
    """Printable ASCII (also MUTF-8 dex strings) and UTF-16LE runs of 4+ characters, with byte offsets."""
    out = [(m.start(), m.group().decode('ascii')) for m in re.finditer(rb'[\x20-\x7e]{4,}', data)]
    out += [(m.start(), m.group().decode('utf-16le')) for m in re.finditer(rb'(?:[\x20-\x7e]\x00){4,}', data)]
    return out


def mask(text: str, rules) -> str:
    for _, mid, rx in rules:
        text = rx.sub(f'<{mid}>', text)
    return text


def scan(apk: pathlib.Path, rules) -> list[dict]:
    hits = []

    def check(file: str, where: str, text: str):
        for rule, mid, rx in rules:
            if rule == 'literal_slug' and not DEX.search(file):
                continue  # dex strings only: text assets are the private-name rule's
            for m in rx.finditer(text):
                ctx = text[max(0, m.start() - 50):m.end() + 50]
                hits.append({'file': file, 'at': where, 'rule': rule, 'id': mid, 'context': mask(ctx, rules)})

    for name, data in entries(apk):
        check(name, 'entry name', name)
        if data is None:
            continue
        if pathlib.PurePosixPath(name).suffix.lower() in TEXT_EXT:
            try:
                for i, line in enumerate(data.decode('utf-8').splitlines(), 1):
                    check(name, f'line {i}', line)
                continue
            except UnicodeDecodeError:
                pass
        for off, s in runs(data):
            check(name, f'byte {off}', s)
    return hits


def entries(target: pathlib.Path):
    """(posix name, bytes or None for a directory) for each entry of an APK/zip, or each file under a folder (#1074)."""
    if target.is_dir():
        for f in sorted(p for p in target.rglob('*') if p.is_file()):
            yield f.relative_to(target).as_posix(), f.read_bytes()
        return
    with zipfile.ZipFile(target) as z:
        for info in z.infolist():
            yield info.filename, None if info.is_dir() else z.read(info)


def self_test(apk: pathlib.Path, rules, base: int, slug_words: list[str] = ()) -> dict:
    """Plant one hit per rule in a copy of the APK; every planted hit must be caught at its own file."""
    planted = {}
    for rule, mid, rx in rules:
        if rule == 'home_path':
            token = '/opt/flowstate\\Documents\\x'
        elif rule == 'user_name':
            token = user()
        elif rule == 'pc_name':
            token = host()
        elif rule == 'hashed_name':
            token = 's' + min(rx.prefixes)  # an id of the bundle's 's<16 hex>' shape
        elif rule == 'unmapped_id':
            token = 's' + secrets.token_hex(8)  # a random id the opaque map does not hold
        elif rule == 'literal_slug':
            continue  # planted below, in a dex entry, with the first slug only
        else:
            continue  # private names: planted below with the first one only (enough to prove the list is read)
        planted[mid] = token
    # #1096: one slug, as a dex string (the rule reads dex entries only). Its mask must be the slug's own.
    slug_planted, slug_entries = {}, {}
    slug_rules = [rx for rule, _, rx in rules if rule == 'literal_slug']
    if slug_rules and slug_words:
        word = slug_words[0]  # slug#1 is the first sorted slug word
        slug_entries['classes98.dex'] = b'dex\n035\x00' + bytes([len(word)]) + word.encode() + b'\x00\x01\x02'
        slug_planted['slug#1'] = word
    names = [(mid, rx) for rule, mid, rx in rules if rule == 'private_name']
    if names:
        first = load_names(NAMES_FILE)[0]
        planted[names[0][0]] = first.replace('-', '_').upper()  # a variant spelling, not the exact list entry
    entries = {}
    for i, (mid, token) in enumerate(planted.items()):
        kind = i % 3
        if kind == 0:
            entries[f'assets/judge/zz_planted_{i}.json'] = json.dumps({'title': f'Ask {token} now'}).encode()
        elif kind == 1:
            entries[f'classes9{i}.dex'] = b'dex\n035\x00' + b'\x07' + token.encode() + b'\x00\x01\x02'
        else:
            entries[f'res/raw/zz_planted_{i}.bin'] = b'\x00\x01' + f'say {token}'.encode('utf-16le') + b'\x00\x00'
    entries.update(slug_entries)
    with tempfile.TemporaryDirectory() as tmp:
        if apk.is_dir():
            copy = pathlib.Path(tmp) / 'planted'
            shutil.copytree(apk, copy)
            for name, data in entries.items():
                (copy / name).parent.mkdir(parents=True, exist_ok=True)
                (copy / name).write_bytes(data)
        else:
            copy = pathlib.Path(tmp) / 'planted.apk'
            shutil.copyfile(apk, copy)
            with zipfile.ZipFile(copy, 'a') as z:
                for name, data in entries.items():
                    z.writestr(name, data)
        hits = scan(copy, rules)
    planted.update(slug_planted)
    caught = {mid: any(h['id'] == mid and h['file'] in entries for h in hits) for mid in planted}
    ok = all(caught.values()) and len(hits) >= base + len(planted)
    return {'planted': len(planted), 'caught': sum(caught.values()), 'files': sorted(entries), 'ok': ok,
            'per_rule': caught}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('apk', type=pathlib.Path, help='an APK/zip, or a folder (e.g. a fetched Web entry)')
    ap.add_argument('--out', type=pathlib.Path)
    ap.add_argument('--self-test', action='store_true')
    a = ap.parse_args()
    names = load_names(NAMES_FILE)
    dictionary = local_dictionary()
    opaque = opaque_ids((names or []) + dictionary)
    rules = build_rules(names, dictionary, opaque) + slug_rules((names or []) + dictionary)
    if names is None:
        mode = f'names list absent ({NAMES_FILE.name} not found): generic patterns only (home paths, user name, PC name)'
    else:
        mode = f'names list: {len(names)} private names from data/apk_privacy/{NAMES_FILE.name} + generic patterns'
    mode += f'; hash dictionary: {len(names or []) + len(dictionary)} words (md5/sha1/sha256)'
    mode += f'; opaque map: {len(opaque)} ids'
    print(f'apk privacy gate: {a.apk.name}; {mode}')
    hits = scan(a.apk, rules)
    for h in hits:
        print(f"  HIT {h['rule']:12} {h['file']} {h['at']}: {h['context']!r}")
    hashed = [h for h in hits if h['rule'] == 'hashed_name']
    by_file = {}
    for h in hashed:
        by_file[h['file']] = by_file.get(h['file'], 0) + 1
    result = {'apk': a.apk.name, 'apk_sha256': None if a.apk.is_dir() else hashlib.sha256(a.apk.read_bytes()).hexdigest(), 'folder': a.apk.is_dir(), 'mode': mode,
              'rules': sorted({r for r, _, _ in rules}), 'apk_private_hits': len(hits), 'hits': hits,
              'apk_hash_dictionary_matches': len(hashed), 'hashed_name_hits_by_file': by_file,
              'apk_unmapped_id_hits': sum(h['rule'] == 'unmapped_id' for h in hits), 'opaque_map_ids': len(opaque),
              'dex_literal_slugs': sum(h['rule'] == 'literal_slug' for h in hits),
              'slug_words': len(slugs((names or []) + dictionary))}
    rc = 1 if hits else 0
    if a.self_test:
        st = self_test(a.apk, rules, len(hits), slugs((names or []) + dictionary))
        result['self_test'] = st
        print(f"  self-test: planted {st['planted']}, caught {st['caught']} ({', '.join(st['files'])})")
        if not st['ok']:
            print('FAIL: the planted control was not caught; the gate is broken')
            rc = 1
    if a.out:
        a.out.parent.mkdir(parents=True, exist_ok=True)
        a.out.write_text(json.dumps(result, indent=1) + '\n', encoding='utf-8')
    print(f'apk_hash_dictionary_matches={len(hashed)}')
    print(f"apk_unmapped_id_hits={result['apk_unmapped_id_hits']}")
    print(f"dex_literal_slugs={result['dex_literal_slugs']} (over {result['slug_words']} slug words)")
    print(f'apk_private_hits={len(hits)}')
    if hits:
        print('FAIL: private text in the APK. Fix the bundle builder (tools/judge_bundle/export.py), not the APK.')
    return rc


if __name__ == '__main__':
    sys.exit(main())
