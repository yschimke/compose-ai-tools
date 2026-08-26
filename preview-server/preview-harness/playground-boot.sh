#!/usr/bin/env bash
# Boot a daemon-backed `compose-preview serve` with the **playground** lane enabled over a locally
# packed Android bundle, and print its base URL — the target for playground.spec.mjs (the browser
# e2e of the Kotlin playground: editor → compile → first-frame → /pg live redemption).
#
# Unlike the serve-lanes boots, this enables `--playground-android-bundle` (the compile classpath a
# snippet builds against) rather than serving the bundle as a catalog. The playground lane compiles
# and runs user-supplied code in-process, so it is **refused under --public** — this boots
# token-gated instead (SERVE_TOKEN). `--accept-docs` enables the `/d/` store the Remote Compose mode
# publishes into. The Android/Robolectric daemon sidecar (lib-daemon-android, shipped outside the CLI
# tarball, #1685) is wired via JAVA_OPTS, and `android.jar` comes from the runner's Android SDK.
#
# Runs MODULE-LESS from a scratch dir outside the Gradle project (same reason as serve-lanes-boot.sh)
# so serve hosts only the playground lane, not the repo's ~20 preview modules.
#
# Env:
#   SERVE_CLI                 compose-preview launcher (default: :cli:installDist output)
#   SERVE_BUNDLE              packed Android bundle used as the compile classpath
#                             (default: playground-lane.png at the repo root)
#   LIB_DAEMON_ANDROID_DIR    staged :daemon:android runtime jars (:cli:stageDaemonAndroidLibs output)
#   SERVE_PORT                bind port (default 8727 — above the two serve-lanes ports)
#   SERVE_TOKEN               the access token (default playground-e2e); the spec passes ?token=…
#   SERVE_LOG                 serve log file, relative to the repo root (default playground-lane.log)
#
# Writes the pid to playground-lane.pid (repo root) for teardown.
set -euo pipefail

REPO_ROOT="$(pwd)"
CLI="$(readlink -f "${SERVE_CLI:-cli/build/install/compose-preview/bin/compose-preview}")"
BUNDLE="$(readlink -f "${SERVE_BUNDLE:-playground-lane.png}")"
LIB_DAEMON_ANDROID="$(readlink -f "${LIB_DAEMON_ANDROID_DIR:-cli/build/staged-daemon-android-libs}")"
PORT="${SERVE_PORT:-8727}"
TOKEN="${SERVE_TOKEN:-playground-e2e}"
LOG="${REPO_ROOT}/${SERVE_LOG:-playground-lane.log}"
PID_FILE="${REPO_ROOT}/playground-lane.pid"
BASE="http://127.0.0.1:${PORT}"

if [ ! -x "$CLI" ]; then
  echo "::error::serve CLI not found or not executable at $CLI — run :cli:installDist first" >&2
  exit 1
fi
if [ ! -f "$BUNDLE" ]; then
  echo "::error::playground bundle not found at $BUNDLE — run \`compose-preview bundle pack --module samples:android-live-lane -o $BUNDLE\` first" >&2
  exit 1
fi
if [ ! -d "$LIB_DAEMON_ANDROID" ]; then
  echo "::error::lib-daemon-android jars dir not found at $LIB_DAEMON_ANDROID — run :cli:stageDaemonAndroidLibs first" >&2
  exit 1
fi

# The Robolectric daemon resolves android.jar from a local SDK; without one the render lanes report
# unavailable. CI runners export ANDROID_HOME; a dev box may only have sdk.dir in local.properties.
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -f "${REPO_ROOT}/local.properties" ]; then
  sdk_dir="$(sed -n 's/^sdk\.dir=//p' "${REPO_ROOT}/local.properties" | head -1)"
  [ -n "$sdk_dir" ] && export ANDROID_HOME="$sdk_dir"
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  echo "::error::no Android SDK — set ANDROID_HOME (or ANDROID_SDK_ROOT), or add sdk.dir to local.properties. The Robolectric daemon needs android.jar to render." >&2
  exit 1
fi

WORK="$(mktemp -d "${RUNNER_TEMP:-/tmp}/playground-lane.XXXXXX")"
echo "playground: booting playground-lane serve on $BASE (module-less, cwd=$WORK)" >&2
cd "$WORK"
# --live-seats 2: an Android/Robolectric daemon costs ANDROID_LIVE_SEAT_WEIGHT (2) permits, so a
# redeemed /pg session needs at least that budget. NOT --public (the lane is refused there).
JAVA_OPTS="-Dcomposeai.cli.libDaemonAndroidDir=${LIB_DAEMON_ANDROID}" nohup "$CLI" serve \
  --playground-android-bundle "$BUNDLE" --accept-docs \
  --host 127.0.0.1 --port "$PORT" --token "$TOKEN" --live-seats 2 \
  > "$LOG" 2>&1 &
echo $! > "$PID_FILE"

# Wait for the HTTP server to answer, and confirm the playground lane actually came up (the CLI logs
# "playground enabled …" once the compile classpath + compiler resolved; absent it, the /playground
# page and /api routes 404 and every spec assertion would fail cryptically).
up=""
for _ in $(seq 1 120); do
  if curl -sf -o /dev/null --max-time 4 "$BASE/version"; then up=1; break; fi
  sleep 2
done
if [ -z "$up" ]; then
  echo "::error::serve did not answer /version in time" >&2
  tail -80 "$LOG" >&2 || true
  exit 1
fi
if ! grep -q "playground enabled" "$LOG"; then
  echo "::error::the playground lane did not enable — the compile classpath or the BTA compiler didn't resolve. See the log." >&2
  tail -80 "$LOG" >&2 || true
  exit 1
fi

echo "$BASE"
