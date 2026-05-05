#!/usr/bin/env bash
#
# Bootstrap installer for the compose-preview skill bundles.
#
# Installs two sibling skills under ~/.claude/skills/ so Claude Code's
# skill discovery finds them:
#
#   ~/.claude/skills/compose-preview/                  (renderer + CLI)
#   |-- SKILL.md                                       (from skill tarball)
#   |-- design/...                                     (from skill tarball)
#   |-- cli/compose-preview-<ver>/bin/compose-preview  (from CLI tarball)
#   `-- bin/compose-preview -> ../cli/.../compose-preview
#
#   ~/.claude/skills/compose-preview-review/           (PR-review workflows)
#   |-- SKILL.md                                       (from skill tarball)
#   `-- design/...                                     (from skill tarball)
#
# Also symlinks ~/.local/bin/compose-preview so the CLI is on PATH without
# the consumer having to know the skill-bundle layout. Idempotent: rerunning
# with the same version is a no-op.
#
# Download safety:
#   By default this script will NOT download executables. It only proceeds
#   when the user has explicitly opted in. This protects against agent loops
#   that re-invoke the installer on every preview, and against generally
#   blind `curl … | bash` consumption.
#
#   - First-time install requires --yes (or COMPOSE_PREVIEW_ACCEPT_DOWNLOAD=1).
#     Without it, the script prints the exact command to run and exits 1.
#   - When a different version is already installed, an upgrade requires
#     --upgrade (or COMPOSE_PREVIEW_ACCEPT_UPGRADE=1). --upgrade implies --yes.
#   - When the requested version matches what's already installed, the
#     script is a no-op (just refreshes any missing symlinks; never downloads).
#
# Usage:
#   scripts/install.sh --yes                   # install latest release
#   scripts/install.sh --yes 0.3.2             # install a specific version
#   VERSION=0.3.2 scripts/install.sh --yes     # same, via env
#   scripts/install.sh --upgrade               # upgrade to latest
#   scripts/install.sh --upgrade 0.4.0         # upgrade to specific version
#   scripts/install.sh --yes --android-sdk     # also install the Android SDK
#                                              # (cmdline-tools + platforms;android-36
#                                              # + platform-tools + build-tools;36.0.0)
#
# Override locations:
#   SKILL_DIR=~/.claude/skills/compose-preview scripts/install.sh --yes
#   PREFIX=$HOME/.local scripts/install.sh --yes  # for the ~/.local/bin symlink
#   REPO=yschimke/compose-ai-tools scripts/install.sh --yes
#   ANDROID_HOME=/opt/android-sdk scripts/install.sh --yes --android-sdk
#   INSTALL_ANDROID_SDK=1 scripts/install.sh --yes  # same as --android-sdk
#
# Requires: bash, curl, tar, sha256sum (or shasum), and Java 17+ on PATH at
# run time (not install time). The --android-sdk path additionally needs
# unzip and write access to $ANDROID_HOME (sudo when not root).
#
# Claude Code cloud-sandbox mode (auto-detected via $CLAUDE_ENV_FILE or
# $CLAUDE_CODE_SESSION_ID):
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
SKILL_DIR="${SKILL_DIR:-$HOME/.claude/skills/compose-preview}"
PREFIX="${PREFIX:-$HOME/.local}"
INSTALL_ANDROID_SDK="${INSTALL_ANDROID_SDK:-0}"
ACCEPT_DOWNLOAD="${COMPOSE_PREVIEW_ACCEPT_DOWNLOAD:-0}"
ACCEPT_UPGRADE="${COMPOSE_PREVIEW_ACCEPT_UPGRADE:-0}"

# Argument parsing — flags first, then positional VERSION. Flags can appear in
# any order. Unknown flags are an error so typos don't get silently swallowed.
positional=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --android-sdk) INSTALL_ANDROID_SDK=1; shift ;;
    --yes|-y) ACCEPT_DOWNLOAD=1; shift ;;
    --upgrade) ACCEPT_UPGRADE=1; ACCEPT_DOWNLOAD=1; shift ;;
    --) shift; positional+=("$@"); break ;;
    -*) echo "error: unknown flag: $1" >&2; exit 1 ;;
    *) positional+=("$1"); shift ;;
  esac
done
set -- "${positional[@]+"${positional[@]}"}"
VERSION="${1:-${VERSION:-}}"

BIN_DIR="$PREFIX/bin"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"

# Claude Code cloud sandbox auto-detection ---------------------------------
if [[ -z "${CLAUDE_CLOUD:-}" ]]; then
  if [[ -n "${CLAUDE_ENV_FILE:-}" || -n "${CLAUDE_CODE_SESSION_ID:-}" ]]; then
    CLAUDE_CLOUD=1
  else
    CLAUDE_CLOUD=0
  fi
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
# The skill bundles are extracted once into $SKILL_DIR / $REVIEW_SKILL_DIR
# (defaults under ~/.claude/skills/). For every other agent host detected on
# this machine, drop a symlink from that host's user-scope skills directory
# pointing at the canonical bundle, so updates flow through automatically and
# disk content isn't duplicated. Detection mirrors `compose-preview mcp install`.
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
  if have_codex; then
    local codex_dir="${CODEX_HOME:-$HOME/.codex}/skills"
    mkdir -p "$codex_dir"
    link_skill_into_dir "codex" "$codex_dir" "$SKILL_DIR"
    [[ -d "$REVIEW_SKILL_DIR" ]] && link_skill_into_dir "codex" "$codex_dir" "$REVIEW_SKILL_DIR"
  fi
  if have_antigravity; then
    local ag_dir="${ANTIGRAVITY_SKILLS_DIR:-$HOME/.gemini/antigravity/skills}"
    mkdir -p "$ag_dir"
    link_skill_into_dir "antigravity" "$ag_dir" "$SKILL_DIR"
    [[ -d "$REVIEW_SKILL_DIR" ]] && link_skill_into_dir "antigravity" "$ag_dir" "$REVIEW_SKILL_DIR"
  fi
}

# ---- Cloud: ensure Java 17+ is available ---------------------------------
#
# Claude Cloud images currently pre-install JDK 21, which already satisfies
# the Java-17+ requirement. The renderer JARs and plugin are compiled to JDK
# 17 bytecode; a newer JDK runs them without issue. Consumer projects pinned
# to a JDK-17 toolchain are handled by Gradle's own toolchain resolution (or
# the consumer can bump their toolchain to the same major the daemon runs
# on). Only apt-install JDK 17 when no JDK 17+ is detected.

if [[ "$CLAUDE_CLOUD" == 1 ]]; then
  detected_major=""
  if command -v java >/dev/null 2>&1; then
    # `java -version` prints `openjdk version "21.0.10" ...` to stderr.
    # Legacy JDK 8 reports `1.8.x`, which parses to major=1 and gets
    # rejected by the >= 17 check below.
    detected_major="$(java -version 2>&1 | head -1 | awk -F'"' '{print $2}' | awk -F. '{print $1}')"
  fi
  if [[ -n "$detected_major" && "$detected_major" =~ ^[0-9]+$ && "$detected_major" -ge 17 ]]; then
    log "claude cloud: using existing JDK $detected_major on PATH"
  else
    JDK17_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
    if [[ ! -x "$JDK17_HOME/bin/java" ]]; then
      if [[ "$ACCEPT_DOWNLOAD" != 1 && "$ACCEPT_UPGRADE" != 1 ]]; then
        die "no JDK 17+ on PATH; rerun with --yes to apt-install openjdk-17-jdk-headless"
      fi
      log "claude cloud: no JDK 17+ detected; installing openjdk-17-jdk-headless"
      SUDO=""
      if [[ $EUID -ne 0 ]]; then
        command -v sudo >/dev/null 2>&1 || die "need root or sudo to install JDK 17"
        SUDO="sudo"
      fi
      $SUDO apt-get install -y -qq openjdk-17-jdk-headless
    fi
    export JAVA_HOME="$JDK17_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi
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
# The order matters for the download-safety gate further down: detect what's
# already installed BEFORE talking to github.com, so a re-invocation that
# wants the existing version can short-circuit without any network at all.

SKILL_VERSION_FILE="$SKILL_DIR/.skill-version"
INSTALLED_VERSION="$(cat "$SKILL_VERSION_FILE" 2>/dev/null || true)"

if [[ -z "$VERSION" ]]; then
  if [[ -n "$INSTALLED_VERSION" && "$ACCEPT_UPGRADE" != 1 ]]; then
    # Something is already installed and the caller didn't ask to upgrade.
    # Default to the installed version so re-invocations are pure no-ops.
    VERSION="$INSTALLED_VERSION"
    log "using installed version $VERSION (pass --upgrade to fetch latest)"
  else
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

# ---- Download-safety gate -------------------------------------------------
#
# Three states, three behaviours:
#   1. Same version already installed → no download, refresh missing symlinks
#      and exit. Always allowed; does not require any flag.
#   2. Nothing installed → fresh install, requires --yes (or
#      COMPOSE_PREVIEW_ACCEPT_DOWNLOAD=1). Otherwise prints the exact command
#      to run and exits 1 without touching the network beyond the version
#      probe above.
#   3. Different version installed → upgrade, requires --upgrade (or
#      COMPOSE_PREVIEW_ACCEPT_UPGRADE=1). Same fail-with-instructions otherwise.
#
# The point: an agent that re-runs the installer between previews must not
# silently re-fetch tarballs. The user has to actively opt in.

print_install_instructions() {
  cat >&2 <<EOF

compose-preview is not installed.

By default this script does not download executables. To proceed, re-run
it with --yes (which confirms you accept the download):

  scripts/install.sh --yes${VERSION:+ $VERSION}

Or pipe form:

  curl -fsSL https://raw.githubusercontent.com/$REPO/main/scripts/install.sh \\
    | bash -s -- --yes

Equivalent env var: COMPOSE_PREVIEW_ACCEPT_DOWNLOAD=1.
EOF
}

print_upgrade_instructions() {
  cat >&2 <<EOF

compose-preview $INSTALLED_VERSION is currently installed; this run would
replace it with $VERSION.

By default the installer refuses to overwrite an existing version. To
upgrade, re-run with --upgrade:

  scripts/install.sh --upgrade${1:+ $1}

Or pipe form:

  curl -fsSL https://raw.githubusercontent.com/$REPO/main/scripts/install.sh \\
    | bash -s -- --upgrade

Equivalent env var: COMPOSE_PREVIEW_ACCEPT_UPGRADE=1. Pass nothing if you
want to keep $INSTALLED_VERSION — leaving --upgrade off is the safe choice.
EOF
}

# Same-version short-circuit happens later (after function defs), so it can
# call link_skills_for_detected_hosts / maybe_write_env_file. Here we only
# enforce the consent gate, which never needs those helpers.
if [[ "$INSTALLED_VERSION" == "$VERSION" && -x "$LAUNCHER" ]]; then
  : # short-circuit eligible — handled below
elif [[ -n "$INSTALLED_VERSION" && "$INSTALLED_VERSION" != "$VERSION" ]]; then
  if [[ "$ACCEPT_UPGRADE" != 1 ]]; then
    print_upgrade_instructions "$VERSION"
    exit 1
  fi
elif [[ "$ACCEPT_DOWNLOAD" != 1 ]]; then
  print_install_instructions
  exit 1
fi

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
  if [[ "$CLAUDE_CLOUD" == 1 && -n "${CLAUDE_ENV_FILE:-}" && -w "$(dirname "$CLAUDE_ENV_FILE")" ]]; then
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
    } >> "$CLAUDE_ENV_FILE"
    log "wrote env vars to \$CLAUDE_ENV_FILE"
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
# Runs after all helper functions are defined. Refreshes any symlinks the
# caller might have blown away and repairs a missing sibling skill bundle
# (cheap, install_skill_bundle is itself a no-op when the per-bundle marker
# matches), but never re-downloads the CLI tarball or the main skill.
#
# Same-version repair doesn't trigger the consent gate above: the user
# already accepted this version when they first installed it. The gate
# exists to stop *new* downloads going through silently, not to block
# topping up a previously-accepted install.

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
