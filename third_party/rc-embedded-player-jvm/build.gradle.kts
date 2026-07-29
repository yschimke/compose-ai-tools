/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// `:third-party-rc-embedded-player-jvm` — the **desktop half** of the embedded Remote Compose
// player, and the first thing in this repo to run any of it off Android.
//
// It compiles a subset of `:third-party-rc-embedded-player`'s sources against Compose Desktop
// rather than the Android artifacts. Nothing is copied: the files are shared from the Android
// module's source tree by path, so there is exactly one copy of each and no chance of the two
// drifting.
//
// **Why a separate module rather than converting the Android one to KMP.** The CMP plan
// (`PROVENANCE.md`) has the Android module eventually becoming multiplatform, but that conversion
// carries an unsettled risk — whether `com.android.kotlin.multiplatform.library` under AGP 9
// supports the Robolectric unit tests the render harness depends on. This module needs none of that
// resolved: it is a plain `kotlin("jvm")` module that proves the shared sources genuinely compile
// and run on a JVM, today, and it does so *without touching the Android module's build at all*.
// When the KMP conversion happens, this module's file list is the migration order — and until then
// it is the thing that keeps the "platform-neutral" claim honest, because a file that isn't really
// neutral fails to compile here rather than passing a source scan.
//
// The list below is deliberately explicit rather than a directory glob. Adding a file is a claim
// that it is platform-neutral, and it should be a decision someone makes, not something a wildcard
// makes for them.

plugins {
  id("composeai.base-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

/**
 * Sources shared from the Android module, relative to its `src/main/kotlin`.
 *
 * Keep in sync with `PlatformNeutralSourcesTest`: that test scans imports, this compiles them. The
 * scan is the fast signal, this is the real one.
 */
val sharedPlayerSources =
  listOf(
    // Reflective `CoreDocument` accessors + the document data model — plain `remote-core` types.
    "androidx/compose/remote/player/compose/embedded/CoreDataAccessors.kt",
    "androidx/compose/remote/player/compose/embedded/CoreDataModel.kt",
    // Snapshot-backed store: `RemoteComposeState` over Compose's `SnapshotStateMap`.
    "androidx/compose/remote/player/compose/embedded/SnapshotRemoteComposeState.kt",
    // The platform-neutral `RemoteContext` — ported from `AndroidRemoteContext` minus `loadBitmap`.
    "androidx/compose/remote/player/compose/embedded/StoreBackedRemoteContext.kt",
    // Opaque image-loader handle, so the evaluator can carry one without naming `Drawable`.
    "androidx/compose/remote/player/compose/embedded/RcImageSource.kt",
    // The expression evaluator itself, and the composition locals the state path reads.
    "androidx/compose/remote/player/compose/embedded/GraphContext.kt",
    "androidx/compose/remote/player/compose/embedded/RcPlayerCompositionLocals.kt",
    // Core easing constant -> Compose easing; split out of `RcPlayer.kt` so the expression
    // evaluator below doesn't inherit that file's Android coupling for a six-line `when`.
    "androidx/compose/remote/player/compose/embedded/RcPlayerEasing.kt",
    // The `rememberRemote*AsState` family, and the expression/animation evaluator behind it.
    "androidx/compose/remote/player/compose/embedded/state/RcPlayerState.kt",
    "androidx/compose/remote/player/compose/embedded/state/RcPlayerExpression.kt",
    // The canvas text seam's vocabulary: the paint projection the four text functions take, and the
    // ink-bounds carrier they return. Neutral values, so both halves share this one rather than
    // agreeing on two copies of it.
    "androidx/compose/remote/player/compose/embedded/RcPlayerTextPaintSpec.kt",
  )

/**
 * This module's *own* player sources — the jvm half of a seam, in the shared package but with no
 * counterpart to share.
 *
 * `RcPlayerTextPlatformJvm.kt` answers the four functions `RcPlayerTextPlatform.kt` declares for
 * Android over skiko. The name differs from the Android file's on purpose: `include` patterns are
 * matched per *relative* path across every srcDir, so two files at the same relative path could not
 * be told apart — including the jvm one by name would silently pull in the Android one as well.
 */
val jvmPlayerSources =
  listOf("androidx/compose/remote/player/compose/embedded/RcPlayerTextPlatformJvm.kt")

kotlin {
  sourceSets["main"].kotlin.apply {
    srcDir("../rc-embedded-player/src/main/kotlin")
    // `include` filters every srcDir of this source set, so this module's own sources need a
    // pattern too — otherwise the explicit list above would silently exclude them.
    include(sharedPlayerSources + jvmPlayerSources + "ee/**")
  }
}

// The shared sources are AOSP-formatted (4-space) and must stay a verbatim snapshot, so they are
// exempt from this repo's Google-style ktfmt — same as in the Android module, where AGP's source
// sets happen not to be picked up at all. This also covers `jvmPlayerSources`, which is not
// vendored
// but is a direct sibling of a 4-space file in the same package: matching the file it implements
// beats matching this repo's house style for a seam whose two halves are meant to be read together.
// Everything under `ee/**` — this module's tests — is formatted normally.
tasks
  .withType<org.gradle.api.tasks.SourceTask>()
  .matching { it.name.startsWith("ktfmt") }
  .configureEach { exclude("androidx/**") }

dependencies {
  // Document model + operation tree. A plain `java-library` upstream, which is the whole reason a
  // jvm target is possible at all.
  api(libs.compose.remote.core)

  // Compose Desktop, not the Android artifacts — the point of this module.
  @Suppress("DEPRECATION") implementation(compose.runtime)
  @Suppress("DEPRECATION") implementation(compose.foundation)

  testImplementation(libs.junit)

  // `compose.foundation` brings skiko's *API* jar, which is all the jvm text seam needs to compile
  // against — but calling into Skia needs the platform's `libskiko` too, and that ships in a
  // separate
  // per-OS artifact. `compose.desktop.currentOs` is how the Compose plugin names the right one
  // without this file pinning a skiko version that could drift from `compose-multiplatform`.
  //
  // Test-only on purpose. A library has no business choosing the host it will run on; an
  // application
  // consuming this module declares its own natives, exactly as any Compose Desktop app does. What
  // needs them here is `DesktopTextPlatformTest`, which rasterizes for real rather than mocking the
  // canvas — the whole reason it can catch a measure/draw disagreement.
  @Suppress("DEPRECATION") testRuntimeOnly(compose.desktop.currentOs)
}
