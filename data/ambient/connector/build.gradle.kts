// `:data-ambient-connector` glues `:data-ambient-core` (the wire-shape payload) to the daemon's
// data-product / preview-override surface. Ships:
//
//  - `AmbientStateController` — process-static state holder + callback fan-out.
//  - `ShadowAmbientLifecycleObserver` — Robolectric shadow that consults the controller so
//    consumer code wrapping its UI in `AmbientAware { ... }` (or registering its own
//    `AmbientLifecycleCallback`) sees the requested state instead of silently degrading to
//    `Inactive`.
//  - `AmbientOverrideExtension` / `AmbientPreviewOverrideExtension` — Compose `AroundComposable`
//    plumbing that primes the controller before the consumer's `AmbientAware` reaches it.
//  - `AmbientInputDispatchObserver` — `RecordingScriptDispatchObserver` that wakes the controller
//    on activating gestures (touch click / pointer-down, RSB rotary scroll) so a recording can
//    exercise the ambient ↔ interactive flip.
//  - `AmbientDataProductRegistry` — `compose/ambient` registry serving the captured payload.
//
// Android-library only — `androidx.wear.ambient` is Android-only, the desktop daemon doesn't
// register this connector. Published so `:daemon:android`'s external POM can resolve its
// transitive ambient implementation.

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.tapmoc)
}

android { namespace = "ee.schimke.composeai.data.ambient.connector" }

dependencies {
  // Wire-shape + product-kind constants. Re-exported via `api` so consumers
  // (`:daemon:android`) can refer to `AmbientPayload` / `Material3AmbientProduct.KIND` without
  // adding a second `project` dependency.
  api(project(":data-ambient-core"))

  // DataProductRegistry interface, DataExtension, AroundComposableExtension. Re-exported via
  // `api` so the connector's planner / extension classes can be referenced from
  // `RobolectricHost`'s `previewOverrideExtensions` list.
  api(project(":daemon:core"))
  api(project(":data-render-core"))
  api(project(":data-render-compose"))

  // `androidx.wear.ambient.AmbientLifecycleObserver` + `AmbientLifecycleCallback` —
  // the AOSP types the shadow shadows and the controller dispatches against.
  compileOnly(libs.wear)
  testImplementation(libs.wear)

  // `androidx.wear.compose.foundation.AmbientMode` / `AmbientModeManager` /
  // `LocalAmbientModeManager` — the new androidx ambient API surface (mirrors
  // `androidx.wear.compose.foundation.samples.AmbientModeBasicSample`). The
  // extension installs `LocalAmbientModeManager` so consumers reading it via
  // `rememberAmbientModeManager()` style code observe the override without
  // touching the on-device Wear Services SDK. `compileOnly` because consumers
  // (e.g. `:samples:wear`) already pull wear-compose at runtime.
  compileOnly(libs.wear.compose.foundation)
  testImplementation(libs.wear.compose.foundation)

  // Robolectric — the shadow uses `@Implements` / `@Implementation` annotations + `RealObject`.
  // `compileOnly` because the shadow only runs inside a Robolectric sandbox; non-test consumers
  // never instantiate it directly. Daemon's runtime classpath already includes Robolectric.
  compileOnly(libs.robolectric)
  testImplementation(libs.robolectric)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.serialization.json)
}

mavenPublishing {
  configure(
    com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
      javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
      sourcesJar = com.vanniktech.maven.publish.SourcesJar.Sources(),
      variant = "release",
    )
  )
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-ambient-connector",
    displayName = "Compose Preview - Wear OS Ambient Data Product Connector",
    description =
      "Daemon-side Wear OS ambient-mode data-product connector: drives the Robolectric shadow that lets `AmbientAware`-wrapped previews render under a synthetic ambient state.",
  )
  inceptionYear.set("2026")
}
