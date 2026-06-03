#!/usr/bin/env bash
# One-shot driver for the VS Code extension's external-consumer e2e
# (Confetti) in a locked-down cloud sandbox — the Claude Code on the web
# "custom" network mode is the motivating environment. It provisions
# everything `vscode-extension-e2e-external.yml` assumes a GitHub-hosted
# runner already has, working around three sandbox constraints:
#
#   1. The egress allowlist 403s the Microsoft VS Code download hosts, so
#      @vscode/test-electron can't fetch stock VS Code. We provision
#      VSCodium from GitHub and point the harness at it via
#      VSCODE_TEST_EXECUTABLE (honoured by runTest.ts).
#   2. The container ships only JDK 21, but the plugin build pins toolchain
#      17 and Confetti's `:proto` needs a 17 toolchain; foojay
#      auto-provisioning is blocked. We install Temurin 17 from Adoptium
#      GitHub and symlink it into /usr/lib/jvm so Gradle auto-detects it.
#   3. A freshly-downloaded Temurin doesn't trust the sandbox's
#      TLS-intercepting proxy CA, breaking Java-side HTTPS. setup-cloud-jdk.sh
#      copies the system trust store over Temurin's.
#
# JDK topology used here:
#   - publishToMavenLocal builds under JDK 17 (repo's pinned toolchain).
#   - the e2e itself runs with the system JDK 21 as the daemon/render JVM
#     (Confetti androidApp is compileSdk=36 -> Robolectric SDK 36 requires
#     JDK 21), while the JDK-17 symlink keeps toolchain-17 modules resolvable.
#
# Usage:
#   vscode-extension/scripts/run-confetti-e2e.sh
#
# Env (override defaults):
#   SKIP_PUBLISH=1     skip publishToMavenLocal (artifacts already present)
#   E2E_LOG=<path>     log file (default: /tmp/confetti-e2e.log)

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_root"

JDK17="${JDK17:-/opt/jdk17}"
ANDROID_SDK="${ANDROID_HOME:-/opt/android-sdk}"
E2E_LOG="${E2E_LOG:-/tmp/confetti-e2e.log}"
WORKSPACE="${COMPOSE_PREVIEW_E2E_WORKSPACE:-/tmp/compose-preview-external-e2e}"

echo "==> [1/6] provisioning JDK 17 (Temurin + proxy-trusting cacerts + /usr/lib/jvm symlink)"
JDK17="$(scripts/setup-cloud-jdk.sh "$JDK17")"

echo "==> [2/6] ensuring Android SDK at $ANDROID_SDK"
if [[ ! -d "$ANDROID_SDK/platforms" ]]; then
  # install.sh --android-sdk pulls cmdline-tools + platform + build-tools
  # from dl.google.com (allowlisted). JDK 17 is already in place above, so
  # its JDK step is a no-op.
  ANDROID_HOME="$ANDROID_SDK" JAVA_HOME="$JDK17" scripts/install.sh --android-sdk
fi

echo "==> [3/6] provisioning VSCodium"
VSCODIUM="$(vscode-extension/scripts/setup-vscodium.sh)"

if [[ "${SKIP_PUBLISH:-}" != "1" ]]; then
  echo "==> [4/6] publishToMavenLocal under JDK 17"
  JAVA_HOME="$JDK17" PATH="$JDK17/bin:$PATH" ANDROID_HOME="$ANDROID_SDK" \
    ./gradlew publishToMavenLocal --no-daemon
  JAVA_HOME="$JDK17" PATH="$JDK17/bin:$PATH" ANDROID_HOME="$ANDROID_SDK" \
    ./gradlew -p gradle-plugin publishToMavenLocal --no-daemon
else
  echo "==> [4/6] skipping publishToMavenLocal (SKIP_PUBLISH=1)"
fi

echo "==> [5/6] preparing Confetti workspace at $WORKSPACE"
ANDROID_SDK_DIR="$ANDROID_SDK" vscode-extension/scripts/setup-external-e2e.sh "$WORKSPACE" >/dev/null

echo "==> [6/6] running the external-consumer e2e (VSCodium + xvfb, system JDK 21 daemon)"
# Stop any stale Confetti Gradle daemon so a prior run's JVM choice can't
# poison toolchain detection.
( cd "$WORKSPACE" && ./gradlew --stop >/dev/null 2>&1 || true )
cd "$repo_root/vscode-extension"
# `java` resolves to the system JDK 21 (daemon/render JVM); JDK 17 is found
# via the /usr/lib/jvm symlink for toolchain-17 modules.
env -u JAVA_HOME \
  PATH="/opt/node22/bin:/usr/bin:/bin" \
  ANDROID_HOME="$ANDROID_SDK" ANDROID_SDK_ROOT="$ANDROID_SDK" \
  VSCODE_TEST_EXECUTABLE="$VSCODIUM" \
  COMPOSE_PREVIEW_E2E_WORKSPACE="$WORKSPACE" \
  xvfb-run -a npm run test:e2e-external 2>&1 | tee "$E2E_LOG"
