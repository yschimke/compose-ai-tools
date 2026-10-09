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
        image_prefix=kw.get("image_prefix", "guidelines"),
    )


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

    def test_images_only_from_a_pinned_github_location(self) -> None:
        body = gr.build(self.tmp, _args(image_repo="org/repo", image_ref="0123abc"))
        assert body is not None
        self.assertIn(
            "https://raw.githubusercontent.com/org/repo/0123abc/guidelines/catalog/"
            "Stop-1.guidelines.png",
            body,
        )

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
        self.assertTrue((target / "src" / "src" / "Stop.kt").is_file())
        self.assertTrue((target / "ui-builder.guidelines.json").is_file())
        nodes = json.loads((target / "accessibility.json").read_text())
        self.assertEqual([e["previewId"] for e in nodes["entries"]], ["x.Stop"])

    def test_a_module_without_guidelines_stages_nothing(self) -> None:
        root = Path(tempfile.mkdtemp())
        previews = root / "m" / "build" / "compose-previews"
        previews.mkdir(parents=True)
        (previews / "previews.json").write_text(json.dumps({"previews": [{"id": "a"}]}))
        self.assertEqual(gs.stage(root, {"a"}, root / "_g", None), 0)

    def test_a_module_at_the_scan_root_gets_its_own_directory(self) -> None:
        root = Path(tempfile.mkdtemp())
        self.assertEqual(gs.module_key(root, root), "root")
        self.assertEqual(gs.module_key(root / "samples" / "wear", root), "samples_wear")

    def test_safe_relative_refuses_escapes(self) -> None:
        self.assertFalse(gs.safe_relative("../x"))
        self.assertFalse(gs.safe_relative("/etc/passwd"))
        self.assertTrue(gs.safe_relative("src/main/A.kt"))


if __name__ == "__main__":
    unittest.main()
