#!/usr/bin/env bash
# Provision Temurin JDKs that trust the sandbox's TLS-intercepting egress
# proxy, for cloud environments where Gradle toolchain auto-provisioning
# (which goes through api.foojay.io) is blocked by an allowlist network
# policy.
#
# Historically this placed a single JDK 17 on disk — the project toolchain
# pinned in `gradle/gradle-daemon-jvm.properties`. But the render subprocess
# now forks on a JDK chosen to match the *consumer's* bytecode target (see
# `RenderJvmSelection` in the Gradle plugin): a consumer whose modules compile
# to Java 21 needs a JDK 21 the render can fork into, or every preview fails
# with `UnsupportedClassVersionError`. In a sandbox where foojay is blocked,
# that JDK has to be on disk out-of-band too — so this script now provisions a
# *set* of JDK majors (default: 17 21 25) and symlinks each into
# `/usr/lib/jvm` so Gradle toolchain detection finds them.
#
# Two problems this solves, both specific to locked-down cloud sandboxes
# (the Claude Code on the web "custom" network mode is the motivating case):
#
#  1. **Missing JDK major.** The container commonly ships only one JDK, but
#     the daemon toolchain and the per-consumer render toolchain may need
#     others. Gradle's toolchain auto-provision goes through api.foojay.io,
#     which an allowlist network policy blocks — so the JDKs have to be
#     placed on disk. Adoptium publishes Temurin as GitHub release assets,
#     which allowlists that permit github.com *do* serve.
#
#     Caveat that shapes how the release tag is resolved below: some sandboxes
#     gate github.com *per repository* rather than wholesale. Claude Code on
#     the web scopes github.com to the session's attached repos, so a request
#     to `adoptium/temurin17-binaries` comes back 403 — while the release
#     *asset* download (which redirects out to objects.githubusercontent.com)
#     is still served normally. So tag resolution cannot rely on github.com,
#     even though the download can. See `resolve_latest_tag`.
#
#  2. **MITM proxy CA not trusted by a freshly-downloaded JDK.** The sandbox
#     proxy terminates TLS with its own CA. The OS trust store (and the
#     system JDK, whose `cacerts` symlinks to it) carries that CA, which is
#     why `curl` and the pre-installed JDK work. A Temurin tarball straight
#     from Adoptium ships the vanilla Adoptium trust store *without* the proxy
#     CA, so every Java-side HTTPS fetch (Gradle wrapper distribution, Maven
#     Central / Google Maven dependency resolution) fails with:
#       PKIX path building failed: unable to find valid certification path
#       to requested target
#     The fix is to copy the system Java trust store over Temurin's.
#
# Nothing is downloaded for a major this box already has. Reuse is checked in
# two places: this script's own install dir, and then the JDKs actually present
# — `java` on PATH, `$JAVA_HOME`, and the directories Gradle scans for
# toolchains. That second check is the difference between working and dead on a
# container provisioned by something else: a Nix-installed Temurin symlinked
# into `/usr/lib/jvm` is a perfectly good JDK 17, and going to Adoptium for
# another one fails outright where github.com is gated per repository. See
# `discover_jdk_home`.
#
# Usage:
#   scripts/setup-cloud-jdk.sh [PRIMARY_INSTALL_DIR]
#
# Env (override defaults):
#   CLOUD_JDK_MAJORS  space-separated JDK majors to provision
#                     (default: "17 21 25"). The FIRST is the "primary" — its
#                     JAVA_HOME is printed on stdout for
#                     `export JAVA_HOME=$(scripts/setup-cloud-jdk.sh)`.
#   JDK17_VERSION     pin the primary's Temurin tag (back-compat alias for the
#                     primary major when it is 17; default: latest GA). A pinned
#                     tag names a specific build, so it also opts that major out
#                     of reusing whatever JDK happens to be on the box.
#   JDK17_DIR         primary install dir (default: /opt/jdk<primary>, or arg 1).
#   JDK<major>_DIR    install dir for a specific major (default: /opt/jdk<major>).
#   JDK<major>_VERSION pin a specific major's Temurin tag (default: latest GA).
#   CLOUD_JDK_REUSE_EXISTING
#                     `0` to always download, even when a JDK of that major is
#                     already installed elsewhere (default: reuse).
#   CLOUD_JDK_SEARCH_DIRS
#                     space-separated globs searched for existing JDKs
#                     (default: "/usr/lib/jvm/* /opt/jdk* /opt/*jdk*
#                     $HOME/.sdkman/candidates/java/*").
#
# Output: prints the absolute JAVA_HOME of the PRIMARY JDK on stdout.
# Additional majors are best-effort: a major Adoptium hasn't published yet
# (or a transient download failure) logs a warning and is skipped rather than
# failing the whole run — the primary JDK and any that did install are still
# usable.

set -euo pipefail

# The set of majors to provision. First entry is primary (its JAVA_HOME is
# emitted on stdout and never treated as best-effort).
read -r -a JDK_MAJORS <<<"${CLOUD_JDK_MAJORS:-17 21 25}"
primary_major="${JDK_MAJORS[0]}"

# Locate the system Java trust store that already trusts the proxy CA.
# Order: the ca-certificates-managed store, then the system JDK's cacerts
# (usually a symlink to the former).
system_truststore=""
for candidate in \
  /etc/ssl/certs/java/cacerts \
  /usr/lib/jvm/java-21-openjdk-amd64/lib/security/cacerts \
  /usr/lib/jvm/java-17-openjdk-amd64/lib/security/cacerts \
  "${JAVA_HOME:-}/lib/security/cacerts"; do
  if [[ -n "$candidate" && -f "$candidate" ]]; then
    system_truststore="$candidate"
    break
  fi
done

fix_truststore() {
  local jdk="$1"
  local dest="$jdk/lib/security/cacerts"
  if [[ -z "$system_truststore" ]]; then
    echo "[setup-cloud-jdk] WARNING: no system trust store found; leaving Temurin cacerts as-is" >&2
    return 0
  fi
  # Only swap if the Temurin store differs (idempotent re-runs are a no-op).
  # A JDK we did not install may live somewhere unwritable — a Nix/Guix store
  # path is read-only by design — so the copy is best-effort. A JDK whose trust
  # store we cannot replace is still perfectly good for compiling and rendering;
  # only Java-side HTTPS through the MITM proxy is affected, and whatever
  # provisioned that JDK owns its trust store. Failing the whole run over it
  # would be the wrong trade.
  if ! cmp -s "$system_truststore" "$dest"; then
    cp -f "$dest" "$dest.adoptium-orig" 2>/dev/null || true
    if cp -f "$system_truststore" "$dest" 2>/dev/null; then
      echo "[setup-cloud-jdk] swapped $dest <- $system_truststore (trusts proxy CA)" >&2
    else
      echo "[setup-cloud-jdk] WARNING: could not write $dest (read-only JDK?); leaving its trust store as-is" >&2
    fi
  fi
}

# Symlink the JDK into a directory Gradle scans for toolchains
# (`/usr/lib/jvm`, one of the "Common Linux Locations"). Without this,
# Gradle only finds the JDK when it is the *current* daemon JVM — so a build
# whose daemon runs on a different JDK (e.g. the system JDK, because
# `JAVA_HOME` did not propagate to a spawned `gradlew`) cannot resolve a
# `languageVersion=N` toolchain even though the JDK is on disk. This is what
# makes the repo's own `toolchainVersion=17` daemon-jvm pin work under a
# different launcher, and what lets a consumer's Java-21 render fork resolve
# a JDK 21 while the daemon runs on 17. foojay auto-provisioning is the usual
# fallback, but cloud allowlists block it.
link_into_jvm_dir() {
  local jdk="$1"
  local major="$2"
  local jvm_dir="/usr/lib/jvm"
  local link="$jvm_dir/temurin-$major"
  if [[ ! -d "$jvm_dir" ]]; then
    mkdir -p "$jvm_dir" 2>/dev/null || {
      echo "[setup-cloud-jdk] WARNING: cannot create $jvm_dir; toolchain auto-detection may miss this JDK" >&2
      return 0
    }
  fi
  if [[ "$(readlink -f "$link" 2>/dev/null)" != "$(readlink -f "$jdk" 2>/dev/null)" ]]; then
    ln -sfn "$jdk" "$link"
    echo "[setup-cloud-jdk] linked $link -> $jdk (Gradle toolchain auto-detection)" >&2
  fi
}

arch="$(uname -m)"
case "$arch" in
  x86_64) jdk_arch="x64" ;;
  aarch64 | arm64) jdk_arch="aarch64" ;;
  *)
    echo "[setup-cloud-jdk] unsupported arch: $arch" >&2
    exit 1
    ;;
esac

# Resolve the install dir for a major, honouring the back-compat JDK17_DIR /
# arg-1 override for the primary and the generic JDK<major>_DIR otherwise.
install_dir_for() {
  local major="$1"
  local var="JDK${major}_DIR"
  if [[ "$major" == "$primary_major" && -n "${PRIMARY_INSTALL_DIR:-}" ]]; then
    echo "${PRIMARY_INSTALL_DIR}"
  else
    echo "${!var:-/opt/jdk${major}}"
  fi
}

# The feature major of the JDK at <java_home> (17, 21, 8, …), or nothing (rc 1).
#
# Read from `release` (`JAVA_VERSION="17.0.19"`) — every JDK since 9 ships it,
# and it costs no process. Falls back to running `java -version` for an install
# that lacks the file. `1.8.0_412` normalises to 8, so a legacy JDK is never
# mistaken for major "1".
jdk_major_of() {
  local home="$1" v=""
  if [[ -r "$home/release" ]]; then
    v="$(sed -n 's/^JAVA_VERSION="\(.*\)"$/\1/p' "$home/release" | head -n 1)"
  fi
  if [[ -z "$v" && -x "$home/bin/java" ]]; then
    v="$("$home/bin/java" -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p')"
  fi
  [[ -n "$v" ]] || return 1
  case "$v" in
    1.*) printf '%s' "$(printf '%s' "$v" | cut -d. -f2)" ;;
    *) printf '%s' "${v%%[.+_-]*}" ;;
  esac
}

# Echo the JAVA_HOME of a JDK of <major> that is already on this box, or nothing
# (rc 1). Checks the active `java`, then `$JAVA_HOME`, then the locations Gradle
# itself scans for toolchains.
#
# This is what stops the script downloading a JDK the container already has. The
# old reuse check looked only at its own install dir (`/opt/jdk<major>`), so a
# box whose JDK 17 lives anywhere else — a Nix-provisioned Temurin symlinked to
# `/usr/lib/jvm/temurin-17`, say, which is exactly what the coo.ee/env
# bootstrap produces — was treated as having no JDK 17 at all. It then went to
# Adoptium for one, and in a sandbox that gates github.com per-repository the
# fetch 403s and the whole setup aborts. A JDK that is present, on PATH, and of
# the right major is the answer to the question being asked.
discover_jdk_home() {
  local major="$1" cand home
  if command -v java >/dev/null 2>&1; then
    home="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
    if [[ -x "$home/bin/java" && "$(jdk_major_of "$home" || true)" == "$major" ]]; then
      printf '%s' "$home"
      return 0
    fi
  fi
  # `/usr/lib/jvm/*` covers both distro JDKs and the symlink farm this script's
  # own `link_into_jvm_dir` (and other provisioners) maintain there. Overridable
  # via CLOUD_JDK_SEARCH_DIRS (space-separated, globs allowed) for a layout none
  # of these cover — and so the self-test can search a fixture instead of the
  # machine it runs on.
  local dirs="${CLOUD_JDK_SEARCH_DIRS:-/usr/lib/jvm/* /opt/jdk* /opt/*jdk* $HOME/.sdkman/candidates/java/*}"
  # Deliberately unquoted: $dirs must both word-split and glob-expand here.
  # shellcheck disable=SC2086
  for cand in "${JAVA_HOME:-}" $dirs; do
    [[ -n "$cand" && -x "$cand/bin/java" ]] || continue
    if [[ "$(jdk_major_of "$cand" || true)" == "$major" ]]; then
      printf '%s' "$cand"
      return 0
    fi
  done
  return 1
}

# Resolve a pinned Temurin tag for a major, if the caller set JDK<major>_VERSION
# (or the legacy JDK17_VERSION for major 17).
pinned_tag_for() {
  local major="$1"
  local var="JDK${major}_VERSION"
  local pinned="${!var:-}"
  if [[ -z "$pinned" && "$major" == "17" ]]; then
    pinned="${JDK17_VERSION:-}"
  fi
  echo "$pinned"
}

# Resolve the latest GA Temurin tag (e.g. `jdk-17.0.20+8`) for a major.
# Echoes the tag on stdout, returns non-zero if no source yielded a usable one.
#
# Adoptium's API is asked first, with the github.com `releases/latest` redirect
# kept only as a fallback. That ordering — and the shape check on every
# candidate — is deliberate. The previous implementation was:
#
#   tag="$(curl -fsSL -o /dev/null -w '%{url_effective}' "$latest_url" | sed 's#.*/tag/##')"
#
# which fails open in a way that is genuinely hard to see:
#
#   * On failure curl still emits `%{url_effective}` — the *request* URL. That
#     URL contains no `/tag/`, so the sed matches nothing and passes it through
#     unchanged. `tag` ends up holding a full URL, which is non-empty and so
#     sails past the `[[ -z "$tag" ]]` guard.
#   * `set -e` does not catch the failed curl. `pipefail` is set, so the
#     pipeline does report curl's status — but bash suppresses `-e` for a
#     failure raised inside a function that is itself running in a command
#     substitution being assigned (`primary_home="$(install_major "$major")"`),
#     which is exactly the call shape here.
#
# The two combined turned a 403 into a download URL with the whole failed
# request URL interpolated into both the tag and version slots, an inevitable
# 404, and an empty /opt/jdk<major> left on disk.
#
# So: never infer success from a non-empty string. Check curl's status
# explicitly, and require the result to actually look like a Temurin tag.
resolve_latest_tag() {
  local major="$1"
  local next=$((major + 1))
  local body tag

  # Preferred: Adoptium's own API. Returns exactly the tag format the asset
  # naming below expects, and is reachable in sandboxes that gate github.com.
  local api="https://api.adoptium.net/v3/info/release_names"
  api+="?release_type=ga&version=%5B${major}%2C${next}%29&vendor=eclipse"
  api+="&image_type=jdk&os=linux&architecture=${jdk_arch}&jvm_impl=hotspot"
  api+="&page_size=1&sort_method=DEFAULT&sort_order=DESC"
  if body="$(curl -fsSL --retry 2 --retry-delay 1 "$api" 2>/dev/null)"; then
    tag="$(printf '%s' "$body" | grep -o '"jdk-[^"]*"' | head -n 1 | tr -d '"')"
    if [[ "$tag" == jdk-* ]]; then
      printf '%s\n' "$tag"
      return 0
    fi
  fi
  echo "[setup-cloud-jdk] Adoptium API did not yield a tag for JDK $major; trying github.com" >&2

  # Fallback: follow github.com's `releases/latest` redirect. Capture curl's
  # status on its own before touching the value, so a failure is actually seen.
  local eff
  if eff="$(curl -fsSL -o /dev/null -w '%{url_effective}' \
    "https://github.com/adoptium/temurin${major}-binaries/releases/latest" 2>/dev/null)"; then
    # No `/tag/` in the URL leaves `eff` unchanged — caught by the shape check.
    tag="${eff##*/tag/}"
    if [[ "$tag" == jdk-* ]]; then
      printf '%s\n' "$tag"
      return 0
    fi
  fi

  return 1
}

# Install one JDK major. Echoes the install dir on success (stdout), returns
# non-zero on failure. All human-readable logging goes to stderr.
install_major() {
  local major="$1"
  local install_dir
  install_dir="$(install_dir_for "$major")"

  # Reuse an existing install; just make sure its trust store + symlink are set.
  if [[ -x "$install_dir/bin/java" ]]; then
    echo "[setup-cloud-jdk] reusing existing JDK $major at $install_dir" >&2
    fix_truststore "$install_dir"
    link_into_jvm_dir "$install_dir" "$major"
    echo "$install_dir"
    return 0
  fi

  local tag
  tag="$(pinned_tag_for "$major")"

  # Nothing in *our* install dir — but the box may already have this major
  # somewhere else, and downloading a second copy of a JDK that is already on
  # PATH is both wasteful and, in a sandbox that blocks Adoptium, fatal.
  #
  # Skipped when the caller pinned a tag for this major (JDK<major>_VERSION):
  # that names a specific build, so "some JDK 17" is not what was asked for.
  # `CLOUD_JDK_REUSE_EXISTING=0` forces a download unconditionally.
  if [[ -z "$tag" && "${CLOUD_JDK_REUSE_EXISTING:-1}" != "0" ]]; then
    local found
    if found="$(discover_jdk_home "$major")" && [[ -n "$found" ]]; then
      echo "[setup-cloud-jdk] JDK $major already present at $found — reusing it (no download)" >&2
      fix_truststore "$found"
      link_into_jvm_dir "$found" "$major"
      echo "$found"
      return 0
    fi
  fi

  if [[ -z "$tag" ]]; then
    tag="$(resolve_latest_tag "$major")" || tag=""
  fi
  if [[ -z "$tag" ]]; then
    echo "[setup-cloud-jdk] WARNING: could not resolve a Temurin $major release tag; skipping" >&2
    return 1
  fi
  # Adoptium asset naming: tag `jdk-17.0.19+10` -> file `..._17.0.19_10.tar.gz`;
  # early-access lines use `jdk-25+36-ea-beta` -> `..._25_36-ea-beta.tar.gz`.
  local ver="${tag#jdk-}"
  local fname_ver="${ver/+/_}"
  local url="https://github.com/adoptium/temurin${major}-binaries/releases/download/${tag}/OpenJDK${major}U-jdk_${jdk_arch}_linux_hotspot_${fname_ver}.tar.gz"

  echo "[setup-cloud-jdk] major=$major tag=$tag arch=$jdk_arch" >&2
  echo "[setup-cloud-jdk] downloading $url" >&2

  # Download to a temp file *before* creating the install dir, so a failed
  # fetch does not leave an empty /opt/jdk<major> behind masquerading as an
  # install. `rmdir` (not `rm -rf`) cleans up on the later failure paths: it
  # only succeeds on an empty directory, so a caller-supplied dir that already
  # had contents is never destroyed.
  local tmp
  tmp="$(mktemp)"
  if ! curl -fsSL --retry 3 --retry-delay 2 -o "$tmp" "$url"; then
    rm -f "$tmp"
    echo "[setup-cloud-jdk] WARNING: download failed for JDK $major ($url); skipping" >&2
    return 1
  fi

  mkdir -p "$install_dir"
  if ! tar -xzf "$tmp" --strip-components=1 -C "$install_dir"; then
    rm -f "$tmp"
    echo "[setup-cloud-jdk] WARNING: extract failed for JDK $major ($url); skipping" >&2
    rmdir "$install_dir" 2>/dev/null || true
    return 1
  fi
  rm -f "$tmp"

  if [[ ! -x "$install_dir/bin/java" ]]; then
    echo "[setup-cloud-jdk] WARNING: expected $install_dir/bin/java after extract (JDK $major) — skipping" >&2
    rmdir "$install_dir" 2>/dev/null || true
    return 1
  fi

  fix_truststore "$install_dir"
  link_into_jvm_dir "$install_dir" "$major"
  echo "[setup-cloud-jdk] installed Temurin $tag at $install_dir" >&2
  echo "$install_dir"
  return 0
}

# arg 1 overrides the PRIMARY install dir (back-compat with the single-JDK contract).
export PRIMARY_INSTALL_DIR="${1:-${JDK17_DIR:-}}"

primary_home=""
for major in "${JDK_MAJORS[@]}"; do
  if [[ "$major" == "$primary_major" ]]; then
    # The primary is required — a failure here is fatal (preserves the old
    # single-JDK behaviour where a failed install exited non-zero).
    primary_home="$(install_major "$major")"
  else
    # Additional majors are best-effort — never fail the whole run for one.
    install_major "$major" >/dev/null || true
  fi
done

if [[ -z "$primary_home" ]]; then
  echo "[setup-cloud-jdk] ERROR: primary JDK $primary_major failed to install" >&2
  exit 1
fi

echo "$primary_home"
