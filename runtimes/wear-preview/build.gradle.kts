@file:Suppress(
  "DEPRECATION"
) // AndroidSingleVariantLibrary(Boolean, Boolean) is deprecated; see :splash.

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

// `:wear-preview-runtime` — composable-helper authoring path for **Wear TransformingLazyColumn item
// scaling** in an isolated `@Preview`. Sister to `:splash-preview-runtime` /
// `:slot-preview-runtime`:
// a tiny helper consumed inside a regular `@Preview`, no new renderer strategy.
//
// `TlcScalingHost { spec -> … }` hosts a real single-item `TransformingLazyColumn` (the item
// flanked
// by spacer items so the list genuinely scrolls) and hands the caller the *genuine*
// `TransformingLazyColumnItemScope` + `TransformationSpec` — so the preview body is exactly the
// code a
// live list item uses (`Modifier.transformedHeight(this, spec)` + `SurfaceTransformation(spec)`),
// with real Wear scaling. Pair it with a plain `@Preview` for a still, `@ScrollingPreview(GIF,
// reduceMotion = false)` for the scaling scroll GIF, or `ProvideTlcScalePosition` to pin a
// position.
//
// `wear-compose` is `compileOnly`: consumers are Wear apps that already bring it, and keeping it
// off
// the published POM avoids pinning a specific `wear-compose` alpha onto every consumer (same model
// as `:splash`/`:notification` for Compose).
//
// The module's second helper is `CapturingWearWidgetPreview` — a Glance Wear **widget** preview
// that
// renders the host's squircle container AND surfaces the widget's encoded RemoteCompose document as
// the render's `.rc` sidecar, so a bundled widget travels as data rather than compiled `@Preview`
// bytecode. Upstream's `WearWidgetPreview` builds that document internally and keeps the bytes to
// itself, so a preview calling it directly emits no IR. Shared by `:samples:wear-widget` and
// `:samples:design-catalog-remote-m3`. Unlike `TlcScalingHost` it does take a real dependency on
// `:data-render-core` (the `IrSidecarChannel` hand-off it offers into) and on coroutines — see the
// `dependencies` block for why the latter can't be `compileOnly`.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  // Like every other published android-library runtime (`:splash`, `:notification`, …): the
  // maven-publishing convention only runs `configureKotlinCompatibility(...)` when tapmoc is
  // present, so without this the AAR ships without the documented `kotlinCoreLibraries` floor.
  alias(libs.plugins.tapmoc)
}

android {
  namespace = "ee.schimke.composeai.wear.preview"
  // wear-compose 1.7.0-alpha requires `compileSdk = 37` (mirrors :samples:design-catalog-wear-m3).
  compileSdk = 37

  buildFeatures { compose = true }

  // `CapturingWearWidgetPreview` touches Glance Wear / Remote Compose APIs marked
  // `@RestrictTo(LIBRARY_GROUP)`. The file-level `@Suppress("RestrictedApiAndroidX")` quiets the
  // IDE inspection, but AGP lint runs `RestrictedApi` separately — disable it here as AndroidX's
  // own samples (and `:samples:wear-widget` / `:data-remotecompose-connector`) do.
  lint { disable += "RestrictedApi" }
}

dependencies {
  compileOnly(platform(libs.compose.bom.stable))
  compileOnly(libs.compose.ui)
  compileOnly(libs.compose.foundation)
  compileOnly(libs.wear.compose.material3)
  compileOnly(libs.wear.compose.foundation)

  // `CapturingWearWidgetPreview` — the second helper in this module: a Glance Wear widget preview
  // that ALSO surfaces the widget's encoded RemoteCompose document as the render's `.rc` sidecar,
  // so a bundled widget travels as data rather than compiled `@Preview` bytecode.
  // `IrSidecarChannel`
  // is the render-harness hand-off it offers the bytes into, so it's a real (published) dependency
  // rather than `compileOnly` — the helper calls it at render time.
  implementation(project(":data-render-core"))
  // `runBlocking` for the one-shot document capture. Deliberately NOT `compileOnly`: consumers do
  // have coroutines transitively, but at whatever version their own graph resolves — the alpha
  // Compose/glance-wear artifacts drag in 1.9.0, while this module compiles against the repo's
  // pinned 1.11.0. `runBlocking` is not ABI-compatible across that gap (1.11 mangles the call site
  // to `BuildersKt.runBlockingK$default`, which 1.9.0 doesn't declare), so a `compileOnly` edge
  // throws `NoSuchMethodError` at render time and the capture silently degrades to "no `.rc`".
  // Declaring it as a real dependency puts the matching version on every consumer's runtime
  // classpath so compile and runtime agree.
  implementation(libs.kotlinx.coroutines.core)

  // Glance Wear + the Remote Compose creation API the widget document is built from. `compileOnly`
  // for the same reason as `wear-compose` above: consumers are Wear widget modules that already
  // bring these alpha artifacts themselves (`:samples:wear-widget`,
  // `:samples:design-catalog-remote-m3`), and keeping them off the published POM avoids pinning a
  // specific glance-wear / compose-remote alpha onto every consumer of this runtime.
  compileOnly(libs.glance.wear)
  compileOnly(libs.glance.wear.core)
  compileOnly(libs.glance.wear.tooling.preview)
  compileOnly(libs.compose.remote.creation.compose)
}

mavenPublishing {
  configure(
    AndroidSingleVariantLibrary(
      javadocJar = JavadocJar.Empty(),
      sourcesJar = SourcesJar.Sources(),
      variant = "release",
    )
  )
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "wear-preview-runtime",
    displayName = "Compose Preview — Wear Runtime",
    description =
      "Composable helpers for Wear previews: host a component in a real single-item " +
        "TransformingLazyColumn so an isolated `@Preview` shows genuine TLC item scaling (scale + " +
        "fade toward the edges) with the component authored in the normal list-item code; and " +
        "preview a Glance Wear widget in its host container while capturing the widget's encoded " +
        "RemoteCompose document as the render's `.rc` IR sidecar.",
  )
  inceptionYear.set("2026")
}
