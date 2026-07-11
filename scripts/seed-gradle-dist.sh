#!/usr/bin/env bash
# Seed the Gradle wrapper distribution(s) into the wrapper cache from an
# allowlisted mirror, so the first `./gradlew` never has to fetch one over a
# path the sandbox blocks.
#
# The problem: a wrapper's distributionUrl points at services.gradle.org, which
# 307-redirects to a GitHub release
# (github.com/gradle/gradle-distributions/releases/…). Locked-down cloud
# sandboxes (Claude Code on the web's "custom" network mode is the motivating
# case) routinely block github.com's release assets, so the very first
# `./gradlew` — including the SessionStart warm-up — dies fetching the
# distribution with `Server returned HTTP response code: 403`, before the build
# even starts.
#
# The fix: repo.gradle.org's github-downloads-proxy serves the identical bytes
# (it proxies the same GitHub release server-side) and is a normal Gradle host
# that allowlists already permit. So we fetch the distribution from there,
# checksum-verify it, and drop the zip into $GRADLE_USER_HOME/wrapper/dists
# exactly where the wrapper looks for it — Gradle then unpacks + re-verifies +
# marks it ready itself on the first invocation, with no network. No repo
# changes: the wrapper properties are read, never written.
#
# Single- and multi-repo checkouts: a web session often has several repos
# checked out side by side (the workspace root, i.e. the parent of this repo),
# and they can pin *different* Gradle versions. Seeding only this repo's wrapper
# covers one version; the others' first `./gradlew` still dies on the blocked
# redirect. So we discover every wrapper across the local checkouts, dedup by
# distributionUrl, and seed each distinct version. Override the search root with
# COOEE_CHECKOUTS_DIR.
#
# No-op when there is no wrapper anywhere, when a distribution is already cached
# (warm box / prior run), or when a distributionUrl isn't the services.gradle.org
# default (a custom/self-hosted URL is the project's own call). Best-effort:
# any failure warns and leaves that download to Gradle — it never fails the
# session.

set -uo pipefail

log()  { echo "[seed-gradle-dist] $*" >&2; }
warn() { echo "[seed-gradle-dist] WARNING: $*" >&2; }

project_dir="${CLAUDE_PROJECT_DIR:-$(git -C "$(dirname "$0")" rev-parse --show-toplevel 2>/dev/null || pwd)}"

# The workspace root that holds the side-by-side checkouts — the parent of this
# repo. Never root the scan at a broad system directory: fall back to the
# project dir itself so a single-repo layout still gets its own wrapper seeded.
workspace_root() {
  local root="${COOEE_CHECKOUTS_DIR:-}"
  if [[ -z "$root" ]]; then
    root="$(dirname "$project_dir")"
    case "$root" in ""|.|/|/home|/Users|/root|/usr|/var|/opt|/tmp) root="$project_dir" ;; esac
  fi
  printf '%s' "$root"
}

# Every gradle-wrapper.properties across the local checkouts. Bounded depth keeps
# the scan cheap and still catches both a repo-root wrapper and a nested build's
# wrapper (e.g. <repo>/android/gradle/…).
wrapper_props() {
  local root; root="$(workspace_root)"
  [[ -d "$root" ]] || return 0
  find "$root" -maxdepth 5 -type f \
    -path '*/gradle/wrapper/gradle-wrapper.properties' 2>/dev/null | sort
}

# distributionUrl from a gradle-wrapper.properties, unescaping the properties-file
# '\:' -> ':' and stripping any trailing CR. Empty output when absent.
distribution_url() {
  local props="$1" url
  [[ -f "$props" ]] || return 0
  url=$(sed -n 's/^[[:space:]]*distributionUrl[[:space:]]*=[[:space:]]*//p' "$props" | head -1)
  url="${url%$'\r'}"; url="${url//\\:/:}"
  printf '%s' "$url"
}

# base36(md5(s)) — reproduces org.gradle.wrapper.PathAssembler#getHash, the
# scheme Gradle uses to name a distribution's wrapper cache directory from its
# distributionUrl (MD5 of the URL, rendered as an unsigned BigInteger in base
# 36). Pure bash so it needs no python/bc: md5 -> hex, then repeated
# long-division of that base-16 bignum by 36, collecting remainders.
wrapper_hash() {
  local url="$1" hex
  if command -v md5sum >/dev/null 2>&1; then hex=$(printf '%s' "$url" | md5sum | cut -d' ' -f1)
  elif command -v md5 >/dev/null 2>&1; then hex=$(printf '%s' "$url" | md5 -q)
  else return 1; fi
  [[ ${#hex} -eq 32 ]] || return 1

  local -a nib=() out=(); local i
  for (( i=0; i<32; i++ )); do nib+=($((16#${hex:i:1}))); done

  local digits="0123456789abcdefghijklmnopqrstuvwxyz"
  while ((${#nib[@]})); do
    local -a q=(); local carry=0 started=0 d val qi
    for d in "${nib[@]}"; do
      val=$(( carry*16 + d )); qi=$(( val/36 )); carry=$(( val%36 ))
      if (( started || qi )); then q+=("$qi"); started=1; fi
    done
    out=("$carry" "${out[@]}")      # prepend this base36 digit (the remainder)
    nib=("${q[@]}")
  done

  local s=""; ((${#out[@]})) || s=0
  for d in "${out[@]}"; do s+="${digits:d:1}"; done
  printf '%s' "$s"
}

# Seed one wrapper's distribution (props file + its extracted distributionUrl)
# into the wrapper cache.
seed_one() {
  local props="$1" url="$2"
  local repo="${props%/gradle/wrapper/gradle-wrapper.properties}"

  # Only handle the stock services.gradle.org distribution — the one whose GitHub
  # redirect is what gets blocked. A custom distributionUrl is left untouched.
  case "$url" in
    https://services.gradle.org/distributions/*.zip) : ;;
    *) log "$repo wrapper distributionUrl isn't the services.gradle.org default ($url); leaving the download to Gradle."; return 0 ;;
  esac

  local zipname="${url##*/}"                 # gradle-9.6.1-bin.zip
  local distname="${zipname%.zip}"           # gradle-9.6.1-bin
  local ver="${distname#gradle-}"            # 9.6.1-bin
  ver="${ver%-bin}"; ver="${ver%-all}"       # 9.6.1

  # Where the wrapper looks: <dists>/<distname>/<hash>/, hash = base36(md5(url)).
  # GRADLE_USER_HOME defaults to ~/.gradle; distributionPath to wrapper/dists.
  local hash; hash=$(wrapper_hash "$url") \
    || { warn "couldn't compute the wrapper cache hash for $repo; leaving the download to Gradle."; return 0; }
  local dest="${GRADLE_USER_HOME:-$HOME/.gradle}/wrapper/dists/$distname/$hash"

  # Already there? Either Gradle installed it (.ok marker) or a prior seed placed
  # the zip pending unpack. Nothing to do.
  if [[ -f "$dest/$zipname.ok" || -f "$dest/$zipname" ]]; then
    log "Gradle $ver already present in the wrapper cache; nothing to seed."
    return 0
  fi

  # Rewrite services.gradle.org -> the github-downloads-proxy, which mirrors the
  # exact GitHub release asset (v<ver>/<zipname>) the redirect points at.
  local mirror="https://repo.gradle.org/gradle/github-downloads-proxy/gradle/gradle-distributions/releases/download/v$ver/$zipname"

  log "seeding Gradle $ver into the wrapper cache from repo.gradle.org (services.gradle.org's GitHub redirect is blocked here)..."

  local tmp; tmp=$(mktemp -d "${TMPDIR:-/tmp}/seed-gradle-dist.XXXXXX") \
    || { warn "couldn't create a temp dir for the wrapper seed; skipping."; return 0; }
  local zip="$tmp/$zipname"
  if ! curl -fsSL --retry 3 -o "$zip" "$mirror"; then
    warn "couldn't download Gradle $ver from $mirror; leaving the download to Gradle."
    rm -rf "$tmp"; return 0
  fi

  # Verify: prefer the wrapper's pinned distributionSha256Sum, else the mirror's
  # published .sha256. A mismatch means don't trust the bytes — bail, don't seed.
  local want
  want=$(sed -n 's/^[[:space:]]*distributionSha256Sum[[:space:]]*=[[:space:]]*//p' "$props" | head -1)
  want="${want%$'\r'}"
  [[ -n "$want" ]] || want=$(curl -fsSL "$mirror.sha256" 2>/dev/null | tr -d '[:space:]')
  if [[ -n "$want" ]]; then
    local got; got=$(sha256sum "$zip" | cut -d' ' -f1)
    if [[ "$got" != "$want" ]]; then
      warn "Gradle $ver checksum mismatch (got $got, want $want); refusing to seed. Leaving the download to Gradle."
      rm -rf "$tmp"; return 0
    fi
    log "Gradle $ver checksum verified ($want)."
  else
    warn "no checksum available for Gradle $ver; seeding the download unverified."
  fi

  # Place the verified zip where the wrapper expects it. Gradle finds it there,
  # (re-)checksums it against any pinned sum, unpacks it, and writes the .ok
  # marker on the first `./gradlew` — all offline.
  mkdir -p "$dest" && mv -f "$zip" "$dest/$zipname" || {
    warn "couldn't place the Gradle $ver zip in the wrapper cache ($dest); skipping."
    rm -rf "$tmp"; return 0; }
  rm -rf "$tmp"
  log "Gradle $ver seeded into the wrapper cache; ./gradlew will unpack it without fetching the distribution."
}

main() {
  local -A seen=()
  local props url seeded=0
  while IFS= read -r props; do
    [[ -n "$props" ]] || continue
    url=$(distribution_url "$props")
    [[ -n "$url" ]] || continue
    # Same distribution pinned by more than one checkout — seed it once.
    [[ -n "${seen[$url]:-}" ]] && continue
    seen[$url]=1
    seed_one "$props" "$url"
    seeded=$((seeded + 1))
  done < <(wrapper_props)
  (( seeded )) || log "no Gradle wrapper found in the local checkouts; nothing to seed."
}

main "$@"
