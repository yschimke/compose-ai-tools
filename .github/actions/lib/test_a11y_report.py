#!/usr/bin/env python3
"""Tests for a11y-report.py.

Pure stdlib (unittest) — same shape as test_compare_previews.py. Run:

    python3 -m unittest .github/actions/lib/test_a11y_report.py
"""

from __future__ import annotations

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_SPEC = importlib.util.spec_from_file_location(
    "a11y_report", _HERE / "a11y-report.py"
)
ar = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
_SPEC.loader.exec_module(ar)


def _preview(*, id: str, function: str, render: str = "",
             device: str | None = None, kind: str = "COMPOSE") -> dict:
    params: dict = {"kind": kind}
    if device is not None:
        params["device"] = device
    return {
        "id": id,
        "functionName": function,
        "className": "K",
        "sourceFile": "f.kt",
        "params": params,
        "captures": [{"renderOutput": render}] if render else [{"renderOutput": ""}],
    }


def _finding(*, level: str = "WARNING", rule: str = "TouchTargetSizeCheck",
             message: str = "Too small.") -> dict:
    return {
        "level": level,
        "type": rule,
        "message": message,
        "viewDescription": "ComposeView",
        "boundsInScreen": "0,0,40,40",
    }


class DevicePriorityTest(unittest.TestCase):
    def test_large_round_wins(self):
        self.assertLess(
            ar.device_priority("id:wearos_large_round"),
            ar.device_priority("id:wearos_small_round"),
        )

    def test_unknown_device_falls_through(self):
        # Anything not in _DEVICE_PRIORITY scores worse than every listed
        # device, so listed previews always win the tiebreak.
        self.assertGreater(
            ar.device_priority("id:phone_xl"),
            ar.device_priority("id:wearos_small_round"),
        )

    def test_missing_device_falls_through(self):
        self.assertGreater(
            ar.device_priority(None),
            ar.device_priority("id:wearos_small_round"),
        )

    def test_label_strips_id_prefix(self):
        self.assertEqual(ar.variant_label("id:wearos_large_round"), "wearos_large_round")
        self.assertEqual(ar.variant_label(None), "")


class SelectVariantsTest(unittest.TestCase):
    def test_collapses_to_one_per_function(self):
        manifest = {
            "module": "sample-wear",
            "previews": [
                _preview(id="x.ButtonPreview_1", function="ButtonPreview",
                         device="id:wearos_small_round"),
                _preview(id="x.ButtonPreview_2", function="ButtonPreview",
                         device="id:wearos_large_round"),
                _preview(id="x.BadPreview_1", function="BadPreview",
                         device="id:wearos_small_round"),
            ],
        }
        rows = ar.select_variants(manifest, {})
        self.assertEqual(len(rows), 2)
        # Function names sort: BadPreview, ButtonPreview.
        self.assertEqual(rows[0]["functionName"], "BadPreview")
        self.assertEqual(rows[0]["variant"], "wearos_small_round")
        self.assertEqual(rows[1]["functionName"], "ButtonPreview")
        # Large round wins over small round per the global ordering.
        self.assertEqual(rows[1]["variant"], "wearos_large_round")
        self.assertEqual(rows[1]["previewId"], "x.ButtonPreview_2")

    def test_unknown_devices_fall_back_to_id_sort(self):
        manifest = {
            "module": "app",
            "previews": [
                _preview(id="x.Foo_b", function="Foo", device="id:phone"),
                _preview(id="x.Foo_a", function="Foo", device="id:tablet"),
            ],
        }
        rows = ar.select_variants(manifest, {})
        # Both devices are unlisted → tied on priority, so the id-sort
        # tiebreaker picks `Foo_a`.
        self.assertEqual(rows[0]["previewId"], "x.Foo_a")

    def test_filters_out_tile_previews(self):
        manifest = {
            "module": "sample-wear",
            "previews": [
                _preview(id="x.HelloTile_1", function="HelloTile",
                         device="id:wearos_small_round", kind="TILE"),
                _preview(id="x.Button_1", function="Button",
                         device="id:wearos_large_round"),
            ],
        }
        rows = ar.select_variants(manifest, {})
        self.assertEqual([r["functionName"] for r in rows], ["Button"])

    def test_filters_out_scroll_captures(self):
        scroll_preview = _preview(
            id="x.LongList_1", function="LongList",
            device="id:wearos_large_round",
        )
        scroll_preview["captures"] = [
            {"renderOutput": "renders/LongList.png", "scroll": {"mode": "LONG"}},
        ]
        manifest = {
            "module": "sample-wear",
            "previews": [
                scroll_preview,
                _preview(id="x.Button_1", function="Button",
                         device="id:wearos_large_round"),
            ],
        }
        rows = ar.select_variants(manifest, {})
        # The scroll-only function drops; the static button stays.
        self.assertEqual([r["functionName"] for r in rows], ["Button"])

    def test_filters_out_gif_animations(self):
        gif_preview = _preview(
            id="x.Anim_1", function="Anim",
            device="id:phone",
        )
        gif_preview["captures"] = [{"renderOutput": "renders/Anim.gif"}]
        manifest = {"module": "app", "previews": [gif_preview]}
        rows = ar.select_variants(manifest, {})
        self.assertEqual(rows, [])

    def test_merges_a11y_for_chosen_variant(self):
        manifest = {
            "module": "sample-wear",
            "previews": [
                _preview(id="x.Bad_small_round", function="Bad",
                         render="renders/Bad_small.png"),
            ],
        }
        a11y_by_id = {
            "x.Bad_small_round": {
                "previewId": "x.Bad_small_round",
                "findings": [_finding()],
                "annotatedPath": "accessibility-per-preview/Bad_small.a11y.png",
            },
        }
        rows = ar.select_variants(manifest, a11y_by_id)
        self.assertEqual(len(rows[0]["findings"]), 1)
        self.assertEqual(
            rows[0]["annotatedPath"],
            "accessibility-per-preview/Bad_small.a11y.png",
        )
        self.assertEqual(rows[0]["renderOutput"], "renders/Bad_small.png")


class CopyAnnotatedTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.build = self.tmp / "build"
        self.build.mkdir()
        # Lay out the on-disk shape the plugin produces.
        (self.build / "renders").mkdir()
        (self.build / "renders" / "Bad_small.png").write_bytes(b"clean")
        (self.build / "accessibility-per-preview").mkdir()
        (self.build / "accessibility-per-preview" / "Bad_small.a11y.png").write_bytes(b"annotated")
        (self.build / "previews.json").write_text(json.dumps({
            "module": "sample-wear",
            "variant": "debug",
            "previews": [
                _preview(id="x.Bad_small_round", function="Bad",
                         render="renders/Bad_small.png"),
            ],
            "accessibilityReport": "accessibility.json",
        }))
        (self.build / "accessibility.json").write_text(json.dumps({
            "module": "sample-wear",
            "entries": [{
                "previewId": "x.Bad_small_round",
                "findings": [_finding()],
                "annotatedPath": "accessibility-per-preview/Bad_small.a11y.png",
            }],
        }))

    def test_copies_clean_and_annotated(self):
        out = self.tmp / "out"
        import argparse
        ar.cmd_copy_annotated(argparse.Namespace(
            build_dir=str(self.build),
            output_dir=str(out),
        ))
        self.assertTrue((out / "renders" / "sample-wear" / "Bad_small.png").exists())
        self.assertTrue((out / "renders" / "sample-wear" / "Bad_small.a11y.png").exists())
        findings = json.loads((out / "findings.json").read_text())
        self.assertEqual(len(findings["entries"]), 1)
        entry = findings["entries"][0]
        self.assertEqual(entry["cleanBasename"], "Bad_small.png")
        self.assertEqual(entry["annotatedBasename"], "Bad_small.a11y.png")
        self.assertEqual(len(entry["findings"]), 1)


class ReadmeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def _write_findings(self, entries: list[dict]) -> Path:
        path = self.tmp / "findings.json"
        path.write_text(json.dumps({"entries": entries}))
        return path

    def test_readme_with_findings_lists_them(self):
        findings_path = self._write_findings([{
            "module": "sample-wear",
            "functionName": "BadWearButtonPreview",
            "sourceFile": "Previews.kt",
            "previewId": "x.Bad_small_round",
            "variant": "small_round",
            "cleanBasename": "Bad_small.png",
            "annotatedBasename": "Bad_small.a11y.png",
            "findings": [_finding(level="WARNING")],
        }])
        out = self.tmp / "README.md"
        import argparse
        ar.cmd_readme(argparse.Namespace(
            findings=str(findings_path),
            repo="org/repo",
            branch="a11y_main",
            output=str(out),
        ))
        body = out.read_text()
        self.assertIn("Accessibility Report", body)
        self.assertIn("BadWearButtonPreview", body)
        # The annotated PNG wins over the clean one when there are findings.
        self.assertIn("Bad_small.a11y.png", body)
        self.assertIn("a11y_main", body)
        self.assertIn("WARNING", body)
        self.assertIn("TouchTargetSizeCheck", body)

    def test_readme_clean_preview_uses_clean_render(self):
        findings_path = self._write_findings([{
            "module": "sample-wear",
            "functionName": "ButtonPreview",
            "sourceFile": "Previews.kt",
            "previewId": "x.Button_large_round",
            "variant": "large_round",
            "cleanBasename": "Button_large.png",
            "annotatedBasename": "",
            "findings": [],
        }])
        out = self.tmp / "README.md"
        import argparse
        ar.cmd_readme(argparse.Namespace(
            findings=str(findings_path),
            repo="org/repo",
            branch="a11y_main",
            output=str(out),
        ))
        body = out.read_text()
        self.assertIn("Button_large.png", body)
        self.assertIn("No findings", body)


class CommentTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def _entry(self, *, findings: list[dict] | None = None) -> dict:
        return {
            "module": "sample-wear",
            "functionName": "Bad",
            "sourceFile": "Previews.kt",
            "previewId": "x.Bad_small_round",
            "variant": "small_round",
            "cleanBasename": "Bad.png",
            "annotatedBasename": "Bad.a11y.png" if findings else "",
            "findings": findings or [],
        }

    def _run_comment(self, current_entries, *, baseline_entries=None):
        findings_path = self.tmp / "findings.json"
        findings_path.write_text(json.dumps({"entries": current_entries}))
        baseline_path = None
        if baseline_entries is not None:
            baseline_path = self.tmp / "baseline.json"
            baseline_path.write_text(json.dumps({"entries": baseline_entries}))

        import argparse, io, contextlib
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            ar.cmd_comment(argparse.Namespace(
                findings=str(findings_path),
                repo="org/repo",
                head_ref="abc123",
                baseline=str(baseline_path) if baseline_path else None,
            ))
        return buf.getvalue()

    def test_comment_carries_marker(self):
        body = self._run_comment([self._entry(findings=[_finding(level="ERROR")])])
        self.assertTrue(body.startswith("<!-- a11y-report -->"))
        self.assertIn("ERROR", body)
        self.assertIn("abc123", body)

    def test_silent_when_findings_match_baseline(self):
        # Identical findings on both sides → no comment body, the action
        # uses the empty stdout to skip the upsert and the branch push.
        entry = self._entry(findings=[_finding(level="ERROR")])
        body = self._run_comment([entry], baseline_entries=[entry])
        self.assertEqual(body, "")

    def test_emits_when_finding_added(self):
        baseline_entry = self._entry(findings=[])
        current_entry = self._entry(findings=[_finding(level="ERROR")])
        body = self._run_comment([current_entry], baseline_entries=[baseline_entry])
        self.assertIn("<!-- a11y-report -->", body)

    def test_emits_when_finding_removed(self):
        baseline_entry = self._entry(findings=[_finding(level="ERROR")])
        current_entry = self._entry(findings=[])
        body = self._run_comment([current_entry], baseline_entries=[baseline_entry])
        # Different finding count on the baseline side → fingerprint diverges.
        self.assertIn("<!-- a11y-report -->", body)

    def test_silent_when_baseline_missing_but_no_findings(self):
        # No baseline file at all behaves like an empty baseline; so a PR
        # that introduces zero findings on a fresh repo still goes silent.
        body = self._run_comment(
            [self._entry(findings=[])],
            baseline_entries=[self._entry(findings=[])],
        )
        self.assertEqual(body, "")


if __name__ == "__main__":
    unittest.main()
