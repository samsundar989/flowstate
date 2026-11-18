# Asset licences (native app)

Credit lives here, never in the app (rules s.4: no vendor or agency names in anything a wearer sees).
Source files stay out of git; cold copies in `D:\af-archive\example-project\places\`.

| Shipped file | sha256 (shipped) | Source | Author | Licence | Source sha256 | Fetched |
|---|---|---|---|---|---|---|
| `app/src/main/res/drawable-nodpi/place_mountain.jpg` (8192 x 4096) | `78e6d5b600a199f26cb03176b1f73750d6c2698c98095b19ab68c4e1b4ca1095` | https://dl.polyhaven.org/file/ph-assets/HDRIs/hdr/8k/fouriesburg_mountain_cloudy_8k.hdr (page https://polyhaven.com/a/fouriesburg_mountain_cloudy) | Dimitrios Savva (photography), Jarod Guest (processing) | CC0 1.0, https://polyhaven.com/license | `2403c52489fab817486d8b75698098bbb1c0b57d47d513581447e65e363c5200` | 2026-11-13 |
| `app/src/main/res/drawable-nodpi/place_beach.jpg` (8192 x 4096) | `3e58fbd41b792cd3a3b9dffbf6635ca0558e297ce230d5b253ca23f308fa9e75` | https://dl.polyhaven.org/file/ph-assets/HDRIs/hdr/8k/secluded_beach_8k.hdr (page https://polyhaven.com/a/secluded_beach) | Greg Zaal (backplates: Rico Cilliers) | CC0 1.0, https://polyhaven.com/license | `05fa5ea6369992fba1adb446ea2725fbf747681c92923ddb80996a790927b5a4` | 2026-11-13 |
| `app/src/main/res/drawable-nodpi/place_moon.jpg` (4096 x 2048) | `88087deab61b5f37ba24d7602ea015e70b5cab4b211593ee067d1eef42701f7b` | Composed by `tools/places/build_moon_sky.py` from NASA SVS / Visible Earth images; full source table with URLs and hashes in `docs/places_sources.md` | NASA Scientific Visualization Studio; NASA GSFC (Blue Marble) | US federal work, public domain (see the Earth-image note in `docs/places_sources.md`) | see `docs/places_sources.md` | 2026-11-12 |

Both photo skies are baked by `tools/places/build_photo_sky.py` (yaw turn, fixed EV, soft shoulder, sRGB, JPEG q90 4:4:4)
with a small retouch (`RETOUCH` in that file): mountain, one distant farm building; beach, one far standing figure,
one drink can, two pieces of foam litter.
