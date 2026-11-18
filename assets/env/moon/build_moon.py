"""Bake the moon environment pack (assets/env/moon) from public-domain source imagery (SOURCES.md).

usage: py -3.14 assets/env/moon/build_moon.py <download dir>
The download dir holds the four files listed in SOURCES.md under their original names; their sha256 are checked.
Writes, next to this script: stars.jpg (sky, equirect), earth.jpg (globe, equirect), ground_detail.jpg (tileable
regolith), ground_relief.png (8-bit heightfield, 0..255 = -1..+1, scaled at runtime by env.json ground.relief).
Needs numpy, Pillow and OpenEXR (pip install OpenEXR) for the star map.
"""
import hashlib, pathlib, sys

import numpy as np
from PIL import Image, ImageFilter

HERE = pathlib.Path(__file__).parent
SRC = pathlib.Path(sys.argv[1])
SHA = {
    "starmap_2020_4k.exr": "69b841cd048c2ef35543eb1c5819b322530e78aa4b0cab97ec1de3ef56a4ddcd",
    "lroc_color_poles_2k.tif": "13b797422e8c4b8607ff2b2623ac3a046a6da0132d567c2d272d92fad7052c4a",
    "ldem_4.tif": "330afa2556a86fd05ac6ba2f912f246600fdade35de2a0d90593d50d07b01b65",
    "Land_ocean_ice_2048.jpg": "d4dc80a6ef571939d0abe04a9bed3d3d1e6cd63e59514be1c5e43a6b069e6f1e",
}
for name, want in SHA.items():
    got = hashlib.sha256((SRC / name).read_bytes()).hexdigest()
    assert got == want, f"{name}: sha256 {got} != {want}"
Image.MAX_IMAGE_PIXELS = None
srgb = lambda x: np.where(x <= 0.0031308, 12.92 * x, 1.055 * np.power(np.clip(x, 0, None), 1 / 2.4) - 0.055)
to8 = lambda x: Image.fromarray((np.clip(x, 0, 1) * 255 + 0.5).astype(np.uint8))

# 1. Sky: the star map is linear radiance 0..1. Dim it (STAR_GAIN) so the brightest star stays well under the panel
#    text, lift nothing, encode sRGB. 4096 x 2048 is about 11 px per degree.
import OpenEXR
with OpenEXR.File(str(SRC / "starmap_2020_4k.exr")) as f:
    lin = f.channels()["RGB"].pixels.astype(np.float32)
STAR_GAIN = 0.55
to8(srgb(lin * STAR_GAIN)).save(HERE / "stars.jpg", quality=86, optimize=True, progressive=True)

# 2. Earth: the whole-Earth composite, 1024 x 512 (the globe spans about 6 degrees: 512 px around is plenty).
Image.open(SRC / "Land_ocean_ice_2048.jpg").convert("RGB").resize((1024, 512), Image.LANCZOS).save(
    HERE / "earth.jpg", quality=88, optimize=True, progressive=True)

# 3. Ground detail: a highland crop of the lunar colour map, high-passed to unit-mean grey mottling, made tileable by
#    a cross-fade with its half-offset copy, then fine grain on top. Tiled at env.json ground.detail_tile_m.
col = np.asarray(Image.open(SRC / "lroc_color_poles_2k.tif").convert("L"), np.float32) / 255
h0, w0 = col.shape
crop = col[int(h0 * 0.62):int(h0 * 0.62) + 192, int(w0 * 0.05):int(w0 * 0.05) + 192]
big = np.asarray(Image.fromarray((crop * 255).astype(np.uint8)).resize((512, 512), Image.BICUBIC), np.float32) / 255
low = np.asarray(Image.fromarray((big * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(40)), np.float32) / 255
hp = np.clip(big / np.maximum(low, 1e-3), 0.94, 1.07)  # clip: no mare blotch repeats across the tiling
y, x = np.mgrid[0:512, 0:512] / 512
wgt = (np.sin(np.pi * x) * np.sin(np.pi * y)) ** 0.5
tile = wgt * hp + (1 - wgt) * np.roll(np.roll(hp, 256, 0), 256, 1)
rng = np.random.default_rng(7)
grain = np.asarray(Image.fromarray((rng.random((512, 512)) * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8)), np.float32) / 255
tile = (tile - tile.mean()) * 0.9 + (grain - grain.mean()) * 0.45 + 0.8  # mean 0.78 x the vertex shade
to8(np.stack([tile] * 3, -1)).save(HERE / "ground_detail.jpg", quality=85, optimize=True)

# 4. Heightfield: a cratered highland patch of the elevation map (km), normalised to -1..+1, 256 x 256.
dem = np.asarray(Image.open(SRC / "ldem_4.tif"), np.float32)
dh, dw = dem.shape
patch = dem[int(dh * 0.62):int(dh * 0.62) + 64, int(dw * 0.05):int(dw * 0.05) + 64]
patch = np.asarray(Image.fromarray(patch).resize((256, 256), Image.BICUBIC), np.float32)
patch = (patch - patch.mean()) / max(np.abs(patch - patch.mean()).max(), 1e-6)
Image.fromarray(((patch * 0.5 + 0.5) * 255 + 0.5).astype(np.uint8), "L").save(HERE / "ground_relief.png", optimize=True)

for p in ["stars.jpg", "earth.jpg", "ground_detail.jpg", "ground_relief.png"]:
    b = (HERE / p).read_bytes()
    print(f"{p:20s} {len(b):8d} bytes  sha256 {hashlib.sha256(b).hexdigest()}")
