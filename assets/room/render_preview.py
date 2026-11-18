"""Render assets/room/preview.png from room.glb with a tiny numpy z-buffer rasteriser.

    uv run --with numpy --with pillow python assets/room/render_preview.py

Camera: the seated eye (0, 1.2, 0) looking down -Z, pitched 18 deg down, 100 deg
horizontal FoV. Reads the GLB (unlit vertex colours), so it shows what engines get.
Faint outlines mark where the UI from docs/design.md sits (panels, tray, graph volume),
to check the room stays quiet behind it; pass --no-ui to omit them.
"""
import json
import math
import struct
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).parent
W, Hh = 1280, 720
HFOV = math.radians(100)
PITCH = math.radians(-18)
EYE = np.array([0.0, 1.2, 0.0])


def load(path):
    data = path.read_bytes()
    jl = struct.unpack_from("<I", data, 12)[0]
    g = json.loads(data[20:20 + jl])
    bin_ = data[20 + jl + 8:]
    dt = {5126: np.float32, 5123: np.uint16, 5125: np.uint32}
    nc = {"SCALAR": 1, "VEC3": 3, "VEC4": 4}

    def acc(i):
        a = g["accessors"][i]
        v = g["bufferViews"][a["bufferView"]]
        arr = np.frombuffer(bin_, dt[a["componentType"]], a["count"] * nc[a["type"]], v["byteOffset"])
        return arr.reshape(a["count"], nc[a["type"]]) if nc[a["type"]] > 1 else arr

    tris, cols = [], []
    for node in g["nodes"]:  # nodes carry no transforms except EyeAnchor/Room (identity)
        if "mesh" not in node:
            continue
        for pr in g["meshes"][node["mesh"]]["primitives"]:
            P = acc(pr["attributes"]["POSITION"]).astype(float)
            Cc = acc(pr["attributes"]["COLOR_0"]).astype(float)[:, :3]
            I = acc(pr["indices"]).astype(int).reshape(-1, 3)
            tris.append(P[I])
            cols.append(Cc[I])
    return np.concatenate(tris), np.concatenate(cols)


def view(pts):
    c, s = math.cos(-PITCH), math.sin(-PITCH)
    q = pts - EYE
    y = q[..., 1] * c - q[..., 2] * s
    z = q[..., 1] * s + q[..., 2] * c
    return np.stack([q[..., 0], y, z], -1)  # camera looks down -z


def project(v):
    f = (W / 2) / math.tan(HFOV / 2)
    zc = -v[..., 2]
    return np.stack([W / 2 + f * v[..., 0] / zc, Hh / 2 - f * v[..., 1] / zc], -1), zc


def clip_near(tri, col, near=0.05):
    """Clip one view-space triangle against z = -near; returns list of (tri, col)."""
    inside = -tri[:, 2] >= near
    if inside.all():
        return [(tri, col)]
    if not inside.any():
        return []
    poly, pc = [], []
    for i in range(3):
        a, b = tri[i], tri[(i + 1) % 3]
        ca, cb = col[i], col[(i + 1) % 3]
        ia, ib = -a[2] >= near, -b[2] >= near
        if ia:
            poly.append(a); pc.append(ca)
        if ia != ib:
            t = (-near - a[2]) / (b[2] - a[2])
            poly.append(a + t * (b - a)); pc.append(ca + t * (cb - ca))
    out = []
    for k in range(1, len(poly) - 1):
        out.append((np.array([poly[0], poly[k], poly[k + 1]]), np.array([pc[0], pc[k], pc[k + 1]])))
    return out


def raster(tris, cols):
    img = np.zeros((Hh, W, 3))
    img[:] = (0.02, 0.02, 0.025)
    zbuf = np.full((Hh, W), np.inf)
    vt = view(tris)
    for tri, col in zip(vt, cols):
        for t, c in clip_near(tri, col):
            s, zc = project(t)
            x0, x1 = int(max(math.floor(s[:, 0].min()), 0)), int(min(math.ceil(s[:, 0].max()), W - 1))
            y0, y1 = int(max(math.floor(s[:, 1].min()), 0)), int(min(math.ceil(s[:, 1].max()), Hh - 1))
            if x0 > x1 or y0 > y1:
                continue
            (ax, ay), (bx, by), (cx, cy) = s
            den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if abs(den) < 1e-9:
                continue
            X, Y = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
            w0 = ((by - cy) * (X - cx) + (cx - bx) * (Y - cy)) / den
            w1 = ((cy - ay) * (X - cx) + (ax - cx) * (Y - cy)) / den
            w2 = 1 - w0 - w1
            m = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
            if not m.any():
                continue
            iz = w0 / zc[0] + w1 / zc[1] + w2 / zc[2]  # perspective-correct
            depth = 1 / iz
            sub = zbuf[y0:y1 + 1, x0:x1 + 1]
            m &= depth < sub
            if not m.any():
                continue
            pc = (w0[..., None] * c[0] / zc[0] + w1[..., None] * c[1] / zc[1] + w2[..., None] * c[2] / zc[2]) * depth[..., None]
            sub[m] = depth[m]
            img[y0:y1 + 1, x0:x1 + 1][m] = pc[m]
    return img


def ui_outlines(draw):
    """design.md s.2 boxes: design (x, y, z) relative to eye with +z forward -> room (x, 1.2+y, -z)."""
    def rect(cx, cy, cz, w, h, yaw=0.0, tilt=0.0):
        pts = []
        for dx, dy in ((-w / 2, -h / 2), (w / 2, -h / 2), (w / 2, h / 2), (-w / 2, h / 2)):
            # tilt about local x (tray faces up toward the user), then yaw about y
            ly, lz = dy * math.cos(tilt), dy * math.sin(tilt)
            lx = dx
            x = cx + lx * math.cos(yaw) + lz * math.sin(yaw)
            z = cz - lx * math.sin(yaw) + lz * math.cos(yaw)
            pts.append([x, 1.2 + cy + ly, -z])
        return np.array(pts)

    shapes = [rect(-0.25, -0.17, 0.32, 0.24, 0.16, yaw=math.radians(38)),
              rect(0.0, -0.17, 0.40, 0.24, 0.16),
              rect(0.25, -0.17, 0.32, 0.24, 0.16, yaw=math.radians(-38)),
              rect(0.0, -0.29, 0.31, 0.36, 0.10, tilt=math.radians(45))]
    for z in (1.0, 1.4):
        shapes.append(rect(0.0, 0.10, z, 0.80, 0.50))
    for pts in shapes:
        s, zc = project(view(pts))
        if (zc > 0).all():
            draw.line([tuple(p) for p in s] + [tuple(s[0])], fill=(150, 200, 230), width=2)


def main():
    tris, cols = load(HERE / "room.glb")
    img = raster(tris, cols)
    srgb = np.where(img <= 0.0031308, 12.92 * img, 1.055 * np.power(np.clip(img, 0, 1), 1 / 2.4) - 0.055)
    im = Image.fromarray((np.clip(srgb, 0, 1) * 255 + 0.5).astype(np.uint8))
    if "--no-ui" not in sys.argv:
        ui_outlines(ImageDraw.Draw(im))
    out = HERE / ("preview_plain.png" if "--no-ui" in sys.argv else "preview.png")
    im.save(out)
    print(f"wrote {out} ({len(tris)} triangles)")


if __name__ == "__main__":
    main()
