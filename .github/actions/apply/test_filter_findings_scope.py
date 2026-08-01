#!/usr/bin/env python3

import importlib.util
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "filter_findings_scope", HERE / "filter-findings-scope.py"
)
mod = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod)


class FilterFindingsScopeTest(unittest.TestCase):
    def test_keeps_only_affected_modules(self):
        payload = {
            "entries": [
                {"module": "samples:android", "previewId": "A"},
                {"module": "samples:wear", "previewId": "B"},
            ]
        }
        self.assertEqual(
            mod.filter_payload(payload, {"samples:wear"})["entries"],
            [{"module": "samples:wear", "previewId": "B"}],
        )

    def test_scoped_a11y_status_uses_current_status(self):
        payload = {"status": "atf-unavailable", "entries": []}
        current = {"status": None, "entries": []}
        self.assertIsNone(mod.filter_payload(payload, set(), current)["status"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
