"""Fail closed on a release APK without the offline speech recogniser."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

REQUIRED = (
    "assets/moonshine/encoder_model.ort",
    "assets/moonshine/decoder_model_merged.ort",
    "assets/moonshine/tokens.txt",
    "lib/arm64-v8a/libsherpa-onnx-jni.so",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    result = {"apk": str(args.apk), "errors": [], "entries": []}
    try:
        with zipfile.ZipFile(args.apk) as apk:
            entries = {e.filename: e for e in apk.infolist()}
            for name in REQUIRED:
                if name not in entries or entries[name].file_size == 0:
                    result["errors"].append(f"Missing or empty: {name}")
            result["entries"] = [
                {"path": e.filename, "bytes": e.file_size}
                for e in apk.infolist()
                if e.filename.startswith("assets/moonshine/") or e.filename in REQUIRED
            ]
            result["model_bytes"] = sum(
                e.file_size for e in apk.infolist()
                if e.filename.startswith("assets/moonshine/") and e.filename.endswith(".ort")
            )
            if result["model_bytes"] < 40_000_000:
                result["errors"].append("Voice models total less than 40 MB")
            # Read required members: corrupt/truncated archives must not pass on headers alone.
            for name in REQUIRED:
                if name in entries:
                    with apk.open(name) as member:
                        while member.read(1024 * 1024):
                            pass
        with args.apk.open("rb") as source:
            result["sha256"] = hashlib.file_digest(source, "sha256").hexdigest()
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as error:
        result["errors"].append(str(error))
    result["rc"] = int(bool(result["errors"]))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    return result["rc"]


if __name__ == "__main__":
    raise SystemExit(main())
