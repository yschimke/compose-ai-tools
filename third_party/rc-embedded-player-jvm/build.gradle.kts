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
    // `ColorTheme` index -> `android.R.color` name table, plus the light/dark mode resolution both
    // players share. Neutral: it names resources and resolves indices through a caller-supplied
    // lookup, and never touches `Resources` itself — which is what lets this lane behave correctly
    // (fallbacks, and a mode that was actually chosen) with no system palette to read.
    "androidx/compose/remote/player/compose/embedded/ColorThemeResolution.kt",
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
    // ---- the draw path (import-clean via the text + image seams) --------------------------------
    // The op interpreter and paint decoder, plus the component-tree dispatch split out of RcPlayer.
    "androidx/compose/remote/player/compose/embedded/RcPlayerDrawing.kt",
    "androidx/compose/remote/player/compose/embedded/RcPlayerPaint.kt",
    "androidx/compose/remote/player/compose/embedded/RcPlayerDispatch.kt",
    "androidx/compose/remote/player/compose/embedded/RcPlayerCanvas.kt",
    "androidx/compose/remote/player/compose/embedded/RcPlayerModifiers.kt",
    "androidx/compose/remote/player/compose/embedded/RcPlayerDensity.kt",
    // Custom (host-extension) components: schemas, the property reader, the plugin registry, and
    // the
    // dispatch leaf. All neutral Compose + remote-core — the host supplies the actual rendering.
    "androidx/compose/remote/player/compose/embedded/RcPlayerCustom.kt",
    // Per-layout composables (the neutral ones; text/image/custom get jvm siblings, see below).
    "androidx/compose/remote/player/compose/embedded/layout/RcPlayerBoxLayout.kt",
    "androidx/compose/remote/player/compose/embedded/layout/RcPlayerColumnLayout.kt",
    "androidx/compose/remote/player/compose/embedded/layout/RcPlayerRowLayout.kt",
    "androidx/compose/remote/player/compose/embedded/layout/RcPlayerFitBoxLayout.kt",
    "androidx/compose/remote/player/compose/embedded/layout/RcPlayerStateLayout.kt",
    "androidx/compose/remote/player/compose/embedded/layout/RcPlayerCollapsibleLayout.kt",
    "androidx/compose/remote/player/compose/embedded/layout/RcPlayerLayoutAlignment.kt",
    // Component modifiers — all neutral Compose modifier factories.
    "androidx/compose/remote/player/compose/embedded/modifier/AlignByModifier.kt",
    "androidx/compose/remote/player/compose/embedded/modifier/BackgroundModifier.kt",
    "androidx/compose/remote/player/compose/embedded/modifier/BorderModifier.kt",
    "androidx/compose/remote/player/compose/embedded/modifier/ClickModifier.kt",
    "androidx/compose/remote/player/compose/embedded/modifier/ClipModifier.kt",
    "androidx/compose/remote/player/compose/embedded/modifier/GraphicsLayerModifier.kt",
    "androidx/compose/remote/player/compose/embedded/modifier/HeightModifier.kt",
    "androidx/compose/remote/player/compose/embedded/modifier/MarqueeModifier.kt",
    "androidx/compose/remote/player/compose/embedded/modifier/OffsetModifier.kt",
    "androidx/compose/remote/player/compose/embedded/modifier/PaddingModifier.kt",
    "androidx/compose/remote/player/compose/embedded/modifier/RippleModifier.kt",
    "androidx/compose/remote/player/compose/embedded/modifier/ScrollModifier.kt",
    "androidx/compose/remote/player/compose/embedded/modifier/WidthModifier.kt",
    "androidx/compose/remote/player/compose/embedded/modifier/ZIndexModifier.kt",
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
  listOf(
    "androidx/compose/remote/player/compose/embedded/RcPlayerTextPlatformJvm.kt",
    // The jvm draw RemoteContext — StoreBackedRemoteContext + a skiko `loadBitmap` decode, the one
    // platform-bound member of the contract. Written here, so it names skiko rather than the SDK.
    "androidx/compose/remote/player/compose/embedded/JvmRemoteContext.kt",
    // jvm halves of the three Android-only draw seams the shared draw path calls: image decode over
    // skiko, and no-op stubs for the deferred AGSL shaders and particles.
    "androidx/compose/remote/player/compose/embedded/RcPlayerImagePlatformJvm.kt",
    "androidx/compose/remote/player/compose/embedded/RcPlayerShadersJvm.kt",
    "androidx/compose/remote/player/compose/embedded/RcPlayerParticlesJvm.kt",
    // Vendored path utilities the draw path calls (core PathData -> Compose Path). Neutral upstream
    // source that ships only inside the `remote-player-compose` AAR a kotlin(jvm) module can't
    // consume, so it's vendored here; FloatsToPath swaps the conic op to skiko. See those files.
    "androidx/compose/remote/player/compose/utils/PathUtils.kt",
    "androidx/compose/remote/player/compose/utils/FloatsToPath.kt",
    // jvm halves of the two per-layout composables whose Android originals name framework types:
    // the
    // text seam (google:/device: fonts -> nearest standard family) and the image layout (the
    // `Drawable` host loader -> the embedded `ImageBitmap` decode). Different filenames from their
    // Android siblings so the per-relative-path `include` can't pull both in. See those files.
    "androidx/compose/remote/player/compose/embedded/RcPlayerTextLayoutJvm.kt",
    "androidx/compose/remote/player/compose/embedded/layout/RcPlayerImageLayoutJvm.kt",
  )

kotlin {
  sourceSets["main"].kotlin.apply {
    srcDir("../rc-embedded-player/src/main/kotlin")
    // `include` filters every srcDir of this source set, so this module's own sources need a
    // pattern too — otherwise the explicit list above would silently exclude them.
    //
    // Scoped to `…/rcembedded/jvm/**` rather than `ee/**` for the same reason the file lists above
    // are explicit: the pattern is matched across *both* srcDirs, so a bare `ee/**` also sweeps in
    // whatever the Android module keeps under `ee/` — which is Android-only by construction (it
    // names `androidx.compose.ui.text.font.Font(File, …)`, whose jvm counterpart lives in
    // `…text.platform`). This module's own sources are all under the `jvm` package, so scoping the
    // pattern to it keeps the boundary a build-level fact rather than a convention to remember.
    include(sharedPlayerSources + jvmPlayerSources + "ee/schimke/composeai/rcembedded/jvm/**")
  }
}

// Share the Android module's `.rc` test fixtures (e.g. `rc-fixtures/TitleCardRemote-640x480.rc`)
// rather than duplicating the binaries, so the jvm render harness test rasterizes the *same*
// captured document the Android embedded lane does — the only way its output is comparable.
sourceSets["test"].resources.srcDir("../rc-embedded-player/src/test/resources")

// Forward the rc-compare jvm lane's staging properties to the test worker, mirroring the Android
// module's `rc.embedded.*` forwarding. `RcJvmRenderHarness` reads `<id>.rc` + `manifest.json` from
// `rc.jvm.input` and writes `<id>.png` to `rc.jvm.output`; the `rc-compare.mjs --embedded-jvm` lane
// diffs those against the baked PNG, the same shape as the embedded (Android) lane.
tasks.withType<Test>().configureEach {
  // `rc.jvm.svg.report` is `RcJvmFigmaSvgExportTest`'s: it writes the lane's report and the whole
  // `compose-figma.svg` export beside that path, the jvm counterpart of the Android module's
  // `rc.semantics.report` forwarding — so a run can be compared against the embedded lane's export
  // file-for-file, not just by the counts both print.
  // `composeai.fonts.*` are `GoogleFontTypefaceResolver`'s: with a cache directory the harness
  // resolves a `google:` family the way `serve`'s cmp-jvm subprocess does (the cli passes the same
  // property), so an rc-compare row for a document naming a branded face compares the real face
  // against the other lanes' rather than a local substitute. Unset — the default — keeps the
  // resolver off and the render hermetic, which is what `check` wants.
  for (key in
    listOf(
      "rc.jvm.input",
      "rc.jvm.output",
      "rc.jvm.svg.report",
      "composeai.fonts.cacheDir",
      "composeai.fonts.offline",
    )) {
    (project.findProperty(key) as String?)?.let { systemProperty(key, it) }
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
  // Material3 (desktop) for the ripple modifier; androidx.collection for the layout maps — both
  // multiplatform, matching what the Android module pulls in.
  @Suppress("DEPRECATION") implementation(compose.material3)
  implementation(libs.androidx.collection)

  // Production `compose/figma-svg` export for the serve cmp-jvm lane. The player still owns only
  // the Remote Compose interpretation; this connector turns the resulting ordinary CMP scene's
  // slot tables + semantics into the same layered SVG the desktop preview daemon emits.
  implementation(project(":data-layoutinspector-connector"))
  implementation(project(":data-layoutinspector-core"))

  // Downloadable fonts for the jvm text seams (`GoogleFontTypefaceResolver`). Android resolves a
  // `google:` family through `FontsContractCompat`; off Android there is no provider, so the face
  // is fetched through the same `(family, weight, italic) -> File` cache the Robolectric
  // downloadable-font shadow and the figma-svg embed path use — one cache and one resolution rule,
  // so every lane draws the same file for the same family.
  implementation(project(":data-fonts-google"))

  // androidx.tracing 2.x, used directly (not through `:rc-player-trace`) by `RcJvmRenderer`. This
  // module renders through AndroidX's own embedded player, so it traces with AndroidX's own tracer;
  // the category and span names deliberately line up with `RcTraceCategory`'s so a single Perfetto
  // capture puts this lane and the CMP player's lane on comparable tracks. See `RcJvmRenderer.kt`.
  implementation(libs.androidx.tracing.kmp)

  testImplementation(libs.junit)
  // Manifest parsing for the rc-compare jvm render harness (RcJvmRenderHarness) — parsed via the
  // runtime `Json` API, so no serialization compiler plugin is needed.
  testImplementation(libs.kotlinx.serialization.json)

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
