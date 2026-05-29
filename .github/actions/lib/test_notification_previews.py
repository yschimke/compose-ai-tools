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


def _make_module(
    root: Path,
    module: str,
    png_names: list[str],
    declared: list[str] | None = None,
) -> Path:
    build = root / module.replace(":", "/") / "build" / "compose-previews"
    (build / "renders").mkdir(parents=True)
    for name in png_names:
        (build / "renders" / name).write_bytes(f"png-{name}".encode())
    manifest: dict = {"module": module}
    if declared is not None:
        # Declare each render output in the manifest so the stage step can
        # record `declaredPreviewIds` — including names with no PNG on disk
        # (a render that failed), which is the case the comment diff cares
        # about.
        manifest["previews"] = [
            {"id": Path(n).stem, "captures": [{"renderOutput": f"renders/{n}"}]}
            for n in declared
        ]
    (build / "previews.json").write_text(json.dumps(manifest))
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

    def test_stage_records_declared_ids_including_unrendered(self):
        # The manifest declares two notification previews but only one
        # rendered a PNG. `declaredPreviewIds` must carry both so the comment
        # can tell the unrendered one apart from a real removal.
        build = _make_module(
            self.tmp,
            "samples:android",
            ["FooNotification.png"],
            declared=["FooNotification.png", "GoneNotification.png"],
        )
        out = self.tmp / "out"
        rc = np.cmd_stage(argparse.Namespace(
            build_dir=str(build),
            output_dir=str(out),
        ))
        self.assertEqual(rc, 0)
        findings = json.loads((out / "findings.json").read_text())
        self.assertEqual(
            findings["declaredPreviewIds"],
            ["FooNotification", "GoneNotification"],
        )


class CommentTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    @staticmethod
    def _entry(pid: str, sha: str = "a") -> dict:
        return {
            "module": "samples:android",
            "previewId": pid,
            "pngBasename": f"{pid}.png",
            "sidecarBasename": "",
            "sha256": sha,
        }

    def _run_comment(self, head, baseline, declared=None) -> str:
        import contextlib
        import io

        head_path = self.tmp / "head.json"
        payload: dict = {"entries": head}
        if declared is not None:
            payload["declaredPreviewIds"] = declared
        head_path.write_text(json.dumps(payload))
        base_path = self.tmp / "base.json"
        base_path.write_text(json.dumps({"entries": baseline}))

        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            np.cmd_comment(argparse.Namespace(
                findings=str(head_path),
                repo="o/r",
                head_ref="headsha",
                baseline=str(base_path),
                baseline_branch="compose-preview/notifications/main",
            ))
        return buf.getvalue()

    def test_missing_but_still_declared_is_not_rendered_not_removed(self):
        # The head still declares Foo (its module built) but no PNG came out
        # this run — a render failure, not a deletion. Must surface under the
        # "Not rendered" warning, never "Removed". Regression for #1588.
        body = self._run_comment(
            head=[],
            baseline=[self._entry("x.Foo")],
            declared=["x.Foo"],
        )
        self.assertIn("Not rendered", body)
        self.assertIn("[!WARNING]", body)
        self.assertIn("`x.Foo`", body)
        self.assertNotIn("### Removed", body)
        self.assertIn("0 added · 0 changed · 0 removed · 1 not rendered", body)

    def test_missing_and_undeclared_is_removed(self):
        # Head declares something else but not Foo → Foo was genuinely removed
        # from source. Real removal still reported as such.
        body = self._run_comment(
            head=[self._entry("x.Bar")],
            baseline=[self._entry("x.Foo"), self._entry("x.Bar")],
            declared=["x.Bar"],
        )
        self.assertIn("### Removed", body)
        self.assertIn("`x.Foo`", body)
        self.assertNotIn("Not rendered", body)

    def test_no_manifest_coverage_errs_toward_not_rendered(self):
        # Without any declared ids we can't prove a removal, so a missing
        # baseline preview is treated as not-rendered rather than asserting a
        # deletion.
        body = self._run_comment(
            head=[],
            baseline=[self._entry("x.Foo")],
            declared=[],
        )
        self.assertIn("Not rendered", body)
        self.assertNotIn("### Removed", body)

    def test_added_and_changed_still_reported(self):
        body = self._run_comment(
            head=[self._entry("x.New"), self._entry("x.Same", sha="b")],
            baseline=[self._entry("x.Same", sha="a")],
            declared=["x.New", "x.Same"],
        )
        self.assertIn("### Added", body)
        self.assertIn("`x.New`", body)
        self.assertIn("### Changed", body)
        self.assertIn("`x.Same`", body)

    def test_silent_when_no_diff(self):
        body = self._run_comment(
            head=[self._entry("x.Same", sha="a")],
            baseline=[self._entry("x.Same", sha="a")],
            declared=["x.Same"],
        )
        self.assertEqual(body, "")


if __name__ == "__main__":
    unittest.main()
