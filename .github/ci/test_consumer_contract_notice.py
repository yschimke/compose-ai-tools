"""Tests for consumer-contract-notice.py.

Two kinds. The first are ordinary: matching, reporting, the sentinel. The second
matter more — they assert the surface map still describes THIS repository. A
notice that names a path which no longer exists is worse than no notice: it reads
as authoritative and is silently covering nothing.

    python3 .github/ci/test_consumer_contract_notice.py
"""

import importlib.util
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent

_spec = importlib.util.spec_from_file_location(
    "consumer_contract_notice", HERE / "consumer-contract-notice.py"
)
mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mod)


class Matching(unittest.TestCase):
    def test_double_star_spans_directories(self):
        self.assertTrue(mod.matches("daemon/protocol/src/main/kotlin/a/b/C.kt", "daemon/protocol/src/main/**"))

    def test_double_star_does_not_leak_to_siblings(self):
        self.assertFalse(mod.matches("daemon/protocol/src/test/X.kt", "daemon/protocol/src/main/**"))

    def test_exact_file_pattern(self):
        self.assertTrue(mod.matches("schema/spatial-scene.schema.json", "schema/spatial-scene.schema.json"))
        self.assertFalse(mod.matches("schema/other.schema.json", "schema/spatial-scene.schema.json"))

    def test_single_star_is_one_segment(self):
        self.assertTrue(mod.matches("docs/daemon/protocol-fixtures/a.json", "docs/daemon/protocol-fixtures/*"))


class Reporting(unittest.TestCase):
    def test_unrelated_change_reports_the_sentinel(self):
        self.assertEqual(mod.affected(["README.md", "cli/src/Main.kt"]), [])

    def test_fixture_change_names_both_consumers(self):
        hits = mod.affected(["docs/daemon/protocol-fixtures/client-initialize.json"])
        self.assertEqual(len(hits), 1)
        body = mod.report(hits)
        self.assertIn("compose-preview-vscode", body)
        self.assertIn("compose-preview-contracts", body)

    def test_report_carries_the_sticky_marker_first(self):
        body = mod.report(mod.affected(["schema/spatial-scene.schema.json"]))
        self.assertTrue(body.startswith("<!-- consumer-contract-notice -->"))

    def test_report_says_it_does_not_block(self):
        # The whole design rests on this; if the wording is lost, a reader will
        # reasonably assume the notice is a gate and wait for it to go green.
        body = mod.report(mod.affected(["schema/spatial-scene.schema.json"]))
        self.assertIn("blocks the merge", body)

    def test_one_change_can_hit_several_surfaces(self):
        hits = mod.affected([
            "docs/daemon/protocol-fixtures/client-initialize.json",
            "schema/spatial-scene.schema.json",
        ])
        self.assertEqual(len(hits), 2)

    def test_long_path_lists_are_truncated(self):
        many = [f"docs/daemon/protocol-fixtures/f{i}.json" for i in range(25)]
        body = mod.report(mod.affected(many))
        self.assertIn("…and 15 more", body)


class SurfaceMapIsCurrent(unittest.TestCase):
    """Every path in the map must still exist, or the notice covers nothing."""

    def test_every_pattern_resolves_to_something_in_the_tree(self):
        missing = []
        for surface in mod.SURFACES:
            for pattern in surface["patterns"]:
                if pattern.endswith("/**"):
                    ok = (REPO / pattern[:-3]).is_dir()
                elif pattern.endswith("/*"):
                    ok = (REPO / pattern[:-2]).is_dir()
                else:
                    ok = (REPO / pattern).exists()
                if not ok:
                    missing.append(f"{surface['name']}: {pattern}")
        self.assertEqual(missing, [], f"surface map names paths that no longer exist: {missing}")

    def test_every_surface_names_at_least_one_consumer(self):
        for surface in mod.SURFACES:
            self.assertTrue(surface["consumers"], f"{surface['name']} names no consumer")

    def test_consumer_repos_are_the_known_two(self):
        known = {mod.EXT, mod.CONTRACTS}
        for surface in mod.SURFACES:
            self.assertLessEqual(set(surface["consumers"]), known, surface["name"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
