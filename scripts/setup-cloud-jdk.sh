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
# Usage:
#   scripts/setup-cloud-jdk.sh [PRIMARY_INSTALL_DIR]
#
# Env (override defaults):
#   CLOUD_JDK_MAJORS  space-separated JDK majors to provision
#                     (default: "17 21 25"). The FIRST is the "primary" — its
#                     JAVA_HOME is printed on stdout for
#                     `export JAVA_HOME=$(scripts/setup-cloud-jdk.sh)`.
#   JDK17_VERSION     pin the primary's Temurin tag (back-compat alias for the
#                     primary major when it is 17; default: latest GA).
#   JDK17_DIR         primary install dir (default: /opt/jdk<primary>, or arg 1).
#   JDK<major>_DIR    install dir for a specific major (default: /opt/jdk<major>).
#   JDK<major>_VERSION pin a specific major's Temurin tag (default: latest GA).
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
  if ! cmp -s "$system_truststore" "$dest"; then
    cp -f "$dest" "$dest.adoptium-orig" 2>/dev/null || true
    cp -f "$system_truststore" "$dest"
    echo "[setup-cloud-jdk] swapped $dest <- $system_truststore (trusts proxy CA)" >&2
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
  if [[ -z "$tag" ]]; then
    tag="$(curl -fsSL -o /dev/null -w '%{url_effective}' \
      "https://github.com/adoptium/temurin${major}-binaries/releases/latest" | sed 's#.*/tag/##')"
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

  mkdir -p "$install_dir"
  local tmp
  tmp="$(mktemp)"
  if ! curl -fsSL -o "$tmp" "$url"; then
    rm -f "$tmp"
    echo "[setup-cloud-jdk] WARNING: download failed for JDK $major ($url); skipping" >&2
    return 1
  fi
  tar -xzf "$tmp" --strip-components=1 -C "$install_dir"
  rm -f "$tmp"

  if [[ ! -x "$install_dir/bin/java" ]]; then
    echo "[setup-cloud-jdk] WARNING: expected $install_dir/bin/java after extract (JDK $major) — skipping" >&2
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
