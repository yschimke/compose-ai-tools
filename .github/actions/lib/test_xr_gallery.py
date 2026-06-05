#!/usr/bin/env python3
"""Tests for xr-gallery.py.

Pure stdlib (unittest) — no third-party deps so the test runs anywhere the
action runs. Run directly:

    python3 -m unittest .github/actions/lib/test_xr_gallery.py

The script under test has a hyphen in its filename, so we load it via importlib
rather than a normal import.
"""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_SPEC = importlib.util.spec_from_file_location("xr_gallery", _HERE / "xr-gallery.py")
xg = importlib.util.module_from_spec(_SPEC)
assert _SPEC.loader is not None
_SPEC.loader.exec_module(xg)


def _scene(panels: list[dict]) -> dict:
    return {
        "schemaVersion": 1,
        "camera": {"kind": "orbit", "target": {"x": 0, "y": 0, "z": 0}, "distance": 1.0,
                   "yawDeg": 0.0, "pitchDeg": 0.0},
        "panels": panels,
    }


def _panel(pid: str, w: int, h: int) -> dict:
    return {
        "id": pid,
        "poseInRoot": {"translation": {"x": 0, "y": 0, "z": 0},
                       "rotation": {"x": 0, "y": 0, "z": 0, "w": 1}},
        "sizeDp": {"width": w, "height": h},
        "texture": f"{pid}.png",
    }


class FoldTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.gallery = self.root / "gallery"
        self.gallery.mkdir()
        (self.gallery / "README.md").write_text("# Gallery\n\nSome 2D previews.\n")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def _make_scene(self, name: str, panels: list[dict], *, write_textures=True) -> Path:
        d = self.root / "consumer" / "build" / "compose-previews" / "renders" / name
        d.mkdir(parents=True)
        (d / "scene.json").write_text(json.dumps(_scene(panels)))
        if write_textures:
            for p in panels:
                (d / p["texture"]).write_bytes(b"\x89PNG\r\n\x1a\n" + p["id"].encode())
        return d

    def test_fold_copies_artifacts_and_appends_section(self) -> None:
        self._make_scene("WorkspacePreview", [_panel("browse", 360, 520),
                                              _panel("detail", 480, 520)])
        n = xg.fold(self.gallery, self.root)
        self.assertEqual(n, 1)

        dest = self.gallery / "renders" / "xr" / "WorkspacePreview"
        self.assertTrue((dest / "scene.json").is_file())
        self.assertTrue((dest / "browse.png").is_file())
        self.assertTrue((dest / "detail.png").is_file())

        readme = (self.gallery / "README.md").read_text()
        self.assertIn("## XR spatial previews", readme)
        self.assertIn("### `WorkspacePreview` — 2 panel(s)", readme)
        self.assertIn("[`scene.json`](renders/xr/WorkspacePreview/scene.json)", readme)
        self.assertIn("![browse](renders/xr/WorkspacePreview/browse.png)", readme)
        self.assertIn("`detail` (480×520)", readme)
        # Original 2D content is preserved (append, not overwrite).
        self.assertIn("Some 2D previews.", readme)

    def test_missing_readme_is_noop(self) -> None:
        (self.gallery / "README.md").unlink()
        self._make_scene("P", [_panel("a", 10, 10)])
        self.assertEqual(xg.fold(self.gallery, self.root), 0)

    def test_no_scenes_is_noop(self) -> None:
        self.assertEqual(xg.fold(self.gallery, self.root), 0)
        self.assertNotIn("XR spatial previews", (self.gallery / "README.md").read_text())

    def test_idempotent_second_fold_noop(self) -> None:
        self._make_scene("P", [_panel("a", 10, 10)])
        self.assertEqual(xg.fold(self.gallery, self.root), 1)
        first = (self.gallery / "README.md").read_text()
        # Second fold sees the marker and bails without re-appending.
        self.assertEqual(xg.fold(self.gallery, self.root), 0)
        self.assertEqual((self.gallery / "README.md").read_text(), first)
        self.assertEqual(first.count("## XR spatial previews"), 1)

    def test_missing_texture_file_lists_header_without_crashing(self) -> None:
        self._make_scene("P", [_panel("ghost", 10, 10)], write_textures=False)
        self.assertEqual(xg.fold(self.gallery, self.root), 1)
        readme = (self.gallery / "README.md").read_text()
        self.assertIn("`ghost` (10×10)", readme)
        # Texture wasn't on disk, so it isn't copied, but the section still renders.
        self.assertFalse((self.gallery / "renders" / "xr" / "P" / "ghost.png").exists())

    def test_excludes_external_build_paths(self) -> None:
        # A scene under */external/build/* (the integration checkout's own build) must be ignored.
        d = self.root / "external" / "build" / "compose-previews" / "renders" / "Stray"
        d.mkdir(parents=True)
        (d / "scene.json").write_text(json.dumps(_scene([_panel("x", 1, 1)])))
        self.assertEqual(xg.fold(self.gallery, self.root), 0)


if __name__ == "__main__":
    unittest.main()
