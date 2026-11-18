"""#880: dp used by each top-level element (chrome vs the page pane) of the list windows, from a lint capture.

    python tools/layout_lint/chrome_table.py <capture dir> [--md out.md]
"""
import json
import sys
from pathlib import Path

WINDOWS = ["fleet", "blueprint", "code", "build", "chat"]


def label(e):
    tag = e.get("tag") or ""
    if tag:
        return tag
    t = (e.get("text") or "").strip()
    if t:
        return f"'{t[:28]}'"
    return e["class"].rsplit(".", 1)[-1]


def table(cap: Path):
    rows = []
    for w in WINDOWS:
        p = cap / f"{w}.json"
        if not p.exists():
            continue
        j = json.loads(p.read_text(encoding="utf-8"))
        dens = j["density"]
        for e in j["elements"]:
            if e["parent"] != 0:
                continue
            r, m = e["rect"], e.get("margin") or [0, 0, 0, 0]
            dp = (r[3] - r[1] + m[1] + m[3]) / dens
            kids = [k for k in j["elements"] if k["parent"] == e["index"]]
            lines = len(e.get("lines") or [])
            rows.append((w, label(e), round(dp), lines, len(kids)))
    return rows


def main():
    cap = Path(sys.argv[1])
    rows = table(cap)
    out = ["| window | element | dp | text lines | children |", "|---|---|---|---|---|"]
    out += [f"| {w} | {n} | {dp} | {ln or ''} | {k or ''} |" for w, n, dp, ln, k in rows]
    text = "\n".join(out)
    print(text)
    if "--md" in sys.argv:
        Path(sys.argv[sys.argv.index("--md") + 1]).write_text(text + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
