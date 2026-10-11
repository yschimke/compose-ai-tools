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

# Ensure the JDKs this build needs exist before warming Gradle: the daemon
# toolchain the repo pins (`toolchainVersion` in
# gradle/gradle-daemon-jvm.properties) and the 21/25 render JVMs. Cloud
# containers commonly ship only some of them, and on the allowlist network
# policy foojay auto-provisioning is blocked — so `./gradlew` fails with
# "Unable to download toolchain matching {languageVersion=N}" until one is on
# disk. scripts/setup-cloud-jdk.sh installs Temurin from Adoptium GitHub, fixes
# its trust store for the sandbox's MITM proxy CA, and symlinks it into
# /usr/lib/jvm so Gradle auto-detects it. It reuses any JDK already on the box
# (coo.ee/env's, the image's), and `--check` asks it, without touching the
# network, whether anything is missing at all. Best-effort: a failure here must
# not abort the session (the warm below is also tolerant).
if [ -x scripts/setup-cloud-jdk.sh ]; then
  if missing="$(scripts/setup-cloud-jdk.sh --check)"; then
    :
  else
    echo "[session-start] JDK majors missing: ${missing}; provisioning" >&2
    scripts/setup-cloud-jdk.sh >/dev/null || \
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
