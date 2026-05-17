#!/usr/bin/env python3
"""Tests for vscode-preview-diff.py.

Pure stdlib (unittest) — no third-party deps so the test runs anywhere
the action runs. Run directly:

    python3 -m unittest .github/actions/lib/test_vscode_preview_diff.py

The script under test has a hyphen in its filename, so we load it via
importlib rather than a normal import.
"""

from __future__ import annotations

import importlib.util
import io
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_SPEC = importlib.util.spec_from_file_location(
    "vscode_preview_diff", _HERE / "vscode-preview-diff.py"
)
mod = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
_SPEC.loader.exec_module(mod)


def _png(name: str, content: bytes) -> tuple[str, bytes]:
    return name, content


class TestScan(unittest.TestCase):
    def test_scan_parses_fixture_and_theme(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            (d / "grid-default.dark.png").write_bytes(b"a")
            (d / "grid-default.light.png").write_bytes(b"b")
            (d / "a11y-findings.dark.png").write_bytes(b"c")
            (d / "stray.txt").write_bytes(b"ignored")
            entries = mod._scan(d)
            self.assertEqual(
                set(entries.keys()),
                {
                    "grid-default.dark.png",
                    "grid-default.light.png",
                    "a11y-findings.dark.png",
                },
            )
            self.assertEqual(entries["grid-default.dark.png"]["fixture"], "grid-default")
            self.assertEqual(entries["grid-default.dark.png"]["theme"], "dark")
            self.assertEqual(entries["a11y-findings.dark.png"]["fixture"], "a11y-findings")
            # All shas distinct since contents differ.
            shas = {e["sha256"] for e in entries.values()}
            self.assertEqual(len(shas), 3)

    def test_scan_skips_unmatched_names(self) -> None:
        # `<fixture>.<theme>.png` is the only legal shape — anything that
        # doesn't match (e.g. a stray screenshot dropped by a future
        # debugging step) is logged-and-skipped, not crashed on.
        with tempfile.TemporaryDirectory() as td:
            d = Path(td)
            (d / "noTheme.png").write_bytes(b"x")  # missing theme segment
            (d / "ok.dark.png").write_bytes(b"y")
            entries = mod._scan(d)
            self.assertEqual(set(entries.keys()), {"ok.dark.png"})


class TestGenerate(unittest.TestCase):
    def test_generate_emits_baselines_renders_and_readme(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            inp = Path(td) / "in"
            out = Path(td) / "out"
            inp.mkdir()
            (inp / "grid-default.dark.png").write_bytes(b"abc")
            (inp / "grid-default.light.png").write_bytes(b"def")

            class Args:
                input_dir = str(inp)
                output_dir = str(out)

            self.assertEqual(mod.cmd_generate(Args()), 0)
            baselines = json.loads((out / "baselines.json").read_text())
            self.assertEqual(baselines["schema"], 1)
            self.assertEqual(
                set(baselines["captures"].keys()),
                {"grid-default.dark.png", "grid-default.light.png"},
            )
            self.assertTrue((out / "renders" / "grid-default.dark.png").exists())
            readme = (out / "README.md").read_text()
            self.assertIn("grid-default", readme)
            self.assertIn("`dark`", readme)
            self.assertIn("`light`", readme)


class TestCopyChanged(unittest.TestCase):
    def test_copies_only_changed_and_new(self) -> None:
        with tempfile.TemporaryDirectory() as td:
            base_dir = Path(td) / "base"
            head_dir = Path(td) / "head"
            staging = Path(td) / "staging"
            base_dir.mkdir()
            head_dir.mkdir()

            (base_dir / "grid-default.dark.png").write_bytes(b"same")
            (base_dir / "grid-default.light.png").write_bytes(b"old")
            (head_dir / "grid-default.dark.png").write_bytes(b"same")  # unchanged
            (head_dir / "grid-default.light.png").write_bytes(b"new")  # changed
            (head_dir / "newfix.dark.png").write_bytes(b"brand-new")  # new

            class GenArgs:
                input_dir = str(base_dir)
                output_dir = str(Path(td) / "baseline-out")

            mod.cmd_generate(GenArgs())

            class CopyArgs:
                input_dir = str(head_dir)
                baselines = str(Path(td) / "baseline-out" / "baselines.json")
                output_dir = str(staging)

            self.assertEqual(mod.cmd_copy_changed(CopyArgs()), 0)
            copied = sorted(p.name for p in (staging / "renders").iterdir())
            self.assertEqual(copied, ["grid-default.light.png", "newfix.dark.png"])


class TestPerceptualFilter(unittest.TestCase):
    """Cover the pixelmatch path that absorbs antialiased-edge jitter.

    The headless Chromium harness emits ±1-channel differences on a small
    number of antialiased pixels between runs even when nothing about the
    fixture has changed (originally surfaced by `inspection-tree`
    repeatedly flipping by 2 bytes on `vscode-preview/main`). Without the
    perceptual filter those PNGs read as "changed" and ship a fake row in
    the PR diff comment.
    """

    def _require_pixelmatch(self) -> None:
        try:
            from pixelmatch.contrib.PIL import pixelmatch  # noqa: F401
            from PIL import Image  # noqa: F401
        except ImportError:
            raise unittest.SkipTest("pixelmatch/Pillow not installed")

    def _png(self, path: Path, fn) -> None:
        from PIL import Image
        img = Image.new("RGB", (32, 32), (255, 255, 255))
        for x in range(32):
            for y in range(32):
                img.putpixel((x, y), fn(x, y))
        img.save(path)

    def test_copy_changed_skips_perceptually_identical(self) -> None:
        self._require_pixelmatch()
        with tempfile.TemporaryDirectory() as td:
            base_dir = Path(td) / "base"
            head_dir = Path(td) / "head"
            staging = Path(td) / "staging"
            base_dir.mkdir()
            head_dir.mkdir()

            # Same content but written twice — PIL emits identical bytes,
            # so to simulate the harness flake we flip one corner pixel
            # by 1 on the "head" copy. That mimics the real jitter we
            # measured on `inspection-tree.dark.png`.
            def shape(x: int, y: int) -> tuple[int, int, int]:
                v = 80 if (x // 4 + y // 4) % 2 == 0 else 200
                return (v, v, v)

            self._png(base_dir / "grid-default.dark.png", shape)
            self._png(head_dir / "grid-default.dark.png",
                      lambda x, y: (shape(x, y)[0] + (1 if (x, y) == (0, 0) else 0),
                                    shape(x, y)[1] + (1 if (x, y) == (0, 0) else 0),
                                    shape(x, y)[2] + (1 if (x, y) == (0, 0) else 0)))

            class GenArgs:
                input_dir = str(base_dir)
                output_dir = str(Path(td) / "baseline-out")
            mod.cmd_generate(GenArgs())

            baseline_renders = str(Path(td) / "baseline-out" / "renders")

            class CopyArgs:
                input_dir = str(head_dir)
                baselines = str(Path(td) / "baseline-out" / "baselines.json")
                output_dir = str(staging)
                baseline_renders = None  # strict bytes path

            mod.cmd_copy_changed(CopyArgs())
            strict = sorted(p.name for p in (staging / "renders").iterdir())
            self.assertEqual(
                strict, ["grid-default.dark.png"],
                "without --baseline-renders, sha mismatch must still copy",
            )

            shutil.rmtree(staging)

            class CopyArgsPerceptual:
                input_dir = str(head_dir)
                baselines = str(Path(td) / "baseline-out" / "baselines.json")
                output_dir = str(staging)
                baseline_renders = str(Path(td) / "baseline-out" / "renders")
            mod.cmd_copy_changed(CopyArgsPerceptual())
            perceptual = sorted(
                p.name for p in (staging / "renders").iterdir()
            ) if (staging / "renders").exists() else []
            self.assertEqual(
                perceptual, [],
                "single-pixel jitter must be absorbed by pixelmatch",
            )


class TestCompare(unittest.TestCase):
    def _run(
        self,
        base_files: list[tuple[str, bytes]],
        head_files: list[tuple[str, bytes]],
    ) -> str:
        with tempfile.TemporaryDirectory() as td:
            base_dir = Path(td) / "base"
            head_dir = Path(td) / "head"
            base_dir.mkdir()
            head_dir.mkdir()
            for name, content in base_files:
                (base_dir / name).write_bytes(content)
            for name, content in head_files:
                (head_dir / name).write_bytes(content)

            class GenArgs:
                input_dir = str(base_dir)
                output_dir = str(Path(td) / "baseline-out")

            mod.cmd_generate(GenArgs())

            class CmpArgs:
                input_dir = str(head_dir)
                baselines = str(Path(td) / "baseline-out" / "baselines.json")
                repo = "yschimke/compose-ai-tools"
                base_ref = "BASE"
                head_ref = "HEAD"
                base_branch = "vscode-preview/main"

            buf = io.StringIO()
            old, sys.stdout = sys.stdout, buf
            try:
                mod.cmd_compare(CmpArgs())
            finally:
                sys.stdout = old
            return buf.getvalue()

    def test_no_changes_emits_sentinel(self) -> None:
        out = self._run(
            [_png("grid-default.dark.png", b"a"), _png("grid-default.light.png", b"b")],
            [_png("grid-default.dark.png", b"a"), _png("grid-default.light.png", b"b")],
        )
        # The post step uses this exact sentinel to suppress empty
        # comments — keep the wording stable.
        self.assertIn("No visual changes detected.", out)
        self.assertIn("<!-- vscode-preview-diff -->", out)

    def test_changed_capture_renders_before_after(self) -> None:
        out = self._run(
            [_png("grid-default.dark.png", b"old")],
            [_png("grid-default.dark.png", b"new")],
        )
        self.assertIn("### Changed", out)
        self.assertIn("BASE/renders/grid-default.dark.png", out)
        self.assertIn("HEAD/renders/grid-default.dark.png", out)

    def test_new_capture_only_after(self) -> None:
        out = self._run(
            [],
            [_png("a11y-findings.dark.png", b"hi")],
        )
        self.assertIn("### New", out)
        self.assertIn("HEAD/renders/a11y-findings.dark.png", out)
        self.assertNotIn("BASE/renders/a11y-findings.dark.png", out)

    def test_removed_capture_listed(self) -> None:
        out = self._run(
            [_png("grid-default.dark.png", b"a")],
            [],
        )
        self.assertIn("### Removed", out)


if __name__ == "__main__":
    unittest.main()
