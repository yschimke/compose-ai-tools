# The Remote Compose player from Swift

How `ee.schimke.composeai:rc-player-compose`'s iOS entry point is packaged, what it looks like from
Swift, and the two places where the Kotlin API reads worse across the boundary than it does in
Kotlin. Companion to [RC_CMP_WASM_PLAYER.md](RC_CMP_WASM_PLAYER.md), which covers the player itself.

Implements #4068.

## Consuming it

```swift
// Package.swift
.package(url: "https://github.com/yschimke/compose-ai-tools.git", from: "1.15.1")
```

```swift
import RcComposePlayer
import UIKit

let controller = RcComposeViewController(
  bytes: KotlinByteArray.from(documentData),
  theme: .system,
  onEvent: { event in handle(event) },
  typefaces: RcTypefaceLoaderCompanion.shared.Default,
  onError: { message in show(message) }
)
```

**Device and Apple-silicon simulator only.** There is no Intel-simulator slice anywhere in this
stack: Compose Multiplatform 1.11 stopped publishing the variant, so `:rc-player-compose` cannot
declare `iosX64` and its three siblings dropped theirs rather than publish a stack that resolves
three of its four artifacts on one target ([#4066](https://github.com/yschimke/compose-ai-tools/issues/4066)).
Intel Macs cannot build against this. Stated here rather than discovered at link time.

## How it is built and shipped

| step | where |
|---|---|
| `iosArm64` + `iosSimulatorArm64` static frameworks | `rc-player/compose/build.gradle.kts` |
| combined into `RcComposePlayer.xcframework` | `assembleRcComposePlayerReleaseXCFramework` (registered by `XCFrameworkConfig`) |
| zipped reproducibly + SHA-256 | `:rc-player-compose:rcPlayerXcframeworkChecksum` |
| attached to the GitHub Release | `release.yml` → `publish-xcframework` |
| `Package.swift` pointed at it, `swift/<version>` tagged | same job, via `scripts/update-package-swift.sh` |

**Why a `swift/<version>` tag rather than the `v<version>` release tag.** SPM's `binaryTarget`
addresses a zip by URL and pins it by checksum, verified at resolve time. Both values can only be
written *after* the asset exists — so `Package.swift` at `v<version>` necessarily still describes the
*previous* release, and a consumer resolving that tag would download the wrong binary. The Swift tag
points at the commit made after the upload, which is the first commit where the file and the asset
agree.

**The zip is built reproducibly** (`isPreserveFileTimestamps = false`, `isReproducibleFileOrder =
true`) because the checksum has to match the bytes a consumer downloads. Without it, a re-run of the
release job would produce a `Package.swift` that no longer matches the already-uploaded asset.

**CI links it.** Until this landed, `ci.yml` compiled the iOS *test* targets and never linked or
packaged a framework, so framework assembly was entirely unexercised — and the note at the top of
the `rc-player-tests` job records that `linkDebugTestIosSimulatorArm64` has died before. The player
job now runs `rcPlayerXcframeworkChecksum` as a separate Gradle invocation, deliberately: the two
release links are the heaviest work in the job and running them alongside the test compiles is what
has failed in the past.

## What the Kotlin API actually looks like from Swift

#4068 asked whether the retyped parameters from #4058 and #4060 read *worse* across the boundary
than the `Int` and `Map` they replaced. Read out of the generated Objective-C header rather than
guessed:

**`RcPlayerTheme` — better.** It exports as an Obj-C enum class with `light` / `dark` / `system`
class properties, so Swift writes `theme: .system`. That is a plain improvement on passing `-2`.

**`RcTypefaceLoader` — better.** It exports as an Obj-C *protocol*, so a Swift host implements
`families` and `typeface(family:settings:)` directly rather than assembling a dictionary of Kotlin
objects. The companion values are reachable as `RcTypefaceLoaderCompanion.shared.Default` /
`.Empty` — slightly awkward, and the one place the Kotlin form is nicer.

**`RcPlayerEvent` — worse, and unavoidably so.** A Kotlin `sealed interface` flattens to a bare
Obj-C protocol with no members:

```objc
__attribute__((swift_name("RcPlayerEvent")))
@protocol RCPRcPlayerEvent
@required
@end
```

so Swift gets no exhaustive `switch`, only casts against the concrete classes:

```swift
func handle(_ event: RcPlayerEvent) {
  if let action = event as? RcPlayerEventHostAction {
    …
  } else if let named = event as? RcPlayerEventHostNamedAction {
    …
  } else if let debug = event as? RcPlayerEventDebugMessage {
    …
  }
  // No compiler error when a new case is added — see below.
}
```

The cost is real: adding a case to `RcPlayerEvent` is a source-compatible change in Kotlin that
compiles fine in Swift and silently does nothing at runtime. Nothing in Kotlin/Native's Obj-C export
fixes that today. What it argues for is treating a new `RcPlayerEvent` case as a **minor-version,
release-noted** change rather than an additive one, since Swift consumers cannot be told by their
compiler.

**Type names are clean because the stack is exported.** The frameworks `export(...)`
`:rc-player-runtime`, `:rc-player-protocol` and `:rc-player-trace`, which is legal because each
module already depends on the next with `api`. Without it, Kotlin/Native prefixes every transitively
sourced type with its module name and a Swift consumer sees `Rc_player_runtimeRcPlayerEvent` and
`Rc_player_protocolRcDocument` in the callbacks and parameters it has to name.

## Not covered here

CocoaPods. SPM reuses the release chain that already exists; a podspec is a second artifact and a
second publish path to keep in step, and nothing has asked for it yet.
