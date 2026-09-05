// Render-matrix axes and the contact sheet that stitches their cells into one PNG.
//
// `MatrixAxes` expands the device × locale × uiMode × fontScale cross-product and caps it;
// `MatrixCell` maps one point of that product onto `PreviewOverrides` and its wire JSON;
// `ContactSheet` lays the resulting PNGs out in a labelled grid. Pure functions over protocol
// types and image bytes — nothing here spawns a daemon, reads a project or opens a socket.
//
// This module exists because these types used to live in `:mcp`, and both surfaces that use them
// are the same code by design (issue #1788): the `render_matrix` MCP tool and the CLI's
// `render-matrix` command. `:cli` therefore compiled against `:mcp` for an offline command,
// which is the coupling that has to go before `:mcp` moves to compose-preview-server as layer 2
// (#5176). Same lift, same reason, as `:daemon-client` before it (#3824 preparation item 3):
// after the move, the MCP server consumes this as a published layer-1 coordinate rather than
// owning it.
//
// The package is `ee.schimke.composeai.render.matrix`, deliberately not the old
// `ee.schimke.composeai.mcp` — two published artifacts sharing one package is a split package,
// which breaks JPMS and OSGi consumers. Per docs/API_STABILITY.md § 1 the published surface of
// `:mcp` is its MCP tool names and input schemas, not its Kotlin types, so the rename needs no
// compatibility shim.

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
  api(project(":daemon:core"))
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
