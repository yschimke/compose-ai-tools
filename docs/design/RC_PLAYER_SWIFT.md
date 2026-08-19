# The Remote Compose player from Swift

How `ee.schimke.composeai:rc-player-compose`'s iOS entry point is packaged, what it looks like from
Swift, and the two places where the Kotlin API reads worse across the boundary than it does in
Kotlin. Companion to [RC_CMP_WASM_PLAYER.md](RC_CMP_WASM_PLAYER.md), which covers the player itself.

Implements #4068.

## Consuming it

> [!WARNING]
> **The Swift distribution is not shipping yet — see [#4222](https://github.com/yschimke/compose-ai-tools/issues/4222).**
> The release job that builds `RcComposePlayer.xcframework.zip` runs out of heap during the
> Kotlin/Native release link, so it is disabled: no release carries the asset, `Package.swift` on
> `main` still holds its `v0.0.0` placeholder, and no bare `X.Y.Z` tag exists to resolve. The
> `.package(...)` line below therefore describes the intended shape, not something you can add to a
> project today — a resolve fails on the placeholder checksum. Everything after it (the call shape,
> the export quirks) is accurate and still checked against the Kotlin source. The iOS klibs
> themselves do publish to Maven Central and can be consumed from a Kotlin Multiplatform project.

```swift
// Package.swift
.package(url: "https://github.com/yschimke/compose-ai-tools.git", from: "1.15.1")
```

```swift
import RcComposePlayer
import UIKit

let controller = RcComposeViewControllerKt.RcComposeViewController(
  bytes: KotlinByteArray(bytes: documentData),
  theme: .system,
  onEvent: { event in handle(event) },
  typefaces: RcTypefaceLoaderCompanion.shared.Default,
  onError: { message in show(message) }
)
```

Three things in that call are not what a Swift author would write from scratch, and all three are
properties of Kotlin/Native's Objective-C export rather than choices made here. They are spelled
out because the generated header is the only other place they are written down, and it ships inside
the zip.

**`RcComposeViewControllerKt.` is not a typo.** `RcComposeViewController` is a *top-level* Kotlin
function, and Kotlin/Native exports top-level declarations as static members of a class named after
their file — so the entry point lands on `RcComposeViewControllerKt`, not in the global namespace.
Read out of the generated header, the selector is
`RcComposeViewController(bytes:theme:onEvent:typefaces:onError:)`, retaining Kotlin's capital `R`.

**Every argument is required.** The Kotlin declaration defaults `theme`, `onEvent`, `typefaces`, and
`onError`; Objective-C has no default arguments, so the exported selector takes all five. Passing
`.system` and `RcTypefaceLoaderCompanion.shared.Default` reproduces the Kotlin defaults.

**`Data` does not bridge to `ByteArray`.** `KotlinByteArray` exports only `init(size:)`,
`get(index:)`, and `set(index:value:)` — there is no `Data` initializer, and none is generated.
The copy has to be written on the Swift side; this extension is what the sample above calls, and the
sign reinterpretation matters because Kotlin's `Byte` is signed while Swift's `UInt8` is not:

```swift
extension KotlinByteArray {
  convenience init(bytes: Data) {
    self.init(size: Int32(bytes.count))
    for (offset, byte) in bytes.enumerated() {
      set(index: Int32(offset), value: Int8(bitPattern: byte))
    }
  }
}
```

These are ergonomics gaps rather than defects — the call works exactly as written — and closing
them means adding a Swift wrapper target beside the binary target, which
[#4068](https://github.com/yschimke/compose-ai-tools/issues/4068) leaves for after the first
published framework.

**"Works exactly as written" is checked, not asserted — but the check is currently off.**
`scripts/check-swift-sample.sh` extracts the `swift` blocks above and type-checks them against the
assembled XCFramework, and the `rc-player-tests` CI job ran it right after building one. It no
longer does: assembling the framework is what [#4222](https://github.com/yschimke/compose-ai-tools/issues/4222)
disabled, and the script exits 0 when no framework is present, so leaving it wired in would have
reported green while checking nothing. **Until that issue is closed, the sample below is asserted
rather than checked** — run `scripts/check-swift-sample.sh` by hand against a locally assembled
framework if you edit it. The intent, restored with the link: this document is the tested
artifact — there is no second copy of the sample to keep in sync — and every property described
here is a property of Kotlin/Native's Objective-C export rather than a choice made in this repo, so
it can change under us without any Kotlin source changing. That is exactly the drift the check
exists to catch: an earlier draft of this page called a bare `RcComposeViewController(...)` and a
`KotlinByteArray.from(_:)` that does not exist, and nothing caught either until a reviewer read the
generated header by hand.

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
| `Package.swift` pointed at it, bare `<version>` tagged | same job, via `scripts/update-package-swift.sh` |

**Why a separate Swift tag rather than the `v<version>` release tag.** SPM's `binaryTarget`
addresses a zip by URL and pins it by checksum, verified at resolve time. Both values can only be
written *after* the asset exists — so `Package.swift` at `v<version>` necessarily still describes the
*previous* release, and a consumer resolving that tag would download the wrong binary. The Swift tag
points at the commit made after the upload, which is the first commit where the file and the asset
agree.

**Why the Swift tag is bare `1.16.0` and not `swift/1.16.0`.** SwiftPM reads a ref as a semantic
version only when the *entire* ref is `X.Y.Z` or `vX.Y.Z`; a leading `v` is the only decoration it
strips. A `swift/1.16.0` ref is therefore not a version at all, and `.package(..., from: "1.16.0")`
never saw it.

It did, however, see **`v1.16.0`** — the release tag, whose `Package.swift` describes the *previous*
release by construction. So the old scheme did not make `from:` resolve nothing; it made it resolve
the **wrong framework**, which is worse and quieter. Measured against Swift 6.3: with both refs
present SwiftPM selects the bare tag, in both orderings (bare on the older commit and on the newer),
so once `publish-xcframework` has run the bare tag governs.

Two things follow, and they are the reason this is a mitigation rather than a cure:

- **There is a window.** `v<version>` exists from the moment the release chain writes it until
  `publish-xcframework` pushes the bare tag. A `from:` resolve inside it gets the stale framework.
  Normally minutes, since both happen in one release run.
- **A failed `publish-xcframework` leaves the window open indefinitely**, with a SwiftPM-visible
  `v<version>` pointing at the wrong binary. Re-run the job — it is idempotent, and it refuses to
  move an existing Swift tag that describes a different asset.

Closing it properly needs `Package.swift` at `v<version>` to be correct *already*, which the
checksum-after-upload ordering forbids, or the Swift package to live in its own repository where the
version tags are its own. Tracked on [#4068](https://github.com/yschimke/compose-ai-tools/issues/4068).

**And the binary is built from `v<version>`, not from `main`.** `main` can advance between the tag
being cut and `publish-xcframework` starting; building from the branch would attach a framework of
newer source to the older release, disagreeing with the Maven artifacts and source release that
`publish-gradle-plugin` builds from the tag. Only the `Package.swift` commit touches `main`, on a
fresh checkout taken after the upload.

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
