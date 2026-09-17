#!/usr/bin/env python3
"""Tests for `design-renders.py` — the manifest and the sticky comment.

Pure stdlib, run directly (`python3 .github/actions/lib/test_design_renders.py -v`) the way the
other suites in this directory are. Nothing here opens an image: a design render is compared by the
sha256 the manifest carries, so the perceptual-diff libraries `test_compare_previews.py` needs are
not a dependency of this lane.
"""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "design_renders", Path(__file__).with_name("design-renders.py")
)
assert _spec and _spec.loader
design_renders = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(design_renders)


class ManifestTest(unittest.TestCase):
    def test_hashes_every_render_and_sorts_by_id(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            (directory / "ui-builder-menus.png").write_bytes(b"menus")
            (directory / "google-home-wear.svg").write_bytes(b"<svg/>")

            manifest = design_renders.build_manifest(directory)

            self.assertEqual(design_renders.SCHEMA, manifest["schema"])
            self.assertEqual(
                ["google-home-wear", "ui-builder-menus"],
                [row["id"] for row in manifest["designs"]],
            )
            row = manifest["designs"][1]
            self.assertEqual("ui-builder-menus.png", row["file"])
            self.assertEqual(5, row["bytes"])
            self.assertEqual(64, len(row["sha256"]))

    def test_ignores_files_that_are_not_renders(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            (directory / "a.png").write_bytes(b"a")
            (directory / "_designs.json").write_text("{}", encoding="utf-8")
            (directory / "notes.md").write_text("hi", encoding="utf-8")

            manifest = design_renders.build_manifest(directory)

            self.assertEqual(["a"], [row["id"] for row in manifest["designs"]])

    def test_a_refusal_is_a_row_with_no_file(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            (directory / "blank.png").write_bytes(b"blank")

            manifest = design_renders.build_manifest(
                directory, {"jetcaster": "layoutMode has no parameter to be written to"}
            )

            rows = {row["id"]: row for row in manifest["designs"]}
            self.assertNotIn("file", rows["jetcaster"])
            self.assertIn("layoutMode", rows["jetcaster"]["refused"])
            self.assertIn("file", rows["blank"])

    def test_a_render_that_exists_beats_a_refusal_claimed_for_it(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            (directory / "wear-list.png").write_bytes(b"drawn")

            manifest = design_renders.build_manifest(directory, {"wear-list": "refused"})

            rows = {row["id"]: row for row in manifest["designs"]}
            self.assertIn("file", rows["wear-list"])
            self.assertNotIn("refused", rows["wear-list"])
            self.assertEqual(1, len(manifest["designs"]))


def _manifest(*rows: dict) -> dict:
    return {"schema": design_renders.SCHEMA, "designs": list(rows)}


def _drawn(design: str, sha: str) -> dict:
    return {"id": design, "file": f"{design}.png", "sha256": sha, "bytes": 1}


class CommentTest(unittest.TestCase):
    def comment(self, head: dict, baseline: dict | None = None) -> str:
        return design_renders.build_comment(
            head=head,
            baseline=baseline,
            repo="yschimke/compose-ui-builder",
            head_branch="ui-builder-designs/pr-5",
            baseline_branch="ui-builder-designs/main",
        )

    def test_starts_with_the_sticky_marker(self):
        body = self.comment(_manifest(_drawn("a", "1")))
        self.assertTrue(body.startswith(design_renders.MARKER))

    def test_a_changed_design_shows_before_and_after_from_two_branches(self):
        body = self.comment(
            _manifest(_drawn("ui-builder-menus", "after")),
            _manifest(_drawn("ui-builder-menus", "before")),
        )

        self.assertIn("### Changed", body)
        self.assertIn(
            "raw.githubusercontent.com/yschimke/compose-ui-builder/ui-builder-designs/main/"
            "ui-builder-menus.png",
            body,
        )
        self.assertIn(
            "raw.githubusercontent.com/yschimke/compose-ui-builder/ui-builder-designs/pr-5/"
            "ui-builder-menus.png",
            body,
        )

    def test_an_identical_design_is_counted_not_shown(self):
        body = self.comment(
            _manifest(_drawn("a", "same"), _drawn("b", "moved")),
            _manifest(_drawn("a", "same"), _drawn("b", "before")),
        )

        self.assertIn("1 design(s) unchanged.", body)
        self.assertNotIn("| `a` |", body)
        self.assertIn("| `b` |", body)

    def test_with_no_baseline_every_design_is_added(self):
        body = self.comment(_manifest(_drawn("a", "1"), _drawn("b", "2")))

        self.assertIn("### Added", body)
        self.assertNotIn("### Changed", body)
        self.assertIn("| `a` |", body)
        self.assertIn("| `b` |", body)

    def test_a_design_that_was_refused_and_now_draws_reads_as_added(self):
        body = self.comment(
            _manifest(_drawn("jetcaster", "1")),
            _manifest({"id": "jetcaster", "refused": "CarouselScope"}),
        )

        self.assertIn("### Added", body)
        self.assertNotIn("### Changed", body)

    def test_a_refusal_names_the_design_and_the_reason(self):
        body = self.comment(
            _manifest({"id": "jetcaster", "refused": "a CarouselScope DSL slot\nand a grid span"})
        )

        self.assertIn("### Refused by the export", body)
        self.assertIn("| `jetcaster` |", body)
        # Flattened, so one refusal cannot break the table it sits in.
        self.assertIn("a CarouselScope DSL slot and a grid span", body)

    def test_a_pipe_in_a_reason_is_escaped(self):
        body = self.comment(_manifest({"id": "d", "refused": "shape | number"}))
        self.assertIn("shape \\| number", body)

    def test_a_design_that_disappeared_is_listed(self):
        body = self.comment(_manifest(_drawn("a", "1")), _manifest(_drawn("a", "1"), _drawn("b", "2")))

        self.assertIn("### No longer rendered", body)
        self.assertIn("- `b`", body)

    def test_an_empty_head_says_nothing_changed_rather_than_going_blank(self):
        body = self.comment(_manifest(), _manifest(_drawn("a", "1")))

        self.assertIn("No design changed in this pull request", body)
        # And does not claim the baseline's design went away: nothing was rendered because nothing
        # was touched, which is not the same as a design being removed.
        self.assertNotIn("No longer rendered", body)

    def test_the_changed_table_falls_back_to_the_head_branch_without_a_baseline_branch(self):
        body = design_renders.build_comment(
            head=_manifest(_drawn("a", "after")),
            baseline=_manifest(_drawn("a", "before")),
            repo="o/r",
            head_branch="head",
            baseline_branch=None,
        )
        self.assertIn("raw.githubusercontent.com/o/r/head/a.png", body)


class RoundTripTest(unittest.TestCase):
    def test_a_manifest_written_to_disk_is_readable_by_the_comment(self):
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            (directory / "a.png").write_bytes(b"a")
            manifest = design_renders.build_manifest(directory)
            path = directory / "_designs.json"
            path.write_text(json.dumps(manifest), encoding="utf-8")

            reloaded = json.loads(path.read_text(encoding="utf-8"))
            body = design_renders.build_comment(
                head=reloaded, baseline=None, repo="o/r", head_branch="b", baseline_branch=None
            )

            self.assertIn("| `a` |", body)


if __name__ == "__main__":
    unittest.main()
