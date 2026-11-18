"""python -m pytest tools/brand_gate/test_gate.py"""
import json, pathlib, sys

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import gate  # noqa: E402

RX = gate.load_words()


def hits(text):
    return [m.group() for m in RX.finditer(text)]


def test_words_whole_word_any_case():
    assert hits('Built for the QUEST with claude') == ['QUEST', 'claude']
    assert hits('metadata, questions, unityped, googled') == []
    assert hits('Hugging-Face and hugging_face and huggingface') == ['Hugging-Face', 'hugging_face', 'huggingface']


def test_model_sizes_are_model_names():
    assert hits('31B/E4B on a panel') == ['31B', 'E4B']
    assert hits('a 1.5B model, 270M, A3B') == ['1.5B', '270M', 'A3B']
    # commit SHAs, byte counts and lowercase units are not sizes
    assert hits('e42257a9e7f78b31b02f 8cb8e4b1 4 MB 12 min') == []


def test_allowlist_refuses_shown_text(tmp_path):
    p = tmp_path / 'allow.json'
    p.write_text(json.dumps([{'file': 'x', 'reason': 'r', 'shown': True}]))
    with pytest.raises(SystemExit):
        gate.load_allow(p)
    p.write_text(json.dumps([{'file': 'x', 'shown': False}]))
    with pytest.raises(SystemExit):
        gate.load_allow(p)


def test_capture_and_assets_scopes(tmp_path):
    cap = tmp_path / 'cap'; cap.mkdir()
    (cap / 'w.json').write_text(json.dumps({'window': 'w', 'strings': ['fine'],
                                            'elements': [{'name': 'TextView@1', 'text': 'Ask Gemini'}]}))
    main = tmp_path / 'main'; (main / 'assets').mkdir(parents=True)
    (main / 'assets' / 'a.json').write_text(json.dumps({'openai_key_name': 'plain', 'title': 'from OpenAI'}))
    s, counts = gate.run(RX, [], capture=cap, src=main)
    assert counts == {'windows': 1, 'asset_files': 1}
    assert [h['match'] for h in s.hits['rendered']] == ['Gemini']
    assert [(h['at'], h['match']) for h in s.hits['assets']] == [('$.title', 'OpenAI')]  # keys are not copy


def test_self_test_catches_plants(tmp_path):
    cap = tmp_path / 'cap'; cap.mkdir()
    main = tmp_path / 'main'; (main / 'assets').mkdir(parents=True)
    st = gate.self_test(RX, [], cap, main, None)
    assert st['ok'] and st['caught'] == {'rendered': True, 'assets': True}


def test_apk_arsc_skips_entry_paths(tmp_path, monkeypatch):
    import zipfile
    apk = tmp_path / 'a.apk'
    with zipfile.ZipFile(apk, 'w') as z:
        z.writestr('resources.arsc', b'')
        z.writestr('res/O1.xml', b'')  # R8-shortened resource path
    monkeypatch.setattr(gate, 'arsc_strings', lambda data: ['res/O1.xml', 'Powered by o1'])
    s = gate.Scan(RX, [])
    gate.scan_apk(s, apk)
    assert [(h['text'], h['match']) for h in s.hits['apk']] == [('Powered by o1', 'o1')]


@pytest.mark.parametrize('css', [
    '.panel .meta {color:white}',
    '.button {cursor:pointer}',
    '.title {display:-webkit-box}',
    '.title {-webkit-line-clamp:2}',
    '.title {-webkit-box-orient:vertical}',
], ids=['meta_class_selector', 'cursor_pointer_property', 'webkit_box_display',
        'webkit_line_clamp_property', 'webkit_box_orient_property'])
def test_windows_css_syntax_is_not_display_copy(css):
    s = gate.Scan(RX, [])
    gate.scan_text(s, 'apk', 'assets/windows.js', 'assets/windows.js', f'const CSS = `{css}`;')
    assert s.hits['apk'] == []


@pytest.mark.parametrize('source', [
    'const title = "cursor:pointer";',
    'const CSS = `.x {content:"Cursor";cursor:pointer}`;',
    'const CSS = `.x {content:"Meta"} .panel .meta {color:white}`;',
    'const CSS = `.x {content:"webkit";display:-webkit-box}`;',
])
def test_css_classification_still_catches_displayed_brands(source):
    for scope in ('assets', 'apk', 'rendered'):
        s = gate.Scan(RX, [])
        gate.scan_text(s, scope, 'assets/windows.js', 'assets/windows.js', source)
        assert s.hits[scope]


def test_css_classification_does_not_exempt_diff_text():
    s = gate.Scan(RX, [])
    gate.scan_text(s, 'apk', 'assets/judge/diffs.json', 'diffs.json',
                   json.dumps({'lines': ['const CSS = `.x {cursor:pointer}`;']}))
    assert s.hits['apk']
