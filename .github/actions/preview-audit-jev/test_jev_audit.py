"""Tests for jev_audit.py: picking the changed previews, grouping them, and the report."""
import importlib.util
import json
import os
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "jev_audit.py"
spec = importlib.util.spec_from_file_location("jev_audit", SCRIPT)
ja = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ja)

DIFF = textwrap.dedent(
    """\
    <!-- preview-diff -->
    ## Preview Changes
    ### Changed (2 variant(s))
    **`StatusBodyPreview`** (wear)
    | Before | After |
    **`HomePreview`** (app)
    ### Removed (1)
    **`OldPreview`** (app)
    """
)

FAKE_CLI = textwrap.dedent(
    """\
    #!/bin/bash
    if [ "$1" = --version ]; then echo "compose-preview ${FAKE_VERSION:-2.82.0}"; exit 0; fi
    echo "$*" >> "$CALLS"
    m=$(echo "$*" | sed -E 's/.*--module ([^ ]+).*/\\1/')
    mkdir -p "$m/build/compose-previews"
    cat > "$m/build/compose-previews/guidelines.json" <<J
    {"costUsd":0.0100,"recheckModel":"x/vision","recheckCostUsd":0.0060,"problems":["p"],
     "results":[{"previewId":"$m/p1","unchecked":["r2"],"record":{"verdicts":[
       {"ruleId":"compose.text.not-truncated","verdict":"fail","confidence":0.92,"nodeIds":["t"],
        "reason":"cut at edge"},{"ruleId":"r3","verdict":"pass","confidence":0.9}]}}]}
    J
    exit 1
    """
)


class ChangedFunctionsTest(unittest.TestCase):
    def test_changed_and_new_only(self) -> None:
        self.assertEqual(
            ja.changed_functions(DIFF), {"wear": {"StatusBodyPreview"}, "app": {"HomePreview"}}
        )

    def test_group_first_match_else_default(self) -> None:
        config = {
            "default": "mobile.json",
            "groups": [
                {"module": "wearApp", "match": ".widgets.", "guidelines": "w.json", "surface": "widget"},
                {"module": "wearApp", "guidelines": "s.json", "surface": "screen"},
            ],
        }
        self.assertEqual(ja.group_for("wearApp", "wearApp/a.widgets.X", config)["guidelines"], "w.json")
        self.assertEqual(ja.group_for("wearApp", "wearApp/a.ui.Y", config)["surface"], "screen")
        self.assertEqual(ja.group_for("app", "app/Z", config)["guidelines"], "mobile.json")


class RunTest(unittest.TestCase):
    def run_audit(self, version: str = "2.82.0", **env: str) -> tuple[str, list[str]]:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "bin").mkdir()
            cli = root / "bin/compose-preview"
            cli.write_text(FAKE_CLI)
            cli.chmod(cli.stat().st_mode | stat.S_IEXEC)
            audit = root / "_audit"
            audit.mkdir()
            (audit / "preview-diff.md").write_text(DIFF)
            (audit / "context.md").write_text("# PR #12\n")
            (audit / "wear.show.json").write_text(json.dumps({"previews": [
                {"id": "wear/StatusBodyPreview_small", "functionName": "StatusBodyPreview"},
                {"id": "wear/Other", "functionName": "Other"}]}))
            (audit / "app.show.json").write_text(json.dumps({"previews": [
                {"id": "app/HomePreview_Light", "functionName": "HomePreview"},
                {"id": "app/OldPreview", "functionName": "OldPreview"}]}))
            (root / "jev.json").write_text(json.dumps({
                "default": "mobile.json", "groups": [{"module": "wear", "guidelines": "wear.json"}]}))
            calls = root / "calls"
            run_env = {**os.environ, "PATH": f"{root / 'bin'}:{os.environ['PATH']}",
                       "CALLS": str(calls), "FAKE_VERSION": version,
                       "JEV_CONFIG": str(root / "jev.json"), **env}
            subprocess.run([sys.executable, "-I", str(SCRIPT)], cwd=root, env=run_env,
                           check=True, capture_output=True)
            return (audit / "report.md").read_text(), calls.read_text().splitlines()

    def test_defaults_and_cost_split(self) -> None:
        report, calls = self.run_audit()
        self.assertEqual(len(calls), 2)
        app = next(c for c in calls if "--module app" in c)
        self.assertIn("--guidelines mobile.json", app)
        self.assertIn("--model typesafe/jev-1.13", app)
        self.assertIn("--recheck-model xiaomi/mimo-v2.6-flash", app)
        self.assertIn("## Preview audit (typesafe/jev-1.13)", report)
        self.assertIn("**2 finding(s)** on 2 changed preview(s), 2 rule verdict(s) left unchecked", report)
        self.assertIn("Cost: **$0.0200**; $0.0120 of that rechecking", report)
        self.assertIn("· $0.0100 (recheck $0.0060)", report)

    def test_recheck_off(self) -> None:
        _, calls = self.run_audit(RECHECK_MODEL="none")
        self.assertTrue(all("--recheck-model" not in c for c in calls))

    def test_older_cli_checks_from_text_without_recheck(self) -> None:
        report, calls = self.run_audit(version="2.81.0")
        self.assertTrue(all("--recheck-model" not in c for c in calls))
        self.assertIn("compose-preview 2.81.0 is pinned here", report)


if __name__ == "__main__":
    unittest.main()
