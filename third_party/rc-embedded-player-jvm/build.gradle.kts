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
  )

kotlin {
  sourceSets["main"].kotlin.apply {
    srcDir("../rc-embedded-player/src/main/kotlin")
    // `include` filters every srcDir of this source set, so this module's own sources need a
    // pattern too — otherwise the explicit list above would silently exclude them.
    include(sharedPlayerSources + "ee/**")
  }
}

// The shared sources are AOSP-formatted (4-space) and must stay a verbatim snapshot, so they are
// exempt from this repo's Google-style ktfmt — same as in the Android module, where AGP's source
// sets happen not to be picked up at all. Only this module's own sources are formatted.
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
}
