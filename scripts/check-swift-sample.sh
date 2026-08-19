#!/usr/bin/env bash
# Type-check the Swift usage sample in docs/design/RC_PLAYER_SWIFT.md against the real XCFramework.
#
# The sample is the *only* documentation of how to call the player from Swift, and everything
# awkward about it — the `RcComposeViewControllerKt.` file facade, all five arguments being
# required, `Data` not bridging to `ByteArray` — is a property of Kotlin/Native's Objective-C export
# rather than a choice made here. Which means the doc drifts silently whenever the export changes,
# and the first person to notice is a consumer whose project will not build.
#
# So the doc is the tested artifact: the fenced `swift` blocks are extracted and compiled, with no
# second copy of the code to keep in sync. Two kinds of block are skipped, both self-describing:
#   * the `Package.swift` manifest fragment — a manifest, not app code;
#   * any block containing `…`, which marks it as illustrative rather than complete.
#
# Type-check only (`-typecheck`): this proves every name, selector and type in the sample exists as
# written, which is the failure mode. Linking a static Kotlin/Native framework would add minutes and
# catch nothing extra at the API level.
#
# Skips loudly, and exits 0, when the toolchain or the framework is absent — the framework only
# builds on macOS, so this is a no-op on a Linux runner rather than a failure.
#
# Usage: scripts/check-swift-sample.sh [path/to/RcComposePlayer.xcframework]
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

doc="docs/design/RC_PLAYER_SWIFT.md"
xcframework="${1:-rc-player/compose/build/XCFrameworks/release/RcComposePlayer.xcframework}"

if ! command -v xcrun > /dev/null 2>&1; then
  echo "skip: no xcrun on this host; the Swift sample is only checkable on macOS" >&2
  exit 0
fi

# Prefer the simulator slice: it builds for the host architecture, so no device signing is involved.
slice="$xcframework/ios-arm64-simulator/RcComposePlayer.framework"
if [ ! -d "$slice" ]; then
  echo "skip: no assembled framework at $slice" >&2
  echo "      build one with ./gradlew :rc-player-compose:assembleRcComposePlayerReleaseXCFramework" >&2
  exit 0
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
sample="$work/Sample.swift"

# The preamble supplies only what the prose leaves to the reader — the document bytes and the two
# handlers the sample calls. Everything the sample asserts about the *player's* API comes from the
# doc itself.
cat > "$sample" <<'PREAMBLE'
import Foundation
import UIKit

let documentData = Data()
func handle(_ event: RcPlayerEvent) {}
func show(_ message: String) {}
PREAMBLE

python3 - "$doc" >> "$sample" <<'EXTRACT'
import re, sys

text = open(sys.argv[1]).read()
blocks = re.findall(r"```swift\n(.*?)```", text, re.S)
kept = 0
for block in blocks:
    if "// Package.swift" in block:
        continue
    if "…" in block:  # an illustrative block, not complete code
        continue
    sys.stdout.write(block)
    sys.stdout.write("\n")
    kept += 1
if kept == 0:
    sys.exit("error: no compilable swift blocks found in the doc — did the fences change?")
EXTRACT

sdk="$(xcrun --sdk iphonesimulator --show-sdk-path)"
arch="$(uname -m)"
target="$arch-apple-ios13.0-simulator"

echo "type-checking $doc against $slice ($target)"
# `-Xcc -Wno-...` is not used: the only warning the export produces today is Compose Multiplatform's
# own nested-type mapping, and it should stay visible rather than be suppressed here.
xcrun -sdk "$sdk" swiftc \
  -target "$target" \
  -F "$(dirname "$slice")" \
  -typecheck "$sample"

echo "swift sample: ok"
