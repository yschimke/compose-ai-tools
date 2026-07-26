#!/usr/bin/env python3
"""Unit tests for daemon-roundtrip.py's JSON-RPC transport.

Pure stdlib (unittest). Run: python3 .github/ci/test_daemon_roundtrip.py -v

The cases pin the distinction that cost a CI misdiagnosis: a daemon that is
alive but still booting its Robolectric sandbox pool must surface as a
TimeoutError naming the method and the budget, NOT as "daemon stream closed".
Only a real EOF on the daemon's stdout is a closed stream.
"""

import importlib.util
import json
import os
import re
import subprocess
import sys
import unittest
from pathlib import Path

_HERE = Path(__file__).resolve().parent

# Load daemon-roundtrip.py as a module (hyphenated filename → importlib).
_spec = importlib.util.spec_from_file_location(
    "daemon_roundtrip", _HERE / "daemon-roundtrip.py"
)
mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mod)


class FakeStdin:
    """Collects the frames the driver writes, so requests don't need a JVM."""

    def __init__(self):
        self.written = bytearray()

    def write(self, data):
        self.written.extend(data)

    def flush(self):
        pass


class FakeProc:
    """Minimal subprocess.Popen stand-in: stdin sink + a settable exit code."""

    def __init__(self, exit_code=None):
        self.stdin = FakeStdin()
        self._exit_code = exit_code

    def poll(self):
        return self._exit_code


class _PipeFixture(unittest.TestCase):
    """Wires a DaemonDriver to an os.pipe we can feed (or close) by hand."""

    def setUp(self):
        read_fd, self.write_fd = os.pipe()
        self.read_end = os.fdopen(read_fd, "rb", buffering=0)
        self.addCleanup(self.read_end.close)
        self.addCleanup(self._close_write)
        self.driver = mod.DaemonDriver({}, Path("."))
        self.driver._proc = FakeProc()
        self.driver._reader = mod.FramedReader(self.read_end)

    def _close_write(self):
        try:
            os.close(self.write_fd)
        except OSError:
            pass

    def feed(self, payload):
        body = json.dumps(payload).encode("utf-8")
        os.write(self.write_fd, f"Content-Length: {len(body)}\r\n\r\n".encode("ascii"))
        os.write(self.write_fd, body)


class FramedReaderEofVsTimeout(_PipeFixture):
    def test_timeout_does_not_set_eof(self):
        reader = self.driver._reader
        self.assertIsNone(reader.read_message(timeout_s=0.05))
        self.assertFalse(reader.at_eof, "a silent-but-open stream is not EOF")

    def test_close_sets_eof(self):
        self._close_write()
        reader = self.driver._reader
        self.assertIsNone(reader.read_message(timeout_s=5.0))
        self.assertTrue(reader.at_eof)

    def test_message_split_across_writes(self):
        body = json.dumps({"jsonrpc": "2.0", "id": 1, "result": {"ok": True}}).encode()
        os.write(self.write_fd, f"Content-Length: {len(body)}\r\n\r\n".encode("ascii"))
        os.write(self.write_fd, body[:5])
        os.write(self.write_fd, body[5:])
        msg = self.driver._reader.read_message(timeout_s=5.0)
        self.assertEqual(msg["result"], {"ok": True})


class RequestErrors(_PipeFixture):
    def test_slow_daemon_reports_timeout_not_closed_stream(self):
        """The regression: initialize outliving its budget on a live daemon."""
        with self.assertRaises(TimeoutError) as ctx:
            self.driver.request("initialize", {}, timeout_s=0.2)
        self.assertIn("initialize", str(ctx.exception))
        self.assertNotIn("stream closed", str(ctx.exception))

    def test_dead_daemon_reports_closed_stream_with_exit_code(self):
        self.driver._proc = FakeProc(exit_code=1)
        self._close_write()
        with self.assertRaises(RuntimeError) as ctx:
            self.driver.request("initialize", {}, timeout_s=30.0)
        message = str(ctx.exception)
        self.assertIn("stream closed", message)
        self.assertIn("exited with code 1", message)

    def test_response_returns_result(self):
        self.feed({"jsonrpc": "2.0", "id": 1, "result": {"protocolVersion": 2}})
        self.assertEqual(
            self.driver.request("initialize", {}, timeout_s=5.0),
            {"protocolVersion": 2},
        )

    def test_notifications_are_stashed_not_dropped(self):
        self.feed({"jsonrpc": "2.0", "method": "daemonReady"})
        self.feed({"jsonrpc": "2.0", "id": 1, "result": {}})
        self.driver.request("initialize", {}, timeout_s=5.0)
        self.assertEqual(
            [m.get("method") for m in self.driver._pending_notifications],
            ["daemonReady"],
        )


class CollectNotifications(_PipeFixture):
    def test_timeout_reports_shortfall_not_closed_stream(self):
        self.feed({"jsonrpc": "2.0", "method": "renderFinished", "params": {}})
        with self.assertRaises(TimeoutError) as ctx:
            self.driver.collect_notifications({"renderFinished"}, expected=2, timeout_s=0.2)
        self.assertIn("only saw 1/2", str(ctx.exception))

    def test_eof_reports_closed_stream(self):
        self._close_write()
        with self.assertRaises(RuntimeError) as ctx:
            self.driver.collect_notifications({"renderFinished"}, expected=1, timeout_s=30.0)
        self.assertIn("closed mid-collect", str(ctx.exception))


class InitTimeoutDefault(unittest.TestCase):
    """Client patience must be >= the daemon's own sandbox-boot budget.

    The daemon defaults `composeai.daemon.sandboxBootTimeoutMs` to 10 minutes
    and refuses to answer `initialize` until the pool is up, so anything below
    that here turns a slow-but-healthy cold boot into a red CI leg.
    """

    def test_flag_default_is_at_least_the_ten_minute_boot_budget(self):
        help_text = subprocess.run(
            [sys.executable, str(_HERE / "daemon-roundtrip.py"), "--help"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout
        self.assertIn("--init-timeout-s", help_text)
        match = re.search(r"--init-timeout-s INIT_TIMEOUT_S\s+.*?\(default: ([\d.]+)\)", help_text, re.S)
        if match is None:
            # argparse only prints the default when the help string asks for
            # it; fall back to parsing the source constant.
            match = re.search(r'"--init-timeout-s",\s*\n\s*type=float,\s*\n\s*default=([\d.]+)',
                              (_HERE / "daemon-roundtrip.py").read_text())
        self.assertIsNotNone(match, "could not determine the --init-timeout-s default")
        self.assertGreaterEqual(float(match.group(1)), 600.0)


if __name__ == "__main__":
    unittest.main()
