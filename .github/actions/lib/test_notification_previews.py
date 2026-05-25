"""Tests for notification-previews.py multi-module staging.

Coverage focuses on the staging step's multi-module fold-in: that
``--build-dir`` is repeatable, that the resulting ``findings.json`` carries
per-entry module provenance, and that flat-basename collisions across
modules fail loud rather than silently clobber.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path


_HERE = Path(__file__).parent
_SPEC = importlib.util.spec_from_file_location(
    "notification_previews", str(_HERE / "notification-previews.py")
)
np = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(np)  # type: ignore[union-attr]


def _make_module(root: Path, module: str, png_names: list[str]) -> Path:
    build = root / module.replace(":", "/") / "build" / "compose-previews"
    (build / "renders").mkdir(parents=True)
    for name in png_names:
        (build / "renders" / name).write_bytes(f"png-{name}".encode())
    (build / "previews.json").write_text(json.dumps({"module": module}))
    return build


class StageTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def test_single_module_back_compat(self):
        build = _make_module(self.tmp, "samples:android", [
            "FooNotification.png",
            "BarNotification.png",
        ])
        out = self.tmp / "out"
        # Bare string still works (legacy callers / unit tests).
        rc = np.cmd_stage(argparse.Namespace(
            build_dir=str(build),
            output_dir=str(out),
        ))
        self.assertEqual(rc, 0)
        findings = json.loads((out / "findings.json").read_text())
        self.assertEqual(len(findings["entries"]), 2)
        self.assertTrue(all(e["module"] == "samples:android" for e in findings["entries"]))
        self.assertTrue((out / "renders" / "FooNotification.png").exists())

    def test_multiple_modules_merge_into_one_findings(self):
        a = _make_module(self.tmp, "samples:android", ["FooNotification.png"])
        b = _make_module(self.tmp, "samples:wear", ["WearNotification.png"])
        out = self.tmp / "out"
        rc = np.cmd_stage(argparse.Namespace(
            build_dir=[str(a), str(b)],
            output_dir=str(out),
        ))
        self.assertEqual(rc, 0)
        findings = json.loads((out / "findings.json").read_text())
        modules = sorted({e["module"] for e in findings["entries"]})
        self.assertEqual(modules, ["samples:android", "samples:wear"])
        self.assertTrue((out / "renders" / "FooNotification.png").exists())
        self.assertTrue((out / "renders" / "WearNotification.png").exists())

    def test_module_with_no_pngs_does_not_abort_other_modules(self):
        # When `composePreviewRenderAll` runs across every module, plenty of
        # them will have a build/compose-previews dir with no notification
        # PNGs. That's not an error condition — the stage step should keep
        # going and only fail when *every* module came up empty.
        empty = _make_module(self.tmp, "lib:core", [])
        full = _make_module(self.tmp, "samples:android", ["FooNotification.png"])
        out = self.tmp / "out"
        rc = np.cmd_stage(argparse.Namespace(
            build_dir=[str(empty), str(full)],
            output_dir=str(out),
        ))
        self.assertEqual(rc, 0)
        findings = json.loads((out / "findings.json").read_text())
        self.assertEqual(len(findings["entries"]), 1)
        self.assertEqual(findings["entries"][0]["module"], "samples:android")

    def test_collision_across_modules_fails_loud(self):
        # Two modules emitting the same FQN-derived basename indicates a
        # genuine upstream bug; the staging step must refuse rather than let
        # one module's render silently overwrite the other's.
        a = _make_module(self.tmp, "samples:android", ["FooNotification.png"])
        b = _make_module(self.tmp, "samples:wear", ["FooNotification.png"])
        out = self.tmp / "out"
        rc = np.cmd_stage(argparse.Namespace(
            build_dir=[str(a), str(b)],
            output_dir=str(out),
        ))
        self.assertEqual(rc, 1)

    def test_all_modules_empty_still_hard_fails(self):
        empty1 = _make_module(self.tmp, "lib:core", [])
        empty2 = _make_module(self.tmp, "lib:util", [])
        out = self.tmp / "out"
        rc = np.cmd_stage(argparse.Namespace(
            build_dir=[str(empty1), str(empty2)],
            output_dir=str(out),
        ))
        self.assertEqual(rc, 1)


if __name__ == "__main__":
    unittest.main()
