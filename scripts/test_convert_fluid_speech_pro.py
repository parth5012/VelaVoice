#!/usr/bin/env python3
import os
import json
import sys
import subprocess
import tempfile

SCRIPT = os.path.join(os.path.dirname(__file__), "convert_fluid_speech_pro.py")
PY = sys.executable


def assert_eq(actual, expected, msg):
    if actual != expected:
        print("[FAIL] %s: %r != %r" % (msg, actual, expected))
        sys.exit(1)
    print("[PASS] %s" % msg)


def run_tests():
    print("Running convert_fluid_speech_pro tests...")

    r = subprocess.run([PY, SCRIPT, "--help"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    assert_eq(r.returncode, 0, "help exits 0")

    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "out")
        r = subprocess.run([PY, SCRIPT, "--nemo", os.path.join(d, "nope.nemo"), "--out", out],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        assert r.returncode != 0, "missing nemo should fail"
        print("[PASS] missing nemo fails")

    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "out")
        nemo = os.path.join(d, "m.nemo")
        open(nemo, "w").write("x")
        r = subprocess.run([PY, SCRIPT, "--nemo", nemo, "--out", out,
                            "--wer-before", "0.10", "--wer-after", "0.80"],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        assert r.returncode == 2, "quality gate should exit 2, got %d" % r.returncode
        print("[PASS] quality gate fails on large delta")

    sys.path.insert(0, os.path.dirname(__file__))
    import convert_fluid_speech_pro as conv
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "out")
        os.makedirs(out)
        for c in conv.REQUIRED_COMPONENTS:
            p = os.path.join(out, c)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "wb") as f:
                f.write(b"hello")
        ok, missing = conv.verify_bundle(out)
        assert_eq(ok, True, "verify_bundle ok")
        assert_eq(missing, [], "no missing")
        row_path = os.path.join(d, "row.json")
        row = conv.emit_registry_row(out, row_path)
        assert os.path.isfile(row_path)
        assert row["id"] == "fluid-speech-pro"
        assert row["minRamMb"] == 2048
        assert row["multilingual"] is True
        print("[PASS] emit_registry_row schema")

    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "out")
        os.makedirs(out)
        ok, missing = conv.verify_bundle(out)
        assert_eq(ok, False, "incomplete bundle not ok")
        assert len(missing) > 0
        print("[PASS] incomplete bundle detected")

    print("All convert_fluid_speech_pro tests passed.")


if __name__ == "__main__":
    run_tests()