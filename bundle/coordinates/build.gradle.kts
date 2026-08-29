// Resolving a preview bundle's Maven coordinates into local jars.
//
// A `.previewbundle` records its classpath as coordinates (`ClasspathEntry.Maven`) rather than
// carrying every jar, so anything that *runs* a bundle — `compose-preview bundle daemon`,
// `bundle render`, and `serve` — has to turn those coordinates back into files: check the local
// Gradle/Maven caches first, then fetch from the configured remote repositories.
//
// Split out of `:cli` for #3824 preparation item 7. `serve` needed it, and while it lived in
// `:cli` an extracted preview server could only have got it by depending on the CLI. It is not
// part of `:bundle-format`: reading the format is offline and synchronous, while this does HTTP
// over ktor and coroutines, and a format module should not drag a network client onto the render
// subprocess classpath.
plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  // The manifest's `ClasspathEntry.Maven` shape is what gets resolved, so the format types are
  // part of this module's public surface.
  api(project(":bundle-format"))

  // Okio file IO for the cache probes and the downloaded-jar writes.
  api(libs.composeai.common.io)

  // HTTP fetch for coordinates absent from the local caches. `implementation`: a caller resolves
  // coordinates, it does not speak ktor.
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.okhttp)

  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "bundle-coordinates",
    displayName = "Compose Preview — Bundle Coordinate Resolution",
    description =
      "Resolve a preview bundle's recorded Maven coordinates into local jars: local Gradle and " +
        "Maven cache probes first, then a fetch from the configured remote repositories. Used by " +
        "the CLI's bundle commands and by an extracted preview server.",
  )
  inceptionYear.set("2026")
}

kotlin {
  // Published contract an extracted preview server compiles against across a repo boundary
  // (#3824), so every declaration states its visibility and every public one its return type.
  explicitApi()

  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin.
tasks.named("check") { dependsOn("checkKotlinAbi") }
