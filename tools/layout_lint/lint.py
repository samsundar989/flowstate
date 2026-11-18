"""Layout lint (#773): rule violations from rendered windows.

Input: a capture directory holding <window>.png + <window>.json (LayoutLintCapture.kt for native views and the
3D architecture boxes, web_capture.mjs for live WebView dashboards). Output: report.json, report.md and crops/
in the same directory.

    python tools/layout_lint/lint.py <capture dir> [--check-baseline] [--write-baseline] [--self-test]

Rules (every violation names the element):
  text_clipped         text ellipsised, cut off vertically or horizontally, or label characters dropped,
                       unless the element's tag contains "lint-ok:clip"
  word_broken          a line ends inside a word (letter/digit on both sides of the break)
  outside_parent       an element drawn beyond its parent's bounds (scroll containers excepted)
  near_miss_alignment  siblings whose text left edges (column) or first baselines (row) differ by 1-3 px
  gross_misalignment   (#807) stacked text whose left edge (column) or first baseline (row) sits more than 3 px
                       and less than a column gap (24 dp; 2.4 cm on the 3D scene) off the stack's common edge,
                       the edge most of the stack shares (ties: the first element's)
  off_grid_spacing     padding/margin outside approved look reference values (legacy/scene: 8 dp grid)
  small_target         native look target under 64 dp; legacy/scene under 3 deg visual angle (docs/design_constraints.md item 6:
                       ">= 3 deg visual angle ... 5.2 cm at 1 m (2*d*tan 1.5 deg)")
  text_too_small       text below both the approved surface token and the legacy 1.0 deg floor (the ~1 deg legibility floor,
                       .autoflow/wiki/decisions.md line 150, rubric P2.1 "readable at 1-2 m")
  low_contrast         WCAG contrast of text colour against the rendered background under 4.5:1
  scroll_slit          (#1191) a scroll container whose viewport shows fewer than three whole rows of its own content
                       (fewer than all of them, when it has under three): a hand-scrolled slit nobody can use
  control_clipped      (#1191) a clickable view whose visible rect is smaller than its rect in the captured state
                       (cut by a scroll viewport or a panel edge, or not shown at all while its parent is)
"""
from __future__ import annotations

import argparse
import json
import hashlib
import math
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
BASELINE = HERE / "baseline.json"
RULES = ["text_clipped", "word_broken", "outside_parent", "near_miss_alignment", "gross_misalignment", "off_grid_spacing",
         "small_target", "text_too_small", "low_contrast", "scroll_slit", "control_clipped"]
TARGET_DEG = 3.0
TEXT_DEG = 1.0
MIN_XHEIGHT_DEG = 0.35    # #861: Microsoft MR "Text in Unity" minimum legible angle, on the x-height; gated after one push (#880)
LIST_WINDOWS = ("fleet", "blueprint", "code", "build", "chat")   # #880: windows whose page is a list of rows
MIN_ROWS = 3              # #880: a list window must show at least this many whole rows on its first page
CONTRAST = 4.5
GRID_DP = 8
# Approved look: .autoflow/wiki/look_nov17.md:44-47,55 and its inline reference values.
# Code.dc.html:15,17,29,31,34,41-42; Question.dc.html:14,17,24; Dock.dc.html.
# 84 dp switch inset = Dock.dc.html:19 switch width 68 + look_nov17.md:55 gap 16.
LOOK_SPACING_DP = (0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 32, 36, 40, 48, 56, 64, 84)
EXCEPTIONS = HERE / "exceptions.json"


def uses_look(w):
    # The recorded briefing is the K8 DOM mirror of Main.dc.html, not legacy web.
    # look_nov17.md:44-47,55 supplies the same 30 dp label / 64 dp target floors.
    approved = w.meta.get("kind") == "android-view" or (
        w.name == "chat_briefing_web" and w.meta.get("source") == "windows.js look K8 (LookK8.mount HTML)")
    return approved and w.name != SEEDED and bool(w.meta.get("density"))


def signature(v):
    """Bind an allowance to the measured fault, never a count or wildcard."""
    fields = [v[k] for k in ("window", "rule", "element_index", "detail", "rect")]
    return hashlib.sha256(json.dumps(fields, ensure_ascii=True).encode()).hexdigest()


def apply_exceptions(violations):
    entries = json.loads(EXCEPTIONS.read_text(encoding="utf-8")) if EXCEPTIONS.exists() else []
    allowed = {e["signature"]: e["reason"] for e in entries if e.get("reason", "").strip()}
    real, exceptions = [], []
    for v in violations:
        reason = allowed.get(signature(v))
        (exceptions if reason else real).append({**v, "exception_reason": reason} if reason else v)
    return real, exceptions

SEEDED = "seeded_fixture"
SEEDED_EXPECTED = {("text_clipped", "seed-overflow"), ("near_miss_alignment", "seed-left-b"), ("small_target", "seed-small"),
                   ("gross_misalignment", "seed-gross")}
COLUMN_GAP_DP = 24        # 3 grid units: a wider offset is a deliberate indent or column, not a misalignment
COLUMN_GAP_M = 0.024      # the same gap on the 3D scene, which has no dp
BASELINE_VERSION = 3      # #807: gross_misalignment, settings, stations, graph detail; #1191: scroll_slit, control_clipped, settings_<tab>
GATED_KINDS = {"android-view", "scene-mirror"}   # deterministic Recorded content; live web is reported, not gated


def deg(size_m: float, distance_m: float) -> float:
    return math.degrees(2 * math.atan(size_m / (2 * distance_m)))


def luminance(rgb) -> float:
    def ch(c):
        c = c / 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = rgb[:3]
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)


def contrast(a, b) -> float:
    la, lb = sorted((luminance(a), luminance(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)


def argb(hexs: str):
    v = int(hexs.lstrip("#"), 16)
    return (v >> 24) & 255, (v >> 16) & 255, (v >> 8) & 255, v & 255


def visible_lines(e):
    inner = e.get("inner")
    lines = e.get("lines") or []
    if not inner:
        return lines
    return [ln for ln in lines if ln["top"] < inner[3] - 1]


def contained(child, parent, tol=1):
    return (child[0] >= parent[0] - tol and child[1] >= parent[1] - tol and
            child[2] <= parent[2] + tol and child[3] <= parent[3] + tol)


class Window:
    def __init__(self, path: Path):
        self.path = path
        self.meta = json.loads(path.read_text(encoding="utf-8"))
        self.name = self.meta["window"]
        self.elements = self.meta["elements"]
        self.by_index = {e["index"]: e for e in self.elements}
        self.children = defaultdict(list)
        for e in self.elements:
            self.children[e["parent"]].append(e)
        png = path.with_suffix(".png")
        self.png = png
        self.image = None

    def open_image(self):
        if self.image is None and self.png.exists():
            with Image.open(self.png) as original:
                self.image = original.convert("RGB")

    def close_image(self):
        if self.image is not None:
            self.image.close()
            self.image = None

    def background(self, e):
        self.open_image()
        if "background_rgb" in e:
            return tuple(e["background_rgb"])
        if self.image is None:
            return (0, 0, 0)
        box = e.get("inner") or e["rect"]
        vr = e.get("visible_rect") or e["rect"]
        x0, y0 = max(box[0], vr[0]), max(box[1], vr[1])
        x1, y1 = min(box[2], vr[2]), min(box[3], vr[3])
        x0, y0 = max(0, int(x0)), max(0, int(y0))
        x1, y1 = min(self.image.width, int(x1)), min(self.image.height, int(y1))
        if x1 - x0 < 2 or y1 - y0 < 2:
            return (0, 0, 0)
        crop = self.image.crop((x0, y0, x1, y1))
        crop.thumbnail((200, 200))
        counts = Counter((r // 8, g // 8, b // 8) for r, g, b in (crop.get_flattened_data() if hasattr(crop, 'get_flattened_data') else crop.getdata()))
        q = counts.most_common(1)[0][0]
        return tuple(c * 8 + 4 for c in q)


def violation(w: Window, rule: str, e, detail: str, severity: float, rect=None, extent_px=0.0, lost=False, deg_direct=None):
    """extent_px: how big the fault is on the panel (offset, overflow, hidden text height); the report ranks
    faults by that size in degrees of visual angle at the window's distance, text lost first."""
    size_deg = deg_direct if deg_direct is not None else deg(max(0.0, extent_px) * w.meta["m_per_px"], w.meta["distance_m"])
    return {"window": w.name, "rule": rule, "element": e.get("name"), "tag": e.get("tag"),
            "detail": detail, "severity": round(max(0.0, min(1.0, severity)), 3),
            "fault_deg": round(size_deg, 3), "text_lost": bool(lost), "element_index": e.get("index"),
            "rect": [int(v) for v in (rect or e.get("visible_rect") or e["rect"])]}


def column_gap_px(w: Window) -> float:
    dens = w.meta.get("density")
    return COLUMN_GAP_DP * dens if dens else COLUMN_GAP_M / w.meta["m_per_px"]


def height(r) -> float:
    return (r[3] - r[1]) if r else 0


def span_scale(e) -> float:
    """#861: the smallest size a RelativeSizeSpan/AbsoluteSizeSpan gives any of the element's text (1 = no span)."""
    return min(1.0, float(e.get("min_span_scale", 1.0)))


def type_stats(w: Window):
    """#861: the smallest visible text on the window as a wearer sees it. x-height angle at the window's default
    distance (min_text_angle_deg) and after one push-away step (min_text_angle_push_deg), em angle, and the
    list rows shown on the first page (rows_per_page: blank-line separated rows in the recorded page)."""
    d, mpp = w.meta["distance_m"], w.meta["m_per_px"]
    push = w.meta.get("push_distance_m")
    xs, ems, sizes = [], [], []
    rows = None
    for e in w.elements:
        text = (e.get("text") or "").strip()
        if e.get("visible_rect") is None or not text or not e.get("text_size_px"):
            continue
        k = span_scale(e)
        ems.append(e["text_size_px"] * k * mpp)
        sizes.append(e["text_size_px"] * k / (w.meta.get("density") or 1))
        if e.get("x_height_px"):
            xs.append(e["x_height_px"] * k * mpp)
        if e.get("tag") == "recorded-page":
            # A list row is one logical line of the window's text (one appendLine in RecordedContent.window), counted
            # only when every wrapped line of it lies inside the page box.
            inner = e.get("inner")
            whole = [ln for ln in (e.get("lines") or []) if not inner or ln["bottom"] <= inner[3] + 1]
            shown = "".join(ln["text"] for ln in whole)
            full = "".join(ln["text"] for ln in (e.get("lines") or []))
            if len(shown) < len(full):          # a row cut at the box edge does not count
                shown = shown[:shown.rfind("\n") + 1] if "\n" in shown else ""
            rows = len([r for r in shown.split("\n") if r.strip()])
    out = {"min_sp": round(min(sizes), 1) if sizes else None,
           "min_em_deg": round(deg(min(ems), d), 3) if ems else None,
           "min_text_angle_deg": round(deg(min(xs), d), 3) if xs else None,
           "min_text_angle_push_deg": round(deg(min(xs), push), 3) if xs and push else None,
           "push_distance_m": round(push, 3) if push else None, "distance_m": round(d, 3),
           "rows_per_page": rows, "look_tokens": uses_look(w)}
    return out


def whole(e) -> bool:
    vr = e.get("visible_rect")
    return vr is not None and list(vr) == list(e["rect"])


def slits_and_clipped(w: Window):
    """#1191: scroll_slit and control_clipped (the Settings list was a 44 px slit over 30 rows)."""
    out = []
    target_px = 2 * w.meta["distance_m"] * math.tan(math.radians(TARGET_DEG / 2)) / w.meta["m_per_px"]
    for e in w.elements:
        par = w.by_index.get(e["parent"])
        if e.get("clickable") and not whole(e) and (par is None or par.get("visible_rect") is not None):
            r, vr = e["rect"], e.get("visible_rect")
            seen = height(vr) * max(0, vr[2] - vr[0]) if vr else 0
            area = max(1, height(r) * (r[2] - r[0]))
            cut = max(height(r) - height(vr), (r[2] - r[0]) - (vr[2] - vr[0])) if vr else max(height(r), r[2] - r[0])
            out.append(violation(w, "control_clipped", e,
                                 f"{seen / area:.0%} of it shown ({'not shown' if not vr else f'visible {vr} of {r}'})",
                                 0.6 + 0.4 * (1 - seen / area), rect=r, extent_px=cut, lost=True))
        if e.get("scroll_container") and e.get("visible_rect") is not None:
            # Count semantic vertical rows through structural columns and nested scroll wrappers.
            # Horizontal panning does not reduce the number of whole vertical rows visible.
            if "HorizontalScrollView" in e.get("class", ""):
                continue
            def rows_under(node):
                kids = w.children.get(node["index"], [])
                if kids and (node.get("orientation") == "vertical" or node.get("scroll_container")):
                    return [row for child in kids for row in rows_under(child)]
                return [node]
            rows = [row for child in w.children.get(e["index"], []) for row in rows_under(child)]
            if not rows:
                continue
            viewport = e["visible_rect"]
            shown = sum(1 for r in rows if r["rect"][1] >= viewport[1] - 1 and r["rect"][3] <= viewport[3] + 1)
            need = min(MIN_ROWS, len(rows))
            if shown < need:
                vr = e["visible_rect"]
                out.append(violation(w, "scroll_slit", e,
                                     f"viewport {height(vr):.0f} px shows {shown} whole rows of {len(rows)} (< {need})",
                                     0.9, extent_px=max(0.0, target_px * need - height(vr)), lost=True))
    return out


def lint_window(w: Window):
    out = []
    m_per_px = w.meta["m_per_px"]
    d = w.meta["distance_m"]
    density = w.meta.get("density")
    target_m = 2 * d * math.tan(math.radians(TARGET_DEG / 2))
    out += slits_and_clipped(w)
    for e in w.elements:
        if e.get("visible_rect") is None:
            # Text the layout squeezed to nothing or pushed past a non-scrolling panel edge: content lost.
            # A scroll pane only excuses it when the pane itself has room to scroll (#807: the graph detail's
            # ScrollView is laid out 0 px tall, so its text can never be scrolled into view).
            par = w.by_index.get(e["parent"])
            scrolls, anc = False, par
            while anc is not None:              # any scroll pane above it that has room to scroll excuses it
                if anc.get("scroll_container") and height(anc.get("visible_rect")) > 1:
                    scrolls = True
                    break
                anc = w.by_index.get(anc["parent"])
            lost_text = (e.get("text") or e.get("hidden_text") or "").strip()
            if lost_text and par is not None and par.get("visible_rect") is not None \
                    and not scrolls and "lint-ok:clip" not in (e.get("tag") or ""):
                # hidden_text: a container not shown hides its children; their size is not dumped, assume 16 sp.
                ts = e.get("text_size_px") or (28.8 if e.get("hidden_text") else 0)
                lost_px = max(ts * 1.2 * max(1, len(e.get("lines") or [])), min(height(e["rect"]), 4 * ts) if ts else 0)
                where = "a 0 px scroll pane" if (e.get("scroll_container") or par.get("scroll_container")) \
                    else "squeezed to 0 px or pushed past the panel edge"
                if e.get("hidden_text"):
                    where += f' (hides "{lost_text[:60]}")'
                out.append(violation(w, "text_clipped", e, f"not shown: {where}",
                                     1.0, rect=par["visible_rect"], extent_px=lost_px, lost=True))
            continue
        tag = e.get("tag") or ""
        text = e.get("text")
        parent = w.by_index.get(e["parent"])
        lines = e.get("lines") or []
        # text_clipped
        if text and text.strip() and "lint-ok:clip" not in tag:
            inner = e.get("inner")
            hidden = [ln for ln in lines if inner and ln["bottom"] > inner[3] + 1]
            ell = sum(ln.get("ellipsis", 0) for ln in lines)
            wide = [ln for ln in visible_lines(e) if inner and ln["right"] > inner[2] + 1 and e["class"] != "Text3D"]
            dropped = e.get("chars_dropped", 0)
            reasons = []
            if hidden:
                reasons.append(f"{len(hidden)} of {len(lines)} lines cut off below the box")
            if ell:
                reasons.append(f"{ell} characters ellipsised")
            if wide:
                reasons.append(f"{len(wide)} lines wider than the box")
            if dropped:
                reasons.append(f"{dropped} characters dropped (label keeps 3 rows of 9)")
            if reasons:
                sev = max(len(hidden) / max(1, len(lines)), dropped / max(1, len(text)), 0.5 if ell or wide else 0)
                ts = e.get("text_size_px") or 0
                extent = max(sum(ln["bottom"] - ln["top"] for ln in hidden), ell * 0.5 * ts, dropped * 0.5 * ts,
                             max((ln["right"] - inner[2] for ln in wide), default=0))
                out.append(violation(w, "text_clipped", e, "; ".join(reasons), 0.6 + 0.4 * sev, extent_px=extent, lost=True))
        # word_broken
        if text and lines:
            broken = []
            shown = {id(ln) for ln in visible_lines(e)}
            for ln in lines[:-1]:
                if id(ln) not in shown:
                    continue
                end = ln["end"]
                if 0 < end < len(text) and text[end - 1].isalnum() and text[end].isalnum():
                    a = text[:end].split()[-1] if text[:end].split() else ""
                    b = text[end:].split()[0] if text[end:].split() else ""
                    broken.append(f"{a[-12:]}|{b[:12]}")
            if broken:
                out.append(violation(w, "word_broken", e, "breaks inside: " + ", ".join(broken[:6]) +
                                     (f" (+{len(broken) - 6})" if len(broken) > 6 else ""), 0.9,
                                     extent_px=e.get("text_size_px") or 0))
        # outside_parent
        # The page itself scrolls in a WebView, so body/html never clip their children.
        if parent is not None and not parent.get("scroll_container") and parent.get("class") not in ("dom:body", "dom:html"):
            rect = e["rect"]
            pr = parent["rect"]
            if e["class"] == "Text3D":
                rect = [min(ln["left"] for ln in lines), min(ln["top"] for ln in lines),
                        max(ln["right"] for ln in lines), max(ln["bottom"] for ln in lines)]
            if not contained(rect, pr):
                over = max(pr[0] - rect[0], pr[1] - rect[1], rect[2] - pr[2], rect[3] - pr[3])
                size = max(1, min(rect[2] - rect[0], rect[3] - rect[1]))
                cm = over * m_per_px * 100
                vr = e.get("visible_rect")
                cut = bool(text and text.strip() and e["class"] != "Text3D" and vr and
                           (vr[2] - vr[0]) * (vr[3] - vr[1]) < 0.9 * (rect[2] - rect[0]) * (rect[3] - rect[1]))
                out.append(violation(w, "outside_parent", e,
                                     f"extends {over:.0f} px ({cm:.1f} cm) beyond {parent.get('name')}", 0.6 + 0.4 * min(1, over / size),
                                     rect=[min(rect[0], pr[0]), min(rect[1], pr[1]), max(rect[2], pr[2]), max(rect[3], pr[3])],
                                     extent_px=over, lost=cut))
        # off_grid_spacing
        if w.meta.get("grid") and density:
            bad, dev = [], 0.0
            for label, values in (("padding", e.get("padding")), ("margin", e.get("margin"))):
                for side, v in zip("ltrb", values or []):
                    off = (min(abs(v - token * density) for token in LOOK_SPACING_DP) if uses_look(w) else
                           abs(v - round(v / density / GRID_DP) * GRID_DP * density)) if v else 0
                    if off > 1.0:
                        bad.append(f"{label}-{side} {v}px={v / density:.1f}dp")
                        dev = max(dev, off)
            if bad:
                out.append(violation(w, "off_grid_spacing", e, ", ".join(bad), 0.3, extent_px=dev))
        # small_target
        if e.get("clickable"):
            vr = e["visible_rect"]
            side = min(vr[2] - vr[0], vr[3] - vr[1]) * m_per_px
            floor_m = 64 * density * m_per_px if uses_look(w) else target_m
            if side < floor_m - m_per_px:
                out.append(violation(w, "small_target", e,
                                     f"{side * 100:.1f} cm < {floor_m * 100:.1f} cm ({'look target 64 dp' if uses_look(w) else '3 deg'} at {d:.2f} m)", 0.5 + 0.5 * (1 - side / floor_m),
                                     deg_direct=deg(floor_m, d) - deg(side, d)))
        # text_too_small / low_contrast
        if text and text.strip() and e.get("text_size_px"):
            em = e["text_size_px"] * span_scale(e) * m_per_px
            a = deg(em, d)
            # Dock.dc.html:15,17-18 explicitly scales dock labels and Settings tabs/rows.
            token_dp = (17 if w.name == "dock" else
                        (19 if (e.get("tag") or "").startswith("settings-tab-") else 21) if w.name.startswith("settings_") else
                        26 if w.name == "code" else 30)
            # Existing labels that already meet the angular floor remain valid. The approved
            # tokens additionally accept smaller angles at their captured production distance.
            floor_deg = min(TEXT_DEG, deg(token_dp * density * m_per_px, d)) if uses_look(w) else TEXT_DEG
            if a < floor_deg - (0.01 if uses_look(w) else 0):
                out.append(violation(w, "text_too_small", e, f"em {em * 1000:.1f} mm = {a:.2f} deg at {d:.2f} m (< {floor_deg:.2f} deg)",
                                     0.4 + 0.6 * (1 - a / floor_deg), deg_direct=floor_deg - a))
            if e.get("text_color"):
                al, r, g, b = argb(e["text_color"])
                bg = w.background(e)
                k = al / 255 * float(e.get("alpha", 1.0))
                fg = tuple(round(k * c + (1 - k) * bc) for c, bc in zip((r, g, b), bg))
                ratio = contrast(fg, bg)
                if ratio < CONTRAST:
                    out.append(violation(w, "low_contrast", e, f"{ratio:.2f}:1 text {fg} on {bg}", 0.4 + 0.6 * (1 - ratio / CONTRAST),
                                         extent_px=e["text_size_px"]))
    out += alignment(w)
    return out


def edge(e, orientation):
    lines = e.get("lines")
    if orientation == "vertical":
        if lines and e.get("gravity_h", "start") == "start" and not e.get("button"):
            return lines[0]["left"]
        return None
    if lines:
        return lines[0]["baseline"]
    return None


def alignment(w: Window):
    out = []
    for e in w.elements:
        orient = e.get("orientation")
        if not orient or e.get("visible_rect") is None:
            continue
        kids = [k for k in w.children[e["index"]] if k.get("visible_rect") is not None]
        edges = [(k, edge(k, orient)) for k in kids]
        edges = [(k, v) for k, v in edges if v is not None]
        if len(edges) < 2:
            continue
        groups = defaultdict(list)
        for k, v in edges:
            groups[round(v)].append(k)
        values = sorted(groups)
        flagged = set()
        for i, a in enumerate(values):
            for b in values[i + 1:]:
                if 1 <= b - a <= 3:
                    minority = a if len(groups[a]) < len(groups[b]) else b
                    other = b if minority == a else a
                    for k in groups[minority]:
                        if k["index"] in flagged:
                            continue
                        flagged.add(k["index"])
                        what = "left edge" if orient == "vertical" else "baseline"
                        ref = groups[other][0]
                        out.append(violation(w, "near_miss_alignment", k,
                                             f"{what} {minority} px vs {other} px of {ref.get('name')} ({abs(b - a)} px off)", 0.7,
                                             rect=[min(k["rect"][0], ref["rect"][0]), min(k["rect"][1], ref["rect"][1]),
                                                   max(k["rect"][2], ref["rect"][2]), max(k["rect"][3], ref["rect"][3])],
                                             extent_px=abs(b - a)))
        # gross_misalignment (#807): off the stack's common edge by more than 3 px but less than a column gap.
        gap = column_gap_px(w)
        common = max(values, key=lambda v: (len(groups[v]), -min(k["index"] for k in groups[v])))
        ref = min(groups[common], key=lambda k: k["index"])
        what = "left edge" if orient == "vertical" else "baseline"
        for v in values:
            off = abs(v - common)
            if not (3 < off < gap):
                continue
            for k in groups[v]:
                out.append(violation(w, "gross_misalignment", k,
                                     f"{what} at {v} px, the stack's is {common} px ({ref.get('name')}): {off} px off", 0.8,
                                     rect=[min(k["rect"][0], ref["rect"][0]), min(k["rect"][1], ref["rect"][1]),
                                           max(k["rect"][2], ref["rect"][2]), max(k["rect"][3], ref["rect"][3])],
                                     extent_px=off))
    return out


def crop(w: Window, v, path: Path):
    w.open_image()
    if w.image is None:
        return None
    x0, y0, x1, y1 = v["rect"]
    pad = 40
    box = (max(0, x0 - pad), max(0, y0 - pad), min(w.image.width, x1 + pad), min(w.image.height, y1 + pad))
    if box[2] <= box[0] or box[3] <= box[1]:
        return None
    img = w.image.crop(box).copy()
    draw = ImageDraw.Draw(img)
    draw.rectangle((x0 - box[0], y0 - box[1], x1 - box[0], y1 - box[1]), outline=(255, 40, 40), width=4)
    if img.width > 1600:
        img = img.resize((1600, max(1, round(img.height * 1600 / img.width))))
    img.save(path)
    return path


def counts(violations, windows):
    per = {w.name: {r: 0 for r in RULES} for w in windows}
    for v in violations:
        per[v["window"]][v["rule"]] += 1
    return per


def type_gate(type_per):
    """#880: fail when any gated window's smallest x-height falls under MIN_XHEIGHT_DEG after one push-away step, or a
    list window shows fewer than MIN_ROWS whole rows on its first page (a missing list window counts as a failure)."""
    problems = []
    for win, t in sorted(type_per.items()):
        push = t.get("min_text_angle_push_deg")
        if not t.get("look_tokens") and push is not None and push < MIN_XHEIGHT_DEG:
            problems.append(f"{win}: x-height {push} deg after one push < {MIN_XHEIGHT_DEG}")
    # New structured pages use per-element type/target rules and semantic scroll rows.
    if any(t.get("look_tokens") for t in type_per.values()):
        return problems + [f"{win}: required capture missing" for win in LIST_WINDOWS if win not in type_per]
    for win in LIST_WINDOWS:
        rows = (type_per.get(win) or {}).get("rows_per_page")
        if rows is None or rows < MIN_ROWS:
            problems.append(f"{win}: rows_per_page {rows} < {MIN_ROWS}")
    return problems


def git_main_baseline():
    try:
        raw = subprocess.run(["git", "show", "main:tools/layout_lint/baseline.json"], cwd=REPO, capture_output=True,
                             text=True, check=True).stdout
        return json.loads(raw)
    except Exception:
        return None


def check_baseline(per, total):
    base = json.loads(BASELINE.read_text(encoding="utf-8"))
    problems = []
    if total > base["total"]:
        problems.append(f"total {total} > baseline {base['total']}")
    for win, rules in per.items():
        for rule, n in rules.items():
            allowed = base["windows"].get(win, {}).get(rule, 0)
            if n > allowed:
                problems.append(f"{win}/{rule}: {n} > baseline {allowed}")
    main = git_main_baseline()
    if main:
        mv, bv = main.get("version", 1), base.get("version", 1)
        if bv == mv + 1 and bv == BASELINE_VERSION and base.get("date") and base.get("date") != main.get("date"):
            # A new baseline version (new rules or windows) is set once, by the task that adds them; it records
            # what it replaced. From then on the may-only-go-down check below applies against it.
            print(f"layout lint: baseline v{bv} ({base['date']}) replaces main's v{mv} (total {main['total']})")
        elif bv != mv:
            problems.append(f"baseline version {bv} vs main's {mv}: a new version must be exactly one above main's, "
                            f"dated, and match lint.py BASELINE_VERSION={BASELINE_VERSION}")
        else:
            if base["total"] > main["total"]:
                problems.append(f"baseline total raised from main's {main['total']} to {base['total']} (it may only go down)")
            for win, rules in base["windows"].items():
                for rule, n in rules.items():
                    if n > main["windows"].get(win, {}).get(rule, n):
                        problems.append(f"baseline {win}/{rule} raised above main's (it may only go down)")
    if total < base["total"]:
        print(f"layout lint: {total} is below the baseline {base['total']}; lower it with --write-baseline")
    return problems


APP = REPO / "native" / "overhand" / "app" / "src" / "main" / "java"
# Causes that are a rule of the code, not a literal on the element (the 3D labels are meshes).
KNOWN_CAUSES = [
    (lambda v, e: v["window"].startswith("architecture_graph") and v["rule"] in ("word_broken", "text_clipped"),
     "name.chunked(9).take(3)"),
    (lambda v, e: v["window"].startswith("architecture_graph") and v["rule"] == "outside_parent",
     "n.x.toFloat()-0.20f,n.y.toFloat()+0.06f-row*0.095f"),
    (lambda v, e: list(e.get("padding") or []) == [12, 4, 12, 4] and e.get("class", "").endswith("TextView"),
     "setPadding(12,4,12,4)"),
]


class Sources:
    """Finds the app source line that creates an element: a known rule of the code, its tag, or its text."""

    def __init__(self):
        self.lines = []
        for p in sorted(APP.rglob("*.kt")):
            rel = p.relative_to(REPO).as_posix()
            for i, ln in enumerate(p.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
                self.lines.append((rel, i, ln))

    def find(self, needle: str):
        for rel, i, ln in self.lines:
            if needle in ln:
                return f"{rel}:{i}", ln.strip()[:140]
        return None

    def cause(self, v, e, w):
        for test, needle in KNOWN_CAUSES:
            if test(v, e):
                hit = self.find(needle)
                if hit:
                    return hit
        tag = e.get("tag")
        if tag and not tag.startswith("seed"):
            hit = self.find(f'"{tag}"')
            if hit:
                return hit
        text = (e.get("text") or "").strip()
        parts = [text] + [p.strip() for p in text.replace("\n", "·").split("·")]
        for part in parts:                       # an exact string literal first (pieces only when distinctive)
            if part and (part == text or len(part) >= 10) and self.find(f'"{part}"'):
                return self.find(f'"{part}"')
        for part in parts:                       # then a literal that starts with a long enough piece of it
            if len(part) >= 12:
                hit = self.find(f'"{part[:40]}')
                if hit:
                    return hit
        if len(text) >= 6:                       # last: a literal that opens the same way (text rewritten at runtime)
            hit = self.find(f'"{text[:6]}')
            if hit:
                return hit
        return None, f"built by {w.meta.get('source')} (its text comes from the recorded data)"


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("capture")
    ap.add_argument("--check-baseline", action="store_true")
    ap.add_argument("--write-baseline", action="store_true")
    ap.add_argument("--self-test", action="store_true", help="fail unless the seeded fixture yields exactly its seeded defects")
    a = ap.parse_args(argv)
    cap = Path(a.capture)
    paths = sorted(p for p in cap.glob("*.json") if p.name not in {"report.json", "capture_index.json"})
    windows = [Window(p) for p in paths if "elements" in json.loads(p.read_text(encoding="utf-8"))]
    violations = []
    for w in windows:
        violations += lint_window(w)
        w.close_image()
    seeded = [v for v in violations if v["window"] == SEEDED]
    raw_real = [v for v in violations if v["window"] != SEEDED]
    real, exceptions = apply_exceptions(raw_real)
    real_windows = [w for w in windows if w.name != SEEDED]
    gated_names = {w.name for w in real_windows if w.meta.get("kind") in GATED_KINDS}
    per = counts(real, real_windows)
    gated = {k: v for k, v in per.items() if k in gated_names}
    total = sum(sum(r.values()) for r in gated.values())
    by_rule = {r: sum(per[w][r] for w in gated) for r in RULES}
    crops = cap / "crops"
    crops.mkdir(exist_ok=True)
    for old in crops.glob("*.png"):
        old.unlink()
    named = {w.name: w for w in windows}
    sources = Sources()

    def rel(p):
        return str(p.relative_to(cap)).replace("\\", "/") if p else None

    # Ten worst as a wearer sees them: text lost first, then the fault's size in degrees at the window's distance.
    # One entry per cause (rule + source line): the same fault repeated across windows is listed once, with the
    # other windows named. At most two entries per window, so one window cannot fill the list.
    for v in real:
        w = named[v["window"]]
        v["source"], v["source_line"] = sources.cause(v, w.by_index.get(v["element_index"], {}), w)
    ranked = sorted((v for v in real if v["window"] in gated_names),
                    key=lambda v: (v["text_lost"], v["fault_deg"], v["severity"]), reverse=True)
    top, per_win, causes = [], Counter(), {}
    for v in ranked:
        cause = (v["rule"], v["source"] or (v["window"], v["element_index"]))
        if cause in causes:
            if v["window"] not in causes[cause]["also_in"] and v["window"] != causes[cause]["window"]:
                causes[cause]["also_in"].append(v["window"])
            continue
        if len(top) == 10 or per_win[v["window"]] >= 2:
            continue
        v["also_in"] = []
        causes[cause] = v
        top.append(v)
        per_win[v["window"]] += 1
    for i, v in enumerate(top, 1):
        v["crop"] = rel(crop(named[v["window"]], v, crops / f"top{i:02d}_{v['window']}_{v['rule']}.png"))
    # developer's "misaligned text": the gross misalignments, one crop per window.
    misaligned = []
    for name in sorted({v["window"] for v in real if v["rule"] == "gross_misalignment" and v["window"] in gated_names}):
        v = max((x for x in real if x["window"] == name and x["rule"] == "gross_misalignment"), key=lambda x: x["fault_deg"])
        w = named[name]
        misaligned.append({**v, "crop": rel(crop(w, v, crops / f"misaligned_{name}.png"))})
    arch = [v for v in real if v["window"].startswith("architecture") and v["rule"] in {"text_clipped", "word_broken", "outside_parent"}]
    developer = []
    # Default project first, then alternate the rules so overflow and wrapping both show (two of each rule).
    ranked_arch = sorted(arch, key=lambda v: (not named[v["window"]].meta.get("default_project"), -v["severity"]))
    pick, per_rule = [], Counter()
    for v in ranked_arch:
        if per_rule[v["rule"]] < 2:
            pick.append(v)
            per_rule[v["rule"]] += 1
    for i, v in enumerate(pick[:6], 1):
        p = crop(named[v["window"]], v, crops / f"developer{i}_{v['window']}_{v['rule']}.png")
        developer.append({**v, "crop": rel(p)})
    seeded_found = {(v["rule"], v["tag"]) for v in seeded}
    gross = by_rule["gross_misalignment"]
    # #861: type as visual angle and rows per page, per gated native window (distance from WindowLayout, as the app).
    type_per = {w.name: type_stats(w) for w in real_windows if w.name in gated_names and w.meta.get("kind") == "android-view"}
    angles = [t["min_text_angle_deg"] for t in type_per.values() if t["min_text_angle_deg"] is not None]
    pushed = [t["min_text_angle_push_deg"] for t in type_per.values() if t["min_text_angle_push_deg"] is not None]
    rows = {k: t["rows_per_page"] for k, t in type_per.items() if t["rows_per_page"] is not None}
    report = {"layout_lint_violations": total, "layout_lint_windows": len(real_windows),
              "layout_lint_gross_misaligned": gross,
              "type_min_angle_deg": min(angles) if angles else None,
              "type_min_angle_push_deg": min(pushed) if pushed else None,
              "type_min_xheight_floor_deg": MIN_XHEIGHT_DEG,
              "rows_per_page_min": min(rows.values()) if rows else None, "type": type_per,
              "gated_windows": sorted(gated_names), "by_rule": by_rule, "per_window": per,
              "top10": top, "misaligned": misaligned, "developer_architecture": developer, "seeded": seeded,
              "exceptions": exceptions, "raw_violations": raw_real, "seeded_ok": seeded_found == SEEDED_EXPECTED and len(seeded) == len(SEEDED_EXPECTED), "violations": real}
    (cap / "report.json").write_text(json.dumps(report, indent=1), encoding="utf-8")
    md = ["# Layout lint report", "", f"Capture: `{cap.as_posix()}`", "",
          "## Ten worst faults, as a wearer sees them", "",
          "Ranked by: text lost first, then the fault's size in degrees of visual angle at the window's default "
          "distance (offset, overflow or hidden text height; for small text and targets, shortfall below the "
          "approved surface token or the legacy angular floor). At most two per window.", "",
          "| # | window | rule | size (deg) | text lost | element | source | same cause also in |", "|---|---|---|---|---|---|---|---|"]
    for i, v in enumerate(top, 1):
        md.append(f"| {i} | {v['window']} | {v['rule']} | {v['fault_deg']:.2f} | {'yes' if v['text_lost'] else 'no'} | "
                  f"`{v['element']}` | {('`' + v['source'] + '`') if v['source'] else v['source_line']} | "
                  f"{', '.join(v['also_in']) or '-'} |")
    md.append("")
    for i, v in enumerate(top, 1):
        md += [f"{i}. **{v['rule']}** `{v['window']}` `{v['element']}`: {v['detail']}",
               f"   Source: `{v['source']}` `{v['source_line']}`" if v["source"] else f"   Source: {v['source_line']}",
               f"   ![crop]({v['crop']})"]
    md += ["", "## Summary", "",
           f"- **layout_lint_violations = {total}** (gated: deterministic Recorded windows + architecture boxes)",
           f"- **layout_lint_windows = {len(real_windows)}** rendered ({len(gated_names)} gated; live WebView windows are reported, not gated)",
           f"- **layout_lint_gross_misaligned = {gross}**",
           f"- seeded fixture: {'OK' if report['seeded_ok'] else 'FAILED'}, found {sorted(seeded_found)}", "",
           "## developer's words: \"misaligned text\" (gross misalignment, worst per window)", ""]
    for v in misaligned:
        src = f"`{v['source']}` `{v['source_line']}`" if v["source"] else v["source_line"]
        md += [f"- `{v['window']}` `{v['element']}`: {v['detail']} ({v['fault_deg']:.2f} deg). Source: {src}", f"  ![crop]({v['crop']})"]
    if not misaligned:
        md.append("- No unexcepted gross misalignment found. Named exceptions are retained in report.json.")
    md += ["", "## Per window", "", "| window | " + " | ".join(RULES) + " | total |", "|---" * (len(RULES) + 2) + "|"]
    for win in sorted(per):
        r = per[win]
        flag = "" if win in gated_names else " (live, not gated)"
        md.append(f"| {win}{flag} | " + " | ".join(str(r[x]) for x in RULES) + f" | {sum(r.values())} |")
    md.append("| **gated total** | " + " | ".join(str(by_rule[x]) for x in RULES) + f" | **{total}** |")
    md += ["", "## Type as visual angle and rows per page (#861)", "",
           f"x-height floor {MIN_XHEIGHT_DEG} deg (Microsoft Mixed Reality, Text in Unity: minimum legible 0.35-0.4 deg), "
           "Angles remain diagnostic for approved native look surfaces; per-element token rules and semantic scroll-row checks gate them. "
           "Legacy surfaces retain the angular and recorded-page gates. Distances come from production placement code.", "",
           "| window | distance m | min sp | min em deg | min x-height deg | after push (m) | x-height deg after push | rows/page |",
           "|---|---|---|---|---|---|---|---|"]
    for win, t in sorted(type_per.items()):
        md.append(f"| {win} | {t['distance_m']} | {t['min_sp']} | {t['min_em_deg']} | {t['min_text_angle_deg']} | "
                  f"{t['push_distance_m']} | {t['min_text_angle_push_deg']} | {t['rows_per_page'] if t['rows_per_page'] is not None else '-'} |")
    md += ["", "## developer's words: \"overflow and wrapping in the architecture boxes\"", ""]
    for v in developer:
        label = named[v["window"]].meta.get("project_label")
        md += [f"- **{v['rule']}** `{v['window']}`{f' ({label})' if label else ''} `{v['element']}`: {v['detail']}", f"  ![crop]({v['crop']})"]
    if not developer:
        md.append("- No architecture overflow or word wrapping violation found.")
    md += ["", "## All violations", ""]
    for v in real:
        md.append(f"- {v['window']} · {v['rule']} · `{v['element']}` · {v['detail']} · {v['fault_deg']:.2f} deg")
    (cap / "report.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"layout_lint_violations={total} layout_lint_windows={len(real_windows)} layout_lint_gross_misaligned={gross} "
          f"seeded_ok={report['seeded_ok']}")
    print("by_rule " + " ".join(f"{k}={v}" for k, v in by_rule.items()))
    print(f"type_min_angle_deg={report['type_min_angle_deg']} type_min_angle_push_deg={report['type_min_angle_push_deg']} "
          f"rows_per_page_min={report['rows_per_page_min']} rows " + " ".join(f"{k}={v}" for k, v in sorted(rows.items())))
    rc = 0
    if a.self_test or a.check_baseline:
        if not report["seeded_ok"]:
            print(f"SELF-TEST FAILED: seeded fixture gave {sorted(seeded_found)} ({len(seeded)}), expected {sorted(SEEDED_EXPECTED)}")
            rc = 1
    if a.write_baseline:
        import datetime
        main_base = git_main_baseline() or {}
        BASELINE.write_text(json.dumps({"version": BASELINE_VERSION, "date": datetime.date.today().isoformat(),
                                        "replaces": {"version": main_base.get("version", 1), "total": main_base.get("total")},
                                        "total": total, "by_rule": by_rule, "windows": gated,
                                        "capture": cap.as_posix()}, indent=1) + "\n", encoding="utf-8")
        print(f"baseline written: {BASELINE}")
    for w in windows:
        if w.image:
            w.image.close()
    if a.check_baseline:
        problems = check_baseline(gated, total) + type_gate(type_per)
        for p in problems:
            print("LAYOUT LINT GATE: " + p)
        if problems:
            rc = 1
    return rc


if __name__ == "__main__":
    sys.exit(main())
