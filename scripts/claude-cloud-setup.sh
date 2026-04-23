#!/usr/bin/env bash
#
# One-shot bootstrap for running compose-preview in a Claude Code cloud
# sandbox (claude.ai/code / "Claude on the web").
#
# Handles the three things the default cloud image is missing:
#   1. JDK 17 (only 21 is pre-installed; the Gradle toolchain pin is 17).
#   2. The `compose-preview` CLI on PATH.
#   3. Optionally, an Android SDK (cmdline-tools + platforms;android-36)
#      so `sample-android` and other Android consumers can render.
#
# Designed for two invocation styles:
#
#   # As a remote one-liner (Environment setup script, no repo yet):
#   curl -fsSL https://raw.githubusercontent.com/yschimke/compose-ai-tools/main/scripts/claude-cloud-setup.sh | bash
#
#   # From a checkout (repeat runs, SessionStart hook, etc):
#   scripts/claude-cloud-setup.sh
#
# All steps are idempotent and cheap on repeat.
#
# CLI install uses github.com release-download URLs only; api.github.com is
# rate-limited on shared cloud-sandbox IPs and would otherwise 403.
#
# Flags:
#   --no-prewarm   skip the Gradle warm-up (useful outside a repo)
#   --android      also install the Android SDK
#   -h / --help    show this banner
#
# Env overrides:
#   COMPOSE_PREVIEW_VERSION  pin the CLI version (default: latest release)
#   PREFIX                   install prefix (default: $HOME/.local)
#   ANDROID_HOME             Android SDK root (default: $HOME/android-sdk)
#   REPO                     GitHub slug (default: yschimke/compose-ai-tools)
#
# When $CLAUDE_ENV_FILE is set (SessionStart hook context), JAVA_HOME / PATH /
# ANDROID_HOME are appended to it so subsequent tool invocations see them.

set -euo pipefail

JDK_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
PREFIX="${PREFIX:-$HOME/.local}"
REPO="${REPO:-yschimke/compose-ai-tools}"
PREWARM=1
ANDROID=0

for arg in "$@"; do
  case "$arg" in
    --no-prewarm) PREWARM=0 ;;
    --android)    ANDROID=1 ;;
    -h|--help) cat <<'EOF'
claude-cloud-setup.sh -- bootstrap compose-preview in a Claude Code cloud sandbox

Installs JDK 17, the compose-preview CLI (via github.com release assets;
api.github.com is skipped since it's rate-limited on shared sandbox IPs),
and optionally an Android SDK. Idempotent.

Invocation:
  # Remote one-liner (Environment setup script):
  curl -fsSL https://raw.githubusercontent.com/yschimke/compose-ai-tools/main/scripts/claude-cloud-setup.sh | bash

  # From a checkout:
  scripts/claude-cloud-setup.sh

Flags:
  --no-prewarm   skip the Gradle warm-up (useful outside a repo)
  --android      also install the Android SDK
  -h, --help     show this banner

Env overrides:
  COMPOSE_PREVIEW_VERSION  pin the CLI version (default: latest release)
  PREFIX                   install prefix (default: $HOME/.local)
  ANDROID_HOME             Android SDK root (default: $HOME/android-sdk)
  REPO                     GitHub slug (default: yschimke/compose-ai-tools)

When $CLAUDE_ENV_FILE is set (SessionStart hook context), JAVA_HOME / PATH
(and ANDROID_HOME when --android) are appended to it.
EOF
      exit 0 ;;
    *) echo "unknown arg: $arg (try --help)" >&2; exit 2 ;;
  esac
done

log() { echo "==> $*"; }
die() { echo "error: $*" >&2; exit 1; }

SUDO=""
if [[ $EUID -ne 0 ]]; then
  command -v sudo >/dev/null 2>&1 || die "need root or sudo for apt-get"
  SUDO="sudo"
fi

# 1. JDK 17 -----------------------------------------------------------------
if [[ ! -x "$JDK_HOME/bin/java" ]]; then
  log "installing openjdk-17-jdk-headless"
  $SUDO apt-get install -y -qq openjdk-17-jdk-headless
fi
export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

# 2. compose-preview CLI ----------------------------------------------------
VERSION="${COMPOSE_PREVIEW_VERSION:-}"
if [[ -z "$VERSION" ]]; then
  log "resolving latest release of $REPO"
  RESOLVED="$(curl -fsSLI -o /dev/null -w '%{url_effective}' \
    "https://github.com/$REPO/releases/latest")" \
    || die "could not reach github.com/$REPO/releases/latest"
  VERSION="${RESOLVED##*/v}"
  [[ -n "$VERSION" && "$VERSION" != "$RESOLVED" ]] \
    || die "could not parse version from $RESOLVED"
fi

OPT_DIR="$PREFIX/opt/compose-preview/$VERSION"
LAUNCHER="$OPT_DIR/compose-preview-${VERSION}/bin/compose-preview"
BIN_DIR="$PREFIX/bin"

if [[ ! -x "$LAUNCHER" ]]; then
  log "installing compose-preview $VERSION to $OPT_DIR"
  mkdir -p "$OPT_DIR"
  TMP="$(mktemp)"
  trap 'rm -f "$TMP"' EXIT
  curl -fsSL -o "$TMP" \
    "https://github.com/$REPO/releases/download/v${VERSION}/compose-preview-${VERSION}.tar.gz"
  tar -xzf "$TMP" -C "$OPT_DIR"
  [[ -x "$LAUNCHER" ]] || die "launcher missing after extract: $LAUNCHER"
fi

mkdir -p "$BIN_DIR"
ln -sf "$LAUNCHER" "$BIN_DIR/compose-preview"
export PATH="$BIN_DIR:$PATH"
log "compose-preview $VERSION ready at $BIN_DIR/compose-preview"

# 3. Android SDK (optional) -------------------------------------------------
if [[ "$ANDROID" == 1 ]]; then
  ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
  SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  if [[ ! -x "$SDKMANAGER" ]]; then
    log "installing Android cmdline-tools under $ANDROID_HOME"
    $SUDO apt-get install -y -qq unzip
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    curl -fsSL -o /tmp/cmdline-tools.zip \
      https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip -q -o /tmp/cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools"
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -f /tmp/cmdline-tools.zip
  fi
  log "accepting SDK licenses and installing platform/build-tools"
  yes | "$SDKMANAGER" --licenses >/dev/null
  "$SDKMANAGER" "platforms;android-36" "build-tools;36.0.0" "platform-tools" >/dev/null
  export ANDROID_HOME
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

# 4. Pre-warm Gradle + plugin cache -----------------------------------------
# Only meaningful when run from a checkout of compose-ai-tools.
if [[ "$PREWARM" == 1 && -x "./gradlew" && -d "sample-cmp" ]]; then
  log "pre-warming Gradle cache via :sample-cmp:renderAllPreviews"
  ./gradlew :sample-cmp:renderAllPreviews --no-daemon || true
fi

# 5. Export env vars to SessionStart env file -------------------------------
if [[ -n "${CLAUDE_ENV_FILE:-}" ]]; then
  {
    echo "JAVA_HOME=$JAVA_HOME"
    echo "PATH=$BIN_DIR:$JAVA_HOME/bin:\$PATH"
    if [[ "$ANDROID" == 1 ]]; then
      echo "ANDROID_HOME=$ANDROID_HOME"
      echo "ANDROID_SDK_ROOT=$ANDROID_HOME"
    fi
  } >> "$CLAUDE_ENV_FILE"
  log "wrote env vars to \$CLAUDE_ENV_FILE"
fi

log "done"
