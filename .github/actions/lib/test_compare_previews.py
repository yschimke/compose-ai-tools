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


class PerceptualFilterEndToEndTest(unittest.TestCase):
    """End-to-end: ``cmd_copy_changed`` and ``cmd_compare`` must consult the
    perceptual filter when ``--baseline-renders`` is supplied."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

        # Three previews: byte-equal, sha-different-but-perceptually-clean
        # (the issue #190 noise), and sha-different-with-real-change.
        self.equal_png = self.tmp / "Equal.png"
        self.equal_png.write_bytes(b"equal")
        self.noise_png = self.tmp / "Noise_noise.png"
        self.noise_png.write_bytes(b"noise-current")
        self.real_png = self.tmp / "Real.png"
        self.real_png.write_bytes(b"real-current")

        baseline_root = self.tmp / "_baselines" / "renders" / "app"
        baseline_root.mkdir(parents=True)
        (baseline_root / "Equal.png").write_bytes(b"equal")
        (baseline_root / "Noise_noise.png").write_bytes(b"noise-baseline")
        (baseline_root / "Real.png").write_bytes(b"real-baseline")
        self.baseline_renders = baseline_root.parent

        self.cli_path = self.tmp / "cli.json"
        self.cli_path.write_text(json.dumps({
            "schema": "compose-preview-show/v1",
            "previews": [
                _entry(id="Equal", function="EqualFn", png=str(self.equal_png), sha="equal"),
                _entry(id="Noise_noise", function="NoiseFn", png=str(self.noise_png), sha="new-noise-sha"),
                _entry(id="Real", function="RealFn", png=str(self.real_png), sha="new-real-sha"),
            ],
        }))
        self.baselines = self.tmp / "baselines.json"
        self.baselines.write_text(json.dumps({
            "app/Equal": {"sha256": "equal", "functionName": "EqualFn", "renderBasename": "Equal.png"},
            "app/Noise_noise": {"sha256": "old-noise-sha", "functionName": "NoiseFn", "renderBasename": "Noise_noise.png"},
            "app/Real": {"sha256": "old-real-sha", "functionName": "RealFn", "renderBasename": "Real.png"},
        }))

        # Same filename-based stub as PerceptualFilterTest: the convention
        # "noise" in the basename → unchanged, anything else → changed.
        self._real = cp._perceptually_changed
        cp._perceptually_changed = lambda prior, current: "noise" not in current.name
        self.addCleanup(setattr, cp, "_perceptually_changed", self._real)

    def test_copy_changed_filters_perceptual_noise(self):
        from types import SimpleNamespace
        out_dir = self.tmp / "out"
        rc = cp.cmd_copy_changed(SimpleNamespace(
            cli_json=str(self.cli_path),
            baselines=str(self.baselines),
            output_dir=str(out_dir),
            baseline_renders=str(self.baseline_renders),
        ))
        self.assertEqual(rc, 0)
        copied = sorted(p.name for p in (out_dir / "renders" / "app").iterdir())
        # Equal: byte-identical → skipped.
        # Noise_noise: sha-different but perceptually clean → skipped.
        # Real: sha-different and perceptually different → copied.
        self.assertEqual(copied, ["Real.png"])

    def test_compare_filters_perceptual_noise_from_changed_section(self):
        from types import SimpleNamespace
        import io
        import contextlib
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            cp.cmd_compare(SimpleNamespace(
                cli_json=str(self.cli_path),
                baselines=str(self.baselines),
                repo="owner/repo",
                base_ref="bef0",
                head_ref="aft0",
                baseline_renders=str(self.baseline_renders),
            ))
        out = buf.getvalue()
        # Split at the Unchanged collapsible so each section's contents can
        # be asserted independently — noise rolls into Unchanged, not Changed.
        changed_part, _, unchanged_part = out.partition("<details><summary>Unchanged")
        self.assertIn("`RealFn`", changed_part)
        self.assertNotIn("`NoiseFn`", changed_part)
        self.assertIn("`NoiseFn`", unchanged_part)
        self.assertIn("`EqualFn`", unchanged_part)


class PerceptualFilterRealLibTest(unittest.TestCase):
    """Smoke-test the actual pixelmatch path against ``ActivityListLongPreview``
    flake PNGs from issue #190. Skipped when the lib isn't installed
    locally (the GitHub action installs it explicitly)."""

    @classmethod
    def setUpClass(cls):
        try:
            from pixelmatch.contrib.PIL import pixelmatch  # noqa: F401
            from PIL import Image  # noqa: F401
        except ImportError:
            raise unittest.SkipTest("pixelmatch/Pillow not installed")
        cls.fixtures = Path(__file__).resolve().parent / "fixtures" / "issue-190"
        if not cls.fixtures.exists():
            raise unittest.SkipTest("issue-190 fixture PNGs not present")

    def test_today_flake_collapses_to_unchanged(self):
        # Both PNGs are real ActivityListLongPreview captures from today's
        # PRs (#244 and #249), confirmed sha256-different but with only
        # 4 differing AA-corner pixels at ΔE ≤ 7.
        a = self.fixtures / "ActivityListLongPreview_A.png"
        b = self.fixtures / "ActivityListLongPreview_B.png"
        self.assertFalse(cp._perceptually_changed(a, b))

    def test_size_mismatch_reads_as_changed(self):
        from PIL import Image
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        small = tmp / "small.png"
        big = tmp / "big.png"
        with Image.new("RGB", (10, 10), (0, 0, 0)) as s:
            s.save(small)
        with Image.new("RGB", (20, 20), (0, 0, 0)) as b:
            b.save(big)
        self.assertTrue(cp._perceptually_changed(small, big))


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


class PerceptualFilterTest(unittest.TestCase):
    """sha-different but pixelmatch-clean PNGs must read as ``unchanged``.

    The filter is opt-in via ``--baseline-renders`` and falls back to
    strict-bytes when the directory isn't passed. Tests stub
    ``_perceptually_changed`` so unittest stays stdlib-only — the real
    pixelmatch path is exercised by the CI action that installs the lib.
    """

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.calls: list[tuple[Path, Path]] = []
        self._real_perceptually_changed = cp._perceptually_changed

        def stub(prior: Path, current: Path) -> bool:
            self.calls.append((prior, current))
            # File-name convention used by tests below: PNGs whose name
            # contains "noise" model the today's-flake case (sha-different
            # but visually identical), everything else is a real change.
            return "noise" not in current.name

        cp._perceptually_changed = stub
        self.addCleanup(setattr, cp, "_perceptually_changed", self._real_perceptually_changed)

    def _setup_pair(self, *, baseline_basename: str, current_basename: str) -> tuple[Path, Path, Path]:
        """Build a baseline-renders dir + a current PNG path for one preview."""
        baseline_root = self.tmp / "_baselines" / "renders" / "m"
        baseline_root.mkdir(parents=True)
        (baseline_root / baseline_basename).write_bytes(b"baseline-bytes")
        current_png = self.tmp / current_basename
        current_png.write_bytes(b"current-bytes")
        return baseline_root.parent, current_png, baseline_root / baseline_basename

    def test_is_changed_fast_path_when_shas_match(self):
        # Doesn't even consult the perceptual filter when the bytes agree.
        info = {"sha256": "s", "module": "m", "pngPath": "/x.png"}
        bl = {"sha256": "s", "renderBasename": "x.png"}
        self.assertFalse(cp._is_changed(info, bl, self.tmp))
        self.assertEqual(self.calls, [])

    def test_is_changed_falls_back_to_strict_when_baseline_renders_missing(self):
        # No baseline-renders dir → preserve current strict-bytes behaviour.
        info = {"sha256": "new", "module": "m", "pngPath": "/x.png"}
        bl = {"sha256": "old", "renderBasename": "x.png"}
        self.assertTrue(cp._is_changed(info, bl, None))
        self.assertEqual(self.calls, [])

    def test_is_changed_defers_to_perceptual_filter_on_sha_mismatch(self):
        # Filename ends with "noise.png" → stub returns False (not changed).
        baseline_dir, current_png, prior_png = self._setup_pair(
            baseline_basename="A_noise.png", current_basename="A_noise.png")
        info = {"sha256": "new", "module": "m", "pngPath": str(current_png)}
        bl = {"sha256": "old", "renderBasename": "A_noise.png"}
        self.assertFalse(cp._is_changed(info, bl, baseline_dir))
        # The filter was consulted and pointed at the right files.
        self.assertEqual(self.calls, [(prior_png, current_png)])

    def test_is_changed_surfaces_real_change_through_perceptual_filter(self):
        baseline_dir, current_png, _ = self._setup_pair(
            baseline_basename="A.png", current_basename="A.png")
        info = {"sha256": "new", "module": "m", "pngPath": str(current_png)}
        bl = {"sha256": "old", "renderBasename": "A.png"}
        self.assertTrue(cp._is_changed(info, bl, baseline_dir))

    def test_is_changed_when_baseline_basename_unknown_falls_back_to_changed(self):
        # Old-format baselines.json without renderBasename: don't drop the
        # change, surface it. Strictly more permissive than ignoring it.
        info = {"sha256": "new", "module": "m", "pngPath": "/x.png"}
        bl = {"sha256": "old"}  # no renderBasename
        self.assertTrue(cp._is_changed(info, bl, self.tmp / "_baselines/renders"))


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


def _resource_envelope(*resources: dict) -> dict:
    """Wrap one or more `resources` entries in the
    `compose-preview-show-resources/v1` envelope shape the CLI emits."""
    return {
        "schema": "compose-preview-show-resources/v1",
        "resources": list(resources),
        "counts": None,
        "manifestReferences": [],
    }


def _resource_entry(*, id: str, module: str, type: str = "VECTOR",
                    captures: list) -> dict:
    """Build a `ResourcePreviewResult`-shaped dict for the envelope."""
    return {
        "id": id,
        "module": module,
        "type": type,
        "sourceFiles": {},
        "captures": captures,
    }


def _resource_capture(*, render_output: str, png_path: str | None,
                      sha256: str | None,
                      qualifiers: str | None = None,
                      shape: str | None = None) -> dict:
    """Build a `ResourceCaptureResult`-shaped dict for the envelope."""
    return {
        "variant": {"qualifiers": qualifiers, "shape": shape},
        "renderOutput": render_output,
        "pngPath": png_path,
        "sha256": sha256,
        "changed": None,
    }


class GenerateResourcesTests(unittest.TestCase):
    """`cmd_generate_resources` reads `compose-preview show-resources --json`
    output and stages the rendered PNGs / GIFs into
    `<output>/renders/<module>/resources/...`."""

    def setUp(self) -> None:
        self.tmp = tempfile.mkdtemp()
        self.tmp_path = Path(self.tmp)
        self.png_root = self.tmp_path / "renders"
        self.png_root.mkdir()
        self.output = self.tmp_path / "out"
        self.cli_json = self.tmp_path / "_resources.json"

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp)

    def _stage_png(self, relative: str, content: bytes) -> Path:
        """Drop a fixture PNG on disk and return its absolute path — mirrors
        what the CLI emits as `pngPath` for a real render."""
        target = self.png_root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        return target

    def _write_envelope(self, *resources: dict) -> None:
        self.cli_json.write_text(json.dumps(_resource_envelope(*resources)))

    def _run(self) -> int:
        from types import SimpleNamespace
        return cp.cmd_generate_resources(SimpleNamespace(
            cli_json=str(self.cli_json),
            output_dir=str(self.output),
        ))

    def test_no_manifests_is_a_clean_noop(self) -> None:
        # Empty envelope (the CLI's "no Android modules" output).
        self._write_envelope()
        self.assertEqual(self._run(), 0)
        # Output dir shouldn't be created when there's nothing to do — keeps
        # the baselines tree byte-identical for projects that don't write XML
        # resources.
        self.assertFalse(self.output.exists())

    def test_translates_gradle_module_path_to_filesystem_layout(self) -> None:
        """The CLI emits `module: "samples:android"` (gradle path); the
        baselines tree wants `samples/android` so `renders/<module>/...`
        resolves to a real directory tree on the GitHub-served raw URLs."""
        png = self._stage_png("ic_logo_xhdpi.png", b"vector-bytes")
        self._write_envelope(_resource_entry(
            id="drawable/ic_logo", module="samples:android",
            captures=[_resource_capture(
                render_output="renders/resources/drawable/ic_logo_xhdpi.png",
                png_path=str(png), sha256="abc", qualifiers="xhdpi",
            )],
        ))
        self.assertEqual(self._run(), 0)
        baselines_file = self.output / "resource-baselines.json"
        self.assertTrue(baselines_file.exists())
        baselines = json.loads(baselines_file.read_text())
        first_key = next(iter(baselines))
        self.assertTrue(
            first_key.startswith("samples/android::"),
            f"expected 'samples/android::' prefix, got {first_key!r}",
        )

    def test_copies_pngs_and_writes_resource_baselines_json(self) -> None:
        png = self._stage_png("ic_logo_xhdpi.png", b"png-bytes")
        self._write_envelope(_resource_entry(
            id="drawable/ic_logo", module="app",
            captures=[_resource_capture(
                render_output="renders/resources/drawable/ic_logo_xhdpi.png",
                png_path=str(png), sha256="abc", qualifiers="xhdpi",
            )],
        ))

        self.assertEqual(self._run(), 0)

        # PNG copied to the canonical baselines layout — the leading
        # `renders/` of the manifest's `renderOutput` is absorbed into the
        # destination root so it doesn't double-up.
        dest = self.output / "renders" / "app" / "resources" / "drawable" / "ic_logo_xhdpi.png"
        self.assertTrue(dest.exists())
        self.assertEqual(dest.read_bytes(), b"png-bytes")

        baselines = json.loads((self.output / "resource-baselines.json").read_text())
        self.assertEqual(len(baselines), 1)
        key = next(iter(baselines))
        self.assertIn("app::drawable/ic_logo::renders/resources/drawable/ic_logo_xhdpi.png", key)
        entry = baselines[key]
        self.assertEqual(entry["resourceId"], "drawable/ic_logo")
        self.assertEqual(entry["resourceType"], "VECTOR")
        self.assertEqual(entry["qualifiers"], "xhdpi")
        self.assertIsNone(entry["shape"])
        self.assertEqual(entry["renderBasename"], "resources/drawable/ic_logo_xhdpi.png")

    def test_skips_captures_whose_pngs_arent_in_envelope(self) -> None:
        # CLI emits null pngPath/sha256 when discovery saw the resource but
        # the renderer didn't produce a PNG (capture too expensive, error,
        # etc.). These shouldn't land in the baseline.
        self._write_envelope(_resource_entry(
            id="drawable/ghost", module="app",
            captures=[_resource_capture(
                render_output="renders/resources/drawable/ghost_xhdpi.png",
                png_path=None, sha256=None, qualifiers="xhdpi",
            )],
        ))
        self.assertEqual(self._run(), 0)
        # No baselines file should be written, since no PNG exists to record.
        self.assertFalse((self.output / "resource-baselines.json").exists())

    def test_records_adaptive_icon_shape_per_capture(self) -> None:
        circle = self._stage_png("ic_launcher_xhdpi_SHAPE_circle.png", b"circle")
        legacy = self._stage_png("ic_launcher_xhdpi_LEGACY.png", b"legacy")
        self._write_envelope(_resource_entry(
            id="mipmap/ic_launcher", module="app", type="ADAPTIVE_ICON",
            captures=[
                _resource_capture(
                    render_output="renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_circle.png",
                    png_path=str(circle), sha256="c1",
                    qualifiers="xhdpi", shape="CIRCLE",
                ),
                _resource_capture(
                    render_output="renders/resources/mipmap/ic_launcher_xhdpi_LEGACY.png",
                    png_path=str(legacy), sha256="l1",
                    qualifiers="xhdpi", shape="LEGACY",
                ),
            ],
        ))
        self.assertEqual(self._run(), 0)
        baselines = json.loads((self.output / "resource-baselines.json").read_text())
        shapes = sorted(entry["shape"] for entry in baselines.values())
        self.assertEqual(shapes, ["CIRCLE", "LEGACY"])

    def test_appends_resource_section_to_existing_readme(self) -> None:
        # Simulate `cmd_generate` having already written a composable README.
        self.output.mkdir()
        (self.output / "README.md").write_text("# Preview Baselines\n\n## sample-android\n\n…\n")
        png = self._stage_png("ic_logo_xhdpi.png", b"png")
        self._write_envelope(_resource_entry(
            id="drawable/ic_logo", module="app",
            captures=[_resource_capture(
                render_output="renders/resources/drawable/ic_logo_xhdpi.png",
                png_path=str(png), sha256="abc", qualifiers="xhdpi",
            )],
        ))
        self.assertEqual(self._run(), 0)
        readme = (self.output / "README.md").read_text()
        # Original composable section preserved.
        self.assertIn("## sample-android", readme)
        # Resource section appended.
        self.assertIn("## Android XML Resource Previews", readme)
        self.assertIn("`drawable/ic_logo`", readme)
        self.assertIn("VECTOR", readme)

    def test_missing_envelope_file_is_a_clean_noop(self) -> None:
        # The action's `> _resources.json 2>/dev/null || true` redirect
        # truncates to 0 bytes if the CLI fails. Treat as no entries.
        self.cli_json.write_text("")
        self.assertEqual(self._run(), 0)
        self.assertFalse(self.output.exists())


class CopyChangedResourcesTests(unittest.TestCase):
    """`cmd_copy_changed_resources` mirrors `cmd_copy_changed` for resource
    captures: reads `compose-preview show-resources --json`, compares each
    capture's sha256 against the prior baseline, and copies new/changed
    entries into `<output>/renders/<module>/...`."""

    def setUp(self) -> None:
        self.tmp = tempfile.mkdtemp()
        self.tmp_path = Path(self.tmp)
        self.png_root = self.tmp_path / "renders"
        self.png_root.mkdir()
        self.output = self.tmp_path / "out"
        self.baselines = self.tmp_path / "resource-baselines.json"
        self.cli_json = self.tmp_path / "_resources.json"

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp)

    def _stage_png(self, relative: str, content: bytes) -> Path:
        target = self.png_root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        return target

    def _write_envelope(self, *resources: dict) -> None:
        self.cli_json.write_text(json.dumps(_resource_envelope(*resources)))

    def _run(self) -> int:
        from types import SimpleNamespace
        return cp.cmd_copy_changed_resources(SimpleNamespace(
            cli_json=str(self.cli_json),
            baselines=str(self.baselines),
            output_dir=str(self.output),
        ))

    def test_copies_only_new_or_changed_pngs(self) -> None:
        # Three captures: one matches baseline (skip), one differs (changed,
        # copy), one is absent from baseline (new, copy).
        same = self._stage_png("same_xhdpi.png", b"same")
        changed = self._stage_png("changed_xhdpi.png", b"NEW-CONTENT")
        new = self._stage_png("new_xhdpi.png", b"new")
        same_sha = cp.sha256(same)

        self._write_envelope(
            _resource_entry(id="drawable/same", module="app", captures=[_resource_capture(
                render_output="renders/resources/drawable/same_xhdpi.png",
                png_path=str(same), sha256=same_sha, qualifiers="xhdpi",
            )]),
            _resource_entry(id="drawable/changed", module="app", captures=[_resource_capture(
                render_output="renders/resources/drawable/changed_xhdpi.png",
                png_path=str(changed), sha256="NEW-SHA", qualifiers="xhdpi",
            )]),
            _resource_entry(id="drawable/new", module="app", captures=[_resource_capture(
                render_output="renders/resources/drawable/new_xhdpi.png",
                png_path=str(new), sha256="brand-new-sha", qualifiers="xhdpi",
            )]),
        )
        self.baselines.write_text(json.dumps({
            "app::drawable/same::renders/resources/drawable/same_xhdpi.png": {
                "sha256": same_sha,
                "renderBasename": "resources/drawable/same_xhdpi.png",
            },
            "app::drawable/changed::renders/resources/drawable/changed_xhdpi.png": {
                "sha256": "OLD-SHA",
                "renderBasename": "resources/drawable/changed_xhdpi.png",
            },
        }))

        self.assertEqual(self._run(), 0)
        # Only `changed` and `new` should be copied.
        copied = sorted(p.name for p in (self.output / "renders" / "app" / "resources" / "drawable").iterdir())
        self.assertEqual(copied, ["changed_xhdpi.png", "new_xhdpi.png"])

    def test_no_resources_is_a_clean_noop(self) -> None:
        self._write_envelope()
        self.assertEqual(self._run(), 0)
        self.assertFalse(self.output.exists())


class CompareResourcesTests(unittest.TestCase):
    """`cmd_compare_resources` emits a "Resource Changes" markdown section
    against `compose-preview show-resources --json` + `resource-baselines.json`.
    Empty stdout when no diff is detected."""

    def setUp(self) -> None:
        self.tmp = tempfile.mkdtemp()
        self.tmp_path = Path(self.tmp)
        self.png_root = self.tmp_path / "renders"
        self.png_root.mkdir()
        self.baselines = self.tmp_path / "resource-baselines.json"
        self.cli_json = self.tmp_path / "_resources.json"

    def tearDown(self) -> None:
        shutil.rmtree(self.tmp)

    def _stage_png(self, relative: str, content: bytes) -> Path:
        target = self.png_root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        return target

    def _write_envelope(self, *resources: dict) -> None:
        self.cli_json.write_text(json.dumps(_resource_envelope(*resources)))

    def _run(self, base_ref: str = "deadbeef", head_ref: str = "cafef00d") -> str:
        import io, contextlib
        from types import SimpleNamespace
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            cp.cmd_compare_resources(SimpleNamespace(
                cli_json=str(self.cli_json),
                baselines=str(self.baselines),
                repo="owner/repo",
                base_ref=base_ref,
                head_ref=head_ref,
            ))
        return buf.getvalue()

    def test_emits_resource_changes_section_for_changed_capture(self) -> None:
        png = self._stage_png("ic_logo_xhdpi.png", b"NEW")
        self._write_envelope(_resource_entry(
            id="drawable/ic_logo", module="app",
            captures=[_resource_capture(
                render_output="renders/resources/drawable/ic_logo_xhdpi.png",
                png_path=str(png), sha256="NEW-SHA", qualifiers="xhdpi",
            )],
        ))
        self.baselines.write_text(json.dumps({
            "app::drawable/ic_logo::renders/resources/drawable/ic_logo_xhdpi.png": {
                "sha256": "OLD-SHA",
                "renderBasename": "resources/drawable/ic_logo_xhdpi.png",
            },
        }))
        out = self._run()
        # First line is the dedicated marker so the action's post step can
        # find / patch a sibling sticky comment without colliding with the
        # composable `<!-- preview-diff -->` marker.
        self.assertTrue(out.startswith("<!-- preview-diff-resources -->"))
        self.assertIn("## Resource Changes", out)
        self.assertIn("### Changed (1 variant(s) across 1 resource(s))", out)
        self.assertIn("`drawable/ic_logo`", out)
        # Before/After URLs pinned to the supplied SHAs.
        self.assertIn("/deadbeef/renders/app/resources/drawable/ic_logo_xhdpi.png", out)
        self.assertIn("/cafef00d/renders/app/resources/drawable/ic_logo_xhdpi.png", out)

    def test_groups_adaptive_icon_shapes_under_one_resource_heading(self) -> None:
        # Four shape-mask captures of the same adaptive icon, all changed —
        # they should appear under a single `mipmap/ic_launcher` group, not
        # four separate ones (otherwise the comment drowns the diff).
        captures = []
        for shape in ("CIRCLE", "ROUNDED_SQUARE", "SQUARE", "LEGACY"):
            png = self._stage_png(f"ic_launcher_xhdpi_{shape}.png", shape.encode())
            captures.append(_resource_capture(
                render_output=f"renders/resources/mipmap/ic_launcher_xhdpi_{shape}.png",
                png_path=str(png), sha256=f"NEW-{shape}",
                qualifiers="xhdpi", shape=shape,
            ))
        self._write_envelope(_resource_entry(
            id="mipmap/ic_launcher", module="app", type="ADAPTIVE_ICON",
            captures=captures,
        ))
        self.baselines.write_text(json.dumps({
            f"app::mipmap/ic_launcher::renders/resources/mipmap/ic_launcher_xhdpi_{shape}.png": {
                "sha256": "OLD",
                "renderBasename": f"resources/mipmap/ic_launcher_xhdpi_{shape}.png",
            }
            for shape in ("CIRCLE", "ROUNDED_SQUARE", "SQUARE", "LEGACY")
        }))
        out = self._run()
        # 4 variants but 1 resource group.
        self.assertIn("### Changed (4 variant(s) across 1 resource(s))", out)
        # Other 3 shapes link out as variants under the hero.
        self.assertIn("Other variants:", out)

    def test_new_resource_shows_with_after_image(self) -> None:
        png = self._stage_png("new_icon_xhdpi.png", b"hi")
        self._write_envelope(_resource_entry(
            id="drawable/new_icon", module="app",
            captures=[_resource_capture(
                render_output="renders/resources/drawable/new_icon_xhdpi.png",
                png_path=str(png), sha256="hello", qualifiers="xhdpi",
            )],
        ))
        self.baselines.write_text("{}")
        out = self._run()
        self.assertIn("### New (1 variant(s) across 1 resource(s))", out)
        self.assertIn("`drawable/new_icon`", out)

    def test_removed_resource_appears_as_strikethrough(self) -> None:
        # Empty CLI envelope — every baseline entry counts as removed.
        self._write_envelope()
        self.baselines.write_text(json.dumps({
            "app::drawable/gone::renders/resources/drawable/gone_xhdpi.png": {
                "sha256": "abc",
                "module": "app",
                "resourceId": "drawable/gone",
                "renderBasename": "resources/drawable/gone_xhdpi.png",
            },
        }))
        out = self._run()
        self.assertIn("### Removed", out)
        self.assertIn("~`drawable/gone`~", out)

    def test_empty_when_nothing_changed(self) -> None:
        # Resource exists, baseline matches → no diff section at all.
        png = self._stage_png("stable_xhdpi.png", b"stable")
        sha = cp.sha256(png)
        self._write_envelope(_resource_entry(
            id="drawable/stable", module="app",
            captures=[_resource_capture(
                render_output="renders/resources/drawable/stable_xhdpi.png",
                png_path=str(png), sha256=sha, qualifiers="xhdpi",
            )],
        ))
        self.baselines.write_text(json.dumps({
            "app::drawable/stable::renders/resources/drawable/stable_xhdpi.png": {
                "sha256": sha,
                "renderBasename": "resources/drawable/stable_xhdpi.png",
            },
        }))
        out = self._run()
        self.assertEqual(out, "",
                         "Comment should append nothing when no diff was detected.")


class LoadBaselinesTest(unittest.TestCase):
    """Pins `_load_baselines`'s permissive shape against everything the
    `git show … > file 2>/dev/null || true` redirect in
    `preview-comment/action.yml` can produce."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def test_missing_file_is_empty_dict(self):
        self.assertEqual(cp._load_baselines(self.tmp / "nope.json"), {})

    def test_empty_file_is_empty_dict(self):
        # The original failure mode: bash `> file 2>/dev/null || true`
        # truncates `file` to 0 bytes before git show runs; the file is
        # then `exists() == True` but contents = "".
        p = self.tmp / "empty.json"
        p.write_text("")
        self.assertEqual(cp._load_baselines(p), {})

    def test_whitespace_only_file_is_empty_dict(self):
        # Defensive — strip-then-test rather than literal "" check, in case
        # a future redirect path leaves a stray newline.
        p = self.tmp / "ws.json"
        p.write_text("   \n\t  \n")
        self.assertEqual(cp._load_baselines(p), {})

    def test_malformed_json_is_empty_dict(self):
        p = self.tmp / "bad.json"
        p.write_text('{"oops')
        self.assertEqual(cp._load_baselines(p), {})

    def test_non_dict_payload_is_empty_dict(self):
        # baselines.json is supposed to be an object; treat anything else
        # as no baselines rather than letting downstream `key in baselines`
        # blow up on a list/string/null.
        for payload in ('[]', '"oops"', '42', 'null'):
            p = self.tmp / "shape.json"
            p.write_text(payload)
            self.assertEqual(cp._load_baselines(p), {}, f"{payload} should normalise to {{}}")

    def test_valid_dict_payload_returns_parsed(self):
        p = self.tmp / "ok.json"
        p.write_text(json.dumps({"app/Foo": {"sha256": "abc"}}))
        self.assertEqual(cp._load_baselines(p), {"app/Foo": {"sha256": "abc"}})


class ResourcePerceptualFilterTest(unittest.TestCase):
    """Mirrors `PerceptualFilterTest` for the resource path: sha-different
    but pixelmatch-clean resource captures (typical of `AdaptiveIconDrawable`
    composite renders, where the AA mask + `PorterDuff.SRC_IN` step produces
    sub-pixel jitter between Robolectric runs) must read as ``unchanged`` in
    both `cmd_compare_resources` and `cmd_copy_changed_resources`.

    The strict-bytes fallback path (no `--baseline-renders`) is exercised by
    the existing `CompareResourcesTests` / `CopyChangedResourcesTests`."""

    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        # Filename convention: PNGs whose name contains "noise" model the
        # adaptive-icon AA flake (sha-different, pixelmatch-clean), anything
        # else is a genuine pixel diff.
        self._real = cp._perceptually_changed
        cp._perceptually_changed = lambda prior, current: "noise" not in current.name
        self.addCleanup(setattr, cp, "_perceptually_changed", self._real)

        # Resource baseline tree mimics what the action's
        # `git archive preview_resources_main renders | tar -x` produces.
        baseline_root = self.tmp / "_resource_baselines" / "renders" / "app" / "resources" / "mipmap"
        baseline_root.mkdir(parents=True)
        (baseline_root / "ic_launcher_xhdpi_SHAPE_circle_noise.png").write_bytes(b"baseline-noise")
        (baseline_root / "ic_launcher_xhdpi_SHAPE_square.png").write_bytes(b"baseline-real")
        self.baseline_renders = self.tmp / "_resource_baselines" / "renders"

        # Current renders the CLI just produced — same filenames, "new" bytes.
        self.noise_png = self.tmp / "ic_launcher_xhdpi_SHAPE_circle_noise.png"
        self.noise_png.write_bytes(b"current-noise")
        self.real_png = self.tmp / "ic_launcher_xhdpi_SHAPE_square.png"
        self.real_png.write_bytes(b"current-real")

        self.cli_json = self.tmp / "_resources.json"
        self.cli_json.write_text(json.dumps(_resource_envelope(_resource_entry(
            id="mipmap/ic_launcher", module="app", type="ADAPTIVE_ICON",
            captures=[
                _resource_capture(
                    render_output="renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_circle_noise.png",
                    png_path=str(self.noise_png), sha256="new-noise",
                    qualifiers="xhdpi", shape="CIRCLE",
                ),
                _resource_capture(
                    render_output="renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_square.png",
                    png_path=str(self.real_png), sha256="new-real",
                    qualifiers="xhdpi", shape="SQUARE",
                ),
            ],
        ))))
        self.baselines = self.tmp / "resource-baselines.json"
        self.baselines.write_text(json.dumps({
            "app::mipmap/ic_launcher::renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_circle_noise.png": {
                "sha256": "old-noise",
                "renderBasename": "resources/mipmap/ic_launcher_xhdpi_SHAPE_circle_noise.png",
            },
            "app::mipmap/ic_launcher::renders/resources/mipmap/ic_launcher_xhdpi_SHAPE_square.png": {
                "sha256": "old-real",
                "renderBasename": "resources/mipmap/ic_launcher_xhdpi_SHAPE_square.png",
            },
        }))

    def test_compare_resources_filters_perceptual_noise(self) -> None:
        from types import SimpleNamespace
        import io
        import contextlib
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = cp.cmd_compare_resources(SimpleNamespace(
                cli_json=str(self.cli_json),
                baselines=str(self.baselines),
                repo="owner/repo",
                base_ref="bef0",
                head_ref="aft0",
                baseline_renders=str(self.baseline_renders),
            ))
        self.assertEqual(rc, 0)
        out = buf.getvalue()
        # Real change still surfaces; the noise capture is filtered out so
        # the Changed section reports 1 variant, not 2.
        self.assertIn("### Changed (1 variant(s)", out)
        self.assertIn("`mipmap/ic_launcher`", out)
        # The noise capture was the CIRCLE shape — it must NOT appear as a
        # variant link in the comment body.
        self.assertNotIn("ic_launcher_xhdpi_SHAPE_circle_noise.png", out)

    def test_copy_changed_resources_filters_perceptual_noise(self) -> None:
        from types import SimpleNamespace
        out_dir = self.tmp / "out"
        rc = cp.cmd_copy_changed_resources(SimpleNamespace(
            cli_json=str(self.cli_json),
            baselines=str(self.baselines),
            output_dir=str(out_dir),
            baseline_renders=str(self.baseline_renders),
        ))
        self.assertEqual(rc, 0)
        # Only the real-diff PNG should be copied; the AA-noise capture is
        # filtered before it gets staged for the preview_resources_pr push.
        copied = sorted(p.name for p in
                        (out_dir / "renders" / "app" / "resources" / "mipmap").iterdir())
        self.assertEqual(copied, ["ic_launcher_xhdpi_SHAPE_square.png"])

    def test_strict_bytes_fallback_when_baseline_renders_omitted(self) -> None:
        # No `baseline_renders` argument → falls back to strict-sha behaviour
        # (the first-ever-PR shape, when preview_resources_main has no
        # renders/ tree to extract). Both captures are sha-different so both
        # should surface as Changed.
        from types import SimpleNamespace
        import io
        import contextlib
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            cp.cmd_compare_resources(SimpleNamespace(
                cli_json=str(self.cli_json),
                baselines=str(self.baselines),
                repo="owner/repo",
                base_ref="bef0",
                head_ref="aft0",
                # No baseline_renders → getattr returns None → strict path.
            ))
        self.assertIn("### Changed (2 variant(s)", buf.getvalue())


class CompareResourcesAgainstEmptyBaselineTest(unittest.TestCase):
    """End-to-end regression for the failure mode that broke PR comments
    after #269 landed: `git show … > resource-baselines.json` truncated the
    file to zero bytes when `preview_main` didn't yet have a
    `resource-baselines.json`, and `cmd_compare_resources` blew up with
    `JSONDecodeError`."""

    def test_empty_baselines_file_treats_every_resource_as_new(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        # Empty baseline file — the exact shape the action's truncating
        # redirect produces on first run.
        baselines = tmp / "resource-baselines.json"
        baselines.write_text("")

        png = tmp / "foo_xhdpi.png"
        png.write_bytes(b"contents")
        cli_json = tmp / "_resources.json"
        cli_json.write_text(json.dumps(_resource_envelope(_resource_entry(
            id="drawable/foo", module="app",
            captures=[_resource_capture(
                render_output="renders/resources/drawable/foo_xhdpi.png",
                png_path=str(png), sha256="contents-sha", qualifiers="xhdpi",
            )],
        ))))

        # Capture stdout; the run must succeed (no JSONDecodeError) AND emit
        # markdown listing the resource as new.
        import io, contextlib
        from types import SimpleNamespace
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = cp.cmd_compare_resources(SimpleNamespace(
                cli_json=str(cli_json),
                baselines=str(baselines),
                repo="owner/repo",
                base_ref="deadbeef",
                head_ref="cafef00d",
            ))
        self.assertEqual(rc, 0)
        self.assertIn("## Resource Changes", buf.getvalue())
        self.assertIn("### New", buf.getvalue())
        self.assertIn("`drawable/foo`", buf.getvalue())


if __name__ == "__main__":
    unittest.main()
