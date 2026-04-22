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
           sha: str | None = None, captures: list | None = None) -> dict:
    return {
        "id": id,
        "module": module,
        "functionName": function,
        "className": "K",
        "sourceFile": source,
        "pngPath": png,
        "sha256": sha,
        # CLI always emits `captures[]`; omitting it exercises the legacy
        # (pre-fan-out) fallback path in load_cli_output. Callers that want
        # a multi-capture entry pass an explicit list.
        **({"captures": captures} if captures is not None else {}),
    }


def _capture(*, png: str | None = None, sha: str | None = None,
             advanceTimeMillis: int | None = None,
             scroll: dict | None = None) -> dict:
    """Build a CaptureResult-shaped dict for use in `_entry(captures=[…])`."""
    return {
        "pngPath": png,
        "sha256": sha,
        "advanceTimeMillis": advanceTimeMillis,
        "scroll": scroll,
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

        # Fixture PNGs mirror the filenames the renderer would actually
        # write — `compare-previews.py` copies under those names so two
        # captures of a multi-mode preview don't collide on disk.
        self.red = self.tmp / "Red.png"
        self.red.write_bytes(_png_with(b"red bytes"))
        self.blue = self.tmp / "Blue.png"
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

        self.png = self.tmp / "A.png"
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
                base_ref="deadbeef",
                head_ref="cafef00d",
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

    def test_urls_pin_to_sha_refs_not_branch_names(self):
        # The whole point of using refs over branch names: the comment
        # survives preview_main/preview_pr advancing after merge. Assert
        # the SHAs we pass in actually land in the generated img src URLs.
        out = self._run(
            {"previews": [_entry(id="Changed", function="F", sha="new", png="/c.png")]},
            {"app/Changed": {"sha256": "old", "functionName": "F"}},
        )
        self.assertIn("raw.githubusercontent.com/owner/repo/deadbeef/", out)
        self.assertIn("raw.githubusercontent.com/owner/repo/cafef00d/", out)


class MultiCaptureLoadTest(unittest.TestCase):
    """`@ScrollingPreview(modes = [TOP, END])` / time fan-out: one preview
    must surface as one row per capture so each PNG shows up in the diff."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def _write(self, name: str, payload) -> Path:
        p = self.tmp / name
        p.write_text(json.dumps(payload))
        return p

    def test_multi_capture_preview_expands_to_one_row_per_capture(self):
        # TOP + END scroll fan-out — two captures under one preview id.
        path = self._write("e.json", {
            "previews": [_entry(
                id="Scroll", function="ScrollFn", png="/top.png", sha="top-sha",
                captures=[
                    _capture(png="/top.png", sha="top-sha",
                             scroll={"mode": "TOP"}),
                    _capture(png="/end.png", sha="end-sha",
                             scroll={"mode": "END"}),
                ],
            )],
        })
        out = cp.load_cli_output(path)

        # Two rows: first keeps the bare key for back-compat with existing
        # baselines; the second slots in as #1.
        self.assertEqual(set(out), {"app/Scroll", "app/Scroll#1"})

        first = out["app/Scroll"]
        self.assertEqual(first["sha256"], "top-sha")
        self.assertEqual(first["captureLabel"], "scroll top")
        self.assertEqual(first["renderBasename"], "top.png")

        second = out["app/Scroll#1"]
        self.assertEqual(second["sha256"], "end-sha")
        self.assertEqual(second["captureLabel"], "scroll end")
        self.assertEqual(second["renderBasename"], "end.png")

    def test_time_and_scroll_cross_product_carries_both_dimensions(self):
        # Combined time × scroll dimension: two captures, each with both
        # an advanceTimeMillis and a scroll. captureLabel joins them.
        path = self._write("cross.json", {
            "previews": [_entry(
                id="Cross", function="CrossFn", png="/a.png", sha="a",
                captures=[
                    _capture(png="/a.png", sha="a",
                             advanceTimeMillis=500, scroll={"mode": "END"}),
                ],
            )],
        })
        out = cp.load_cli_output(path)
        self.assertEqual(out["app/Cross"]["captureLabel"], "500ms \u00B7 scroll end")

    def test_legacy_entry_without_captures_still_loads_as_one_row(self):
        # Pre-fan-out CLI (no `captures[]` field) falls back to the
        # top-level sha/png so the action keeps working against older
        # CLI tarballs pinned in CI matrices.
        path = self._write("legacy.json", {
            "previews": [_entry(id="Flat", png="/f.png", sha="s")],
        })
        out = cp.load_cli_output(path)
        self.assertEqual(set(out), {"app/Flat"})
        self.assertEqual(out["app/Flat"]["captureLabel"], "")
        self.assertEqual(out["app/Flat"]["renderBasename"], "f.png")


class CaptureLabelTest(unittest.TestCase):
    """Helper that builds the per-capture dimension label."""

    def test_static_capture_has_empty_label(self):
        self.assertEqual(cp._capture_label({"advanceTimeMillis": None, "scroll": None}), "")

    def test_time_only(self):
        self.assertEqual(cp._capture_label({"advanceTimeMillis": 500, "scroll": None}), "500ms")

    def test_scroll_only(self):
        self.assertEqual(
            cp._capture_label({"advanceTimeMillis": None, "scroll": {"mode": "LONG"}}),
            "scroll long",
        )

    def test_time_and_scroll_joined_with_middle_dot(self):
        self.assertEqual(
            cp._capture_label({"advanceTimeMillis": 200, "scroll": {"mode": "END"}}),
            "200ms \u00B7 scroll end",
        )


class MultiCaptureGenerateTest(unittest.TestCase):
    """`generate` mode must copy every capture (not just the first) onto
    the baseline branch so PR compares can pair them up by basename."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

        self.top_png = self.tmp / "S_SCROLL_top.png"
        self.top_png.write_bytes(b"top-bytes")
        self.end_png = self.tmp / "S_SCROLL_end.png"
        self.end_png.write_bytes(b"end-bytes")

        self.cli_path = self.tmp / "cli.json"
        self.cli_path.write_text(json.dumps({
            "previews": [_entry(
                id="S", module="m", function="Fn",
                png=str(self.top_png), sha="top-sha",
                captures=[
                    _capture(png=str(self.top_png), sha="top-sha",
                             scroll={"mode": "TOP"}),
                    _capture(png=str(self.end_png), sha="end-sha",
                             scroll={"mode": "END"}),
                ],
            )],
        }))
        self.out = self.tmp / "preview_main"

    def test_copies_each_capture_under_its_renderer_basename(self):
        from types import SimpleNamespace
        rc = cp.cmd_generate(SimpleNamespace(
            cli_json=str(self.cli_path),
            output_dir=str(self.out),
            repo="owner/repo",
            branch="preview_main",
        ))
        self.assertEqual(rc, 0)

        # Both captures land on disk; neither collides on `<id>.png`.
        copied = sorted(p.name for p in (self.out / "renders" / "m").iterdir())
        self.assertEqual(copied, ["S_SCROLL_end.png", "S_SCROLL_top.png"])

        # Baselines keyed per-capture; both carry their renderBasename
        # so a later compare run can build raw-GitHub URLs correctly.
        baselines = json.loads((self.out / "baselines.json").read_text())
        self.assertEqual(set(baselines), {"m/S", "m/S#1"})
        self.assertEqual(baselines["m/S"]["renderBasename"], "S_SCROLL_top.png")
        self.assertEqual(baselines["m/S#1"]["renderBasename"], "S_SCROLL_end.png")

        # README rows include the capture label so the gallery distinguishes
        # the top frame from the end frame visually.
        readme = (self.out / "README.md").read_text()
        self.assertIn("Fn \u00B7 scroll top", readme)
        self.assertIn("Fn \u00B7 scroll end", readme)


class MultiCaptureCopyChangedTest(unittest.TestCase):
    """`copy-changed` mode must diff per-capture so a regression on only
    the END frame copies just the END PNG, not the (unchanged) TOP PNG."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

        self.top_png = self.tmp / "S_SCROLL_top.png"
        self.top_png.write_bytes(b"top-bytes")
        self.end_png = self.tmp / "S_SCROLL_end.png"
        self.end_png.write_bytes(b"end-bytes")

        self.cli_path = self.tmp / "cli.json"
        self.cli_path.write_text(json.dumps({
            "previews": [_entry(
                id="S", module="m", function="Fn",
                png=str(self.top_png), sha="top-sha",
                captures=[
                    _capture(png=str(self.top_png), sha="top-sha",
                             scroll={"mode": "TOP"}),
                    _capture(png=str(self.end_png), sha="new-end-sha",
                             scroll={"mode": "END"}),
                ],
            )],
        }))
        self.baselines = self.tmp / "baselines.json"
        self.baselines.write_text(json.dumps({
            "m/S": {"sha256": "top-sha", "functionName": "Fn"},
            "m/S#1": {"sha256": "old-end-sha", "functionName": "Fn"},
        }))
        self.out_dir = self.tmp / "out"

    def test_only_the_changed_capture_gets_copied(self):
        from types import SimpleNamespace
        rc = cp.cmd_copy_changed(SimpleNamespace(
            cli_json=str(self.cli_path),
            baselines=str(self.baselines),
            output_dir=str(self.out_dir),
        ))
        self.assertEqual(rc, 0)

        copied = sorted(p.name for p in (self.out_dir / "renders" / "m").iterdir())
        self.assertEqual(copied, ["S_SCROLL_end.png"])


class MultiCaptureCompareTest(unittest.TestCase):
    """Compare markdown must link every capture of a multi-capture preview
    — the original regression that prompted this change."""

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
                base_ref="deadbeef",
                head_ref="cafef00d",
            ))
        return buf.getvalue()

    def test_new_multi_capture_preview_links_every_variant(self):
        # Preview didn't exist in the baseline — both captures should appear,
        # the hero inline and the others as linked variants.
        out = self._run(
            {"previews": [_entry(
                id="Scroll", function="ScrollFn",
                png="/t.png", sha="top-sha",
                captures=[
                    _capture(png="/S_SCROLL_top.png", sha="top-sha",
                             scroll={"mode": "TOP"}),
                    _capture(png="/S_SCROLL_end.png", sha="end-sha",
                             scroll={"mode": "END"}),
                ],
            )]},
            {},
        )
        self.assertIn("### New (2 variant(s) across 1 function(s))", out)
        # Hero image: first capture's basename.
        self.assertIn("S_SCROLL_top.png", out)
        # Second capture rendered as a variant link with its label.
        self.assertIn("[scroll end]", out)
        self.assertIn("S_SCROLL_end.png", out)


if __name__ == "__main__":
    unittest.main()
