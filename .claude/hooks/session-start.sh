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

# Warm Gradle. `help` is the cheapest task that still triggers:
#   - wrapper distribution download (~150MB on cold)
#   - toolchain JDK auto-provision via foojay (`gradle/gradle-daemon-jvm.properties`
#     pins Java 17; the harness JDK is already on PATH so this is a no-op)
#   - plugin classpath resolution into `~/.gradle/caches/modules-2/`
# Stderr goes to the hook log so timing is visible; stdout stays clean
# (gradle's stdout is whitespace under `--quiet`).
./gradlew --quiet help >&2
