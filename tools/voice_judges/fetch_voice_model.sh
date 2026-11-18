#!/usr/bin/env bash
# Fetch the on-device recogniser for the app build (t805): the speech library AAR and the English speech model,
# from their public release pages, checked against fixed SHA-256 sums. Fills data/voice/bundle/, which the Gradle
# build packages into the APK (never into git). Already-downloaded files in data/voice/dl/ are reused.
# Usage: bash tools/voice_judges/fetch_voice_model.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DL=$ROOT/data/voice/dl; OUT=$ROOT/data/voice/bundle
REL=https://github.com/k2-fsa/sherpa-onnx/releases/download
AAR=sherpa-onnx-static-link-onnxruntime-2.0.1.aar
AAR_SHA=07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575
MODEL=sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27
MODEL_SHA=9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d
mkdir -p "$DL" "$OUT/assets/moonshine"

get() {  # url file sha
  local f=$DL/$2
  if [ ! -f "$f" ] || ! echo "$3  $f" | sha256sum -c --status; then
    echo "downloading $1"; curl -fL --retry 3 -o "$f.part" "$1"; mv "$f.part" "$f"
  fi
  echo "$3  $f" | sha256sum -c --quiet || { echo "checksum mismatch: $f"; exit 1; }
}
get "$REL/v2.0.1/$AAR" "$AAR" "$AAR_SHA"
get "$REL/asr-models/$MODEL.tar.bz2" "$MODEL.tar.bz2" "$MODEL_SHA"

cp "$DL/$AAR" "$OUT/$AAR"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
tar -xjf "$DL/$MODEL.tar.bz2" -C "$tmp"
for f in encoder_model.ort decoder_model_merged.ort tokens.txt LICENSE; do cp "$tmp/$MODEL/$f" "$OUT/assets/moonshine/$f"; done
echo "voice bundle ready: $OUT ($(du -sh "$OUT" | cut -f1))"
