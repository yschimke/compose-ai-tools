#!/usr/bin/env python3
"""Tests for the `xr-composite` pin drift gate.

The gate's whole value is that it fires on the one case that otherwise degrades silently — a
compositor change with an unmoved pin — and stays quiet on everything else. Both halves are worth
pinning: a gate that never fires is the bug it exists to prevent, and one that fires on a README
typo gets disabled.
"""

from __future__ import annotations

import unittest

import check_xr_composite_pin as gate


class PinParsing(unittest.TestCase):
    def test_reads_the_pin(self):
        self.assertEqual(gate.pin_from('xr-composite = "1.47.0"\n'), "1.47.0")

    def test_reads_the_pin_among_neighbours(self):
        catalog = (
            'xr-arcore-testing = "1.0.0-beta02"\n'
            "# a comment mentioning xr-composite\n"
            'xr-composite = "2.0.1"\n'
            'xr-glimmer = "1.0.0-alpha18"\n'
        )
        self.assertEqual(gate.pin_from(catalog), "2.0.1")

    def test_absent_pin_is_none(self):
        self.assertIsNone(gate.pin_from('xr-compose = "1.0.0-alpha17"\n'))

    def test_does_not_match_a_library_coordinate(self):
        # `[libraries]` entries also start with a name; only a bare version assignment counts.
        self.assertIsNone(
            gate.pin_from(
                'xr-composite = { module = "x:y", version.ref = "xr-composite" }\n'
            )
        )


class ShippingChanges(unittest.TestCase):
    def test_source_and_build_files_ship(self):
        self.assertEqual(
            gate.shipping_changes(
                [
                    "renderers/xr-composite/src/main.cpp",
                    "renderers/xr-composite/CMakeLists.txt",
                    "renderers/xr-composite/materials/unlit_texture.mat",
                    "renderers/xr-composite/build.sh",
                ]
            ),
            [
                "renderers/xr-composite/src/main.cpp",
                "renderers/xr-composite/CMakeLists.txt",
                "renderers/xr-composite/materials/unlit_texture.mat",
                "renderers/xr-composite/build.sh",
            ],
        )

    def test_docs_and_tests_do_not_ship(self):
        self.assertEqual(
            gate.shipping_changes(
                [
                    "renderers/xr-composite/README.md",
                    "renderers/xr-composite/test/serve_smoke.py",
                    "renderers/xr-composite/test/models/PROVENANCE.md",
                ]
            ),
            [],
        )

    def test_ignores_paths_outside_the_tree(self):
        self.assertEqual(
            gate.shipping_changes(
                ["renderers/xr/src/main/kotlin/X.kt", "cli/build.gradle.kts"]
            ),
            [],
        )

    def test_a_shipping_change_alongside_docs_still_counts(self):
        self.assertEqual(
            gate.shipping_changes(
                [
                    "renderers/xr-composite/README.md",
                    "renderers/xr-composite/src/spatial_scene.hpp",
                ]
            ),
            ["renderers/xr-composite/src/spatial_scene.hpp"],
        )


class PinMoved(unittest.TestCase):
    def test_an_unmoved_pin_is_the_failure_case(self):
        self.assertFalse(gate.pin_moved("1.47.0", "1.47.0"))

    def test_a_bumped_pin_passes(self):
        self.assertTrue(gate.pin_moved("1.47.0", "1.48.0"))

    def test_a_pin_absent_at_the_base_counts_as_moved(self):
        # Only the PR that introduces the entry sees this; it must not be asked to bump a pin no
        # consumer was reading yet. Every later diff compares two real values.
        self.assertTrue(gate.pin_moved(None, "1.47.0"))


class OverrideReason(unittest.TestCase):
    def test_a_stated_reason_is_extracted(self):
        body = "Some summary.\n\nXR-Release: none - literals replaced by equal constants\n"
        self.assertEqual(
            gate.override_reason(body), "literals replaced by equal constants"
        )

    def test_an_em_dash_or_colon_separator_works(self):
        self.assertEqual(gate.override_reason("XR-Release: none \u2014 pure rename"), "pure rename")
        self.assertEqual(gate.override_reason("XR-Release: none: pure rename"), "pure rename")

    def test_a_bare_opt_out_without_a_reason_does_not_match(self):
        # The whole point of the escape hatch is that it costs a sentence someone must justify.
        self.assertIsNone(gate.override_reason("XR-Release: none"))
        self.assertIsNone(gate.override_reason("XR-Release: none -   "))

    def test_unrelated_bodies_do_not_match(self):
        self.assertIsNone(gate.override_reason(""))
        self.assertIsNone(gate.override_reason(None))
        self.assertIsNone(gate.override_reason("We should cut an xr release for this."))

    def test_case_insensitive_and_indented(self):
        self.assertEqual(gate.override_reason("  xr-release: NONE - regen only"), "regen only")


class RepositoryState(unittest.TestCase):
    def test_the_checked_in_catalog_declares_a_pin(self):
        # The gate, the CLI resource and the plugin resource all read this one entry; losing it
        # turns three call sites into build errors and this gate into a no-op.
        self.assertRegex(gate.current_pin(), r"^\d+\.\d+\.\d+")


if __name__ == "__main__":
    unittest.main(verbosity=2)
