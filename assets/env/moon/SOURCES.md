# Moon pack: sources

All four inputs are works of the US space agency (public domain in the US: produced by a US federal agency,
no copyright; the agency asks for credit and no implied endorsement). Credit lives here only: no agency name or
logo appears in the app (rules s.4). `build_moon.py` checks each sha256 before baking.

| File (as downloaded) | URL | Licence line | sha256 |
|---|---|---|---|
| `lroc_color_poles_2k.tif` (lunar colour map, 2048 x 1024) | https://svs.gsfc.nasa.gov/vis/a000000/a004700/a004720/lroc_color_poles_2k.tif (CGI Moon Kit, SVS 4720) | NASA Scientific Visualization Studio, public domain; credit: NASA's Scientific Visualization Studio (Ernie Wright; Noah Petro), LRO LROC data | `13b797422e8c4b8607ff2b2623ac3a046a6da0132d567c2d272d92fad7052c4a` |
| `ldem_4.tif` (lunar elevation, 4 px/deg, km) | https://svs.gsfc.nasa.gov/vis/a000000/a004700/a004720/ldem_4.tif (CGI Moon Kit, SVS 4720) | as above; LRO LOLA data | `330afa2556a86fd05ac6ba2f912f246600fdade35de2a0d90593d50d07b01b65` |
| `starmap_2020_4k.exr` (celestial sphere, 4096 x 2048, linear) | https://svs.gsfc.nasa.gov/vis/a000000/a004800/a004851/starmap_2020_4k.exr (Deep Star Maps 2020, SVS 4851) | NASA Scientific Visualization Studio, public domain; credit: NASA/Goddard Space Flight Center Scientific Visualization Studio; Gaia DR2 (ESA/Gaia/DPAC), Hipparcos, Tycho-2 | `69b841cd048c2ef35543eb1c5819b322530e78aa4b0cab97ec1de3ef56a4ddcd` |
| `Land_ocean_ice_2048.jpg` (Blue Marble: land surface, ocean colour, sea ice; 2048 x 1024) | canonical: https://visibleearth.nasa.gov/images/57730 (`eoimages.gsfc.nasa.gov/.../57730/land_ocean_ice_2048.jpg`, unreachable from this machine on Nov 12 2026); fetched from the Wikimedia Commons copy https://upload.wikimedia.org/wikipedia/commons/c/cd/Land_ocean_ice_2048.jpg | NASA Goddard Space Flight Center image by Reto Stockli, enhancements by Robert Simmon (Visible Earth 57730); Commons file page tags it `{{NASA-image}}` (public domain, US federal work) | `d4dc80a6ef571939d0abe04a9bed3d3d1e6cd63e59514be1c5e43a6b069e6f1e` |

Note on the Earth image: the Commons page also carries an uploader GFDL tag and its summary shows "CC BY-SA 3.0";
that tag is the uploader's, the underlying NASA work is public domain and the file is byte-for-byte the NASA
release (same name and size as the Visible Earth file). If a stricter reading is wanted, re-download from the
canonical URL when reachable and re-run the bake: the sha256 check will say whether the bytes differ.

## Baked outputs (committed, served at `env/moon/`)

| File | Made from | Bytes |
|---|---|---:|
| `stars.jpg` 4096 x 2048 | star map x 0.55 (STAR_GAIN), sRGB, JPEG q86 | 1,629,352 |
| `earth.jpg` 1024 x 512 | Blue Marble, Lanczos down-size, JPEG q88 | 93,099 |
| `ground_detail.jpg` 512 x 512 | highland crop of the colour map, high-passed, tileable, plus grain | 70,909 |
| `ground_relief.png` 256 x 256 | highland crop of the elevation map, normalised -1..+1 | 18,153 |

Rebuild: download the four files into one folder, then `py -3.14 assets/env/moon/build_moon.py <folder>`
(numpy, Pillow, OpenEXR).
