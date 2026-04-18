#!/usr/bin/env python3
"""Tests for compare-previews.py.

Pure stdlib (unittest) — no third-party deps so the test runs anywhere
the action runs. Run directly:

    python3 -m unittest .github/actions/lib/test_compare_previews.py

The script under test has a hyphen in its filename, so we load it via
importlib rather than a normal import.
"""

from __future__ import annotations

import importlib.util
import json
import shutil
import tempfile
import unittest
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_SPEC = importlib.util.spec_from_file_location("compare_previews", _HERE / "compare-previews.py")
cp = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
_SPEC.loader.exec_module(cp)


def _png_with(content: bytes) -> bytes:
    """Smallest legal-ish PNG — content embedded in IDAT to vary sha256 cheaply."""
    # We don't care about validity; load_cli_output never decodes the bytes.
    return content


def _entry(*, id: str, module: str = "app", function: str = "Fn",
           source: str | None = "f.kt", png: str | None = None,
           sha: str | None = None) -> dict:
    return {
        "id": id,
        "module": module,
        "functionName": function,
        "className": "K",
        "sourceFile": source,
        "pngPath": png,
        "sha256": sha,
    }


class LoadCliOutputTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def _write(self, name: str, payload) -> Path:
        p = self.tmp / name
        p.write_text(json.dumps(payload))
        return p

    def test_envelope_shape_parses(self):
        path = self._write("e.json", {
            "schema": "compose-preview-show/v1",
            "previews": [_entry(id="X", module="app", png="/p.png", sha="abc")],
            "counts": {"total": 1, "changed": 0, "unchanged": 1, "missing": 0},
        })
        out = cp.load_cli_output(path)
        self.assertEqual(set(out), {"app/X"})
        self.assertEqual(out["app/X"]["sha256"], "abc")
        self.assertEqual(out["app/X"]["pngPath"], "/p.png")

    def test_legacy_bare_list_still_parses(self):
        # Pre-0.4.x CLI tarballs emit a bare array — back-compat path.
        path = self._write("legacy.json", [_entry(id="X", module="app", sha="abc")])
        out = cp.load_cli_output(path)
        self.assertEqual(out["app/X"]["sha256"], "abc")

    def test_envelope_with_no_previews_is_empty_dict(self):
        path = self._write("empty.json", {"schema": "compose-preview-show/v1", "previews": []})
        self.assertEqual(cp.load_cli_output(path), {})

    def test_unexpected_shape_raises_systemexit(self):
        # A bare string at top level isn't a list and isn't an envelope —
        # surface a clear error rather than blowing up inside the loop.
        path = self._write("garbage.json", "not a manifest")
        with self.assertRaises(SystemExit) as ctx:
            cp.load_cli_output(path)
        self.assertIn("Unexpected CLI JSON shape", str(ctx.exception))

    def test_null_pngPath_and_sha_normalize_to_empty_string(self):
        # The downstream baselines/copy-changed logic uses truthiness on
        # these fields — null must become "" so `if not info["sha256"]`
        # works.
        path = self._write("nulls.json", {
            "previews": [_entry(id="X", png=None, sha=None)],
        })
        out = cp.load_cli_output(path)
        self.assertEqual(out["app/X"]["sha256"], "")
        self.assertEqual(out["app/X"]["pngPath"], "")


class VariantLabelTest(unittest.TestCase):
    def test_extracts_suffix_after_last_underscore(self):
        self.assertEqual(cp._variant_label("Foo_German"), "German")

    def test_no_underscore_returns_empty(self):
        # No underscore means there's no variant suffix to extract.
        self.assertEqual(cp._variant_label("FooPreview"), "")

    def test_only_last_underscore_matters(self):
        self.assertEqual(cp._variant_label("a_b_c_d"), "d")


class CopyChangedTest(unittest.TestCase):
    """End-to-end test of the call site that actually broke in CI."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

        # Two PNGs on disk — `red` is "changed" relative to baselines,
        # `blue` matches its baseline sha.
        self.red = self.tmp / "red.png"
        self.red.write_bytes(_png_with(b"red bytes"))
        self.blue = self.tmp / "blue.png"
        self.blue.write_bytes(_png_with(b"blue bytes"))

        cli_payload = {
            "schema": "compose-preview-show/v1",
            "previews": [
                _entry(id="Red", function="RedFn", png=str(self.red), sha="new-red-sha"),
                _entry(id="Blue", function="BlueFn", png=str(self.blue), sha="blue-sha"),
                _entry(id="Missing", function="MissingFn", png="", sha=""),
            ],
        }
        self.cli_path = self.tmp / "cli.json"
        self.cli_path.write_text(json.dumps(cli_payload))

        self.baselines = self.tmp / "baselines.json"
        self.baselines.write_text(json.dumps({
            "app/Red": {"sha256": "old-red-sha", "functionName": "RedFn"},
            "app/Blue": {"sha256": "blue-sha", "functionName": "BlueFn"},
        }))

        self.out_dir = self.tmp / "out"

    def test_copies_only_changed_and_new(self):
        # ``argparse.Namespace``-shaped kwargs object — cmd_copy_changed
        # only reads attribute access, so a SimpleNamespace works.
        from types import SimpleNamespace
        rc = cp.cmd_copy_changed(SimpleNamespace(
            cli_json=str(self.cli_path),
            baselines=str(self.baselines),
            output_dir=str(self.out_dir),
        ))
        self.assertEqual(rc, 0)

        copied = sorted(p.name for p in (self.out_dir / "renders" / "app").iterdir())
        # Red copied (sha changed); Blue skipped (sha matches);
        # Missing skipped (no PNG path).
        self.assertEqual(copied, ["Red.png"])

    def test_treats_missing_baselines_file_as_all_new(self):
        from types import SimpleNamespace
        rc = cp.cmd_copy_changed(SimpleNamespace(
            cli_json=str(self.cli_path),
            baselines=str(self.tmp / "no-such-file.json"),
            output_dir=str(self.out_dir),
        ))
        self.assertEqual(rc, 0)
        copied = sorted(p.name for p in (self.out_dir / "renders" / "app").iterdir())
        # Both rendered previews are "new" against a non-existent baseline.
        self.assertEqual(copied, ["Blue.png", "Red.png"])


class GenerateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

        self.png = self.tmp / "a.png"
        self.png.write_bytes(b"png-bytes")

        self.cli_path = self.tmp / "cli.json"
        self.cli_path.write_text(json.dumps({
            "previews": [
                _entry(id="A", module="m", function="Fn", png=str(self.png), sha="sha-a"),
                # Entry with no PNG should be skipped from baselines.json.
                _entry(id="B", module="m", function="FnB", png="", sha=""),
            ],
        }))
        self.out = self.tmp / "preview_main"

    def test_writes_baselines_and_readme_and_copies_pngs(self):
        from types import SimpleNamespace
        rc = cp.cmd_generate(SimpleNamespace(
            cli_json=str(self.cli_path),
            output_dir=str(self.out),
            repo="owner/repo",
            branch="preview_main",
        ))
        self.assertEqual(rc, 0)

        baselines = json.loads((self.out / "baselines.json").read_text())
        # Only the entry with a sha lands in baselines.
        self.assertEqual(set(baselines), {"m/A"})
        self.assertEqual(baselines["m/A"]["sha256"], "sha-a")

        readme = (self.out / "README.md").read_text()
        # README references the rendered PNG via the raw GitHub URL.
        self.assertIn("raw.githubusercontent.com/owner/repo/preview_main", readme)
        self.assertIn("`Fn`", readme)

        # PNG copied under renders/<module>/<id>.png.
        self.assertTrue((self.out / "renders" / "m" / "A.png").exists())


class CompareMarkdownTest(unittest.TestCase):
    """The compare command writes Markdown to stdout — capture it and assert."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def _run(self, current_payload, baselines_payload) -> str:
        cli_path = self.tmp / "cli.json"
        cli_path.write_text(json.dumps(current_payload))
        bl_path = self.tmp / "baselines.json"
        bl_path.write_text(json.dumps(baselines_payload))

        import io
        import contextlib
        from types import SimpleNamespace

        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            cp.cmd_compare(SimpleNamespace(
                cli_json=str(cli_path),
                baselines=str(bl_path),
                repo="owner/repo",
                pr="42",
                base_branch="preview_main",
                head_branch="preview_pr/42",
            ))
        return buf.getvalue()

    def test_no_changes_message(self):
        out = self._run(
            {"previews": [_entry(id="X", sha="s1", png="/p.png")]},
            {"app/X": {"sha256": "s1", "functionName": "Fn"}},
        )
        self.assertIn("No visual changes detected.", out)
        self.assertIn("1 preview(s) unchanged", out)

    def test_marks_added_changed_and_removed(self):
        out = self._run(
            {
                "previews": [
                    _entry(id="Changed", function="Same", sha="new", png="/c.png"),
                    _entry(id="New", function="Brand", sha="new2", png="/n.png"),
                ],
            },
            {
                "app/Changed": {"sha256": "old", "functionName": "Same"},
                "app/Gone": {"sha256": "any", "functionName": "Removed"},
            },
        )
        # All three sections appear in the comment body.
        self.assertIn("### Changed", out)
        self.assertIn("### New", out)
        self.assertIn("### Removed", out)
        # Marker comment so subsequent runs can edit-in-place.
        self.assertIn("<!-- preview-diff -->", out)


if __name__ == "__main__":
    unittest.main()
