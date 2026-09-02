#!/usr/bin/env python3

from __future__ import annotations

import contextlib
import io
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_xr_renderer_pin as gate  # noqa: E402


class XrRendererPinTest(unittest.TestCase):
    def test_reads_pin(self) -> None:
        self.assertEqual("2.0.0", gate.pin_from('xr-renderer = "2.0.0"'))

    def test_rejects_snapshot_offline(self) -> None:
        with mock.patch.object(gate, "current_pin", return_value="2.0.0-SNAPSHOT"):
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, gate.main(["--offline"]))

    def test_missing_artifact_fails(self) -> None:
        with (
            mock.patch.object(gate, "current_pin", return_value="2.0.0"),
            mock.patch.object(gate, "artifact_exists", return_value=False),
            contextlib.redirect_stderr(io.StringIO()),
        ):
            self.assertEqual(1, gate.main([]))

    def test_transport_failure_warns(self) -> None:
        with (
            mock.patch.object(gate, "current_pin", return_value="2.0.0"),
            mock.patch.object(gate, "artifact_exists", side_effect=gate.Unanswered("offline")),
            contextlib.redirect_stderr(io.StringIO()),
        ):
            self.assertEqual(0, gate.main([]))


if __name__ == "__main__":
    unittest.main()

