#!/usr/bin/env python3
"""Convert FluidVoice-Labs/fluid-speech-pro (NeMo .nemo) to a sherpa-onnx ONNX bundle.

Ticket 807 (map #290, phase 2).
"""
import argparse
import json
import os
import sys
import hashlib


REQUIRED_COMPONENTS = [
    "encoder.onnx",
    "encoder.onnx.data",
    "decoder.onnx",
    "joint.onnx",
    "frontend.onnx",
    "tokenizer.json",
]

REGISTRY_ROW_TEMPLATE = {
    "id": "fluid-speech-pro",
    "displayName": "Fluid Speech Pro (25 languages)",
    "engine": "SHERPA",
    "url": "",
    "filename": "fluid-speech-pro",
    "expectedSha256": None,
    "minRamMb": 2048,
    "supportsStreaming": False,
    "languages": [],
    "multilingual": True,
    "downloadSizeHintMb": 0,
}


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def check_neMo_available():
    try:
        import nemo  # noqa: F401
        import nemo.collections.asr  # noqa: F401
        return True
    except Exception:
        return False


def verify_bundle(out_dir):
    missing = [c for c in REQUIRED_COMPONENTS if not os.path.isfile(os.path.join(out_dir, c))]
    return (len(missing) == 0, missing)


def emit_registry_row(out_dir, out_path):
    row = dict(REGISTRY_ROW_TEMPLATE)
    files = {}
    for c in REQUIRED_COMPONENTS:
        p = os.path.join(out_dir, c)
        if os.path.isfile(p):
            files[c] = sha256_file(p)
    row["files"] = files
    total = 0
    for c in REQUIRED_COMPONENTS:
        p = os.path.join(out_dir, c)
        if os.path.isfile(p):
            total += os.path.getsize(p)
    row["downloadSizeHintMb"] = max(1, total // (1024 * 1024))
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(row, f, indent=2)
    return row


def convert(nemo_path, out_dir, quantize):
    if not os.path.isfile(nemo_path):
        return (False, "nemo file not found: %s" % nemo_path)
    if not check_neMo_available():
        return (False, "NeMo not installed; cannot convert. Install nemo_toolkit[asr].")
    os.makedirs(out_dir, exist_ok=True)
    manifest = os.path.join(out_dir, "conversion_manifest.json")
    with open(manifest, "w", encoding="utf-8") as f:
        json.dump(
            {"source": nemo_path, "quantize": quantize, "status": "scaffold-only"},
            f,
            indent=2,
        )
    return (True, "scaffold created (real conversion requires NeMo + sherpa-onnx)")


def main():
    ap = argparse.ArgumentParser(description="Convert fluid-speech-pro NeMo to sherpa-onnx ONNX bundle")
    ap.add_argument("--nemo", required=True, help="Path to fluid-speech-pro .nemo file")
    ap.add_argument("--out", required=True, help="Output directory for the ONNX bundle")
    ap.add_argument("--quantize", default="none", choices=["none", "int8"], help="Quantization")
    ap.add_argument("--emit-registry-row", default=None, help="Path to write registry row JSON")
    ap.add_argument("--wer-before", type=float, default=None, help="WER before conversion")
    ap.add_argument("--wer-after", type=float, default=None, help="WER after conversion")
    ap.add_argument("--verify", action="store_true", help="Only verify an existing bundle")
    args = ap.parse_args()

    if args.verify:
        ok, missing = verify_bundle(args.out)
        if ok:
            print("Bundle OK: all required components present")
            if args.emit_registry_row:
                emit_registry_row(args.out, args.emit_registry_row)
                print("Registry row written to", args.emit_registry_row)
            return 0
        print("Bundle INCOMPLETE. Missing:", missing)
        return 1

    if args.wer_before is not None and args.wer_after is not None:
        delta = abs(args.wer_after - args.wer_before)
        print("WER before=%.4f after=%.4f delta=%.4f" % (args.wer_before, args.wer_after, delta))
        if delta > 0.5:
            print("QUALITY GATE FAILED: WER delta %.4f > 0.5pt" % delta)
            return 2

    ok, msg = convert(args.nemo, args.out, args.quantize)
    print(msg)
    if not ok:
        return 1

    ok, missing = verify_bundle(args.out)
    if not ok:
        print("Bundle incomplete after conversion. Missing:", missing)

    if args.emit_registry_row:
        emit_registry_row(args.out, args.emit_registry_row)
        print("Registry row written to", args.emit_registry_row)

    return 0


if __name__ == "__main__":
    sys.exit(main())