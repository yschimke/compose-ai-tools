#!/usr/bin/env bash
#
# Bootstrap installer for the compose-preview skill bundle.
#
# Installs into ~/.claude/skills/compose-preview/ so Claude Code's skill
# discovery finds it. Layout:
#
#   ~/.claude/skills/compose-preview/
#   |-- SKILL.md                                       (from skill tarball)
#   |-- design/...                                     (from skill tarball)
#   |-- cli/compose-preview-<ver>/bin/compose-preview  (from CLI tarball)
#   `-- bin/compose-preview -> ../cli/.../compose-preview
#
# Also symlinks ~/.local/bin/compose-preview so the CLI is on PATH without
# the consumer having to know the skill-bundle layout. Idempotent: rerunning
# with the same version is a no-op.
#
# Usage:
#   scripts/install.sh               # install latest release
#   scripts/install.sh 0.3.2         # install a specific version
#   VERSION=0.3.2 scripts/install.sh # same, via env
#
# Override locations:
#   SKILL_DIR=~/.claude/skills/compose-preview scripts/install.sh
#   PREFIX=$HOME/.local scripts/install.sh    # for the ~/.local/bin symlink
#   REPO=yschimke/compose-ai-tools scripts/install.sh
#
# Requires: bash, curl, tar, sha256sum (or shasum), and Java 17 on PATH at
# run time (not install time).
#
# Claude Code cloud-sandbox mode (auto-detected via $CLAUDE_ENV_FILE or
# $CLAUDE_CODE_SESSION_ID):
#   - apt-installs openjdk-17-jdk-headless if Java 17 isn't already available
#     (the pre-installed JDK 21 can't satisfy this project's toolchain pin).
#   - Skips api.github.com lookups (they 403 on shared sandbox IPs due to
#     unauthenticated rate limiting) and resolves versions via the public
#     github.com HTML redirect instead. Sha256 verification is best-effort.
#   - Appends JAVA_HOME and PATH to $CLAUDE_ENV_FILE so subsequent tool
#     invocations see them.
# Force on/off explicitly with CLAUDE_CLOUD=1 / CLAUDE_CLOUD=0.

set -euo pipefail

REPO="${REPO:-yschimke/compose-ai-tools}"
SKILL_DIR="${SKILL_DIR:-$HOME/.claude/skills/compose-preview}"
PREFIX="${PREFIX:-$HOME/.local}"
VERSION="${1:-${VERSION:-}}"

BIN_DIR="$PREFIX/bin"

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

# ---- Cloud: ensure JDK 17 is available -----------------------------------

if [[ "$CLAUDE_CLOUD" == 1 ]]; then
  JDK17_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
  if [[ ! -x "$JDK17_HOME/bin/java" ]]; then
    log "claude cloud: installing openjdk-17-jdk-headless"
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

# ---- Resolve version ------------------------------------------------------

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
SKILL_ASSET="compose-preview-skill-${VERSION}.tar.gz"
CLI_URL="https://github.com/$REPO/releases/download/v${VERSION}/${CLI_ASSET}"
SKILL_URL="https://github.com/$REPO/releases/download/v${VERSION}/${SKILL_ASSET}"

CLI_DEST="$SKILL_DIR/cli"
LAUNCHER="$CLI_DEST/compose-preview-${VERSION}/bin/compose-preview"
SKILL_LAUNCHER="$SKILL_DIR/bin/compose-preview"
SKILL_VERSION_FILE="$SKILL_DIR/.skill-version"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

maybe_write_env_file() {
  if [[ "$CLAUDE_CLOUD" == 1 && -n "${CLAUDE_ENV_FILE:-}" && -w "$(dirname "$CLAUDE_ENV_FILE")" ]]; then
    {
      [[ -n "${JAVA_HOME:-}" ]] && echo "JAVA_HOME=$JAVA_HOME"
      echo "PATH=$BIN_DIR:${JAVA_HOME:+$JAVA_HOME/bin:}\$PATH"
    } >> "$CLAUDE_ENV_FILE"
    log "wrote env vars to \$CLAUDE_ENV_FILE"
  fi
}

# ---- Everything-already-installed short-circuit --------------------------
# Skill version marker + CLI launcher + both symlinks all line up -> done.

if [[ -x "$LAUNCHER" \
   && "$(readlink "$SKILL_LAUNCHER" 2>/dev/null || true)" == *"compose-preview-${VERSION}"* \
   && "$(readlink "$BIN_DIR/compose-preview" 2>/dev/null || true)" == "$LAUNCHER" \
   && "$(cat "$SKILL_VERSION_FILE" 2>/dev/null || true)" == "$VERSION" ]]; then
  log "compose-preview $VERSION already installed and linked"
  "$LAUNCHER" --help >/dev/null 2>&1 || die "installed launcher is broken: $LAUNCHER"
  maybe_write_env_file
  exit 0
fi

mkdir -p "$SKILL_DIR"

# ---- Skill tarball (best-effort: older releases don't ship one) ----------
# Skip re-download when the marker matches. Otherwise wipe the specific
# top-level paths carried by the new tarball (so stale files from an older
# version's skill bundle don't linger) before extracting. Anything the
# user has added outside those paths is preserved.

if [[ "$(cat "$SKILL_VERSION_FILE" 2>/dev/null || true)" == "$VERSION" ]]; then
  log "skill bundle $VERSION already extracted — skipping download"
elif curl -fL --progress-bar -o "$TMP/skill.tar.gz" "$SKILL_URL" 2>/dev/null; then
  log "refreshing skill bundle in $SKILL_DIR"
  # Prune only the top-level entries the new tarball actually carries.
  while IFS= read -r entry; do
    [[ -n "$entry" && "$entry" != "." && "$entry" != ".." ]] || continue
    rm -rf "$SKILL_DIR/$entry"
  done < <(tar -tzf "$TMP/skill.tar.gz" | sed -e 's|^\./||' -e 's|/.*||' | awk 'NF' | sort -u)
  tar -xzf "$TMP/skill.tar.gz" -C "$SKILL_DIR"
  printf '%s\n' "$VERSION" > "$SKILL_VERSION_FILE"
else
  log "warning: $SKILL_ASSET not found on the release; skipping skill files"
  log "         (CLI still installs; release predates skill packaging)"
fi

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
  die "launcher failed smoke test (needs Java 17 on PATH or JAVA_HOME)"
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

log "installed compose-preview $VERSION"
log "skill bundle: $SKILL_DIR"
log "next: run 'compose-preview doctor' in your project to verify Gradle access"
