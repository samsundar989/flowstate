# Environment packs

A pack is a folder `assets/env/<name>/` with an `env.json` manifest and the images it names. The Web build
serves every `*.json|*.jpg|*.png` here at `env/<name>/` (plugin in `web/agentdeck/vite.config.ts`, one copy in
git); the native app reads the same folder. The study room is the built-in default (`room`, no pack).

- Web: `?env=moon` or `?env=room` picks the start; with either, a small SCENE tile by the tray's right edge
  cycles room / moon by poke or ray. Without `env` the app is unchanged (no tile, no pack loaded).
- Manifest `overhand.env/1` (see `moon/env.json`): `frame` (origin on the real floor under the seated eye,
  azimuth clockwise from forward, elevation above the eye), `sky` (equirect image, intensity, yaw), `sun`,
  `ground` (heightfield + relief curve by distance, flat radius under the seat, albedo, tiled detail),
  `bodies` (globes placed by azimuth/elevation/distance/angular size, lit by the sun, optional spin).
- Keep everything inside the 200 m camera far plane; keep the sky dim enough that panel text wins (STAR_GAIN).

## Any video as a background (not built; what the honest version needs)

**360 or 180 video on a dome.** An equirect (360) or half-equirect (180) video mapped on the inside of a sphere
or hemisphere around the seat, unlit, with the panels in front. This is the only version that feels like being
somewhere. Needs: footage shot for it (a phone-shot flat clip stretched on a dome looks wrong); at least 5.7K
equirect for 360 (about 16 px/deg) or 4K per eye for 180 to look sharp, which is near the Quest browser's decode
limit, so expect 4K 30 fps H.264/HEVC in practice and softness; a stable horizon (any camera motion makes a seated
viewer sick, so tripod only); stereo 180 needs top-bottom layout and per-eye UV offsets; the video element must be
muted to autoplay, and the Quest browser only starts it after a user gesture; a still frame of the clip as the
fallback while it loads. Cost in-app: a `VideoTexture` on a sphere, one decode per frame (battery and heat), and
the sky intensity control we already have to keep text winning. Licence: own footage or CC0 only, same rules as
the moon pack.

**A flat video on a wide curved screen.** A 16:9 clip on a cylinder segment (about 100 to 120 degrees wide,
3 to 5 m away, behind the panels) with its edges faded to the dark void by an alpha ramp, like a window onto
a beach. Works with any ordinary clip (a phone video of a beach is fine), costs one 1080p decode, and does not
pretend to surround the user, so it never looks broken. Needs: the curved mesh with a horizontal edge fade in the
shader, a dimmer (the clip will be brighter than the panels' dark faces; cap it around 40 percent), and loop
points without a visible jump. This is the version to build first if developer wants "a beach" this month.

Both read a pack the same way: `"sky": { "type": "video360" | "video180" | "screen", "video": "beach.mp4",
"poster": "beach.jpg", ... }`. Large video files must not go in git (hard rule): they would be fetched from a
host at runtime, which also means an outside request the release checks would then need to allow.
