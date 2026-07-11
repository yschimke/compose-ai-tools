#!/usr/bin/env bash
# SessionStart hook for compose-ai-tools.
#
# Always: point this clone's `core.hooksPath` at `.githooks/` so the ktfmt
# pre-commit guard runs on commits. Cheap, idempotent.
#
# Web-only (CLAUDE_CODE_REMOTE=true): pre-warm Gradle so the first `./gradlew`
# invocation inside the session doesn't cold-download the 150MB distribution
# and resolve every plugin from Maven Central. Without this warm-up, running
# `ktfmtCheckAll` or any `:test` task in a fresh container costs ~1m of setup
# before the first useful byte; with it, those tasks start in seconds because
# `~/.gradle/wrapper/dists/`, the toolchain JDK, and the plugin classpath are
# already populated.
#
# Idempotency: re-running the hook on a warm container is a no-op modulo a
# few seconds for Gradle to confirm the daemon is up. Safe to call from
# `startup`, `resume`, `clear`, or `compact` sources.

set -euo pipefail

cd "${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"

if [ -x scripts/install-git-hooks.sh ]; then
  scripts/install-git-hooks.sh >&2
fi

# Local sessions stop here — devs already have Gradle warm. The rest is
# web-session-only setup that costs nothing on warm containers but saves a
# real chunk of cold-start latency.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# Seed the Gradle wrapper distribution(s) from the allowlisted repo.gradle.org
# github-downloads-proxy BEFORE any `./gradlew` runs. The wrapper's
# distributionUrl (services.gradle.org) 307-redirects to a github.com release
# asset, which the "custom" cloud egress policy blocks (403) — so the warm below
# would otherwise die with `Server returned HTTP response code: 403` before the
# build starts. The proxy serves the identical, checksum-verified bytes and is an
# allowlisted Gradle host. Covers single- and multi-repo checkouts (every wrapper
# across the side-by-side clones). Best-effort: a failure leaves the download to
# Gradle and never aborts the session.
if [ -x scripts/seed-gradle-dist.sh ]; then
  scripts/seed-gradle-dist.sh >&2 || \
    echo "[session-start] seed-gradle-dist.sh failed; Gradle will fetch the distribution itself" >&2
fi

# Ensure a JDK 17 toolchain exists before warming Gradle. The build pins
# `toolchainVersion=17` (gradle/gradle-daemon-jvm.properties), but cloud
# containers commonly ship only JDK 21, and on the allowlist network policy
# foojay auto-provisioning is blocked — so `./gradlew` fails with "Unable to
# download toolchain matching {languageVersion=17}" and *no* Gradle task
# works until a 17 is placed on disk. scripts/setup-cloud-jdk.sh installs
# Temurin 17 from Adoptium GitHub, fixes its trust store for the sandbox's
# MITM proxy CA, and symlinks it into /usr/lib/jvm so Gradle auto-detects it.
# Best-effort: a failure here must not abort the session (the warm below is
# also tolerant), and it's skipped entirely when a 17 is already discoverable.
if ! ls -d /usr/lib/jvm/*17* /opt/jdk17 >/dev/null 2>&1; then
  if [ -x scripts/setup-cloud-jdk.sh ]; then
    scripts/setup-cloud-jdk.sh >&2 || \
      echo "[session-start] setup-cloud-jdk.sh failed; Gradle may not build" >&2
  fi
fi

# Warm Gradle. `help` is the cheapest task that still triggers:
#   - wrapper distribution download (~150MB on cold)
#   - daemon JVM resolution against the JDK 17 toolchain provisioned above
#   - plugin classpath resolution into `~/.gradle/caches/modules-2/`
# Stderr goes to the hook log so timing is visible; stdout stays clean
# (gradle's stdout is whitespace under `--quiet`). Tolerant: a warm failure
# is logged but never aborts the session.
./gradlew --quiet help >&2 || \
  echo "[session-start] gradle warm failed (non-fatal)" >&2
