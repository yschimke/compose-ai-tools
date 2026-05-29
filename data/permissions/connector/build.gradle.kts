// `:data-permissions-connector` glues `:data-permissions-core` (the wire-shape) to the daemon's
// data-product / preview-override surface. Mirrors `:data-ambient-connector`'s split — payload in
// the core module, daemon-side machinery (controller, around-composable extension, planner,
// Robolectric shadow for tracking queries) here. Ships:
//
//  - `PermissionsController` — process-static state holder for the current grant map and the set
//    of permissions the screen has queried during the active render / interactive session.
//  - `PermissionsOverrideExtension` / `PermissionsPreviewOverrideExtension` — `AroundComposable`
//    plumbing that seeds Robolectric's `ShadowApplication.grantPermissions/denyPermissions` from
//    the override so consumer screens reading `ContextCompat.checkSelfPermission(...)` (or any
//    standard Android check API) observe the requested value without a connector-specific Compose
//    API. Planner is always-on (like `KeyboardPreviewOverrideExtension`) so the shadow tracker
//    sees every query on every render — the panel still sees an empty `queried` list when no
//    permission was checked.
//  - `ShadowContextWrapperPermissionTracker` — Robolectric shadow that intercepts
//    `ContextWrapper.checkPermission(String, int, int)` so `ContextCompat.checkSelfPermission(...)`
//    calls land in `PermissionsController.recordQuery`. The shadow forwards to the real
//    implementation so Robolectric's own grant tracking still wins; we're purely observing.
//  - `PermissionsDataProductRegistry` — `compose/permissions` registry serving the captured
//    payload (effective grant map + queried permission list).
//
// Android-library because the wire-up uses `android.Manifest`, `android.content.pm.PackageManager`,
// and Robolectric shadows on `android.content.ContextWrapper`. The desktop daemon doesn't register
// this connector — permissions are an Android platform concept and the wallpaper / theme override
// already covers the cross-backend "themed preview" need.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.tapmoc)
}

android { namespace = "ee.schimke.composeai.data.permissions.connector" }

dependencies {
  // Wire-shape + product-kind constants. Re-exported via `api` so consumers (`:daemon:android`)
  // can refer to `PermissionsPayload` / `Material3PermissionsProduct.KIND` without adding a second
  // `project` dependency.
  api(project(":data-permissions-core"))

  // DataProductRegistry interface, DataExtension, AroundComposableExtension. Re-exported via
  // `api` so the connector's planner / extension classes can be referenced from
  // `RobolectricHost`'s `previewOverrideExtensions` list.
  api(project(":daemon:core"))
  api(project(":data-render-core"))
  api(project(":data-render-compose"))

  // Robolectric — the shadow on `ContextWrapper.checkPermission` uses `@Implements` /
  // `@Implementation` annotations + `@RealObject`. `compileOnly` because the shadow only runs
  // inside a Robolectric sandbox; non-test consumers never instantiate it directly. Daemon's
  // runtime classpath already includes Robolectric.
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
    artifactId = "data-permissions-connector",
    displayName = "Compose Preview - Permissions Data Product Connector",
    description =
      "Daemon-side Android runtime-permissions data-product connector: lets `renderNow.overrides.permissions` flip a preview's grant state and records which permissions the screen queries.",
  )
  inceptionYear.set("2026")
}
