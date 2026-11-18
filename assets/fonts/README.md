# Fonts

The headset UI uses IBM Plex Mono (labels, numbers, chips, status lines) and IBM Plex Sans (prose),
the same pairing as the autoflow dashboard. uikit renders text from MSDF atlases, so the shipped
files are `web/agentdeck/public/fonts/{mono,sans}-{regular,semibold}.{json,png}` (512 x 256, 48 px
glyphs, distance range 6, ~120 KB per page). Font names appear in code only, never on screen.

Regenerate (CPU, seconds):

    npm pack @ibm/plex-mono@2.5.0 @ibm/plex-sans@1.1.0   # unpack; use fonts/complete/woff/*-{Regular,SemiBold}.woff
    npm i msdf-bmfont-xml@2
    msdf-bmfont -f json -i charset.txt -s 48 -r 6 -m 512,512 -t msdf -p 2 --smart-size --pot -o mono-regular mono-regular.woff

`charset.txt` = ASCII 32-126 plus `·–—…`. Licence: SIL OFL 1.1 (`OFL-IBM-Plex.txt`).
