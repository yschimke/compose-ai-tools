#!/usr/bin/env bash
# Point `Package.swift`'s binary target at a released XCFramework zip.
#
# Swift Package Manager pins a `binaryTarget` by URL *and* SHA-256, and verifies the checksum when a
# consumer resolves the package. So these two values can only be written once the asset exists —
# which is why `release.yml` calls this after uploading the zip, not before.
#
# It rewrites exactly the two values and nothing else, so the comments in `Package.swift` (which
# explain the Intel-simulator gap and the bare `<version>` Swift tag scheme) survive a release.
#
# Usage: scripts/update-package-swift.sh <url> <sha256> [package-swift-path]
#        scripts/update-package-swift.sh --self-test
set -euo pipefail

rewrite() {
  local file="$1" url="$2" checksum="$3"
  local before after
  before="$(cat "$file")"
  # Already describing this exact asset. A `workflow_dispatch` re-release of an existing tag hits
  # this every time — the zip is built reproducibly, so the second run computes the same checksum
  # for the same URL and there is nothing to change. That is success, not the "no binaryTarget to
  # rewrite" failure below; conflating the two aborts the recovery path precisely when it is
  # working. Checked before the rewrite so the distinction does not depend on the regexes.
  if printf '%s' "$before" | grep -qF "\"$url\"" &&
    printf '%s' "$before" | grep -qF "checksum: \"$checksum\""; then
    echo "$file already points at $url; nothing to rewrite"
    return 0
  fi
  # `url:` may sit on its own line (swift-format wraps long URLs), so match across the newline.
  after="$(
    printf '%s' "$before" |
      perl -0pe "s{url:\\s*\"[^\"]*\"}{url:\n        \"$url\"}s" |
      perl -0pe "s{checksum: \"[0-9a-f]*\"}{checksum: \"$checksum\"}s"
  )"
  if [ "$before" = "$after" ]; then
    echo "error: $file has no binaryTarget url/checksum to rewrite" >&2
    return 1
  fi
  printf '%s\n' "$after" > "$file"
}

self_test() {
  local dir
  dir="$(mktemp -d)"
  trap 'rm -rf "$dir"' RETURN
  cat > "$dir/Package.swift" <<'FIXTURE'
// A comment that must survive.
    .binaryTarget(
      name: "RcComposePlayer",
      url:
        "https://example.invalid/old/RcComposePlayer.xcframework.zip",
      checksum: "0000000000000000000000000000000000000000000000000000000000000000"
    )
FIXTURE
  rewrite "$dir/Package.swift" "https://example.invalid/new/RcComposePlayer.xcframework.zip" "abc123"

  grep -q 'https://example.invalid/new/RcComposePlayer.xcframework.zip' "$dir/Package.swift" ||
    { echo "self-test: url was not rewritten" >&2; return 1; }
  grep -q 'checksum: "abc123"' "$dir/Package.swift" ||
    { echo "self-test: checksum was not rewritten" >&2; return 1; }
  grep -q 'A comment that must survive' "$dir/Package.swift" ||
    { echo "self-test: the file was clobbered rather than edited" >&2; return 1; }
  grep -q 'old/RcComposePlayer' "$dir/Package.swift" &&
    { echo "self-test: the previous url is still present" >&2; return 1; }

  # Rewriting to the values already present is the `workflow_dispatch` re-release path, and it must
  # succeed rather than trip the "nothing to rewrite" failure below.
  rewrite "$dir/Package.swift" "https://example.invalid/new/RcComposePlayer.xcframework.zip" "abc123" ||
    { echo "self-test: a no-op rewrite should have succeeded" >&2; return 1; }
  grep -q 'checksum: "abc123"' "$dir/Package.swift" ||
    { echo "self-test: the no-op rewrite changed the checksum" >&2; return 1; }

  # A file with nothing to rewrite must fail loudly: a release that silently left the placeholder
  # checksum in place would publish a Package.swift no consumer can resolve.
  printf 'nothing here\n' > "$dir/Empty.swift"
  if rewrite "$dir/Empty.swift" "https://example.invalid/x.zip" "abc" 2>/dev/null; then
    echo "self-test: a file with no binary target should have failed" >&2
    return 1
  fi

  echo "update-package-swift self-test: ok"
}

if [ "${1:-}" = "--self-test" ]; then
  self_test
  exit 0
fi

if [ $# -lt 2 ]; then
  sed -n '2,12p' "$0" >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
rewrite "${3:-$repo_root/Package.swift}" "$1" "$2"
