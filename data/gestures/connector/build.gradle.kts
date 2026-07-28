// `:data-gestures-connector` glues `:data-gestures-core` (the wire-shape payload) to the daemon's
// data-product / preview-override surface for the Wear OS one-handed-gesture framework
// (`Modifier.oneHandedGesture` in `wear-compose 1.7.0-alpha`). Ships:
//
//  - `GestureStateController` — process-static registry + state holder for the gesture handlers a
//    preview reports, plus the enabled / hint / last-invoked state the data product serves.
//  - `reportedOneHandedGesture` / `GestureHint` — the reporting seam a previewable Wear screen uses
//    instead of the raw framework API: identical on-device behaviour (delegates to the real
//    modifier / `OneHandedGestureIndicator`) but also reports to the controller, since the
//    framework's own registry is internal and Pixel-Watch-only.
//  - `GestureOverrideExtension` / `GesturePreviewOverrideExtension` — Compose `AroundComposable`
//    plumbing planned from `renderNow.overrides.gestures`: force-shows hints (immediate mode) or
//    invokes a registered handler (interactive mode), and toggles `LocalOneHandedGestureEnabled`.
//  - `GestureDataProductRegistry` — `compose/gestures` registry serving the captured payload.
//
// Android-library only — the gesture API is Android/Wear-only, so the desktop daemon doesn't
// register this connector. Published so `:daemon:android`'s external POM can resolve its transitive
// gesture implementation.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.tapmoc)
}

android {
  namespace = "ee.schimke.composeai.data.gestures.connector"
  // The wear-compose 1.7.0-alpha AARs (`compose-material3`, `compose-foundation`,
  // `compose-material-core`) declare `minCompileSdk = 37`, so this module compiles against API 37
  // to see the gesture types directly. Override the conventions plugin's `compileSdk = 36` default.
  // Same pattern `:data-remotecompose-connector` / `:samples:remotecompose` use for their alpha
  // pins.
  compileSdk = 37
  defaultConfig {
    // Tell AGP this AAR is consumable from compileSdk 36 even though we compile against 37. Our
    // `compileOnly` wear-compose dep means the connector's bytecode references gesture-API types,
    // but those are NOT propagated to consumers — so `:daemon:android` at compileSdk 36 links
    // against the connector without seeing (or resolving) the alpha AARs. The runtime classloader
    // gate (`isWearGestureAvailable`) handles consumers whose classpath lacks wear-compose 1.7.
    aarMetadata { minCompileSdk = 36 }
  }
}

dependencies {
  // Wire-shape + product-kind constants. Re-exported via `api` so consumers (`:daemon:android`)
  // can refer to `GesturePayload` / `Material3GestureProduct.KIND` without a second `project` dep.
  api(project(":data-gestures-core"))

  // DataProductRegistry interface, DataExtension, AroundComposableExtension. Re-exported via `api`
  // so the connector's planner / extension classes can be referenced from `RobolectricHost`'s
  // `previewOverrideExtensions` list.
  api(project(":daemon:core"))
  api(project(":data-render-core"))
  api(project(":data-render-compose"))

  // `androidx.wear.compose.material3.onehandedgesture.*` — the real one-handed-gesture API the
  // reporting seam wraps (`oneHandedGesture`, `OneHandedGestureIndicator`, `GestureAction`,
  // `LocalOneHandedGestureEnabled`) plus `LocalContentColor`. `compileOnly` because consumers
  // using the public reporting/indicator helpers are Wear apps that already pull
  // wear-compose-material3 at runtime; `:daemon:android` can still link the connector without
  // transitively publishing the alpha Wear stack.
  compileOnly(libs.wear.compose.material3)
  testImplementation(libs.wear.compose.material3)

  // The forced still-capture path renders wear-compose-material3's shipped indicator AVD at its
  // peak frame. The real alpha06 state-backed indicator completes during Robolectric idle pre-roll.
  compileOnly(platform(libs.compose.bom.stable))
  compileOnly("androidx.compose.animation:animation-graphics")
  testImplementation(platform(libs.compose.bom.stable))
  testImplementation("androidx.compose.animation:animation-graphics")

  // Robolectric — `ShadowSdkGestureInputManager` uses `@Implements` / `@Implementation` to shadow
  // wear-compose-material3's internal `SdkGestureInputManagerImpl`. `compileOnly` because the
  // shadow
  // only runs inside a Robolectric sandbox; the daemon's runtime classpath already includes it.
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
    artifactId = "data-gestures-connector",
    displayName = "Compose Preview - Wear OS Gestures Data Product Connector",
    description =
      "Daemon-side Wear OS one-handed-gesture data-product connector: reports the gesture handlers a preview registers and drives hint / invoke overrides so gestures are observable off a Pixel Watch.",
  )
  inceptionYear.set("2026")
}
