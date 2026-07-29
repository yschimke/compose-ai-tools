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
// What it is: a pure-Compose interpreter for a `CoreDocument` — it walks the document's operation
// tree and emits Compose layout/draw nodes directly, where `remote-player-view`'s
// `RemoteComposePlayer` is an Android `View` that paints to a framework `Canvas` and is bridged into
// Compose via `AndroidView`. That difference is the whole point of the comparison lane: the embedded
// player composes, measures, and draws with Compose's own primitives, so its output is what a host
// embedding Remote Compose content *inside* a Compose tree actually sees.
//
// Android-library for now (`android.graphics.Paint`/`Typeface`/`RuntimeShader` on the text and
// shader paths, `AndroidRemoteContext` for the platform `RemoteContext`). The CMP android/jvm split
// that lets this render headlessly without Robolectric is tracked in `PROVENANCE.md`.

plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
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

  // The player reaches `androidx.compose.remote.core.*` members marked `@RestrictTo(LIBRARY_GROUP)`
  // — unavoidable for an out-of-tree copy of in-tree code. Upstream's own module disables the check.
  lint { disable += "RestrictedApi" }
}

dependencies {
  // Document model + operation tree. `remote-core` is a plain `java-library` upstream, which is what
  // makes the planned jvm target of the CMP split viable at all.
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
}
