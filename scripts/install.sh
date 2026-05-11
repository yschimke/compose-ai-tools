#!/usr/bin/env bash
#
# Bootstrap installer for the compose-preview skill bundles.
#
# Installs two sibling skills into the detected agent host's user-scope
# skills directory (Claude Code, Codex, or Antigravity), with symlinks into
# the other detected hosts so a single install serves all of them:
#
#   <skills-root>/compose-preview/                     (renderer + CLI)
#   |-- SKILL.md                                       (from skill tarball)
#   |-- design/...                                     (from skill tarball)
#   |-- cli/compose-preview-<ver>/bin/compose-preview  (from CLI tarball)
#   `-- bin/compose-preview -> ../cli/.../compose-preview
#
#   <skills-root>/compose-preview-review/              (PR-review workflows)
#   |-- SKILL.md                                       (from skill tarball)
#   `-- design/...                                     (from skill tarball)
#
# <skills-root> resolves in order:
#   1. $SKILL_DIR/.. when explicitly set
#   2. The skill dir of the cloud agent currently running this script
#      (Claude → ~/.claude/skills, Codex → ${CODEX_HOME:-~/.codex}/skills)
#   3. Whichever agent config directory already exists on disk
#   4. ~/.claude/skills (final fallback)
#
# Also symlinks ~/.local/bin/compose-preview so the CLI is on PATH without
# the consumer having to know the skill-bundle layout. Idempotent: rerunning
# with the same version is a no-op; passing no VERSION resolves the latest
# release and replaces whatever is installed.
#
# Usage:
#   scripts/install.sh                         # install latest release
#   scripts/install.sh 0.3.2                   # install a specific version
#   VERSION=0.3.2 scripts/install.sh           # same, via env
#   scripts/install.sh --android-sdk           # also install the Android SDK
#                                              # (cmdline-tools + platforms;android-36
#                                              # + platform-tools + build-tools;36.0.0)
#   scripts/install.sh --jdk 17,21             # install JDK 17 and 21 (first = active)
#   JDKS=17,21 scripts/install.sh              # same, via env
#
# Override locations:
#   SKILL_DIR=~/.claude/skills/compose-preview scripts/install.sh
#   PREFIX=$HOME/.local scripts/install.sh       # for the ~/.local/bin symlink
#   REPO=yschimke/compose-ai-tools scripts/install.sh
#   ANDROID_HOME=$HOME/Android/Sdk scripts/install.sh --android-sdk
#   INSTALL_ANDROID_SDK=1 scripts/install.sh     # same as --android-sdk
#
# ANDROID_HOME default:
#   - $ANDROID_HOME if set
#   - /opt/android-sdk when running as root or in a cloud sandbox
#   - $HOME/Library/Android/sdk on macOS
#   - $HOME/Android/Sdk otherwise (Android Studio's Linux default)
#
# Requires: bash, curl, tar, sha256sum (or shasum), and Java 17+ on PATH at
# run time (not install time). The --android-sdk path additionally needs
# unzip and write access to $ANDROID_HOME (sudo when not root).
#
# Cloud-sandbox mode (auto-detected for Claude/Codex):
#   - Claude: $CLAUDE_ENV_FILE or $CLAUDE_CODE_SESSION_ID
#   - Codex:  $CODEX_SANDBOX or $CODEX_SESSION_ID
#
# Claude-specific env-file behavior:
#   - Uses the pre-installed JDK (21 on current Claude Cloud images) when it's
#     Java 17+ — the CLI, plugin, and renderer AARs are compiled to JDK 17
#     bytecode and run fine on any newer JDK, so there's no need to downgrade.
#     Falls back to apt-installing openjdk-17-jdk-headless only when no Java
#     17+ is available (older base images).
#   - Skips api.github.com lookups (they 403 on shared sandbox IPs due to
#     unauthenticated rate limiting) and resolves versions via the public
#     github.com HTML redirect instead. Sha256 verification is best-effort.
#   - Appends JAVA_HOME, ANDROID_HOME, and PATH to $CLAUDE_ENV_FILE so
#     subsequent tool invocations see them.
#   - If $https_proxy / $http_proxy is set, translates it into
#     JAVA_TOOL_OPTIONS (-Dhttps.proxyHost / -Dhttp.proxyHost) and writes
#     that to $CLAUDE_ENV_FILE too. The JVM's HttpURLConnection ignores the
#     shell proxy env vars, so the Gradle wrapper download otherwise fails
#     with UnknownHostException (anthropics/claude-code#16222).
#   - --android-sdk plays well with the cloud's filesystem snapshot cache: the
#     SDK is written to disk once during the cloud environment's Setup script
#     and reused for every later session. Note that sdkmanager downloads from
#     dl.google.com, which is NOT on the default Trusted network allowlist;
#     the environment must use Custom access with dl.google.com added (or
#     Full).
# Force on/off explicitly with CLAUDE_CLOUD=1 / CLAUDE_CLOUD=0.

set -euo pipefail

REPO="${REPO:-yschimke/compose-ai-tools}"
SKILL_DIR="${SKILL_DIR:-}"
PREFIX="${PREFIX:-$HOME/.local}"
INSTALL_ANDROID_SDK="${INSTALL_ANDROID_SDK:-0}"
JDKS_REQUESTED="${JDKS:-}"
ANDROID_HOME_INPUT="${ANDROID_HOME:-}"

# Argument parsing — flags first, then positional VERSION. Flags can appear in
# any order. Unknown flags are an error so typos don't get silently swallowed.
# --yes/--upgrade are accepted (and ignored) for backwards compatibility with
# old README snippets and pipelines; the consent gate they used to drive was
# removed (it was impractical to thread --yes through every agent invocation).
positional=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --android-sdk) INSTALL_ANDROID_SDK=1; shift ;;
    --jdk|--jdks)
      [[ $# -ge 2 ]] || { echo "error: $1 requires a value" >&2; exit 1; }
      JDKS_REQUESTED="$2"; shift 2 ;;
    --jdk=*|--jdks=*) JDKS_REQUESTED="${1#*=}"; shift ;;
    --yes|-y|--upgrade) shift ;;
    --) shift; positional+=("$@"); break ;;
    -*) echo "error: unknown flag: $1" >&2; exit 1 ;;
    *) positional+=("$1"); shift ;;
  esac
done
set -- "${positional[@]+"${positional[@]}"}"
VERSION="${1:-${VERSION:-}}"

BIN_DIR="$PREFIX/bin"

# Cloud sandbox auto-detection (Claude/Codex) -------------------------------
if [[ -n "${CODEX_SANDBOX:-}" || -n "${CODEX_SESSION_ID:-}" ]]; then
  AGENT_CLOUD_HOST="codex"
elif [[ -n "${CLAUDE_ENV_FILE:-}" || -n "${CLAUDE_CODE_SESSION_ID:-}" ]]; then
  AGENT_CLOUD_HOST="claude"
else
  AGENT_CLOUD_HOST=""
fi
if [[ -z "${CLAUDE_CLOUD:-}" ]]; then
  CLAUDE_CLOUD=$([[ -n "$AGENT_CLOUD_HOST" ]] && echo 1 || echo 0)
fi

# Skill install root — pick the canonical agent-skills directory based on
# (a) the agent host currently running this script, then (b) whichever agent
# config dir already exists, then (c) Claude as the fallback. The
# link_skills_for_detected_hosts helper later symlinks the canonical bundle
# into every other detected host's skill dir, so one install serves Claude,
# Codex, and Antigravity in parallel. Override with `SKILL_DIR=...`.
if [[ -z "$SKILL_DIR" ]]; then
  codex_skills="${CODEX_HOME:-$HOME/.codex}/skills/compose-preview"
  claude_skills="$HOME/.claude/skills/compose-preview"
  antigravity_skills="${ANTIGRAVITY_SKILLS_DIR:-$HOME/.gemini/antigravity/skills}/compose-preview"
  case "$AGENT_CLOUD_HOST" in
    codex)  candidates=("$codex_skills" "$claude_skills" "$antigravity_skills") ;;
    claude) candidates=("$claude_skills" "$codex_skills" "$antigravity_skills") ;;
    *)      candidates=("$claude_skills" "$codex_skills" "$antigravity_skills") ;;
  esac
  for c in "${candidates[@]}"; do
    parent_root="$(dirname "$(dirname "$c")")"
    if [[ -d "$parent_root" ]]; then
      SKILL_DIR="$c"
      break
    fi
  done
  SKILL_DIR="${SKILL_DIR:-$claude_skills}"
fi

# Android SDK location — honor an explicit ANDROID_HOME from the caller; else
# pick a writable default that doesn't require sudo for the common case. A
# system-wide /opt path makes sense for cloud sandboxes (running as root, with
# the filesystem snapshotted across sessions) but is hostile to local users.
if [[ -n "$ANDROID_HOME_INPUT" ]]; then
  ANDROID_HOME="$ANDROID_HOME_INPUT"
elif [[ $EUID -eq 0 || "$CLAUDE_CLOUD" == 1 ]]; then
  ANDROID_HOME="/opt/android-sdk"
elif [[ "$(uname -s)" == "Darwin" ]]; then
  ANDROID_HOME="$HOME/Library/Android/sdk"
else
  ANDROID_HOME="$HOME/Android/Sdk"
fi

die() { echo "error: $*" >&2; exit 1; }
log() { echo "==> $*"; }

require() {
  command -v "$1" >/dev/null 2>&1 || die "missing required tool: $1"
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    die "neither sha256sum nor shasum available"
  fi
}

require curl
require tar

# ---- Skill-bundle symlinks for detected agent hosts ----------------------
#
# The skill bundles are extracted once into $SKILL_DIR / $REVIEW_SKILL_DIR.
# For every other agent host detected on this machine, drop a symlink from
# that host's user-scope skills directory pointing at the canonical bundle,
# so updates flow through automatically and disk content isn't duplicated.
# Detection mirrors `compose-preview mcp install`. The host that already
# owns the canonical bundle is skipped to avoid linking onto itself.
#
# Skill dirs (override via env):
#   - Codex:       ${CODEX_HOME:-$HOME/.codex}/skills
#                  https://developers.openai.com/codex/skills
#   - Antigravity: ${ANTIGRAVITY_SKILLS_DIR:-$HOME/.gemini/antigravity/skills}
#                  https://antigravity.google/docs/skills
#
# Idempotent: a symlink already pointing at the canonical bundle is left alone;
# a stale symlink is repointed; a regular directory or file is left untouched
# with a warning (so we never clobber user-managed content).

have_codex() {
  [[ -d "${CODEX_HOME:-$HOME/.codex}" ]] || command -v codex >/dev/null 2>&1
}

have_antigravity() {
  [[ -d "$HOME/.gemini/antigravity" ]] \
    || [[ "${__CFBundleIdentifier:-}" == "com.google.antigravity" ]] \
    || [[ -n "${ANTIGRAVITY_CLI_ALIAS:-}" ]] \
    || command -v antigravity >/dev/null 2>&1
}

link_skill_into_dir() {
  local host="$1" dir="$2" src="$3"
  local name; name="$(basename "$src")"
  local dst="$dir/$name"
  if [[ -L "$dst" ]]; then
    local current; current="$(readlink "$dst" 2>/dev/null || true)"
    if [[ "$current" == "$src" ]]; then
      return 0
    fi
    log "updating $host skill link: $dst -> $src (was $current)"
    ln -sfn "$src" "$dst"
    return 0
  fi
  if [[ -e "$dst" ]]; then
    log "warning: $host skills dir contains a non-symlink at $dst; leaving it alone"
    return 0
  fi
  log "linking $host skill: $dst -> $src"
  ln -s "$src" "$dst"
}

link_skills_for_detected_hosts() {
  # Run after the canonical bundles exist; otherwise the symlinks would dangle.
  [[ -d "$SKILL_DIR" ]] || return 0
  # Real path of the dir that owns the canonical bundles; skip any host
  # whose skills dir is the same (i.e. would symlink onto itself).
  local owning_root; owning_root="$(dirname "$SKILL_DIR")"
  if have_codex; then
    local codex_dir="${CODEX_HOME:-$HOME/.codex}/skills"
    if [[ "$codex_dir" != "$owning_root" ]]; then
      mkdir -p "$codex_dir"
      link_skill_into_dir "codex" "$codex_dir" "$SKILL_DIR"
      [[ -d "$REVIEW_SKILL_DIR" ]] && link_skill_into_dir "codex" "$codex_dir" "$REVIEW_SKILL_DIR"
    fi
  fi
  if have_antigravity; then
    local ag_dir="${ANTIGRAVITY_SKILLS_DIR:-$HOME/.gemini/antigravity/skills}"
    if [[ "$ag_dir" != "$owning_root" ]]; then
      mkdir -p "$ag_dir"
      link_skill_into_dir "antigravity" "$ag_dir" "$SKILL_DIR"
      [[ -d "$REVIEW_SKILL_DIR" ]] && link_skill_into_dir "antigravity" "$ag_dir" "$REVIEW_SKILL_DIR"
    fi
  fi
  # If the canonical bundle is under codex/antigravity, mirror it into Claude
  # too when ~/.claude exists. (The original code only mirrored "away from"
  # Claude; now the canonical home can be any of them.)
  if [[ -d "$HOME/.claude" ]]; then
    local claude_dir="$HOME/.claude/skills"
    if [[ "$claude_dir" != "$owning_root" ]]; then
      mkdir -p "$claude_dir"
      link_skill_into_dir "claude" "$claude_dir" "$SKILL_DIR"
      [[ -d "$REVIEW_SKILL_DIR" ]] && link_skill_into_dir "claude" "$claude_dir" "$REVIEW_SKILL_DIR"
    fi
  fi
}

# ---- Cloud: ensure required JDK(s) are available --------------------------
#
# Resolve the project's required daemon toolchain from
# gradle/gradle-daemon-jvm.properties when available; default to 17 as a safe
# floor. The active JAVA_HOME points at the required major; additional majors
# requested via --jdk get apt-installed alongside it (Gradle's toolchain
# auto-detection then finds them under /usr/lib/jvm/), which is how a project
# on JDK 17 can also expose 21 for `-Pjdk-version=21` smoke runs.

REQUIRED_JAVA_MAJOR="17"
DAEMON_JVM_PROPS=""
if [[ -n "${BASH_SOURCE[0]:-}" && -f "${BASH_SOURCE[0]}" ]]; then
  script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  candidate="$script_dir/../gradle/gradle-daemon-jvm.properties"
  [[ -f "$candidate" ]] && DAEMON_JVM_PROPS="$candidate"
fi
if [[ -z "$DAEMON_JVM_PROPS" ]]; then
  candidate="$PWD/gradle/gradle-daemon-jvm.properties"
  [[ -f "$candidate" ]] && DAEMON_JVM_PROPS="$candidate"
fi
if [[ -z "$DAEMON_JVM_PROPS" ]] && command -v git >/dev/null 2>&1; then
  git_root="$(git rev-parse --show-toplevel 2>/dev/null || true)"
  if [[ -n "$git_root" ]]; then
    candidate="$git_root/gradle/gradle-daemon-jvm.properties"
    [[ -f "$candidate" ]] && DAEMON_JVM_PROPS="$candidate"
  fi
fi
if [[ -n "$DAEMON_JVM_PROPS" ]]; then
  required_from_props="$(awk -F= '/^toolchainVersion=/{print $2; exit}' "$DAEMON_JVM_PROPS" || true)"
  if [[ -n "$required_from_props" && "$required_from_props" =~ ^[0-9]+$ ]]; then
    REQUIRED_JAVA_MAJOR="$required_from_props"
  fi
fi

# Build the list of JDK majors to install. Default: just the required major
# (which itself defaults to 17). `--jdk 17,21` or `JDKS=17,21` requests
# additional majors; the active one is always REQUIRED_JAVA_MAJOR.
declare -a JDK_MAJORS=()
if [[ -n "$JDKS_REQUESTED" ]]; then
  IFS=',' read -r -a _requested <<<"$JDKS_REQUESTED"
  for m in "${_requested[@]}"; do
    m="${m// /}"
    [[ -n "$m" ]] || continue
    [[ "$m" =~ ^[0-9]+$ ]] || die "invalid --jdk value: '$m' (expected integer major version)"
    JDK_MAJORS+=("$m")
  done
fi
seen_required=0
for m in "${JDK_MAJORS[@]:+${JDK_MAJORS[@]}}"; do
  [[ "$m" == "$REQUIRED_JAVA_MAJOR" ]] && seen_required=1 && break
done
if [[ "$seen_required" == 0 ]]; then
  JDK_MAJORS=("$REQUIRED_JAVA_MAJOR" ${JDK_MAJORS[@]:+"${JDK_MAJORS[@]}"})
fi

install_openjdk_major() {
  local major="$1"
  local jdk_home="/usr/lib/jvm/java-${major}-openjdk-amd64"
  if [[ -x "$jdk_home/bin/java" ]]; then
    log "JDK $major already present at $jdk_home"
    printf '%s\n' "$jdk_home"
    return 0
  fi
  if ! command -v apt-get >/dev/null 2>&1; then
    log "warning: JDK $major not present at $jdk_home and apt-get unavailable; skipping"
    return 1
  fi
  local sudo=""
  if [[ $EUID -ne 0 ]]; then
    command -v sudo >/dev/null 2>&1 || { log "warning: need root or sudo to apt-install openjdk-${major}-jdk-headless; skipping"; return 1; }
    sudo="sudo"
  fi
  log "apt-installing openjdk-${major}-jdk-headless"
  $sudo apt-get install -y -qq "openjdk-${major}-jdk-headless" \
    || { log "warning: apt-get failed for openjdk-${major}-jdk-headless; skipping"; return 1; }
  if [[ -x "$jdk_home/bin/java" ]]; then
    printf '%s\n' "$jdk_home"
    return 0
  fi
  log "warning: openjdk-${major}-jdk-headless installed but $jdk_home/bin/java missing"
  return 1
}

if [[ "$CLAUDE_CLOUD" == 1 || -n "$JDKS_REQUESTED" ]]; then
  # Active JDK: prefer the existing one on PATH if its major matches the
  # required version (avoids a redundant apt round-trip on cloud images that
  # already ship the right JDK), otherwise install it.
  detected_major=""
  if command -v java >/dev/null 2>&1; then
    # `java -version` prints `openjdk version "21.0.10" ...` to stderr.
    # Legacy JDK 8 reports `1.8.x`, which parses to major=1.
    detected_major="$(java -version 2>&1 | head -1 | awk -F'"' '{print $2}' | awk -F. '{print $1}')"
  fi
  if [[ -n "$detected_major" && "$detected_major" =~ ^[0-9]+$ && "$detected_major" -eq "$REQUIRED_JAVA_MAJOR" ]]; then
    log "using existing JDK $detected_major on PATH as the active toolchain"
  else
    if [[ -n "$detected_major" && "$detected_major" =~ ^[0-9]+$ ]]; then
      log "detected JDK $detected_major but project requires JDK $REQUIRED_JAVA_MAJOR; selecting required JDK"
    fi
    JDK_HOME="$(install_openjdk_major "$REQUIRED_JAVA_MAJOR")" \
      || die "could not install required JDK $REQUIRED_JAVA_MAJOR"
    export JAVA_HOME="$JDK_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi

  # Additional JDKs (anything in JDK_MAJORS besides the active major). Gradle's
  # toolchain auto-detection scans /usr/lib/jvm/ so we don't need to export
  # extra env vars.
  for m in "${JDK_MAJORS[@]}"; do
    [[ "$m" == "$REQUIRED_JAVA_MAJOR" ]] && continue
    install_openjdk_major "$m" >/dev/null || true
  done
fi

# ---- Optional: install Android SDK ---------------------------------------
#
# Mirrors the manual procedure in docs/AGENTS.md ("Bringing up a fresh
# sandbox"). Idempotent — checks for $ANDROID_HOME/platforms/android-36 and
# bails out early if present, so re-runs (and the warm-cache path on Claude
# Cloud) are cheap.
#
# Network note: sdkmanager pulls from dl.google.com, which is not on the
# Claude Cloud Trusted allowlist by default (developer.android.com is, but
# that's the docs domain). The reachability probe below fails fast with a
# clear remediation hint when the host is blocked.

install_android_sdk() {
  if [[ -d "$ANDROID_HOME/platforms/android-36" ]]; then
    log "android sdk already present at $ANDROID_HOME (platforms/android-36 found); skipping"
    return 0
  fi

  require curl
  require unzip

  local sudo=""
  if [[ $EUID -ne 0 ]]; then
    command -v sudo >/dev/null 2>&1 || die "need root or sudo to write to $ANDROID_HOME"
    sudo="sudo"
  fi

  local cmdline_zip_url="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

  if ! curl -fsI -o /dev/null --max-time 10 "$cmdline_zip_url" 2>/dev/null; then
    die "cannot reach dl.google.com (Android SDK CDN). On Claude Code on the web, set the environment's network access to Custom and add 'dl.google.com' (the default Trusted list only includes developer.android.com, which doesn't serve the SDK)."
  fi

  log "installing Android command-line tools to $ANDROID_HOME"
  local tmp
  tmp="$(mktemp -d)"
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" RETURN
  local zip="$tmp/cmdline-tools.zip"
  local extract="$tmp/cmdline-tools-extract"
  curl -fsSL -o "$zip" "$cmdline_zip_url" \
    || die "failed to download Android command-line tools"
  mkdir -p "$extract"
  unzip -q "$zip" -d "$extract"
  $sudo mkdir -p "$ANDROID_HOME/cmdline-tools"
  $sudo rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  $sudo mv "$extract/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"

  local sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

  # Pre-write the license-hash files instead of piping `yes` into
  # `sdkmanager --licenses`. The pipe approach exits 141 (SIGPIPE) under
  # `set -o pipefail` whenever sdkmanager closes stdin before `yes` is done
  # writing. These hashes are the same ones android-actions/setup-android
  # writes on GitHub Actions; sdkmanager treats a license as accepted as
  # soon as the hash is on disk.
  log "accepting Android SDK licenses"
  $sudo mkdir -p "$ANDROID_HOME/licenses"
  $sudo tee "$ANDROID_HOME/licenses/android-sdk-license" >/dev/null <<'LIC'
8933bad161af4178b1185d1a37fbf41ea5269c55
d56f5187479451eabf01fb78af6dfcb131a6481e
24333f8a63b6825ea9c5514f83c2829b004d1fee
LIC
  $sudo tee "$ANDROID_HOME/licenses/android-sdk-preview-license" >/dev/null <<'LIC'
84831b9409646a918e30573bab4c9c91346d8abd
LIC
  $sudo tee "$ANDROID_HOME/licenses/android-sdk-arm-dbt-license" >/dev/null <<'LIC'
859f317696f67ef3d7f30a50a5560e7834b43903
LIC
  $sudo tee "$ANDROID_HOME/licenses/google-gdk-license" >/dev/null <<'LIC'
33b6a2b64607f11b759f320ef9dff4ae5c47d97a
LIC
  $sudo tee "$ANDROID_HOME/licenses/intel-android-extra-license" >/dev/null <<'LIC'
d975f751698a77b662f1254ddbeed3901e976f5a
LIC
  $sudo tee "$ANDROID_HOME/licenses/mips-android-sysimage-license" >/dev/null <<'LIC'
e9acab5b5fbb560a72cfaecce8946896ff6aab9d
LIC

  log "installing Android platforms;android-36, platform-tools, build-tools;36.0.0"
  $sudo "$sdkmanager" \
    "platforms;android-36" \
    "platform-tools" \
    "build-tools;36.0.0" >/dev/null

  log "android sdk installed at $ANDROID_HOME"
}

if [[ "$INSTALL_ANDROID_SDK" == 1 ]]; then
  install_android_sdk
  export ANDROID_HOME
  export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
fi

# ---- Resolve version ------------------------------------------------------
#
# Detect what's already installed BEFORE talking to github.com so a
# re-invocation that wants the existing version can short-circuit without
# touching the network at all.

SKILL_VERSION_FILE="$SKILL_DIR/.skill-version"
INSTALLED_VERSION="$(cat "$SKILL_VERSION_FILE" 2>/dev/null || true)"

if [[ -z "$VERSION" ]]; then
  log "resolving latest release of $REPO"
  # Use the public HTML redirect rather than api.github.com; the API is
  # rate-limited on shared sandbox IPs and would 403 for unauthenticated
  # callers. The redirect target is /releases/tag/v<VER>.
  RESOLVED="$(curl -fsSLI -o /dev/null -w '%{url_effective}' \
    "https://github.com/$REPO/releases/latest")" \
    || die "could not reach github.com/$REPO/releases/latest"
  VERSION="${RESOLVED##*/v}"
  [[ -n "$VERSION" && "$VERSION" != "$RESOLVED" ]] \
    || die "could not parse version from $RESOLVED"
fi

CLI_ASSET="compose-preview-${VERSION}.tar.gz"
CLI_URL="https://github.com/$REPO/releases/download/v${VERSION}/${CLI_ASSET}"

CLI_DEST="$SKILL_DIR/cli"
LAUNCHER="$CLI_DEST/compose-preview-${VERSION}/bin/compose-preview"
SKILL_LAUNCHER="$SKILL_DIR/bin/compose-preview"

# Sibling skill — same parent dir as $SKILL_DIR. Bundle covers PR-review
# workflows; pairs with compose-preview but ships separately so an agent
# loading just one of them doesn't pull in the other's content.
REVIEW_SKILL_DIR="$(dirname "$SKILL_DIR")/compose-preview-review"
REVIEW_SKILL_VERSION_FILE="$REVIEW_SKILL_DIR/.skill-version"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

proxy_java_tool_options() {
  # Translate $https_proxy / $http_proxy into JVM -D flags. The JVM's
  # HttpURLConnection (used by the Gradle wrapper) ignores the shell proxy
  # env vars, so without this the wrapper fails on
  # `services.gradle.org` (anthropics/claude-code#16222). Prints an empty
  # string when no proxy URL is set or it lacks an explicit port.
  local url="${https_proxy:-${HTTPS_PROXY:-${http_proxy:-${HTTP_PROXY:-}}}}"
  [[ -n "$url" ]] || return 0
  local hostport="${url#*://}"      # strip scheme
  hostport="${hostport%%/*}"        # strip path
  hostport="${hostport##*@}"        # strip optional user:pass@
  local host="${hostport%:*}"
  local port="${hostport##*:}"
  [[ "$host" != "$hostport" ]] || return 0  # no ':' -> no port, skip
  printf -- '-Dhttps.proxyHost=%s -Dhttps.proxyPort=%s -Dhttp.proxyHost=%s -Dhttp.proxyPort=%s' \
    "$host" "$port" "$host" "$port"
}

maybe_write_env_file() {
  local env_file=""
  if [[ -z "${AGENT_CLOUD_HOST:-}" ]]; then
    return 0
  fi

  case "${AGENT_CLOUD_HOST:-}" in
    claude)
      env_file="${CLAUDE_ENV_FILE:-}"
      ;;
    codex)
      env_file="${CODEX_ENV_FILE:-${CODEX_HOME:-$HOME/.codex}/.env}"
      ;;
    *)
      return 0
      ;;
  esac

  if [[ -n "$env_file" && -w "$(dirname "$env_file")" ]]; then
    local jto sdk_path=""
    jto="$(proxy_java_tool_options)"
    if [[ "$INSTALL_ANDROID_SDK" == 1 ]]; then
      sdk_path="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:"
    fi
    {
      [[ -n "${JAVA_HOME:-}" ]] && echo "JAVA_HOME=$JAVA_HOME"
      [[ "$INSTALL_ANDROID_SDK" == 1 ]] && echo "ANDROID_HOME=$ANDROID_HOME"
      echo "PATH=$BIN_DIR:${JAVA_HOME:+$JAVA_HOME/bin:}${sdk_path}\$PATH"
      [[ -n "$jto" ]] && echo "JAVA_TOOL_OPTIONS=$jto"
    } >> "$env_file"
    log "wrote env vars to $env_file"
  fi
}

# Install one skill tarball into a target dir. Best-effort: older releases
# may not ship every skill bundle, so a missing asset only logs a warning.
# Skip re-download when the marker matches. Otherwise wipe the specific
# top-level paths carried by the new tarball (so stale files from an older
# version's skill bundle don't linger) before extracting. Anything the
# user has added outside those paths is preserved.
install_skill_bundle() {
  local name="$1" dir="$2"
  local asset="${name}-skill-${VERSION}.tar.gz"
  local url="https://github.com/$REPO/releases/download/v${VERSION}/${asset}"
  local marker="$dir/.skill-version"
  local tmpfile="$TMP/${name}-skill.tar.gz"

  mkdir -p "$dir"

  if [[ "$(cat "$marker" 2>/dev/null || true)" == "$VERSION" ]]; then
    log "skill bundle $name@$VERSION already extracted — skipping download"
    return 0
  fi
  if ! curl -fL --progress-bar -o "$tmpfile" "$url" 2>/dev/null; then
    log "warning: $asset not found on the release; skipping"
    return 1
  fi
  log "refreshing skill bundle $name in $dir"
  while IFS= read -r entry; do
    [[ -n "$entry" && "$entry" != "." && "$entry" != ".." ]] || continue
    rm -rf "$dir/$entry"
  done < <(tar -tzf "$tmpfile" | sed -e 's|^\./||' -e 's|/.*||' | awk 'NF' | sort -u)
  tar -xzf "$tmpfile" -C "$dir"
  printf '%s\n' "$VERSION" > "$marker"
}

# ---- Same-version short-circuit ------------------------------------------
# Refreshes any symlinks the caller might have blown away and repairs a
# missing sibling skill bundle (cheap, install_skill_bundle is itself a
# no-op when the per-bundle marker matches), but never re-downloads the CLI
# tarball or the main skill.

if [[ "$INSTALLED_VERSION" == "$VERSION" && -x "$LAUNCHER" ]]; then
  log "compose-preview $VERSION already installed"
  install_skill_bundle "compose-preview-review" "$REVIEW_SKILL_DIR" || true
  mkdir -p "$SKILL_DIR/bin" "$BIN_DIR"
  ln -sfn "../cli/compose-preview-${VERSION}/bin/compose-preview" "$SKILL_LAUNCHER"
  ln -sfn "$LAUNCHER" "$BIN_DIR/compose-preview"
  "$LAUNCHER" --help >/dev/null 2>&1 || die "installed launcher is broken: $LAUNCHER"
  link_skills_for_detected_hosts
  maybe_write_env_file
  exit 0
fi

# ---- Skill tarballs ------------------------------------------------------
# compose-preview is the primary skill (also hosts the CLI launcher).
# compose-preview-review is the sibling skill for PR-review workflows.

install_skill_bundle "compose-preview" "$SKILL_DIR" \
  || log "         (CLI still installs; release predates skill packaging)"
install_skill_bundle "compose-preview-review" "$REVIEW_SKILL_DIR" || true

# ---- CLI tarball ---------------------------------------------------------

if [[ -x "$LAUNCHER" ]]; then
  log "CLI $VERSION already extracted at $LAUNCHER"
else
  # ---- Fetch release metadata (best-effort for sha256) ----
  CLI_DIGEST=""
  log "fetching release metadata for v$VERSION"
  META_HEADERS=(-H "Accept: application/vnd.github+json")
  [[ -n "${GITHUB_TOKEN:-}" ]] && META_HEADERS+=(-H "Authorization: Bearer $GITHUB_TOKEN")

  if META="$(curl -fsSL "${META_HEADERS[@]}" \
       "https://api.github.com/repos/$REPO/releases/tags/v$VERSION" 2>/dev/null)"; then
    CLI_DIGEST="$(printf '%s' "$META" |
      awk -v asset="$CLI_ASSET" '
        /"name":/ { in_asset = ($0 ~ asset) }
        in_asset && /"digest":/ {
          sub(/.*"digest":[[:space:]]*"sha256:/, "")
          sub(/".*/, "")
          print
          exit
        }
      ')"
  else
    log "warning: api.github.com unreachable (likely rate-limited); skipping sha256 verification"
  fi

  log "downloading $CLI_URL"
  curl -fL --progress-bar -o "$TMP/$CLI_ASSET" "$CLI_URL" \
    || die "download failed: $CLI_URL"

  if [[ -n "${CLI_DIGEST:-}" ]]; then
    got="$(sha256_of "$TMP/$CLI_ASSET")"
    [[ "$got" == "$CLI_DIGEST" ]] \
      || die "sha256 mismatch: expected $CLI_DIGEST, got $got"
    log "verified sha256 $got"
  fi

  log "installing CLI to $CLI_DEST"
  mkdir -p "$CLI_DEST"
  tar -xzf "$TMP/$CLI_ASSET" -C "$CLI_DEST"
fi

[[ -x "$LAUNCHER" ]] || die "launcher not found after extract: $LAUNCHER"

# ---- Wire up the in-bundle launcher --------------------------------------

mkdir -p "$SKILL_DIR/bin"
ln -sf "../cli/compose-preview-${VERSION}/bin/compose-preview" "$SKILL_LAUNCHER"
log "skill bundle launcher: $SKILL_LAUNCHER"

# ---- Optional global symlink ---------------------------------------------

mkdir -p "$BIN_DIR"
ln -sf "$LAUNCHER" "$BIN_DIR/compose-preview"
log "symlinked $BIN_DIR/compose-preview -> $LAUNCHER"

# ---- Smoke test -----------------------------------------------------------

if ! "$LAUNCHER" --help >/dev/null 2>&1; then
  die "launcher failed smoke test (needs Java 17+ on PATH or JAVA_HOME)"
fi

# ---- Cloud: write env vars ------------------------------------------------

maybe_write_env_file

# ---- PATH advice ----------------------------------------------------------

case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *)
    if [[ "$CLAUDE_CLOUD" != 1 ]]; then
      cat >&2 <<EOF

note: $BIN_DIR is not on your PATH.

  bash/zsh:  echo 'export PATH="$BIN_DIR:\$PATH"' >> ~/.bashrc  # or ~/.zshrc
  fish:      fish_add_path $BIN_DIR

EOF
    fi
    ;;
esac

link_skills_for_detected_hosts

log "installed compose-preview $VERSION"
log "skill bundle: $SKILL_DIR"
log "next: run 'compose-preview doctor' in your project to verify Gradle access"
