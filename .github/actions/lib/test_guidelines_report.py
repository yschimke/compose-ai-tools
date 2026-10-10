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

    def test_nothing_spent_yet(self) -> None:
        root = Path(tempfile.mkdtemp())
        self.assertEqual(gb.remaining(root / "missing", 0.25), 0.25)


if __name__ == "__main__":
    unittest.main()
