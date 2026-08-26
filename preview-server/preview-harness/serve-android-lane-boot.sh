#!/usr/bin/env bash
# Boot a daemon-backed `compose-preview serve` whose ONE session is a locally packed **Android**
# bundle, live-rendered by the Robolectric daemon, and print its base URL — the Android counterpart
# of serve-lanes-boot.sh (which serves the desktop CMP `--catalogs compose-m3` lane).
#
# Why a local `--bundle` and not `--catalogs <system>`: this lane has to assert the *fail-soft*
# failure mode is gone (#2675), so it must control the bundle's merged manifest. The fixture
# (`:samples:android-live-lane`) declares an `Application` the render classpath doesn't carry — the
# shape that broke every Android catalog on preview.coo.ee in #2669 — and serving it from disk keeps
# the job hermetic (no published design-artifacts branch, no network fetch of a catalog).
#
# Live rendering is gated on the bundle verifying `Trusted`, so this mints a throwaway Ed25519
# keypair, signs the bundle with it, and pins that key in a scratch trust store. Same gate the
# public server runs under — just with a key that lives for the length of the job.
#
# The script FAILS (rather than serving baked PNGs) when the daemon doesn't come up live. That is the
# entire point: the #2669 regression degraded silently, so "serve answered /version" is not a pass
# condition here — "the session registered LIVE and a real render came back 200" is.
#
# Env:
#   SERVE_CLI                 compose-preview launcher (default: :cli:installDist output)
#   SERVE_BUNDLE              packed Android bundle to serve (default: android-live-lane.png at the repo root)
#   LIB_DAEMON_ANDROID_DIR    staged :daemon:android runtime jars (:cli:stageDaemonAndroidLibs output)
#   SERVE_PORT                bind port (default 8726 — one above the desktop lane's 8725)
#   SERVE_SYSTEM              session name the bundle registers under (default androidlane)
#   SERVE_LOG                 serve log file, relative to the repo root (default serve-android-lane.log)
#
# Writes the pid to serve-android-lane.pid (repo root) for teardown. Needs an Android SDK
# (ANDROID_HOME / ANDROID_SDK_ROOT) for `android.jar`; no xvfb / GL — Robolectric rasterises
# offscreen.
set -euo pipefail

REPO_ROOT="$(pwd)"
CLI="$(readlink -f "${SERVE_CLI:-cli/build/install/compose-preview/bin/compose-preview}")"
BUNDLE="$(readlink -f "${SERVE_BUNDLE:-android-live-lane.png}")"
LIB_DAEMON_ANDROID="$(readlink -f "${LIB_DAEMON_ANDROID_DIR:-cli/build/staged-daemon-android-libs}")"
PORT="${SERVE_PORT:-8726}"
SYSTEM="${SERVE_SYSTEM:-androidlane}"
LOG="${REPO_ROOT}/${SERVE_LOG:-serve-android-lane.log}"
PID_FILE="${REPO_ROOT}/serve-android-lane.pid"
BASE="http://127.0.0.1:${PORT}"

if [ ! -x "$CLI" ]; then
  echo "::error::serve CLI not found or not executable at $CLI — run :cli:installDist first" >&2
  exit 1
fi
if [ ! -f "$BUNDLE" ]; then
  echo "::error::android bundle not found at $BUNDLE — run \`compose-preview bundle pack --module samples/android-live-lane\` first" >&2
  exit 1
fi
if [ ! -d "$LIB_DAEMON_ANDROID" ]; then
  echo "::error::lib-daemon-android jars dir not found at $LIB_DAEMON_ANDROID — run :cli:stageDaemonAndroidLibs first" >&2
  exit 1
fi

# The Robolectric daemon resolves `android.jar` from a local SDK and, without one, falls back to
# baked PNGs — indistinguishable from the regression this lane hunts. Fail here with the real reason
# instead. CI runners export ANDROID_HOME; a dev box may only have `sdk.dir` in local.properties.
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -f "${REPO_ROOT}/local.properties" ]; then
  sdk_dir="$(sed -n 's/^sdk\.dir=//p' "${REPO_ROOT}/local.properties" | head -1)"
  [ -n "$sdk_dir" ] && export ANDROID_HOME="$sdk_dir"
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  echo "::error::no Android SDK — set ANDROID_HOME (or ANDROID_SDK_ROOT), or add sdk.dir to local.properties. The Robolectric daemon needs android.jar and would silently degrade to baked PNGs without it." >&2
  exit 1
fi

WORK="$(mktemp -d "${RUNNER_TEMP:-/tmp}/serve-android-lane.XXXXXX")"
SIGNED="${WORK}/${SYSTEM}.png"
cp "$BUNDLE" "$SIGNED"

# 1) Mint a throwaway signing key and pin it in a scratch trust store. `bundle keygen` writes the
#    private key to --output and prints the matching trust-store entry (keyId + base64 publicKey).
KEY_ID="serve-android-lane"
KEYGEN_OUT="${WORK}/keygen.txt"
"$CLI" bundle keygen --output "${WORK}/key.pem" --key-id "$KEY_ID" > "$KEYGEN_OUT"
PUBLIC_KEY="$(sed -n 's/.*"publicKey": "\([^"]*\)".*/\1/p' "$KEYGEN_OUT" | head -1)"
if [ -z "$PUBLIC_KEY" ]; then
  echo "::error::could not read the generated public key from \`bundle keygen\` output" >&2
  cat "$KEYGEN_OUT" >&2
  exit 1
fi
TRUST="${WORK}/producers.json"
cat > "$TRUST" <<JSON
{
  "_comment": "Throwaway trust store for the serve-lanes Android e2e — pins the ephemeral key minted by serve-android-lane-boot.sh so the locally packed bundle verifies Trusted and earns the live lane.",
  "keys": [
    { "keyId": "${KEY_ID}", "name": "serve-lanes-android-e2e", "publicKey": "${PUBLIC_KEY}" }
  ]
}
JSON

# 2) Sign the bundle with it. Without a Trusted verdict serve refuses to live-render and silently
#    falls back to baked PNGs — the very failure mode this lane exists to catch.
"$CLI" bundle sign "$SIGNED" --key "${WORK}/key.pem" --key-id "$KEY_ID" --producer "serve-lanes-android-e2e" >&2

# 3) Launch from a scratch dir OUTSIDE the Gradle project so serve runs module-less (hosting only
#    the bundle) instead of trying to discover and build the repo's ~20 preview modules.
echo "serve-android-lane: booting Robolectric-daemon serve ($SYSTEM) on $BASE (module-less, cwd=$WORK)" >&2
cd "$WORK"
# The Gradle start script forwards JAVA_OPTS to the CLI JVM, where `locateSidecarJars` reads
# `composeai.cli.libDaemonAndroidDir` to assemble the Android daemon `-cp` (the ~200 MB
# lib-daemon-android sidecar ships outside the CLI tarball — #1685).
# --live-seats 2: an Android/Robolectric daemon costs ANDROID_LIVE_SEAT_WEIGHT (2) permits, so a
# single-seat budget would turn the only session away and degrade it to baked PNGs.
JAVA_OPTS="-Dcomposeai.cli.libDaemonAndroidDir=${LIB_DAEMON_ANDROID}" nohup "$CLI" serve \
  --bundle "${SYSTEM}=${SIGNED}" --public --host 127.0.0.1 --port "$PORT" \
  --trust-store "$TRUST" --allow-render-trusted --live-seats 2 \
  > "$LOG" 2>&1 &
echo $! > "$PID_FILE"

# 4) Wait for the HTTP server to answer.
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

# 5) FAIL-LOUD gate #1: the session must register LIVE. `registerStartupBundles` logs
#    "bundle <name> → LIVE from bundle" on the live path and "→ N baked preview(s)" when it fell
#    back. A ClassNotFoundException at Robolectric sandbox bootstrap (the #2669 regression) lands in
#    the baked branch, which is invisible from /version alone.
#
#    Polled, not read once: serve answers /version while the Android daemon is still booting its
#    sandbox pool (~7s cold here, longer on a CI box), so a single grep races the decision and would
#    report a false "degraded". The two outcomes are mutually exclusive, so whichever line lands
#    first is the verdict. `.*` spans the log's arrow so the check doesn't depend on its encoding.
live=""
for _ in $(seq 1 150); do
  if grep -q "bundle ${SYSTEM} .*LIVE from bundle" "$LOG"; then live=1; break; fi
  if grep -q "bundle ${SYSTEM} .*baked preview" "$LOG"; then break; fi
  sleep 2
done
if [ -z "$live" ]; then
  echo "::error::serve did not stand up a LIVE Robolectric session for '${SYSTEM}' — it degraded to baked PNGs (or never decided). This is the #2669 fail-soft shape." >&2
  tail -120 "$LOG" >&2 || true
  exit 1
fi

# 6) FAIL-LOUD gate #2: a real render has to come back. Cold Robolectric sandbox bootstrap plus the
#    android-all-instrumented fetch dominates the first render, so budget generously — and do it
#    here so the spec's per-test budget isn't spent on warm-up.
PID="$(curl -s --max-time 8 "$BASE/$SYSTEM/api/previews" \
  | tr ',' '\n' | grep -oE '"id":"[^"]+"' | head -1 | sed 's/"id":"//;s/"//')"
if [ -z "${PID:-}" ]; then
  echo "::error::no previews exposed at $BASE/$SYSTEM/api/previews" >&2
  tail -120 "$LOG" >&2 || true
  exit 1
fi
warm=""
for _ in $(seq 1 60); do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 60 "$BASE/$SYSTEM/render/$PID.png" || true)"
  if [ "$code" = "200" ]; then warm=1; echo "serve-android-lane: daemon warm (render 200 for $PID)" >&2; break; fi
  sleep 5
done
if [ -z "$warm" ]; then
  echo "::error::the Robolectric daemon never returned a 200 render for $PID — the live lane is not actually rendering" >&2
  tail -160 "$LOG" >&2 || true
  exit 1
fi

# 7) FAIL-LOUD gate #3: no aborted sandbox in the log, even if a render squeaked through.
if grep -q "ClassNotFoundException" "$LOG"; then
  echo "::error::serve log contains ClassNotFoundException — a Robolectric sandbox aborted (Application pin regression)" >&2
  grep -n "ClassNotFoundException" "$LOG" | head -20 >&2 || true
  exit 1
fi

echo "$BASE"
