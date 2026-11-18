"""Seeded test (#773, #807): the fixture panel has one overflow, one 2 px misalignment, one small target and one
10 px gross misalignment, plus a clean stack (two aligned lines and a 48 dp indent) that must stay silent.
The lint must report exactly those four and nothing else.

    python -m pytest tools/layout_lint/test_lint.py      (or: python tools/layout_lint/test_lint.py)

fixtures/seeded_fixture.{png,json} is LayoutLintCapture.seeded() as Robolectric rendered it; run.cmd re-checks
the freshly rendered copy on every run (lint.py --self-test), so the fixture cannot drift from the renderer.
"""
import shutil
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lint  # noqa: E402

FIX = Path(__file__).resolve().parent / "fixtures"


def test_seeded_fixture_reports_exactly_the_seeded_defects():
    w = lint.Window(FIX / "seeded_fixture.json")
    found = sorted((v["rule"], v["tag"]) for v in lint.lint_window(w))
    assert found == sorted(lint.SEEDED_EXPECTED), found


def test_self_test_passes_on_fixture_dir():
    with tempfile.TemporaryDirectory() as d:
        for p in FIX.glob("seeded_fixture.*"):
            shutil.copy(p, d)
        assert lint.main([d, "--self-test"]) == 0


def test_misalignment_is_two_px():
    w = lint.Window(FIX / "seeded_fixture.json")
    v = [v for v in lint.lint_window(w) if v["rule"] == "near_miss_alignment"]
    assert len(v) == 1 and "(2 px off)" in v[0]["detail"], v


def test_gross_misalignment_fires_on_the_planted_line():
    w = lint.Window(FIX / "seeded_fixture.json")
    v = [v for v in lint.lint_window(w) if v["rule"] == "gross_misalignment"]
    assert len(v) == 1 and v[0]["tag"] == "seed-gross" and ": 10 px off" in v[0]["detail"], v
    assert v[0]["fault_deg"] > 0


def test_clean_stack_and_indent_stay_silent():
    w = lint.Window(FIX / "seeded_fixture.json")
    tags = {e.get("tag") for e in w.elements}
    assert {"seed-clean-a", "seed-clean-b", "seed-clean-indent"} <= tags, tags   # the clean case was rendered
    hits = [v for v in lint.lint_window(w) if (v["tag"] or "").startswith("seed-clean")]
    assert hits == [], hits


def test_gross_rule_bounds():
    """More than 3 px and less than a column gap (24 dp = 43.2 px at 1.8 px/dp): 3 px and 44 px stay silent."""
    def window(offset):
        texts = [{"index": i + 1, "parent": 0, "name": f"t{i}", "class": "android.widget.TextView", "tag": f"t{i}",
                  "rect": [x, 60 * i, x + 300, 60 * i + 50], "visible_rect": [x, 60 * i, x + 300, 60 * i + 50],
                  "text": "Line", "gravity_h": "start", "button": False,
                  "lines": [{"start": 0, "end": 4, "left": x, "right": x + 100, "top": 60 * i, "bottom": 60 * i + 50,
                             "baseline": 60 * i + 40, "ellipsis": 0}]}
                 for i, x in enumerate([0, 0, offset])]
        root = {"index": 0, "parent": -1, "name": "root", "class": "LinearLayout", "rect": [0, 0, 1800, 1296],
                "visible_rect": [0, 0, 1800, 1296], "orientation": "vertical"}
        w = lint.Window.__new__(lint.Window)
        w.meta = {"window": "t", "m_per_px": 1 / 1800, "distance_m": 1.0, "density": 1.8}
        w.name, w.elements, w.image = "t", [root] + texts, None
        w.by_index = {e["index"]: e for e in w.elements}
        w.children = lint.defaultdict(list)
        for e in w.elements:
            w.children[e["parent"]].append(e)
        return [v["rule"] for v in lint.alignment(w)]
    assert window(3) == ["near_miss_alignment"]
    assert window(4) == ["gross_misalignment"]
    assert window(43) == ["gross_misalignment"]
    assert window(44) == []


def test_type_gate_fails_small_pushed_text_and_short_list_pages():
    ok = {w: {"min_text_angle_push_deg": 0.375, "rows_per_page": 5} for w in lint.LIST_WINDOWS}
    assert lint.type_gate(ok) == []
    small = {**ok, "settings": {"min_text_angle_push_deg": 0.34, "rows_per_page": None}}
    assert [p for p in lint.type_gate(small) if p.startswith("settings")], lint.type_gate(small)
    short = {**ok, "blueprint": {"min_text_angle_push_deg": 0.5, "rows_per_page": 2}}
    assert lint.type_gate(short) == ["blueprint: rows_per_page 2 < 3"]
    missing = {k: v for k, v in ok.items() if k != "code"}
    assert lint.type_gate(missing) == ["code: rows_per_page None < 3"]


if __name__ == "__main__":
    for t in (test_seeded_fixture_reports_exactly_the_seeded_defects, test_self_test_passes_on_fixture_dir,
              test_misalignment_is_two_px, test_gross_misalignment_fires_on_the_planted_line,
              test_clean_stack_and_indent_stay_silent, test_gross_rule_bounds,
              test_type_gate_fails_small_pushed_text_and_short_list_pages):
        t()
        print("ok", t.__name__)


def test_approved_type_and_target_still_catch_shrunk_controls():
    import copy
    w = lint.Window(FIX / 'seeded_fixture.json')
    w.name = 'approved'
    w.meta.update(kind='android-view', density=1.8, distance_m=2.8)
    e = copy.deepcopy(w.elements[1])
    e.update(text='Readable', text_size_px=54, clickable=True, min_span_scale=1,
             rect=[0, 0, 130, 130], visible_rect=[0, 0, 130, 130], lines=[], padding=[0]*4, margin=[0]*4)
    w.elements = [e]
    w.by_index = {e['index']: e}
    w.children = lint.defaultdict(list)
    assert not {'text_too_small', 'small_target'} & {v['rule'] for v in lint.lint_window(w)}
    e['text_size_px'] /= 2
    e['rect'][3] /= 2
    e['visible_rect'][3] /= 2
    assert {'text_too_small', 'small_target'} <= {v['rule'] for v in lint.lint_window(w)}


def test_exact_exception_cannot_hide_a_different_measurement(monkeypatch, tmp_path):
    import json
    row = dict(window='one', rule='small_target', element_index=2, detail='50 dp', rect=[0,0,50,50])
    p = tmp_path / 'exceptions.json'
    p.write_text(json.dumps([dict(signature=lint.signature(row), reason='Approved 50 dp tab')]))
    monkeypatch.setattr(lint, 'EXCEPTIONS', p)
    assert lint.apply_exceptions([row])[0] == []
    assert lint.apply_exceptions([dict(row, detail='25 dp')])[0]
    p.write_text(json.dumps([dict(signature=lint.signature(row), reason='')]))
    assert lint.apply_exceptions([row])[0]


def test_vertical_rows_survive_wrappers_and_horizontal_panning():
    w = lint.Window(FIX / 'seeded_fixture.json')
    def node(i, parent, rect, **kw):
        return dict(index=i, parent=parent, rect=rect, visible_rect=rect, name=str(i), **kw)
    w.elements = [node(0, -1, [0,0,100,150], scroll_container=True, **{'class':'ScrollView'}),
                  node(1, 0, [0,0,500,400], scroll_container=True, **{'class':'HorizontalScrollView'}),
                  node(2, 1, [0,0,500,400], orientation='vertical')]
    w.elements += [node(i+3, 2, [0,i*40,500,(i+1)*40], orientation='horizontal') for i in range(10)]
    w.by_index = {e['index']:e for e in w.elements}
    w.children = lint.defaultdict(list)
    for e in w.elements: w.children[e['parent']].append(e)
    assert not lint.slits_and_clipped(w)
    w.elements[0]['visible_rect'] = [0,0,100,50]
    assert [v['rule'] for v in lint.slits_and_clipped(w)] == ['scroll_slit']


def test_look_gate_still_requires_the_main_captures():
    ok = {w: {"look_tokens": True} for w in lint.LIST_WINDOWS}
    assert lint.type_gate(ok) == []
    del ok['fleet']
    assert lint.type_gate(ok) == ['fleet: required capture missing']


def test_only_approved_k8_mirror_uses_look_tokens():
    w = lint.Window(FIX / 'seeded_fixture.json')
    w.name = 'chat_briefing_web'
    w.meta.update(kind='scene-mirror', density=1.8, source='windows.js look K8 (LookK8.mount HTML)')
    assert lint.uses_look(w)
    w.meta['source'] = 'legacy web'
    assert not lint.uses_look(w)
    w.meta['source'] = 'windows.js look K8 (LookK8.mount HTML)'
    w.name = 'other_web'
    assert not lint.uses_look(w)
