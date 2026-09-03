#!/usr/bin/env python3
"""Tests for the install action's plugin-readiness guard.

The states it has to keep apart are the whole point (issue #5051): a definite
404 means "not published yet, wait"; a 429 from Central means "no verdict",
which must never be reported as an unpublished release *or* allowed to mask a
neighbour's definite absence.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import unittest
import urllib.error
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location(
    "plugin_readiness", Path(__file__).with_name("plugin-readiness.py")
)
assert _SPEC and _SPEC.loader
readiness = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(readiness)

VERSION = "1.69.0"

MARKER_POM = f"""<project>
  <groupId>ee.schimke.composeai.preview</groupId>
  <artifactId>ee.schimke.composeai.preview.gradle.plugin</artifactId>
  <version>{VERSION}</version>
  <packaging>pom</packaging>
  <dependencies>
    <dependency>
      <groupId>ee.schimke.composeai</groupId>
      <artifactId>compose-preview-plugin</artifactId>
      <version>{VERSION}</version>
    </dependency>
  </dependencies>
</project>
"""

IMPL_POM = f"""<project>
  <groupId>ee.schimke.composeai</groupId>
  <artifactId>compose-preview-plugin</artifactId>
  <version>{VERSION}</version>
  <dependencies>
    <dependency>
      <groupId>ee.schimke.composeai</groupId>
      <artifactId>preview-discovery</artifactId>
      <version>{VERSION}</version>
      <scope>compile</scope>
    </dependency>
    <dependency>
      <groupId>com.squareup.okhttp3</groupId>
      <artifactId>okhttp</artifactId>
      <version>4.12.0</version>
    </dependency>
    <dependency>
      <groupId>ee.schimke.composeai</groupId>
      <artifactId>preview-data-api</artifactId>
      <version>1.0.0</version>
    </dependency>
  </dependencies>
</project>
"""

LEAF_POM = f"""<project>
  <groupId>ee.schimke.composeai</groupId>
  <artifactId>preview-discovery</artifactId>
  <version>{VERSION}</version>
</project>
"""


class _Response:
    def __init__(self, body: str) -> None:
        self._body = body.encode()

    def read(self) -> bytes:
        return self._body

    def __enter__(self) -> "_Response":
        return self

    def __exit__(self, *exc: object) -> None:
        return None


class ReadinessTestCase(unittest.TestCase):
    """Serves a fake Maven mirror; `routes` maps a URL suffix to a body or a status."""

    def setUp(self) -> None:
        self.requested: list[str] = []
        self.routes: dict[str, object] = {}
        self._urlopen = readiness.urllib.request.urlopen
        self._sleep = readiness.time.sleep
        readiness.urllib.request.urlopen = self._fake_urlopen
        readiness.time.sleep = lambda _seconds: None

    def tearDown(self) -> None:
        readiness.urllib.request.urlopen = self._urlopen
        readiness.time.sleep = self._sleep

    def _fake_urlopen(self, req: object, timeout: float = 0) -> _Response:
        url = getattr(req, "full_url", "")
        self.requested.append(url)
        for suffix, answer in self.routes.items():
            if url.endswith(suffix):
                if isinstance(answer, int):
                    raise urllib.error.HTTPError(url, answer, "nope", {}, None)  # type: ignore[arg-type]
                return _Response(str(answer))
        raise urllib.error.HTTPError(url, 404, "not found", {}, None)  # type: ignore[arg-type]

    def serve_everything(self) -> None:
        self.routes = {
            f"{VERSION}/ee.schimke.composeai.preview.gradle.plugin-{VERSION}.pom": MARKER_POM,
            f"compose-preview-plugin/{VERSION}/compose-preview-plugin-{VERSION}.pom": IMPL_POM,
            f"compose-preview-plugin/{VERSION}/compose-preview-plugin-{VERSION}.jar": "jar",
            f"preview-discovery/{VERSION}/preview-discovery-{VERSION}.pom": LEAF_POM,
            f"preview-discovery/{VERSION}/preview-discovery-{VERSION}.jar": "jar",
        }

    def run_guard(self, budget: float = 0) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = readiness.run(VERSION, budget)
        return code, out.getvalue(), err.getvalue()


class ArtifactSetTests(ReadinessTestCase):
    def test_a_fully_propagated_release_passes(self) -> None:
        self.serve_everything()
        code, out, _ = self.run_guard()
        self.assertEqual(0, code)
        self.assertIn("resolvable", out)

    def test_the_marker_alone_is_not_enough(self) -> None:
        # The marker is a stub whose only content is a dependency on the implementation, in
        # another group; a 200 on it says nothing about the classpath the buildscript loads.
        self.serve_everything()
        del self.routes[
            f"compose-preview-plugin/{VERSION}/compose-preview-plugin-{VERSION}.jar"
        ]
        code, _, err = self.run_guard()
        self.assertEqual(1, code)
        self.assertIn("not resolvable", err)

    def test_a_lagging_same_version_sibling_is_caught(self) -> None:
        # preview-discovery is an `api` dependency published by the same release, so it
        # propagates on its own schedule and strands the configuration when it lags.
        self.serve_everything()
        del self.routes[f"preview-discovery/{VERSION}/preview-discovery-{VERSION}.jar"]
        code, _, err = self.run_guard()
        self.assertEqual(1, code)
        self.assertIn("not resolvable", err)

    def test_third_party_and_older_dependencies_are_not_walked(self) -> None:
        self.serve_everything()
        code, _, _ = self.run_guard()
        self.assertEqual(0, code)
        self.assertFalse(
            [u for u in self.requested if "okhttp" in u],
            "a dependency outside this release's own coordinates was probed",
        )
        self.assertFalse(
            [u for u in self.requested if "preview-data-api" in u],
            "a dependency pinned to another version was probed",
        )

    def test_dependency_scan_reads_group_artifact_and_version(self) -> None:
        self.assertEqual(
            [("ee.schimke.composeai", "preview-discovery")],
            readiness.same_version_dependencies(IMPL_POM, VERSION),
        )


class VerdictTests(ReadinessTestCase):
    def test_a_rate_limited_registry_is_not_an_unpublished_release(self) -> None:
        self.routes = {".pom": 429, ".jar": 429}
        code, _, err = self.run_guard()
        self.assertEqual(0, code)
        self.assertIn("could not get a verdict", err)

    def test_an_absent_artifact_is_not_masked_by_an_inconclusive_one(self) -> None:
        # Marker 404 on both mirrors, implementation rate-limited: the definite absence wins, so
        # the run waits and then fails rather than passing on the neighbour's non-answer.
        self.serve_everything()
        del self.routes[
            f"{VERSION}/ee.schimke.composeai.preview.gradle.plugin-{VERSION}.pom"
        ]
        self.routes[f"compose-preview-plugin-{VERSION}.jar"] = 503
        code, _, err = self.run_guard()
        self.assertEqual(1, code)
        self.assertIn("not resolvable", err)

    def test_both_mirrors_are_tried_before_a_no_verdict(self) -> None:
        # A rate-limited Plugin Portal must not hide a 200 on Central.
        original = self._fake_urlopen

        def portal_is_rate_limited(req: object, timeout: float = 0) -> _Response:
            url = getattr(req, "full_url", "")
            if url.startswith("https://plugins.gradle.org"):
                self.requested.append(url)
                raise urllib.error.HTTPError(url, 429, "slow down", {}, None)  # type: ignore[arg-type]
            return original(req, timeout)

        self.serve_everything()
        readiness.urllib.request.urlopen = portal_is_rate_limited
        code, out, _ = self.run_guard()
        self.assertEqual(0, code)
        self.assertIn("resolvable", out)


class BudgetTests(ReadinessTestCase):
    def test_a_spent_budget_stops_probing_rather_than_passing(self) -> None:
        # One sweep always happens — a guard that never checks is worse than a late answer — but
        # once the budget is gone the run fails instead of retrying, and "out of time" must never
        # come back as the no-verdict warning-pass.
        clock = [1000.0]
        readiness.time.monotonic = lambda: clock[0]
        sweeps: list[int] = []

        def slow(req: object, timeout: float = 0) -> _Response:
            sweeps.append(1)
            clock[0] += timeout  # the request spends everything it was allowed
            raise urllib.error.HTTPError(
                getattr(req, "full_url", ""), 404, "not found", {}, None
            )  # type: ignore[arg-type]

        readiness.urllib.request.urlopen = slow
        try:
            code, _, err = self.run_guard(budget=1)
        finally:
            readiness.time.monotonic = __import__("time").monotonic
        self.assertEqual(1, code)
        self.assertIn("not resolvable", err)
        # Two probes: one per mirror, on the single sweep the budget allowed.
        self.assertEqual(2, len(sweeps), "the spent budget did not stop the sweeps")

    def test_later_requests_are_bounded_by_what_is_left(self) -> None:
        deadline = readiness.time.monotonic() + 3
        first = readiness._request_timeout(deadline, minimum=True)
        later = readiness._request_timeout(deadline, minimum=False)
        self.assertGreaterEqual(first, readiness.MIN_FIRST_REQUEST_SECONDS)
        self.assertLessEqual(later, 3.0)
        self.assertEqual(
            0.0, readiness._request_timeout(readiness.time.monotonic() - 1, minimum=False)
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
