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
        # Lay out the on-disk shape the daemon produces. The annotated overlay
        # for every preview lives at `data/<previewId>/a11y-overlay.png`, so
        # every annotatedPath shares the same basename — copy-annotated has
        # to rename on the way out or overlays overwrite each other.
        (self.build / "renders").mkdir()
        (self.build / "renders" / "Bad_small.png").write_bytes(b"clean-bad")
        (self.build / "renders" / "Good_large.png").write_bytes(b"clean-good")
        (self.build / "data" / "x.Bad_small_round").mkdir(parents=True)
        (self.build / "data" / "x.Bad_small_round" / "a11y-overlay.png").write_bytes(b"overlay-bad")
        (self.build / "data" / "x.Good_large_round").mkdir(parents=True)
        (self.build / "data" / "x.Good_large_round" / "a11y-overlay.png").write_bytes(b"overlay-good")
        (self.build / "previews.json").write_text(json.dumps({
            "module": "sample-wear",
            "variant": "debug",
            "previews": [
                _preview(id="x.Bad_small_round", function="Bad",
                         render="renders/Bad_small.png"),
                _preview(id="x.Good_large_round", function="Good",
                         render="renders/Good_large.png"),
            ],
            # `a11y-report.py` reads `accessibility.json` directly, so this field isn't
            # strictly required for this fixture — it's written to mirror what the plugin
            # emits in production so the test catches accidental drift if the script ever
            # migrates to the manifest pointer.
            "dataExtensionReports": {"a11y": "accessibility.json"},
        }))
        (self.build / "accessibility.json").write_text(json.dumps({
            "module": "sample-wear",
            "entries": [
                {
                    "previewId": "x.Bad_small_round",
                    "findings": [_finding()],
                    "annotatedPath": "data/x.Bad_small_round/a11y-overlay.png",
                },
                {
                    "previewId": "x.Good_large_round",
                    "findings": [],
                    "annotatedPath": "data/x.Good_large_round/a11y-overlay.png",
                },
            ],
        }))

    def test_copies_clean_and_annotated(self):
        out = self.tmp / "out"
        import argparse
        ar.cmd_copy_annotated(argparse.Namespace(
            build_dir=str(self.build),
            output_dir=str(out),
        ))
        renders_out = out / "renders" / "sample-wear"
        self.assertTrue((renders_out / "Bad_small.png").exists())
        self.assertTrue((renders_out / "Bad_small.a11y.png").exists())
        self.assertTrue((renders_out / "Good_large.png").exists())
        self.assertTrue((renders_out / "Good_large.a11y.png").exists())
        # Each preview's overlay must keep its own bytes — the production
        # source basenames collide, so a naive copy would let one preview's
        # overlay overwrite another's.
        self.assertEqual((renders_out / "Bad_small.a11y.png").read_bytes(), b"overlay-bad")
        self.assertEqual((renders_out / "Good_large.a11y.png").read_bytes(), b"overlay-good")
        findings = json.loads((out / "findings.json").read_text())
        self.assertEqual(len(findings["entries"]), 2)
        # Normal runs omit `status` entirely — keeps diffs against the baseline
        # trivially clean for the fingerprint comparison in cmd_comment.
        self.assertNotIn("status", findings)
        by_fn = {e["functionName"]: e for e in findings["entries"]}
        self.assertEqual(by_fn["Bad"]["cleanBasename"], "Bad_small.png")
        self.assertEqual(by_fn["Bad"]["annotatedBasename"], "Bad_small.a11y.png")
        self.assertEqual(len(by_fn["Bad"]["findings"]), 1)
        self.assertEqual(by_fn["Good"]["cleanBasename"], "Good_large.png")
        self.assertEqual(by_fn["Good"]["annotatedBasename"], "Good_large.a11y.png")
        self.assertEqual(by_fn["Good"]["findings"], [])

    def test_merges_multiple_build_dirs_into_one_findings(self):
        # Second module fixture (sample-phone) under a sibling build dir.
        # copy-annotated should fold both modules into a single findings.json
        # and keep each module's renders namespaced under `renders/<module>/`.
        other = self.tmp / "build-phone"
        other.mkdir()
        (other / "renders").mkdir()
        (other / "renders" / "Phone.png").write_bytes(b"clean-phone")
        (other / "data" / "p.Phone_default").mkdir(parents=True)
        (other / "data" / "p.Phone_default" / "a11y-overlay.png").write_bytes(b"overlay-phone")
        (other / "previews.json").write_text(json.dumps({
            "module": "sample-phone",
            "variant": "debug",
            "previews": [
                _preview(id="p.Phone_default", function="Phone",
                         render="renders/Phone.png"),
            ],
            "dataExtensionReports": {"a11y": "accessibility.json"},
        }))
        (other / "accessibility.json").write_text(json.dumps({
            "module": "sample-phone",
            "entries": [
                {
                    "previewId": "p.Phone_default",
                    "findings": [_finding(level="ERROR")],
                    "annotatedPath": "data/p.Phone_default/a11y-overlay.png",
                },
            ],
        }))

        out = self.tmp / "out"
        import argparse
        ar.cmd_copy_annotated(argparse.Namespace(
            build_dir=[str(self.build), str(other)],
            output_dir=str(out),
        ))
        self.assertTrue((out / "renders" / "sample-wear" / "Bad_small.png").exists())
        self.assertTrue((out / "renders" / "sample-phone" / "Phone.png").exists())
        findings = json.loads((out / "findings.json").read_text())
        modules = sorted({e["module"] for e in findings["entries"]})
        self.assertEqual(modules, ["sample-phone", "sample-wear"])
        # Three entries: Bad + Good from wear, Phone from phone.
        self.assertEqual(len(findings["entries"]), 3)

    def test_combined_status_propagates_atf_unavailable_from_any_module(self):
        # One module ran clean, the other came back atf-unavailable. The
        # combined report should still carry the unavailable flag so the PR
        # comment surfaces the warning.
        other = self.tmp / "build-phone"
        other.mkdir()
        (other / "renders").mkdir()
        (other / "previews.json").write_text(json.dumps({
            "module": "sample-phone",
            "variant": "debug",
            "previews": [],
            "dataExtensionReports": {"a11y": "accessibility.json"},
        }))
        (other / "accessibility.json").write_text(json.dumps({
            "module": "sample-phone",
            "entries": [],
            "status": "atf-unavailable",
        }))

        out = self.tmp / "out"
        import argparse
        ar.cmd_copy_annotated(argparse.Namespace(
            build_dir=[str(self.build), str(other)],
            output_dir=str(out),
        ))
        findings = json.loads((out / "findings.json").read_text())
        self.assertEqual(findings["status"], "atf-unavailable")

    def test_propagates_atf_unavailable_status_to_findings(self):
        # Simulate the daemon-failure shape `DaemonA11yFetcher` writes when no
        # per-preview ATF fetch succeeds: top-level `status` set, entries
        # empty. The copy-annotated step must propagate the status into
        # findings.json so the downstream comment subcommand can surface it.
        (self.build / "accessibility.json").write_text(json.dumps({
            "module": "sample-wear",
            "entries": [],
            "status": "atf-unavailable",
        }))
        out = self.tmp / "out"
        import argparse
        ar.cmd_copy_annotated(argparse.Namespace(
            build_dir=str(self.build),
            output_dir=str(out),
        ))
        findings = json.loads((out / "findings.json").read_text())
        self.assertEqual(findings["status"], "atf-unavailable")


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
            branch="compose-preview/a11y/main",
            output=str(out),
        ))
        body = out.read_text()
        self.assertIn("Accessibility Report", body)
        self.assertIn("BadWearButtonPreview", body)
        # The annotated PNG wins over the clean one when there are findings.
        self.assertIn("Bad_small.a11y.png", body)
        self.assertIn("compose-preview/a11y/main", body)
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
            branch="compose-preview/a11y/main",
            output=str(out),
        ))
        body = out.read_text()
        self.assertIn("Button_large.png", body)
        self.assertIn("No findings", body)


class CommentTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def _entry(
        self,
        *,
        findings: list[dict] | None = None,
        function: str = "Bad",
        preview_id: str = "x.Bad_small_round",
        module: str = "sample-wear",
    ) -> dict:
        return {
            "module": module,
            "functionName": function,
            "sourceFile": "Previews.kt",
            "previewId": preview_id,
            "variant": "small_round",
            "cleanBasename": f"{function}.png",
            "annotatedBasename": f"{function}.a11y.png" if findings else "",
            "findings": findings or [],
        }

    def _run_comment(
        self,
        current_entries,
        *,
        baseline_entries=None,
        status=None,
        baseline_status=None,
    ):
        findings_path = self.tmp / "findings.json"
        current_payload: dict = {"entries": current_entries}
        if status is not None:
            current_payload["status"] = status
        findings_path.write_text(json.dumps(current_payload))
        baseline_path = None
        if baseline_entries is not None:
            baseline_path = self.tmp / "baseline.json"
            baseline_payload: dict = {"entries": baseline_entries}
            if baseline_status is not None:
                baseline_payload["status"] = baseline_status
            baseline_path.write_text(json.dumps(baseline_payload))

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

    def test_atf_unavailable_surfaces_warning_in_comment(self):
        # Regression for #1453: when the daemon fails, the report carries
        # status="atf-unavailable" and the comment must surface that rather
        # than silently rendering "no findings."
        body = self._run_comment(
            [],
            status="atf-unavailable",
        )
        self.assertIn("<!-- a11y-report -->", body)
        self.assertIn("[!WARNING]", body)
        self.assertIn("ATF data unavailable", body)

    def test_atf_unavailable_breaks_silence_against_clean_baseline(self):
        # The bug in #1453 is that an atf-unavailable run fingerprints
        # identically to a clean run with zero findings — so the comment
        # subcommand stayed silent. With status in the fingerprint, the
        # comment must fire so reviewers see the daemon failure.
        body = self._run_comment(
            [],
            baseline_entries=[],
            status="atf-unavailable",
            baseline_status=None,
        )
        self.assertNotEqual(body, "")
        self.assertIn("ATF data unavailable", body)

    def test_clean_run_still_silent_against_atf_unavailable_baseline(self):
        # Inverse direction: an atf-unavailable baseline shouldn't keep the
        # comment silent when the PR has recovered to a clean run — the
        # fingerprints differ on status alone, so the comment fires with
        # the normal "no findings" body.
        body = self._run_comment(
            [self._entry(findings=[])],
            baseline_entries=[],
            baseline_status="atf-unavailable",
        )
        self.assertNotEqual(body, "")
        self.assertIn("No accessibility findings", body)

    def test_atf_unavailable_preserves_baseline_findings_not_resolved(self):
        # Regression for #1595: when the current run is atf-unavailable its
        # entries are empty, but the baseline had real findings. Those must be
        # preserved as carried-over — NOT diffed away into a "Resolved" block,
        # which would falsely signal the issues were fixed when in fact nothing
        # was checked.
        baseline_entry = self._entry(
            function="Bad", findings=[_finding(level="ERROR", message="Contrast.")]
        )
        body = self._run_comment(
            [],  # daemon down: no current entries
            baseline_entries=[baseline_entry],
            status="atf-unavailable",
        )
        # The prior finding is re-surfaced, not dropped or marked resolved.
        self.assertIn("`Bad`", body)
        self.assertIn("Contrast.", body)
        self.assertNotIn("Resolved", body)
        self.assertNotIn("_No findings._", body)
        # Banner explains no comparison was produced.
        self.assertIn("[!WARNING]", body)
        self.assertIn("no comparison was performed", body)
        self.assertIn("preserved", body)

    def test_atf_unavailable_counts_track_baseline_not_zero(self):
        # Regression for #1595: the summary counters must reflect the baseline
        # findings being preserved, not collapse to a misleading zero from the
        # empty current entries.
        baseline_entries = [
            self._entry(
                function="A", preview_id="x.A",
                findings=[_finding(level="ERROR"), _finding(level="WARNING")],
            ),
            self._entry(
                function="B", preview_id="x.B", findings=[_finding(level="INFO")]
            ),
            self._entry(function="Clean", preview_id="x.Clean", findings=[]),
        ]
        body = self._run_comment(
            [], baseline_entries=baseline_entries, status="atf-unavailable"
        )
        self.assertIn("1 error(s) · 1 warning(s) · 1 info", body)
        # Only the two previews carrying findings are counted; the clean one
        # is not surfaced.
        self.assertIn("across 2 baseline preview(s)", body)

    def test_only_changed_preview_gets_a_block_others_collapsed(self):
        # Two previews exist; only one gains a finding vs the baseline. The
        # changed one gets a full findings table, the unchanged-but-flagged
        # one is collapsed to a name in the <details> roster rather than
        # re-posting its table (#1585).
        kept = self._entry(
            function="Kept", preview_id="x.Kept", findings=[_finding(level="ERROR")]
        )
        added = self._entry(
            function="Added", preview_id="x.Added", findings=[_finding(level="WARNING")]
        )
        baseline_kept = self._entry(
            function="Kept", preview_id="x.Kept", findings=[_finding(level="ERROR")]
        )
        baseline_added = self._entry(function="Added", preview_id="x.Added", findings=[])
        body = self._run_comment(
            [kept, added], baseline_entries=[baseline_kept, baseline_added]
        )
        # The changed preview gets its own h3 block + table.
        self.assertIn("### `Added`", body)
        # The unchanged preview is only listed as a name inside <details>.
        self.assertIn("<summary>Unchanged (1 preview(s))</summary>", body)
        self.assertIn("- `Kept`", body)
        self.assertNotIn("### `Kept`", body)

    def test_new_clean_preview_stays_silent(self):
        # Adding a brand-new preview that has no findings is not an a11y
        # change — it must not break silence with a near-empty comment.
        existing = self._entry(function="A", preview_id="x.A", findings=[])
        new_clean = self._entry(function="B", preview_id="x.B", findings=[])
        body = self._run_comment([existing, new_clean], baseline_entries=[existing])
        self.assertEqual(body, "")

    def test_atf_unavailable_does_not_render_findings_as_resolved(self):
        # When ATF fails, copy-annotated still emits the manifest previews
        # with empty findings. Diffing those against a baseline that had
        # findings must NOT render them as changed-to-empty/"No findings" or
        # "Resolved" — that would make a daemon failure look like the issues
        # were fixed (#1595). Instead the baseline finding is preserved
        # verbatim under the carried-over banner.
        baseline = self._entry(
            function="Bad", preview_id="x.Bad", findings=[_finding(level="ERROR")]
        )
        current = self._entry(function="Bad", preview_id="x.Bad", findings=[])
        body = self._run_comment(
            [current], baseline_entries=[baseline], status="atf-unavailable"
        )
        self.assertIn("ATF data unavailable", body)
        self.assertNotIn("_No findings._", body)
        self.assertNotIn("Resolved", body)
        self.assertNotIn("No accessibility findings", body)
        # The prior finding is preserved, not silently dropped.
        self.assertIn("### `Bad`", body)

    def test_resolved_preview_listed_when_removed(self):
        # A preview that carried a finding on the baseline but is gone now
        # should be called out as resolved.
        baseline = self._entry(
            function="Gone", preview_id="x.Gone", findings=[_finding(level="ERROR")]
        )
        survivor = self._entry(
            function="Stay", preview_id="x.Stay", findings=[_finding(level="WARNING")]
        )
        body = self._run_comment([survivor], baseline_entries=[baseline, survivor])
        self.assertIn("Resolved", body)
        self.assertIn("`Gone`", body)


if __name__ == "__main__":
    unittest.main()
