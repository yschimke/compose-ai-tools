#!/usr/bin/env python3
"""Tests for guidelines-report.py and guidelines-stage.py.

Pure stdlib (unittest), the same shape as test_a11y_report.py. Run:

    python3 .github/actions/lib/test_guidelines_report.py -v
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

_HERE = Path(__file__).resolve().parent


def _load(name: str, file: str):
    spec = importlib.util.spec_from_file_location(name, _HERE / file)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


gr = _load("guidelines_report", "guidelines-report.py")
gs = _load("guidelines_stage", "guidelines-stage.py")
gb = _load("guidelines_budget", "guidelines-budget.py")

RULES = {
    "schema": "compose-ui-builder/catalog-guidelines/v1",
    "catalog": "wear-m3",
    "platform": "wear",
    "version": 6,
    "rules": [
        {"id": "wear.touch-target-48dp", "severity": "warning",
         "source": "https://developer.android.com/design/ui/wear/guides/foundations/accessibility"},
        {"id": "wear.button.emphasis", "severity": "info", "source": "https://developer.android.com/b"},
    ],
}


def _result(preview_id: str, verdicts: list[dict], unchecked: list[str] | None = None) -> dict:
    return {
        "previewId": preview_id,
        "renderHash": "abc",
        "record": {
            "schema": "compose-ui-builder/guidelines-result/v1",
            "previewId": preview_id,
            "revision": 0,
            "model": "deepseek/deepseek-v4.1-flash",
            "rulesVersion": 6,
            "asked": [v["ruleId"] for v in verdicts],
            "verdicts": verdicts,
            "servedModel": "deepseek/deepseek-v4.1-flash",
            "costUsd": 0.0034,
        },
        "unchecked": unchecked or [],
    }


def _args(**kw) -> argparse.Namespace:
    return argparse.Namespace(
        image_repo=kw.get("image_repo"), image_ref=kw.get("image_ref"),
        image_prefix=kw.get("image_prefix", "guidelines"), stage_images=kw.get("stage_images"),
    )


PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 16


def _chunk(kind: bytes, data: bytes) -> bytes:
    import zlib
    return (len(data).to_bytes(4, "big") + kind + data
            + zlib.crc32(kind + data).to_bytes(4, "big"))


# An overlay as GuidelineAnnotator writes it: IHDR, then the format's tEXt entry, then the image.
NUMBERED_PNG = (b"\x89PNG\r\n\x1a\n" + _chunk(b"IHDR", b"\x00" * 13)
                + _chunk(b"tEXt", b"compose-preview-guidelines-overlay\0numbered-v1")
                + _chunk(b"IDAT", b"") + _chunk(b"IEND", b""))


class ReportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        module = self.tmp / "catalog"
        module.mkdir()
        (module / "ui-builder.guidelines.json").write_text(json.dumps(RULES))
        (module / "previews.json").write_text(json.dumps({"previews": [
            {"id": "x.StopKt.Stop", "captures": [{"renderOutput": "renders/Stop-1.png"}]}]}))
        (module / "guidelines.json").write_text(json.dumps({
            "module": "handoff", "catalog": "wear-m3", "model": "deepseek/deepseek-v4.1-flash",
            "results": [
                _result("x.StopKt.Stop", [
                    {"ruleId": "wear.touch-target-48dp", "verdict": "fail", "confidence": 0.85,
                     "nodeIds": ["stop"], "reason": "The icon button is fixed at 36dp."},
                    {"ruleId": "wear.button.emphasis", "verdict": "fail", "confidence": 0.3,
                     "nodeIds": [], "reason": "Low confidence, dropped."},
                ], unchecked=["wear.color.role-pairs"]),
                _result("x.OkKt.Ok", [
                    {"ruleId": "wear.touch-target-48dp", "verdict": "pass", "confidence": 0.9,
                     "nodeIds": [], "reason": ""},
                ]),
            ],
        }))

    def test_comment_lists_findings_with_guide_nodes_and_cost(self) -> None:
        body = gr.build(self.tmp, _args())
        assert body is not None
        self.assertTrue(body.startswith(gr.MARKER))
        self.assertIn("2 changed preview(s) checked", body)
        self.assertIn("**1 finding(s)**", body)
        self.assertIn("**wear.touch-target-48dp** on `stop` (85%)", body)
        self.assertIn("design/ui/wear/guides/foundations/accessibility", body)
        self.assertIn("Unchecked", body)
        self.assertNotIn("Low confidence", body)
        self.assertNotIn("x.OkKt.Ok", body)
        self.assertIn("$0.0068", body)
        self.assertNotIn("<img", body)

    def _renders(self, annotated: bool = True, nodes: list[dict] | None = None,
                 overlay: bytes = NUMBERED_PNG) -> None:
        renders = self.tmp / "catalog" / "renders"
        renders.mkdir(exist_ok=True)
        (renders / "Stop-1.png").write_bytes(PNG)
        if annotated:
            (renders / "Stop-1.guidelines.png").write_bytes(overlay)
        if nodes is not None:
            (self.tmp / "catalog" / "accessibility.json").write_text(json.dumps({"entries": [
                {"previewId": "x.StopKt.Stop", "nodes": nodes}]}))

    def test_images_only_from_a_pinned_github_location(self) -> None:
        self._renders(nodes=[{"ref": "stop", "boundsInScreen": "0,0,10,10"}])
        body = gr.build(self.tmp, _args(image_repo="org/repo", image_ref="0123abc"))
        assert body is not None
        url = ("https://raw.githubusercontent.com/org/repo/0123abc/guidelines/catalog/"
               "Stop-1.guidelines.png")
        # Shown at a width the badges read at, and linked to the full-size picture.
        self.assertIn(f'<a href="{url}"><img src="{url}" width="320" /></a>', body)
        self.assertIn("1 finding location(s) marked", body)
        self.assertIn("- **1** ⚠️ **wear.touch-target-48dp** on `stop` (85%)", body)

    def test_a_finding_naming_nothing_on_the_render_shows_the_render_and_says_so(self) -> None:
        # No accessibility nodes were staged, so `stop` names nothing the annotator can outline.
        self._renders()
        body = gr.build(self.tmp, _args(image_repo="org/repo", image_ref="0123abc"))
        assert body is not None
        self.assertIn("0123abc/guidelines/catalog/Stop-1.png", body)
        self.assertNotIn("Stop-1.guidelines.png", body)
        self.assertIn("Nothing marked", body)
        # Nothing on the picture carries a number, so neither does the finding.
        self.assertIn("- ⚠️ **wear.touch-target-48dp** on `stop`", body)

    def test_findings_carry_the_badge_numbers_the_annotator_draws(self) -> None:
        # The annotator numbers findings with a mark, in the order listed (by confidence); a
        # finding with nothing on the render is listed without a number and does not take one.
        self._renders(nodes=[{"ref": "stop", "boundsInScreen": "0,0,10,10"}])
        report = json.loads((self.tmp / "catalog" / "guidelines.json").read_text())
        report["results"][0]["record"]["verdicts"] = [
            {"ruleId": "wear.button.emphasis", "verdict": "fail", "confidence": 0.6,
             "nodeIds": [], "reason": "Region, listed third.",
             "regions": [{"subjectId": "x.StopKt.Stop", "x": 0.5, "y": 0.5, "width": 0.2,
                          "height": 0.2}]},
            {"ruleId": "wear.touch-target-48dp", "verdict": "fail", "confidence": 0.9,
             "nodeIds": ["stop"], "reason": "Node, listed first."},
            {"ruleId": "wear.layout.no-clipping", "verdict": "fail", "confidence": 0.7,
             "nodeIds": ["gone"], "reason": "Names nothing on the render, listed second.",
             "regions": [{"subjectId": "x.StopKt.Stop", "pictureKind": "long", "x": 0, "y": 0,
                          "width": 1, "height": 1}]},
        ]
        (self.tmp / "catalog" / "guidelines.json").write_text(json.dumps(report))
        body = gr.build(self.tmp, _args(image_repo="org/repo", image_ref="0123abc"))
        assert body is not None
        self.assertIn("2 finding location(s) marked", body)
        listed = [line for line in body.splitlines() if line.startswith("- ")]
        self.assertEqual(len(listed), 4)
        self.assertTrue(listed[0].startswith("- **1** ⚠️ **wear.touch-target-48dp**"), listed[0])
        self.assertTrue(listed[1].startswith("- ⚠️ **wear.layout.no-clipping**"), listed[1])
        self.assertTrue(listed[2].startswith("- **2** ℹ️ **wear.button.emphasis**"), listed[2])

    def test_an_overlay_from_an_older_cli_gets_no_numbers(self) -> None:
        # An older CLI's overlay has rule-id labels and no format entry: shown, but not numbered.
        self._renders(nodes=[{"ref": "stop", "boundsInScreen": "0,0,10,10"}], overlay=PNG)
        body = gr.build(self.tmp, _args(image_repo="org/repo", image_ref="0123abc"))
        assert body is not None
        self.assertIn("Stop-1.guidelines.png", body)
        self.assertIn("1 finding location(s) marked", body)
        self.assertIn("- ⚠️ **wear.touch-target-48dp** on `stop`", body)

    def test_no_numbers_without_the_picture_they_refer_to(self) -> None:
        # Marks exist, but no image location was given, so no picture is embedded.
        self._renders(nodes=[{"ref": "stop", "boundsInScreen": "0,0,10,10"}])
        body = gr.build(self.tmp, _args())
        assert body is not None
        self.assertNotIn("<img", body)
        self.assertIn("- ⚠️ **wear.touch-target-48dp** on `stop`", body)

    def test_a_region_marks_the_render_without_nodes(self) -> None:
        self._renders()
        report = json.loads((self.tmp / "catalog" / "guidelines.json").read_text())
        verdict = report["results"][0]["record"]["verdicts"][0]
        verdict["nodeIds"] = []
        verdict["regions"] = [
            {"subjectId": "x.StopKt.Stop", "x": 0.1, "y": 0.1, "width": 0.2, "height": 0.2},
            {"subjectId": "x.OkKt.Ok", "x": 0.1, "y": 0.1, "width": 0.2, "height": 0.2},
        ]
        (self.tmp / "catalog" / "guidelines.json").write_text(json.dumps(report))
        body = gr.build(self.tmp, _args(image_repo="org/repo", image_ref="0123abc"))
        assert body is not None
        self.assertIn("Stop-1.guidelines.png", body)
        self.assertIn("1 finding location(s) marked", body)

    def test_stage_images_copies_exactly_what_the_comment_embeds(self) -> None:
        self._renders(nodes=[{"ref": "stop", "boundsInScreen": "0,0,10,10"}])
        (self.tmp / "catalog" / "renders" / "Unrelated.png").write_bytes(PNG)
        stage = Path(tempfile.mkdtemp())
        gr.build(self.tmp, _args(stage_images=str(stage)))
        staged = sorted(p.relative_to(stage).as_posix() for p in stage.rglob("*") if p.is_file())
        self.assertEqual(staged, ["guidelines/catalog/Stop-1.guidelines.png"])

    def test_a_file_that_is_not_a_png_is_never_embedded(self) -> None:
        self._renders(annotated=False)
        (self.tmp / "catalog" / "renders" / "Stop-1.png").write_text("<html>")
        body = gr.build(self.tmp, _args(image_repo="org/repo", image_ref="0123abc"))
        assert body is not None
        self.assertNotIn("<img", body)

    def test_a_preview_under_two_module_directories_is_reported_once(self) -> None:
        twin = self.tmp / "catalog-desktop"
        twin.mkdir()
        for name in ("ui-builder.guidelines.json", "previews.json", "guidelines.json"):
            (twin / name).write_text((self.tmp / "catalog" / name).read_text())
        body = gr.build(self.tmp, _args())
        assert body is not None
        self.assertEqual(body.count("#### `Stop`"), 1)
        self.assertIn("2 changed preview(s) checked", body)
        self.assertIn("**1 finding(s)**", body)

    def test_no_results_means_no_comment(self) -> None:
        empty = Path(tempfile.mkdtemp())
        self.assertIsNone(gr.build(empty, _args()))

    def _write(self, report: dict) -> None:
        (self.tmp / "catalog" / "guidelines.json").write_text(json.dumps(report))

    def test_a_failed_request_is_not_checked_rather_than_a_pass(self) -> None:
        # remote-m3-catalog#72: every request failed, so every result came back pending and asked
        # nothing; the comment used to read "0 finding(s) ... No findings".
        pending = _result("x.WidgetKt.Widget", [])
        pending["pending"] = True
        self._write({
            "module": "handoff", "catalog": "remote-m3", "model": "m", "results": [pending],
            "requests": 1, "failedRequests": 1,
            "problems": ["unreadable reply: the reply held no verdicts"],
        })
        body = gr.build(self.tmp, _args())
        assert body is not None
        self.assertIn("**Not checked.**", body)
        self.assertIn("this is not a pass", body)
        self.assertIn("1 of this PR's changed preview(s) were NOT checked", body)
        self.assertIn("1 request(s) failed", body)
        self.assertIn("`Widget`", body)
        self.assertIn("unreadable reply: the reply held no verdicts", body)
        self.assertNotIn("No findings", body)
        self.assertNotIn("✅", body)

    def test_a_pending_result_from_an_older_cli_is_not_checked(self) -> None:
        # An older CLI records no run status; the pending results alone must say it.
        pending = _result("x.WidgetKt.Widget", [])
        pending["pending"] = True
        self._write({"module": "handoff", "catalog": "remote-m3", "model": "m",
                     "results": [pending]})
        body = gr.build(self.tmp, _args())
        assert body is not None
        self.assertIn("**Not checked.**", body)
        self.assertNotIn("No findings", body)

    def test_no_rule_applying_is_reported_with_its_reason(self) -> None:
        skipped = _result("x.ButtonKt.Button", [])
        skipped["noRules"] = ("no rule in the `remote-m3` guidelines applies to surface "
                              "`component` with no profile")
        self._write({"module": "handoff", "catalog": "remote-m3", "model": "m",
                     "results": [skipped], "requests": 0, "failedRequests": 0,
                     "problems": ["1 preview(s) were not checked: " + skipped["noRules"]]})
        body = gr.build(self.tmp, _args())
        assert body is not None
        self.assertIn("**Not checked.**", body)
        self.assertIn("applies to surface 'component' with no profile", body)
        self.assertNotIn("No findings", body)

    def test_a_partly_failed_run_says_which_previews_were_not_checked(self) -> None:
        ok = _result("x.OkKt.Ok", [{"ruleId": "wear.touch-target-48dp", "verdict": "pass",
                                    "confidence": 0.9, "nodeIds": [], "reason": ""}])
        failed = _result("x.StopKt.Stop", [])
        failed["pending"] = True
        self._write({"module": "handoff", "catalog": "wear-m3", "model": "m",
                     "results": [ok, failed], "requests": 2, "failedRequests": 1,
                     "problems": ["the model answered 429: busy"]})
        body = gr.build(self.tmp, _args())
        assert body is not None
        self.assertIn("1 changed preview(s) checked", body)
        # The PR's own unchecked previews lead the comment, ahead of what was checked.
        self.assertTrue(body.index("❌ **1 of this PR's 2 changed preview(s) were NOT checked**")
                        < body.index("1 changed preview(s) checked"))
        self.assertIn("No findings in the 1 preview(s) that were checked.", body)
        self.assertNotIn("✅", body)

    def test_a_module_the_check_wrote_nothing_for_is_not_checked(self) -> None:
        (self.tmp / "catalog" / "guidelines.json").unlink()
        body = gr.build(self.tmp, _args())
        assert body is not None
        self.assertIn("**Not checked.**", body)
        self.assertIn("the check wrote no results", body)
        self.assertIn("`Stop`", body)

    def _rules_tier(self, results: list[dict], cost: float | None = None) -> Path:
        module = self.tmp / "catalog.rules-changed"
        module.mkdir()
        (module / "ui-builder.guidelines.json").write_text(json.dumps(RULES))
        (module / "previews.json").write_text(json.dumps({
            "guidelinesSelection": "rules-changed",
            "previews": [{"id": r["previewId"], "captures": []} for r in results]}))
        report = {"module": "handoff", "catalog": "wear-m3", "model": "m", "results": results}
        if cost is not None:
            report["costUsd"] = cost
        (module / "guidelines.json").write_text(json.dumps(report))
        return module

    def test_the_prs_own_unchecked_previews_lead_and_the_rules_tier_is_apart(self) -> None:
        # wear-m3-catalog#760: a rules change rebased in selected every preview, the budget ran
        # out on those, and the planted WearList previews were never judged.
        mine = _result("x.WearListKt.WearList", [])
        mine["pending"] = True
        self._write({"module": "handoff", "catalog": "wear-m3", "model": "m",
                     "results": [mine], "requests": 0, "failedRequests": 0,
                     "problems": ["the cost cap ($0.2500) was reached; 1 previews were not checked"]})
        other = _result("x.CardKt.Card", [{"ruleId": "wear.touch-target-48dp", "verdict": "pass",
                                          "confidence": 0.9, "nodeIds": [], "reason": ""}])
        late = _result("x.ChipKt.Chip", [])
        late["pending"] = True
        self._rules_tier([other, late])
        (self.tmp / "_rules_only.json").write_text(json.dumps({"staged": 2, "deferred": 900}))
        body = gr.build(self.tmp, _args())
        assert body is not None
        lead = body.index("❌ **1 of this PR's 1 changed preview(s) were NOT checked**")
        self.assertLess(lead, body.index("1 preview(s) checked against"))
        self.assertIn("`WearList`", body.split("1 preview(s) checked against", 1)[0])
        self.assertIn("(0 changed by this PR, 1 because the guidelines changed)", body)
        self.assertIn("1 preview(s) selected only because the guidelines changed were NOT "
                      "checked", body)
        self.assertIn("900 more were left for the catalog publish", body)
        self.assertIn("2 were staged here behind the PR's own (1 of them checked)", body)

    def test_previews_over_the_limit_are_listed_as_not_checked(self) -> None:
        over = [{"id": f"x.ListKt.WearList_{dp}dp", "module": "catalog", "function": "WearList"}
                for dp in (192, 204, 216, 225, 240, 250, 260, 270, 280)]
        (self.tmp / "_over_limit.json").write_text(json.dumps({"limit": 999, "previews": over}))
        args = _args()
        args.max_previews = 2
        body = gr.build(self.tmp, args)
        assert body is not None
        # The limit quoted is the caller's own input, not the handoff's.
        self.assertIn("**9 more changed preview(s) were NOT checked: over this PR's limit of 2**",
                      body)
        self.assertIn("`WearList_192dp`", body)
        self.assertIn("Every preview over the limit", body)
        self.assertIn("`WearList_280dp`", body.split("Every preview over the limit", 1)[1])
        self.assertNotIn("No findings. ✅", body)

    def test_over_the_limit_alone_still_comments(self) -> None:
        (self.tmp / "catalog" / "guidelines.json").unlink()
        (self.tmp / "catalog" / "previews.json").unlink()
        (self.tmp / "_over_limit.json").write_text(json.dumps(
            {"limit": 1, "previews": [{"id": "x.AKt.A`b"}]}))
        body = gr.build(self.tmp, _args())
        assert body is not None
        self.assertIn("over this PR's limit of 1", body)
        # A fork-supplied id cannot close the code span.
        self.assertIn("`A'b`", body)

    def test_a_rules_change_says_main_rechecks_the_rest(self) -> None:
        (self.tmp / "_rules_changed.json").write_text(
            json.dumps({"modules": ["catalog"], "sweep": False}))
        body = gr.build(self.tmp, _args())
        assert body is not None
        self.assertIn("This PR changes the design guidelines.", body)
        self.assertIn("re-checks every other preview against the new rules", body)
        (self.tmp / "_rules_changed.json").write_text(
            json.dumps({"modules": ["catalog"], "sweep": True}))
        self.assertNotIn("This PR changes the design guidelines.", gr.build(self.tmp, _args()))

    def test_results_from_the_catalog_cache_are_counted(self) -> None:
        report = json.loads((self.tmp / "catalog" / "guidelines.json").read_text())
        report["results"][1]["fromCache"] = True
        (self.tmp / "catalog" / "guidelines.json").write_text(json.dumps(report))
        body = gr.build(self.tmp, _args())
        assert body is not None
        self.assertIn("(1 answered from the catalog publish's results at no cost)", body)

    def test_failed_requests_are_reported_with_the_tier_they_hit(self) -> None:
        ok = _result("x.OkKt.Ok", [{"ruleId": "wear.touch-target-48dp", "verdict": "pass",
                                    "confidence": 0.9, "nodeIds": [], "reason": ""}])
        mine = _result("x.WearListKt.WearList", [])
        mine["pending"] = True
        self._write({"module": "handoff", "catalog": "wear-m3", "model": "m",
                     "results": [ok, mine], "failedRequests": 1})
        late = _result("x.ChipKt.Chip", [])
        late["pending"] = True
        module = self._rules_tier([late])
        report = json.loads((module / "guidelines.json").read_text())
        report["failedRequests"] = 3
        (module / "guidelines.json").write_text(json.dumps(report))
        body = gr.build(self.tmp, _args())
        assert body is not None
        mine_part, rules_part = body.split("selected only because the guidelines changed", 1)
        self.assertIn("(1 request(s) failed): `WearList`", mine_part)
        self.assertIn("(3 request(s) failed): `Chip`", rules_part)

    def test_the_run_level_cost_counts_replies_that_could_not_be_used(self) -> None:
        self._rules_tier([_result("x.CardKt.Card", [])], cost=0.05)
        body = gr.build(self.tmp, _args())
        assert body is not None
        # 0.0034 × 2 in the PR's module's records, and the rules tier's run total of 0.05.
        self.assertIn("$0.0568", body)

    def test_a_problem_line_cannot_break_out_of_its_block(self) -> None:
        pending = _result("x.WidgetKt.Widget", [])
        pending["pending"] = True
        self._write({"module": "handoff", "catalog": "wear-m3", "model": "m",
                     "results": [pending], "failedRequests": 1,
                     "problems": ["unreadable reply: ```\n@someone <img src=x>" + "y" * 900]})
        body = gr.build(self.tmp, _args())
        assert body is not None
        block = body.split("```text\n", 1)[1].split("\n```", 1)[0]
        self.assertNotIn("`", block)
        self.assertNotIn("\n", block)
        self.assertLessEqual(len(block), gr.MAX_PROBLEM_CHARS + 1)


class StageTest(unittest.TestCase):
    def test_stages_changed_previews_with_renders_source_nodes_and_rules(self) -> None:
        root = Path(tempfile.mkdtemp())
        module = root / "catalog"
        previews = module / "build" / "compose-previews"
        (previews / "renders").mkdir(parents=True)
        (previews / "renders" / "Stop-1.png").write_bytes(b"png")
        (previews / "renders" / "Other-2.png").write_bytes(b"png")
        (previews / "renders" / "Stop-1_SCROLL_long.png").write_bytes(b"png")
        (module / "src").mkdir()
        (module / "src" / "Stop.kt").write_text("fun Stop() {}\n")
        (previews / "ui-builder.guidelines.json").write_text(json.dumps(RULES))
        (previews / "previews.json").write_text(json.dumps({"module": "catalog", "previews": [
            {"id": "x.Stop", "sourceFile": "src/Stop.kt", "bodyLine": 1,
             "captures": [{"renderOutput": "renders/Stop-1.png"}]},
            {"id": "x.Other", "sourceFile": "../../etc/passwd", "bodyLine": 1,
             "captures": [{"renderOutput": "renders/Other-2.png"}]},
        ]}))
        (previews / "accessibility.json").write_text(json.dumps({"module": "catalog", "entries": [
            {"previewId": "x.Stop", "findings": [], "nodes": []},
            {"previewId": "x.Other", "findings": [], "nodes": []},
        ]}))
        out = root / "_guidelines"

        staged = gs.stage(root, {"x.Stop"}, out, None)

        self.assertEqual(staged, 1)
        target = out / "catalog"
        manifest = json.loads((target / "previews.json").read_text())
        self.assertEqual([p["id"] for p in manifest["previews"]], ["x.Stop"])
        self.assertEqual(manifest["previews"][0]["captures"][0]["renderOutput"], "renders/Stop-1.png")
        self.assertTrue((target / "renders" / "Stop-1.png").is_file())
        self.assertTrue((target / "renders" / "Stop-1_SCROLL_long.png").is_file())
        self.assertTrue((target / "src" / "src" / "Stop.kt").is_file())
        self.assertTrue((target / "ui-builder.guidelines.json").is_file())
        nodes = json.loads((target / "accessibility.json").read_text())
        self.assertEqual([e["previewId"] for e in nodes["entries"]], ["x.Stop"])

    def _two_previews(self) -> Path:
        root = Path(tempfile.mkdtemp())
        module = root / "catalog"
        previews = module / "build" / "compose-previews"
        (previews / "renders").mkdir(parents=True)
        for name in ("Stop-1.png", "Go-2.png"):
            (previews / "renders" / name).write_bytes(b"png")
        (module / "src").mkdir()
        (module / "src" / "Stop.kt").write_text("fun Stop() {}\n")
        (module / "src" / "Go.kt").write_text("fun Go() {}\n")
        (previews / "ui-builder.guidelines.json").write_text(json.dumps(RULES))
        (previews / "previews.json").write_text(json.dumps({"module": "catalog", "previews": [
            {"id": "x.Stop", "sourceFile": "src/Stop.kt", "bodyLine": 1,
             "captures": [{"renderOutput": "renders/Stop-1.png"}]},
            {"id": "x.Go", "sourceFile": "src/Go.kt", "bodyLine": 1,
             "captures": [{"renderOutput": "renders/Go-2.png"}]},
        ]}))
        return root

    def test_the_long_scroll_data_product_is_staged_beside_the_render(self) -> None:
        # Where the renderer writes it: `data/render-scroll-long/`, named by the data product.
        root = self._two_previews()
        previews = root / "catalog" / "build" / "compose-previews"
        (previews / "data" / "render-scroll-long").mkdir(parents=True)
        (previews / "data" / "render-scroll-long" / "Stop-1_SCROLL_long.png").write_bytes(b"long")
        (previews / "data" / "render-scroll-long" / "Wide-3_SCROLL_long.png").write_bytes(b"wide")
        manifest = json.loads((previews / "previews.json").read_text())
        manifest["previews"][0]["dataProducts"] = [
            {"kind": "render/scroll/long",
             "output": "data/render-scroll-long/Stop-1_SCROLL_long.png"}]
        manifest["previews"].append(
            {"id": "x.Wide", "dataProducts": [
                {"kind": "render/scroll/long",
                 "output": "data/render-scroll-long/Wide-3_SCROLL_long.png"}], "captures": []})
        (previews / "previews.json").write_text(json.dumps(manifest))
        out = root / "_guidelines"
        self.assertEqual(gs.stage(root, {"x.Stop", "x.Wide"}, out, None), 2)
        renders = out / "catalog" / "renders"
        self.assertEqual((renders / "Stop-1_SCROLL_long.png").read_bytes(), b"long")
        staged = json.loads((out / "catalog" / "previews.json").read_text())
        wide = next(p for p in staged["previews"] if p["id"] == "x.Wide")
        self.assertEqual(wide["captures"], [
            {"renderOutput": "renders/Wide-3_SCROLL_long.png", "scroll": {"mode": "LONG"}}])

    def test_a_long_data_product_escaping_the_build_dir_is_ignored(self) -> None:
        root = self._two_previews()
        previews = root / "catalog" / "build" / "compose-previews"
        manifest = json.loads((previews / "previews.json").read_text())
        manifest["previews"][0]["dataProducts"] = [
            {"kind": "render/scroll/long", "output": "../../src/Stop.kt"}]
        (previews / "previews.json").write_text(json.dumps(manifest))
        out = root / "_guidelines"
        gs.stage(root, {"x.Stop"}, out, None)
        self.assertFalse((out / "catalog" / "renders" / "Stop-1_SCROLL_long.png").exists())

    def test_a_preview_two_modules_discover_is_staged_once_where_its_source_is(self) -> None:
        # PR #760 in wear-m3-catalog: `:catalog-desktop` re-renders `:catalog`'s previews, with a
        # `sourceFile` reaching into `../catalog/`, so it was checked twice — once without source.
        root = self._two_previews()
        desktop = root / "catalog-desktop" / "build" / "compose-previews"
        (desktop / "renders").mkdir(parents=True)
        (desktop / "renders" / "Stop-1.png").write_bytes(b"png")
        (desktop / "ui-builder.guidelines.json").write_text(json.dumps(RULES))
        (desktop / "accessibility.json").write_text(json.dumps({"entries": []}))
        (desktop / "previews.json").write_text(json.dumps({"module": "catalog-desktop", "previews": [
            {"id": "x.Stop", "sourceFile": "../catalog/src/Stop.kt", "bodyLine": 1,
             "captures": [{"renderOutput": "renders/Stop-1.png"}]},
        ]}))
        out = root / "_guidelines"
        staged = gs.stage(root, {"x.Stop"}, out, None)
        self.assertEqual(staged, 1)
        self.assertTrue((out / "catalog" / "src" / "src" / "Stop.kt").is_file())
        self.assertFalse((out / "catalog-desktop").exists())

    def test_a_source_only_edit_is_staged_though_the_render_did_not_change(self) -> None:
        root = self._two_previews()
        out = root / "_guidelines"
        staged = gs.stage(root, set(), out, None, {"catalog/src/Go.kt"})
        self.assertEqual(staged, 1)
        manifest = json.loads((out / "catalog" / "previews.json").read_text())
        self.assertEqual([p["id"] for p in manifest["previews"]], ["x.Go"])

    def test_changed_rules_stage_every_preview_of_the_module(self) -> None:
        root = self._two_previews()
        out = root / "_guidelines"
        staged = gs.stage(root, set(), out, None, {"catalog/ui-builder.guidelines.json"})
        self.assertEqual(staged, 2)
        manifest = json.loads((out / "catalog.rules-changed" / "previews.json").read_text())
        self.assertEqual(manifest["guidelinesSelection"], "rules-changed")
        self.assertFalse((out / "catalog").exists())

    def test_previews_the_rules_change_selected_are_a_bounded_second_tier(self) -> None:
        root = self._two_previews()
        previews = root / "catalog" / "build" / "compose-previews"
        manifest = json.loads((previews / "previews.json").read_text())
        for name in ("A", "B", "C"):
            (previews / "renders" / f"{name}.png").write_bytes(b"png")
            manifest["previews"].append(
                {"id": f"x.{name}", "captures": [{"renderOutput": f"renders/{name}.png"}]})
        (previews / "previews.json").write_text(json.dumps(manifest))
        out = root / "_guidelines"

        staged = gs.stage(root, {"x.Go"}, out, None,
                          {"catalog/ui-builder.guidelines.json"}, max_rules_only=2)

        self.assertEqual(staged, 3)
        mine = json.loads((out / "catalog" / "previews.json").read_text())
        self.assertEqual([p["id"] for p in mine["previews"]], ["x.Go"])
        self.assertNotIn("guidelinesSelection", mine)
        rules = json.loads((out / "catalog.rules-changed" / "previews.json").read_text())
        self.assertEqual([p["id"] for p in rules["previews"]], ["x.Stop", "x.A"])
        self.assertEqual(json.loads((out / "_rules_only.json").read_text()),
                         {"staged": 2, "deferred": 2})
        # The publish job checks the PR's previews before the rules tier, whatever the names.
        self.assertEqual([p.name for p in gb.order(out)], ["catalog", "catalog.rules-changed"])

    def test_a_rules_tier_never_lands_on_a_real_module_named_like_one(self) -> None:
        root = self._two_previews()
        other = root / "catalog.rules-changed" / "build" / "compose-previews"
        (other / "renders").mkdir(parents=True)
        (other / "renders" / "Real-1.png").write_bytes(b"png")
        (other / "ui-builder.guidelines.json").write_text(json.dumps(RULES))
        (other / "previews.json").write_text(json.dumps({"previews": [
            {"id": "y.Real", "captures": [{"renderOutput": "renders/Real-1.png"}]}]}))
        out = root / "_guidelines"
        gs.stage(root, {"y.Real"}, out, None, {"catalog/ui-builder.guidelines.json"})
        real = json.loads((out / "catalog.rules-changed" / "previews.json").read_text())
        self.assertEqual([p["id"] for p in real["previews"]], ["y.Real"])
        self.assertNotIn("guidelinesSelection", real)
        tier = json.loads((out / "catalog.rules-changed-2" / "previews.json").read_text())
        self.assertEqual(tier["guidelinesSelection"], "rules-changed")
        self.assertEqual(sorted(p["id"] for p in tier["previews"]), ["x.Go", "x.Stop"])

    def _fanout(self, ids: list[tuple[str, str]]) -> Path:
        """One module whose previews are (id, functionName) pairs, each with a render."""
        root = Path(tempfile.mkdtemp())
        previews = root / "catalog" / "build" / "compose-previews"
        (previews / "renders").mkdir(parents=True)
        (previews / "ui-builder.guidelines.json").write_text(json.dumps(RULES))
        entries = []
        for preview_id, function in ids:
            (previews / "renders" / f"{preview_id}.png").write_bytes(b"png")
            entries.append({"id": preview_id, "functionName": function, "className": "x.K",
                            "captures": [{"renderOutput": f"renders/{preview_id}.png"}]})
        (previews / "previews.json").write_text(json.dumps({"previews": entries}))
        return root

    def test_a_rules_change_alone_stages_nothing_by_default(self) -> None:
        root = self._two_previews()
        out = root / "_guidelines"
        staged = gs.stage(root, set(), out, None, {"catalog/ui-builder.guidelines.json"},
                          rules_sweep=False)
        self.assertEqual(staged, 0)
        self.assertFalse((out / "catalog.rules-changed").exists())
        self.assertEqual(json.loads((out / "_rules_changed.json").read_text()),
                         {"modules": ["catalog"], "sweep": False})

    def test_a_rules_change_does_not_pull_in_other_previews_by_default(self) -> None:
        root = self._two_previews()
        out = root / "_guidelines"
        staged = gs.stage(root, {"x.Go"}, out, None, {"catalog/ui-builder.guidelines.json"},
                          rules_sweep=False)
        self.assertEqual(staged, 1)
        mine = json.loads((out / "catalog" / "previews.json").read_text())
        self.assertEqual([p["id"] for p in mine["previews"]], ["x.Go"])
        self.assertFalse((out / "_rules_only.json").exists())

    def test_the_cli_does_not_sweep_unless_asked(self) -> None:
        import subprocess
        import sys
        root = self._two_previews()
        changed = root / "_changed.json"
        changed.write_text("[]")
        files = root / "_files.txt"
        files.write_text("catalog/ui-builder.guidelines.json\n")
        script = str(_HERE / "guidelines-stage.py")
        base = [sys.executable, script, "--changed", str(changed), "--root", str(root),
                "--changed-files", str(files)]
        quiet = subprocess.run(base + ["--out", str(root / "a")], capture_output=True, text=True)
        self.assertEqual(quiet.stdout.strip(), "0")
        swept = subprocess.run(base + ["--out", str(root / "b"), "--rules-sweep"],
                               capture_output=True, text=True)
        self.assertEqual(swept.stdout.strip(), "2")

    def test_the_cap_takes_the_biggest_changes_and_lists_the_rest(self) -> None:
        root = self._fanout([("x.A", "A"), ("x.B", "B"), ("x.C", "C"), ("x.D", "D")])
        out = root / "_guidelines"
        changed = {
            "x.A": {"diff": 0.01}, "x.B": {"diff": 0.4}, "x.C": {"new": True, "diff": 1.0},
            "x.D": {"diff": None},
        }
        staged = gs.stage(root, changed, out, None, max_previews=2)
        self.assertEqual(staged, 2)
        manifest = json.loads((out / "catalog" / "previews.json").read_text())
        # A new preview and an unmeasured diff count as whole changes, ahead of the partial ones.
        self.assertEqual([p["id"] for p in manifest["previews"]], ["x.C", "x.D"])
        over = json.loads((out / "_over_limit.json").read_text())
        self.assertEqual(over["limit"], 2)
        self.assertEqual([p["id"] for p in over["previews"]], ["x.B", "x.A"])
        self.assertEqual(over["previews"][0]["module"], "catalog")

    def test_a_changed_render_ranks_before_a_source_only_selection(self) -> None:
        root = self._two_previews()
        out = root / "_guidelines"
        staged = gs.stage(root, {"x.Go": {"diff": 0.001}}, out, None, {"catalog/src/Stop.kt"},
                          max_previews=1)
        self.assertEqual(staged, 1)
        manifest = json.loads((out / "catalog" / "previews.json").read_text())
        self.assertEqual([p["id"] for p in manifest["previews"]], ["x.Go"])
        over = json.loads((out / "_over_limit.json").read_text())
        self.assertEqual([p["id"] for p in over["previews"]], ["x.Stop"])

    def test_one_render_of_each_function_comes_before_its_fan_out(self) -> None:
        sizes = [(f"x.WearList_{dp}dp", "WearList") for dp in (192, 204, 216, 225, 240)]
        root = self._fanout(sizes + [("x.Card", "Card"),
                                     ("x.Card_VARIANT_disabled", "Card"), ("x.Chip", "Chip")])
        out = root / "_guidelines"
        changed = {preview_id: {"diff": 0.5} for preview_id, _ in sizes}
        changed.update({"x.Card": {"diff": 0.1}, "x.Card_VARIANT_disabled": {"diff": 0.2},
                        "x.Chip": {"diff": 0.05}})
        staged = gs.stage(root, changed, out, None, max_previews=4)
        self.assertEqual(staged, 4)
        manifest = json.loads((out / "catalog" / "previews.json").read_text())
        self.assertEqual([p["id"] for p in manifest["previews"]],
                         ["x.WearList_192dp", "x.Card_VARIANT_disabled", "x.Chip",
                          "x.WearList_204dp"])
        over = json.loads((out / "_over_limit.json").read_text())
        self.assertEqual(len(over["previews"]), 4)

    def test_the_cap_bounds_the_rules_tier_too(self) -> None:
        root = self._two_previews()
        out = root / "_guidelines"
        staged = gs.stage(root, {"x.Go"}, out, None, {"catalog/ui-builder.guidelines.json"},
                          max_previews=1, rules_sweep=True)
        self.assertEqual(staged, 1)
        self.assertFalse((out / "catalog.rules-changed").exists())
        self.assertEqual(json.loads((out / "_rules_only.json").read_text()),
                         {"staged": 0, "deferred": 1})

    def test_unrelated_changes_stage_nothing(self) -> None:
        root = self._two_previews()
        self.assertEqual(gs.stage(root, set(), root / "_g", None, {"README.md"}), 0)

    def test_a_module_without_guidelines_stages_nothing(self) -> None:
        root = Path(tempfile.mkdtemp())
        previews = root / "m" / "build" / "compose-previews"
        previews.mkdir(parents=True)
        (previews / "previews.json").write_text(json.dumps({"previews": [{"id": "a"}]}))
        self.assertEqual(gs.stage(root, {"a"}, root / "_g", None), 0)

    def test_a_wrapper_called_from_another_file_is_staged_with_the_preview(self) -> None:
        root = self._two_previews()
        module = root / "catalog"
        (module / "src" / "Go.kt").write_text("fun Go() = WearScreen { Text(\"go\") }\n")
        (module / "src" / "Frame.kt").write_text("@Composable\nfun WearScreen(content: () -> Unit) {}\n")
        (module / "src" / "Unrelated.kt").write_text("fun Other() {}\n")
        out = root / "_guidelines"
        gs.stage(root, {"x.Go"}, out, None)
        staged = sorted(p.name for p in (out / "catalog" / "src").rglob("*.kt"))
        self.assertEqual(staged, ["Frame.kt", "Go.kt"])

    def test_a_module_at_the_scan_root_gets_its_own_directory(self) -> None:
        root = Path(tempfile.mkdtemp())
        self.assertEqual(gs.module_key(root, root), "root")
        self.assertEqual(gs.module_key(root / "samples" / "wear", root), "samples_wear")

    def test_safe_relative_refuses_escapes(self) -> None:
        self.assertFalse(gs.safe_relative("../x"))
        self.assertFalse(gs.safe_relative("/etc/passwd"))
        self.assertTrue(gs.safe_relative("src/main/A.kt"))


class BudgetTest(unittest.TestCase):
    """`guidelines-max-cost` is one budget across every staged module, not one per directory."""

    def _module(self, root: Path, name: str, costs: list[object]) -> None:
        module = root / name
        module.mkdir(parents=True)
        results = [{"previewId": f"p{i}", "record": {"costUsd": c}} for i, c in enumerate(costs)]
        (module / "guidelines.json").write_text(json.dumps({"results": results}))

    def test_spend_is_summed_across_modules(self) -> None:
        root = Path(tempfile.mkdtemp())
        self._module(root, "a", [0.05, 0.05])
        self._module(root, "b", [0.1])
        self.assertAlmostEqual(gb.remaining(root, 0.25), 0.05)

    def test_never_below_zero(self) -> None:
        root = Path(tempfile.mkdtemp())
        self._module(root, "a", [0.3])
        self.assertEqual(gb.remaining(root, 0.25), 0.0)

    def test_no_file_can_raise_the_budget(self) -> None:
        root = Path(tempfile.mkdtemp())
        self._module(root, "a", [-5, "nan", "inf", None, "x", 0.01])
        (root / "b").mkdir()
        (root / "b" / "guidelines.json").write_text("not json")
        (root / "c").mkdir()
        (root / "c" / "guidelines.json").write_text(json.dumps({"results": {"x": 1}}))
        self.assertAlmostEqual(gb.remaining(root, 0.25), 0.24)

    def test_the_run_total_counts_replies_no_result_carries(self) -> None:
        root = Path(tempfile.mkdtemp())
        self._module(root, "a", [0.01])
        report = json.loads((root / "a" / "guidelines.json").read_text())
        report["costUsd"] = 0.2
        (root / "a" / "guidelines.json").write_text(json.dumps(report))
        self.assertAlmostEqual(gb.remaining(root, 0.25), 0.05)
        # Never below what the records add up to, whatever the file says its total was.
        report["costUsd"] = -1
        (root / "a" / "guidelines.json").write_text(json.dumps(report))
        self.assertAlmostEqual(gb.remaining(root, 0.25), 0.24)

    def test_the_prs_own_modules_come_before_the_rules_tier(self) -> None:
        root = Path(tempfile.mkdtemp())
        for name, selection in (("a.rules-changed", "rules-changed"), ("b", None),
                                ("z", "something-else")):
            module = root / name
            module.mkdir(parents=True, exist_ok=True)
            manifest = {"previews": []}
            if selection:
                manifest["guidelinesSelection"] = selection
            (module / "previews.json").write_text(json.dumps(manifest))
        self.assertEqual([p.name for p in gb.order(root)], ["b", "z", "a.rules-changed"])

    def _staged(self, root: Path, name: str, ids: list[str], selection: str | None = None) -> None:
        module = root / name
        module.mkdir(parents=True, exist_ok=True)
        manifest: dict = {"previews": [{"id": i, "functionName": i} for i in ids]}
        if selection:
            manifest["guidelinesSelection"] = selection
        (module / "previews.json").write_text(json.dumps(manifest))

    def test_trim_enforces_the_limit_in_check_order(self) -> None:
        root = Path(tempfile.mkdtemp())
        self._staged(root, "a.rules-changed", ["r1"], "rules-changed")
        self._staged(root, "b", ["b1", "b2"])
        self._staged(root, "c", ["c1", "c2"])
        # A fork's own list survives, under the trusted limit.
        (root / "_over_limit.json").write_text(json.dumps(
            {"limit": 500, "previews": [{"id": "z1"}]}))
        self.assertEqual(gb.trim(root, 3), 2)
        self.assertEqual([p["id"] for p in json.loads((root / "b" / "previews.json").read_text())
                          ["previews"]], ["b1", "b2"])
        self.assertEqual([p["id"] for p in json.loads((root / "c" / "previews.json").read_text())
                          ["previews"]], ["c1"])
        self.assertFalse((root / "a.rules-changed" / "previews.json").exists())
        over = json.loads((root / "_over_limit.json").read_text())
        self.assertEqual(over["limit"], 3)
        self.assertEqual([p["id"] for p in over["previews"]], ["z1", "c2", "r1"])

    def test_seed_copies_the_catalog_cache_into_every_staged_module(self) -> None:
        root = Path(tempfile.mkdtemp())
        key = "ab" + "0" * 62
        restored = root / "catalog" / "build" / "compose-previews" / "guidelines"
        (restored / "ab").mkdir(parents=True)
        (restored / "ab" / f"{key}.json").write_text("{}")
        (restored / "ab" / "notes.txt").write_text("x")
        (restored / "checked").mkdir()
        (restored / "checked" / ("c" * 64)).write_text("")
        handoff = root / "_guidelines"
        self._staged(handoff, "catalog", ["a"])
        self._staged(handoff, "other", ["b"])
        # A result the handoff carried in is discarded, not served.
        forged = handoff / "catalog" / "guidelines" / "cd"
        forged.mkdir(parents=True)
        (forged / ("cd" + "1" * 62 + ".json")).write_text("{}")
        # The handoff's own tree is never a source.
        inner = handoff / "x" / "build" / "compose-previews" / "guidelines" / "ef"
        inner.mkdir(parents=True)
        (inner / ("ef" + "2" * 62 + ".json")).write_text("{}")

        # Nor any other handoff entry (all are top-level `_…` names), nor a linked directory.
        planted = root / "_pr_renders" / "m" / "build" / "compose-previews" / "guidelines" / "aa"
        planted.mkdir(parents=True)
        (planted / ("aa" + "3" * 62 + ".json")).write_text("{}")
        linked = root / "linked" / "build" / "compose-previews"
        linked.mkdir(parents=True)
        (linked / "guidelines").symlink_to(planted.parent)

        self.assertEqual(gb.seed(handoff, root), 2)

        for module in ("catalog", "other"):
            files = sorted(p.relative_to(handoff / module / "guidelines").as_posix()
                           for p in (handoff / module / "guidelines").rglob("*") if p.is_file())
            self.assertEqual(files, [f"ab/{key}.json"])

    def test_trim_zero_cuts_nothing(self) -> None:
        root = Path(tempfile.mkdtemp())
        self._staged(root, "b", ["b1", "b2"])
        self.assertEqual(gb.trim(root, 0), 0)
        self.assertFalse((root / "_over_limit.json").exists())

    def test_nothing_spent_yet(self) -> None:
        root = Path(tempfile.mkdtemp())
        self.assertEqual(gb.remaining(root / "missing", 0.25), 0.25)


if __name__ == "__main__":
    unittest.main()
