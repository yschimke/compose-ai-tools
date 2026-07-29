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

// `:third-party-rc-embedded-player` — a vendored snapshot of AndroidX's **experimental Compose
// embedded Remote Compose player** (`RcPlayer`), lifted out of the androidx integration-test app
// that hosts it upstream (`compose/remote/integration-tests/player-compose-embedded`). See
// `PROVENANCE.md` for the pinned upstream commit and our local deltas.
//
// Why vendor it: upstream ships this player only as `SoftwareType.TEST_APPLICATION` sources inside
// an integration-test module — there is no published `androidx.compose.remote:remote-player-compose
// -embedded` artifact to depend on. To offer it as a *render lane* next to the existing
// `RemoteDocumentPlayer` (the `remote-player-view` path `RemoteComposeIrReplay` uses today) we need
// the sources in our own build.
//
// What it is: a pure-Compose interpreter for a `CoreDocument`. It walks the document's operation
// tree and emits Compose layout/draw nodes directly, where `remote-player-view`'s
// `RemoteComposePlayer` is an Android `View` painting to a framework `Canvas`, bridged in via
// `AndroidView`. That difference is the whole point of the comparison lane: this player composes,
// measures, and draws with Compose's own primitives, so its output is what a host embedding Remote
// Compose content *inside* a Compose tree actually sees.
//
// Android-library for now (`android.graphics.Paint`/`Typeface`/`RuntimeShader` on the text and
// shader paths, `AndroidRemoteContext` for the platform `RemoteContext`). The CMP android/jvm split
// that lets this render headlessly without Robolectric is tracked in `PROVENANCE.md`.

plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
  // Keep the upstream package so the vendored sources stay a verbatim snapshot — diffing against a
  // newer androidx checkout is a plain `diff -r`, with no rename noise to sift through.
  namespace = "androidx.compose.remote.player.compose.embedded"

  // The alpha `compose-remote` AARs declare `minCompileSdk = 37`; same override the
  // `:data-remotecompose-connector` and `:samples:remotecompose` modules carry.
  compileSdk = 37

  defaultConfig {
    // `AndroidRemoteContext` + the alpha player artifacts require API 29.
    minSdk = 29
    // Consumable from our compileSdk-36 modules (see the connector for the full rationale).
    aarMetadata { minCompileSdk = 36 }
  }

  // AGP 9 defaults `androidResources` to false for library modules. The typeface resolver and the
  // text-layout path both read the GMS font-provider certificates from the googlefonts `R` class,
  // and a dependency `R` class is only generated when resource processing is on — without this the
  // two references fail to resolve.
  buildFeatures { androidResources = true }

  // The render harness is a Robolectric test that inflates real Compose content, so it needs the
  // library's merged resources on the unit-test classpath.
  testOptions { unitTests { isIncludeAndroidResources = true } }

  // The player reaches `androidx.compose.remote.core.*` members marked `@RestrictTo(LIBRARY_GROUP)`
  // — unavoidable for an out-of-tree copy of in-tree code. Upstream's module disables it too.
  lint { disable += "RestrictedApi" }
}

// Hand the render harness its input/output directories. Gradle properties rather than ambient env,
// so a run is reproducible from the command line:
//
//   ./gradlew :third-party-rc-embedded-player:testDebugUnitTest \
//     -Prc.embedded.input=<dir with <id>.rc + manifest.json> -Prc.embedded.output=<dir>
//
// Absent either property the harness skips, so `check` stays green without a staged catalog.
tasks.withType<Test>().configureEach {
  for (key in
    listOf("rc.embedded.input", "rc.embedded.output", "rc.view.output", "rc.semantics.report")) {
    (project.findProperty(key) as String?)?.let { systemProperty(key, it) }
  }
  // Robolectric's NATIVE graphics mode needs a real heap to rasterize into.
  maxHeapSize = "2g"
}

dependencies {
  // Document model + operation tree. `remote-core` is a plain `java-library` upstream, which is
  // what makes the planned jvm target of the CMP split viable at all.
  api(libs.compose.remote.core)
  // `RemoteDocument`, `StateUpdater`, and `AndroidRemoteContext` (the platform `RemoteContext`).
  api(libs.compose.remote.player.core)
  // `ExperimentalRemotePlayerApi` opt-in marker only.
  implementation(libs.compose.remote.player.compose)
  // `LambdaAction` / `PendingIntentAction` (the click-action types `RcPlayer` dispatches) and
  // `CapturedDocument` (the `rememberRemoteDocument` capture result). The player *consumes* these
  // creation-side types even though it never authors a document itself.
  implementation(libs.compose.remote.creation.compose)

  implementation(platform(libs.compose.bom.compat))
  implementation(libs.compose.runtime)
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  // `Text` in the text-layout path and `ripple` in `RippleModifier` — the player leans on Material3
  // for those two rather than reimplementing them.
  implementation(libs.compose.material3)
  // Downloadable Google Fonts: `GoogleFont`, the `Font` factory, and the certs `R` class the
  // typeface resolver hands to `FontRequest`.
  implementation(libs.compose.ui.text.google.fonts)
  // `FontRequest` / `FontsContractCompat` behind the resolver's `google:` font prefix.
  implementation(libs.androidx.core)
  implementation(libs.androidx.collection)

  // `RcEmbeddedRenderHarness` — rasterizes `.rc` documents through the player for the rc-compare
  // lane. Robolectric with `@GraphicsMode(NATIVE)` is the stopgap until the CMP jvm target lets it
  // run on a plain JVM (see PROVENANCE.md).
  testImplementation(platform(libs.compose.bom.compat))
  testImplementation(libs.ui.test.junit4)
  testImplementation(libs.ui.test.manifest)
  testImplementation(libs.robolectric)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.serialization.json)
  // `RcViewPlayerRenderHarness` — the control lane. Renders the same documents through the
  // `remote-player-view`-backed `RemoteDocumentPlayer` in an identical harness, so a divergence can
  // be attributed to the embedded player rather than to software-canvas rasterization.
  testImplementation(libs.compose.remote.player.view)
  // `RcFigmaSvgExportTest` — runs the production `compose/figma-svg` export over each player's
  // captured tree, so it needs the producers themselves (`ComposeSemanticsDataProducer`,
  // `LayoutInspectorDataProducer`, `ComposeFigmaSvgDataProducer`). The connector `api`-exposes
  // `:data-layoutinspector-core`, which carries the payload DTOs the test walks.
  testImplementation(project(":data-layoutinspector-connector"))
}
