# Virtual room (VR-first default scene)

`room.glb`: a calm ~4 x 4 x 2.8 m study (floor, ceiling, three walls, frosted window in the left wall, desk, floor lamp, bookshelf, plant, rug). Fully procedural, CC0 (see `assets/LICENSES.md`).

**Frame.** glTF, metres, +Y up. Origin is on the floor directly below the seated user's eyes; the user faces **-Z**, +X is their right. Node `EyeAnchor` = (0, 1.2, 0), the seated eye. A `docs/design.md` s.2 point (x, y, z) (eye-relative, +z forward) is room point (x, 1.2 + y, -z). Desk top y = 0.72, x -0.70..0.70, z -0.25..-0.95; nothing in the room sits inside the graph volume (z -1.0..-1.4). The back wall (z = -2.8) is a dark muted blue-grey so the graph and panels keep contrast. The +Z side behind the user is open.

**Rendering.** Lighting (hemisphere ambient, window daylight, warm lamp, analytic AO) is baked into `COLOR_0` (linear); all 12 materials are `KHR_materials_unlit`. Use no scene lights and no shadows. three.js loads them as `MeshBasicMaterial` with vertex colours. In Unity, glTFast supports `KHR_materials_unlit`; check after import that the desk lies along the rig's forward axis (importers differ in which axis they flip) and rotate the root 180 deg about Y if it does not. For an eye-origin rig, offset the root by y = -1.2.

**Regenerate.**
```
uv run --with numpy python assets/room/build_room.py
uv run --with numpy --with pillow python assets/room/render_preview.py          # preview.png (with UI outlines)
uv run --with numpy --with pillow python assets/room/render_preview.py --no-ui  # preview_plain.png
npx @gltf-transform/cli validate assets/room/room.glb
cd assets/room/smoke && npm install && node room_stats.mjs                       # -> runs/sim/room_stats.txt
```
