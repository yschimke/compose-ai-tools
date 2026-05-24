// `:data-remotecompose-connector` glues `:data-remotecompose-core` (the wire-shape) to the daemon's
// data-product / preview-override surface. Mirrors `:data-permissions-connector` / `:data-keyboard
// -connector` — payload identity in the core module, daemon-side machinery (controller, around-
// composable extension, planner, data product registry) here. Ships:
//
//  - `RemoteComposeController` — process-static state holder for the named-value map, the host-
//    action ring buffer, and the active profile. Writers: the around-composable seeding from
//    `RemoteComposeOverride`, and user code calling into `LocalRemoteComposeHost.current` from
//    inside its `RemotePreview { ... }` block.
//  - `RemoteComposeOverrideExtension` / `RemoteComposePreviewOverrideExtension` —
//    `AroundComposable` plumbing that installs `LocalRemoteComposeHost` so user code can read the
//    daemon-requested profile + seeded named values and report fired `HostAction`s back. Planner
//    is always-on (like `KeyboardPreviewOverrideExtension`) so the composition local is in place
//    on every render — a screen that only later begins using Remote Compose state still finds the
//    host wired without needing a fresh override.
//  - `RemoteComposeDataProductRegistry` — `compose/remotecompose` registry serving the captured
//    payload (effective named values + host action buffer + active profile).
//
// Android-library because the public adapters bridge to `androidx.compose.remote.creation.compose
// .action.HostAction` and `androidx.compose.remote.creation.profile.RcPlatformProfiles`. The
// alpha `compose-remote` artifacts declare `minCompileSdk = 37`, so this module overrides the
// repo-wide `compileSdk = 36` default from `composeai.android-conventions`. Daemon modules at
// compileSdk 36 still consume the connector AAR because the alpha-API deps are `compileOnly`
// (they never reach the daemon's resolved compile classpath); the connector class files are
// loaded only when a consumer that itself depends on `compose-remote` (e.g. `:samples:
// remotecompose`) lights up the around-composable, at which point the consumer's runtime
// classpath supplies the missing types. `:daemon:android`'s registration guards on classloader
// availability before registering the extension, mirroring the `:data-ambient-connector` /
// Wear-AAR pattern.

plugins {
  id("composeai.maven-publishing")
  id("composeai.android-conventions")
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "ee.schimke.composeai.data.remotecompose.connector"
  // The alpha `compose-remote` AARs (`androidx.compose.remote:remote-creation`,
  // `:remote-creation-compose`, `:remote-tooling-preview` at `1.0.0-alpha010`) declare
  // `minCompileSdk = 37` in their AAR metadata, so this module compiles against API 37 to see
  // their types directly. Override the conventions plugin's `compileSdk = 36` default applied
  // before this block runs. Same pattern `:samples:remotecompose` / `:samples:android-alpha` use
  // for their alpha-channel pins.
  compileSdk = 37
  // The alpha artifacts also require `minSdk >= 29` at runtime. Override the conventions plugin's
  // `minSdk = 24` default; previews referencing `RemotePreview { ... }` are themselves gated to
  // `minSdk = 29` consumers (`:samples:remotecompose` for the reference setup) so this floor
  // matches the smallest deployable target.
  defaultConfig {
    minSdk = 29
    // Tell AGP this AAR is consumable from compileSdk 36 even though we compile against 37.
    // Our `compileOnly` deps on `androidx.compose.remote.*` mean the connector's bytecode
    // references alpha-API types, but those types are NOT propagated to consumers (so
    // daemon:android at compileSdk 36 doesn't see them in its compile classpath). The runtime
    // classloader gate (`isRemoteComposeAvailable` in `:daemon:android`) handles the case where
    // the consumer's runtime classpath lacks compose-remote. Without this override AGP's AAR
    // metadata check ("requires libraries and applications that depend on it to compile against
    // version 37 or later") blocks consumption from daemon:android.
    aarMetadata { minCompileSdk = 36 }
  }
  // The connector's around-composable references `androidx.compose.remote.creation.*` types
  // marked `@RestrictTo(LIBRARY_GROUP)`. Match the `:samples:remotecompose` workaround: source-
  // level `@file:Suppress("RestrictedApiAndroidX")` quiets the IDE, but AGP's lint runs the
  // `RestrictedApi` check separately. AndroidX's own samples disable the lint id; we follow.
  lint { disable += "RestrictedApi" }
}

dependencies {
  // Wire-shape + product-kind constants. Re-exported via `api` so consumers (`:daemon:android`)
  // can refer to `RemoteComposePayload` / `RemoteComposeProduct.KIND` without adding a second
  // `project` dependency.
  api(project(":data-remotecompose-core"))

  // DataProductRegistry interface, DataExtension, AroundComposableExtension. Re-exported via
  // `api` so the connector's planner / extension classes can be referenced from
  // `RobolectricHost`'s `previewOverrideExtensions` list.
  api(project(":daemon:core"))
  api(project(":data-render-core"))
  api(project(":data-render-compose"))

  // Compose runtime + UI for `Composable`, `compositionLocalOf`, `CompositionLocalProvider`,
  // `DisposableEffect`, `mutableStateOf`. `compileOnly` matches the pattern across other
  // connectors — the consumer always brings its own Compose through its BOM.
  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.runtime)
  compileOnly(libs.compose.ui)
  // `PreviewWrapperProvider` (the type `RemoteOverridablePreviewWrapper` implements) ships in
  // `ui-tooling-preview:1.11.0-rc01` — the first published version of the preview-wrapper API.
  // `compileOnly` because the consumer brings the same prerelease artifact directly when it
  // applies the `@PreviewWrapper` annotation.
  compileOnly(libs.compose.ui.tooling.preview.wrapper)
  testImplementation(platform(libs.compose.bom.compat))
  testImplementation(libs.compose.runtime)
  testImplementation(libs.compose.ui)

  // Alpha `compose-remote` artifacts. `compileOnly` so they don't surface in the connector AAR's
  // resolved compile classpath for downstream consumers — `:daemon:android` stays at compileSdk
  // 36 because it never sees these types directly, only the connector's own classes that
  // reference them. Runtime resolution happens through the consumer's classpath (e.g.
  // `:samples:remotecompose` already depends on these artifacts at 1.0.0-alpha010); when the
  // consumer doesn't, `:daemon:android`'s registration step guards on classloader availability
  // and skips the extension.
  //
  // The version coordinate is pinned to `compose-remote = 1.0.0-alpha010` in
  // `libs.versions.toml`. `wear-compose-remote-material3` is intentionally NOT a dep here — the
  // connector deals in the lower-level creation API (`HostAction`, `RemoteContext`,
  // `RcPlatformProfiles`); the Wear Material 3 components live with consumer preview code.
  compileOnly(libs.compose.remote.creation)
  compileOnly(libs.compose.remote.creation.compose)
  compileOnly(libs.compose.remote.tooling.preview)
  // Player-side APIs used by `RemoteOverridablePreview` to wire daemon-supplied named-value
  // overrides into the running `RemoteComposePlayer` via its `StateUpdater`. Same `compileOnly`
  // pattern as the creation deps above — only consumers that actually pull `compose-remote`
  // light up the override path; everyone else gets a no-op classloader guard.
  compileOnly(libs.compose.remote.player.compose)
  compileOnly(libs.compose.remote.player.core)
  compileOnly(libs.compose.remote.player.view)
  testImplementation(libs.compose.remote.player.core)

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
    artifactId = "data-remotecompose-connector",
    displayName = "Compose Preview - Remote Compose Data Product Connector",
    description =
      "Daemon-side Remote Compose data-product connector: lets `renderNow.overrides.remoteCompose` seed named values + a profile into a `RemotePreview { ... }` block, captures HostAction events the remote runtime fires, and surfaces it all through `data/fetch?kind=compose/remotecompose`.",
  )
  inceptionYear.set("2026")
}
