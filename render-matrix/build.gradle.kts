// Render-matrix axes and the contact sheet stitching their cells into one PNG: `MatrixAxes` expands
// and caps the device × locale × uiMode × fontScale product, `MatrixCell` maps a point onto
// `PreviewOverrides`, `ContactSheet` lays out a labelled grid. Pure functions, no daemon or socket.
//
// Shared by the CLI's `render-matrix` and the MCP server's `render_matrix` (as a published layer-1
// coordinate). Its own package (`…render.matrix`) to avoid a split package across artifacts.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  // `explicitApi()` and the ABI dump gate, following `:daemon-client`. This is a published module
  // that an out-of-repository MCP server will compile against once #5176's move lands, so an
  // implicitly-public declaration is an API decision nobody made and a surface change nobody
  // reviewed. Regenerate the dump with `./gradlew :render-matrix:updateKotlinAbi`.
  explicitApi()

  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }

dependencies {
  // `PreviewOverrides` and `UiMode` are on `MatrixCell`'s public surface (`toOverrides()`), so
  // `api` rather than `implementation`: a consumer resolving from POM metadata must see them.
  api(libs.composeai.daemon.core)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "render-matrix",
    displayName = "Compose Preview — Render Matrix",
    description =
      "Render-matrix axis expansion and contact-sheet composition shared by the compose-preview " +
        "CLI's render-matrix command and the MCP server's render_matrix tool: the device × " +
        "locale × uiMode × fontScale cross-product, its cell cap and override mapping, and the " +
        "labelled grid PNG the cells stitch into.",
  )
  inceptionYear.set("2025")
}
