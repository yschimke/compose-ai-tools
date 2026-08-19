// swift-tools-version:5.9
//
// Swift Package Manager distribution for the Remote Compose player's iOS framework (#4068).
//
// This file is REWRITTEN BY `release.yml` on every release: the job assembles
// `RcComposePlayer.xcframework.zip`, attaches it to the GitHub Release, and commits the new `url`
// and `checksum` back to `main` before tagging that commit `<version>`. Editing the two values by
// hand is never right — they have to describe an asset that already exists, and SPM verifies the
// checksum at resolve time.
//
// Consume a released version by its Swift tag — a BARE `<version>` (e.g. `1.16.0`), which is what
// makes `from:` work. SwiftPM only reads a tag as a semantic version when the whole ref is `X.Y.Z`
// or `vX.Y.Z`, so a prefixed ref such as `swift/1.16.0` is invisible to the syntax below. The bare
// tag is distinct from the `v<version>` release tag, whose `Package.swift` still describes the
// PREVIOUS release (see docs/design/RC_PLAYER_SWIFT.md):
//
//     .package(url: "https://github.com/yschimke/compose-ai-tools.git", from: "1.15.1")
//
// Coverage: `iosArm64` (device) and `iosSimulatorArm64` (Apple-silicon simulator) only. There is no
// Intel-simulator slice anywhere in this stack — Compose Multiplatform 1.11 stopped publishing the
// variant — so Intel Macs cannot build against this. Stated here rather than discovered at link
// time. See docs/design/RC_PLAYER_SWIFT.md.
import PackageDescription

let package = Package(
  name: "RcComposePlayer",
  platforms: [.iOS(.v13)],
  products: [
    .library(name: "RcComposePlayer", targets: ["RcComposePlayer"])
  ],
  targets: [
    .binaryTarget(
      name: "RcComposePlayer",
      url:
        "https://github.com/yschimke/compose-ai-tools/releases/download/v0.0.0/RcComposePlayer.xcframework.zip",
      // Placeholder until the first release job runs. A resolve against this fails loudly with a
      // checksum mismatch rather than silently fetching something else.
      checksum: "0000000000000000000000000000000000000000000000000000000000000000"
    )
  ]
)
