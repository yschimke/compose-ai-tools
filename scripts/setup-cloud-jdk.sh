#!/usr/bin/env bash
# Provision a Temurin JDK 17 that trusts the sandbox's TLS-intercepting
# egress proxy, for cloud environments where the project toolchain
# (JDK 17, pinned in `gradle/gradle-daemon-jvm.properties`) is not the
# JDK the container ships.
#
# Two problems this solves, both specific to locked-down cloud sandboxes
# (the Claude Code on the web "custom" network mode is the motivating
# case):
#
#  1. **Wrong JDK major.** The container commonly ships only JDK 21, but
#     this repo pins `toolchainVersion=17`. Gradle's toolchain
#     auto-provision goes through api.foojay.io, which an allowlist
#     network policy blocks — so a JDK 17 has to be placed on disk
#     out-of-band. Adoptium publishes Temurin as GitHub release assets,
#     which allowlists that permit github.com *do* serve.
#
#  2. **MITM proxy CA not trusted by a freshly-downloaded JDK.** The
#     sandbox proxy terminates TLS with its own CA. The OS trust store
#     (and the system JDK, whose `cacerts` symlinks to it) carries that
#     CA, which is why `curl` and the pre-installed JDK 21 work. A
#     Temurin tarball straight from Adoptium ships the vanilla Adoptium
#     trust store *without* the proxy CA, so every Java-side HTTPS fetch
#     (Gradle wrapper distribution, Maven Central / Google Maven
#     dependency resolution) fails with:
#       PKIX path building failed: unable to find valid certification
#       path to requested target
#     The fix is to copy the system Java trust store over Temurin's.
#
# Usage:
#   scripts/setup-cloud-jdk.sh [INSTALL_DIR]
#
# Env (override defaults):
#   JDK17_VERSION   Temurin tag to pin (default: latest 17 GA)
#   JDK17_DIR       install dir (default: /opt/jdk17, or arg 1)
#
# Output: prints the absolute JAVA_HOME on stdout (suitable for
# `export JAVA_HOME=$(scripts/setup-cloud-jdk.sh)`).

set -euo pipefail

install_dir="${1:-${JDK17_DIR:-/opt/jdk17}}"

# Locate the system Java trust store that already trusts the proxy CA.
# Order: the ca-certificates-managed store, then the system JDK's
# cacerts (usually a symlink to the former).
system_truststore=""
for candidate in \
  /etc/ssl/certs/java/cacerts \
  /usr/lib/jvm/java-21-openjdk-amd64/lib/security/cacerts \
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

# Reuse an existing install; just make sure its trust store is patched.
if [[ -x "$install_dir/bin/java" ]]; then
  echo "[setup-cloud-jdk] reusing existing JDK at $install_dir" >&2
  fix_truststore "$install_dir"
  echo "$install_dir"
  exit 0
fi

arch="$(uname -m)"
case "$arch" in
  x86_64) jdk_arch="x64" ;;
  aarch64|arm64) jdk_arch="aarch64" ;;
  *) echo "[setup-cloud-jdk] unsupported arch: $arch" >&2; exit 1 ;;
esac

tag="${JDK17_VERSION:-}"
if [[ -z "$tag" ]]; then
  tag="$(curl -fsSL -o /dev/null -w '%{url_effective}' \
    https://github.com/adoptium/temurin17-binaries/releases/latest | sed 's#.*/tag/##')"
fi
# Adoptium asset naming: tag `jdk-17.0.19+10` -> file `..._17.0.19_10.tar.gz`
ver="${tag#jdk-}"
fname_ver="${ver/+/_}"
url="https://github.com/adoptium/temurin17-binaries/releases/download/${tag}/OpenJDK17U-jdk_${jdk_arch}_linux_hotspot_${fname_ver}.tar.gz"

echo "[setup-cloud-jdk] tag=$tag arch=$jdk_arch" >&2
echo "[setup-cloud-jdk] downloading $url" >&2

mkdir -p "$install_dir"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT
curl -fsSL -o "$tmp" "$url"
tar -xzf "$tmp" --strip-components=1 -C "$install_dir"

if [[ ! -x "$install_dir/bin/java" ]]; then
  echo "[setup-cloud-jdk] expected $install_dir/bin/java after extract — layout changed?" >&2
  exit 1
fi

fix_truststore "$install_dir"
echo "[setup-cloud-jdk] installed Temurin $tag at $install_dir" >&2
echo "$install_dir"
