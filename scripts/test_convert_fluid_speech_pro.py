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
        # Fractional WER scale: 0.10 -> 0.15 is a 5-point regression and must
        # fail the 0.5pt gate (CodeRabbit round-1: threshold was 50x too lax).
        r = subprocess.run([PY, SCRIPT, "--nemo", nemo, "--out", out,
                            "--wer-before", "0.10", "--wer-after", "0.15"],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        assert r.returncode == 2, "5pt WER regression should exit 2, got %d" % r.returncode
        print("[PASS] quality gate fails on 5pt fractional regression")

        # Small delta (0.3pt) must pass the gate (exit 2 is gate-only; the
        # scaffold conversion itself fails headlessly without NeMo).
        r = subprocess.run([PY, SCRIPT, "--nemo", nemo, "--out", out,
                            "--wer-before", "0.100", "--wer-after", "0.103"],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        assert r.returncode != 2, "0.3pt WER regression must pass the gate (exit 2)"

    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "out")
        os.makedirs(out)
        nemo = os.path.join(d, "m.nemo")
        open(nemo, "w").write("x")
        # Scaffold conversion never writes the required ONNX components, so the
        # bundle stays incomplete: the script must exit non-zero and must NOT
        # emit a registry row for an unverified bundle (CodeRabbit round-1).
        row_path = os.path.join(d, "row.json")
        r = subprocess.run([PY, SCRIPT, "--nemo", nemo, "--out", out,
                            "--emit-registry-row", row_path],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        assert r.returncode != 0, "incomplete bundle after conversion should exit non-zero, got %d" % r.returncode
        assert not os.path.isfile(row_path), "incomplete bundle must not emit a registry row"
        # NOTE: this subprocess case exits at the NeMo gate, before conversion;
        # the post-conversion verify gate itself is covered below in-process
        # with NeMo availability stubbed (CodeRabbit round-2).
        print("[PASS] missing NeMo exits non-zero without registry row")

    # In-process: stub NeMo availability so the scaffold conversion SUCCEEDS,
    # then the incomplete bundle must fail the post-conversion verify gate and
    # skip the registry row (CodeRabbit round-2: the subprocess case could pass
    # for the wrong reason).
    sys.path.insert(0, os.path.dirname(__file__))
    import convert_fluid_speech_pro as conv
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "out")
        os.makedirs(out)
        nemo = os.path.join(d, "m.nemo")
        open(nemo, "w").write("x")
        row_path = os.path.join(d, "row.json")
        orig_argv = sys.argv
        orig_check = conv.check_neMo_available
        orig_convert = conv.convert
        sys.argv = [SCRIPT, "--nemo", nemo, "--out", out,
                    "--emit-registry-row", row_path]
        issues = []
        try:
            conv.check_neMo_available = lambda: True
            captured = {}
            def fake_convert(nemo_path, out_dir, quantize):
                # The scaffold conversion must still run its manifest write but
                # assert it is actually invoked (never NeMo-gated away).
                captured["invoked"] = True
                return orig_convert(nemo_path, out_dir, quantize)
            conv.convert = fake_convert
            rc = conv.main()
        finally:
            sys.argv = orig_argv
            conv.check_neMo_available = orig_check
            conv.convert = orig_convert
        assert captured.get("invoked"), "conversion must run past the NeMo gate (stubbed available)"
        assert rc != 0, "incomplete bundle after successful conversion must exit non-zero, got %d" % rc
        assert not os.path.isfile(row_path), "unverified bundle must not emit a registry row"
        print("[PASS] post-conversion verify gate fails closed with NeMo stubbed")

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