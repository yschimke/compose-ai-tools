#!/usr/bin/env python3
"""Tests for `check_serve_wasm_fork.py`.

The gate's whole value is that it goes RED when the two copies of the Compose/Wasm frontend drift,
so the cases that matter are the failing ones. A gate asserted only on the happy path is
indistinguishable from `exit 0`.

Runs entirely offline against temporary trees — nothing here touches the network or the real
`cli/serve-wasm`.
"""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "check_serve_wasm_fork", Path(__file__).resolve().parent / "check_serve_wasm_fork.py"
)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


class ForkGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.local = root / "local"
        self.vendor = root / "vendor"
        self.pin = root / "pin.json"
        self.pin.write_text(json.dumps({"sha": "0" * 40}) + "\n")

        # A one-file world, so a test asserts on the rule rather than on the real tree's contents.
        self.rel = "wasmJsMain/kotlin/ee/schimke/composeai/servewasm/App.kt"
        gate.LOCAL_DIR = self.local
        gate.VENDOR_DIR = self.vendor
        gate.PIN_FILE = self.pin
        gate.SHARED = [self.rel]
        gate.ALLOWED_DELTAS = []

        for base in (self.local, self.vendor):
            (base / self.rel).parent.mkdir(parents=True, exist_ok=True)
        self.write_both("fun app() = Unit\n")

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def write_both(self, text: str) -> None:
        (self.local / self.rel).write_text(text)
        (self.vendor / self.rel).write_text(text)

    def test_identical_copies_pass(self) -> None:
        self.assertEqual(0, gate.main([]))

    def test_drifted_copy_fails(self) -> None:
        (self.local / self.rel).write_text("fun app() = Unit // local-only change\n")
        self.assertEqual(1, gate.main([]))

    def test_file_deleted_locally_fails(self) -> None:
        """A shared file removed here is drift, not an opt-out."""
        (self.local / self.rel).unlink()
        self.assertEqual(1, gate.main([]))

    def test_missing_vendor_copy_fails(self) -> None:
        """A shared file added upstream but never vendored must not pass silently."""
        (self.vendor / self.rel).unlink()
        self.assertEqual(1, gate.main([]))

    def test_allowed_delta_is_normalised_away(self) -> None:
        (self.vendor / self.rel).write_text("// upstream path\nfun app() = Unit\n")
        (self.local / self.rel).write_text("// local path\nfun app() = Unit\n")
        gate.ALLOWED_DELTAS = [
            {
                "file": self.rel,
                "why": "test",
                "upstream": "// upstream path",
                "local": "// local path",
            }
        ]
        self.assertEqual(0, gate.main([]))

    def test_allowed_delta_does_not_excuse_other_drift(self) -> None:
        """The exemption rewrites one passage; everything else still has to match."""
        (self.vendor / self.rel).write_text("// upstream path\nfun app() = Unit\n")
        (self.local / self.rel).write_text("// local path\nfun app() = 42\n")
        gate.ALLOWED_DELTAS = [
            {
                "file": self.rel,
                "why": "test",
                "upstream": "// upstream path",
                "local": "// local path",
            }
        ]
        self.assertEqual(1, gate.main([]))

    def test_rotted_allowed_delta_fails_loudly(self) -> None:
        """An exemption whose text no longer exists excuses a diff nobody read, so it must not
        degrade into a silent pass."""
        (self.vendor / self.rel).write_text("// upstream path\nfun app() = Unit\n")
        (self.local / self.rel).write_text("// a third wording\nfun app() = Unit\n")
        gate.ALLOWED_DELTAS = [
            {
                "file": self.rel,
                "why": "test",
                "upstream": "// upstream path",
                "local": "// local path",
            }
        ]
        with self.assertRaises(SystemExit):
            gate.main([])


class RealTreeTest(unittest.TestCase):
    """The shared list must describe the tree that actually exists."""

    def test_shared_files_all_exist(self) -> None:
        spec = importlib.util.spec_from_file_location(
            "fresh", Path(__file__).resolve().parent / "check_serve_wasm_fork.py"
        )
        fresh = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fresh)
        for rel in fresh.SHARED:
            self.assertTrue((fresh.LOCAL_DIR / rel).is_file(), f"missing locally: {rel}")
            self.assertTrue((fresh.VENDOR_DIR / rel).is_file(), f"not vendored: {rel}")

    def test_not_compared_files_are_not_also_shared(self) -> None:
        spec = importlib.util.spec_from_file_location(
            "fresh2", Path(__file__).resolve().parent / "check_serve_wasm_fork.py"
        )
        fresh = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(fresh)
        self.assertEqual(set(), set(fresh.SHARED) & set(fresh.NOT_COMPARED))


if __name__ == "__main__":
    unittest.main()
