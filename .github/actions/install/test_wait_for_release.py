#!/usr/bin/env python3
"""Tests for the install action's CLI-release wait.

The window it covers (m3-catalog#584, 2026-10-12): the plugin was on Maven
Central, but the v2.53.0 release was still a draft, so its tarball 404'd at the
public URL for longer than curl's retries lasted. A 404 has to mean "keep
waiting", the wait has to stop at the shared deadline, and a release that is
already public must not wait at all.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import unittest
import urllib.error
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location(
    "wait_for_release", Path(__file__).with_name("wait-for-release.py")
)
assert _SPEC and _SPEC.loader
release = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(release)

VERSION = "2.53.0"


class _Response:
    status = 206

    def __enter__(self) -> "_Response":
        return self

    def __exit__(self, *exc: object) -> None:
        return None


class WaitTestCase(unittest.TestCase):
    """Fakes the clock and the asset URL; `answers` is consumed one probe at a time."""

    def setUp(self) -> None:
        self.clock = [1_000_000.0]
        self.answers: list[object] = []
        self.requests: list[object] = []
        self.naps: list[float] = []
        self._saved = (
            release.urllib.request.urlopen,
            release.time.sleep,
            release.time.time,
        )
        release.urllib.request.urlopen = self._fake_urlopen
        release.time.sleep = self._fake_sleep
        release.time.time = lambda: self.clock[0]

    def tearDown(self) -> None:
        (
            release.urllib.request.urlopen,
            release.time.sleep,
            release.time.time,
        ) = self._saved

    def _fake_sleep(self, seconds: float) -> None:
        self.naps.append(seconds)
        self.clock[0] += seconds

    def _fake_urlopen(self, req: object, timeout: float = 0) -> _Response:
        self.requests.append(req)
        answer = self.answers.pop(0) if self.answers else 404
        url = getattr(req, "full_url", "")
        if isinstance(answer, int):
            raise urllib.error.HTTPError(url, answer, "nope", {}, None)  # type: ignore[arg-type]
        if isinstance(answer, BaseException):
            raise answer
        return _Response()

    def run_wait(self, budget: float) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = release.run(VERSION, self.clock[0] + budget)
        return code, out.getvalue(), err.getvalue()


class WaitTests(WaitTestCase):
    def test_a_public_release_answers_on_the_first_probe(self) -> None:
        self.answers = ["ok"]
        code, out, _ = self.run_wait(600)
        self.assertEqual(0, code)
        self.assertIn("is public", out)
        self.assertEqual([], self.naps)

    def test_probes_the_exact_url_curl_downloads(self) -> None:
        self.answers = ["ok"]
        self.run_wait(600)
        req = self.requests[0]
        self.assertEqual(
            "https://github.com/yschimke/compose-ai-tools/releases/download/"
            f"v{VERSION}/compose-preview-{VERSION}.tar.gz",
            req.full_url,  # type: ignore[attr-defined]
        )
        self.assertEqual("bytes=0-0", req.get_header("Range"))  # type: ignore[attr-defined]

    def test_a_draft_release_is_waited_out(self) -> None:
        # Draft assets 404 at the public URL; the release goes public a little later.
        self.answers = [404, 404, 404, "ok"]
        code, out, _ = self.run_wait(600)
        self.assertEqual(0, code)
        self.assertIn("not public yet", out)
        self.assertEqual([2, 4, 8], self.naps)

    def test_backoff_is_capped(self) -> None:
        self.answers = [404] * 6 + ["ok"]
        code, _, _ = self.run_wait(600)
        self.assertEqual(0, code)
        self.assertEqual([2, 4, 8, 15, 15, 15], self.naps)

    def test_a_transient_failure_is_retried_not_fatal(self) -> None:
        self.answers = [503, ConnectionResetError("reset"), "ok"]
        code, out, _ = self.run_wait(600)
        self.assertEqual(0, code)
        self.assertIn("did not answer", out)

    def test_the_budget_bounds_the_wait_and_the_error_names_the_window(self) -> None:
        code, _, err = self.run_wait(30)
        self.assertEqual(1, code)
        self.assertIn("compose-preview release not public yet", err)
        self.assertIn("publication window", err)
        self.assertLessEqual(sum(self.naps), 30)

    def test_a_spent_budget_fails_without_probing(self) -> None:
        # plugin-readiness-timeout: 0 (or a budget the plugin wait already used up): no
        # waiting, so the download fails as soon as curl's own retries do.
        code, _, err = self.run_wait(0)
        self.assertEqual(1, code)
        self.assertEqual([], self.requests)
        self.assertIn("not public yet", err)


class MainTests(unittest.TestCase):
    def _main(self, env: dict[str, str]) -> int:
        saved = dict(release.os.environ)
        release.os.environ.clear()
        release.os.environ.update(env)
        try:
            with contextlib.redirect_stderr(io.StringIO()):
                release.main()
        except SystemExit as exc:
            return int(exc.code or 0)
        finally:
            release.os.environ.clear()
            release.os.environ.update(saved)
        return 0

    def test_refuses_a_non_release_version(self) -> None:
        self.assertEqual(1, self._main({"VER": "../evil", "DEADLINE": "1"}))

    def test_refuses_a_missing_deadline(self) -> None:
        self.assertEqual(1, self._main({"VER": VERSION, "DEADLINE": ""}))


if __name__ == "__main__":
    unittest.main(verbosity=2)
