plugins {
  id("composeai.maven-publishing")
  // No version on `kotlin("jvm")` — `kotlin-dsl` in the parent (root) build script of this
  // composite already supplies the embedded Kotlin plugin, and re-specifying the version
  // here trips Gradle's "plugin already on the classpath with an unknown version" check.
  // `kotlin("plugin.serialization")` follows the same rule for the same reason.
  kotlin("jvm")
  kotlin("plugin.serialization")
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.tapmoc)
}

ktfmt { googleStyle() }

// Phase A1 of the contrib refactor (see `contrib/README.md`): the `previews.json` schema
// types — `PreviewInfo`, `PreviewManifest`, `Capture`, the scroll/animation/focus capture
// records, the various enums — lift out of `:gradle-plugin`'s root subproject into a
// pure-JVM library so non-Gradle consumers (Bazel rules, Amper task definitions in
// `yschimke/compose-ai-contrib`) can pull `ee.schimke.composeai:preview-discovery` from
// Maven Central without dragging :gradle-plugin or AGP onto their classpath.
//
// Phase A2 (separate PR) moves the ClassGraph scan logic out of `DiscoverPreviewsTask` into
// this module and adds a CLI main; that's where the `implementation(libs.classgraph)`
// dependency lands. For now this is just the data types.
//
// Package is still `ee.schimke.composeai.plugin` to avoid churning every import inside
// `:gradle-plugin` in one go; Phase A2 will rename to `ee.schimke.composeai.discovery`
// alongside the scan-logic move.
//
// Lives inside the `gradle-plugin` composite build (rather than the outer build) so the
// gradle plugin can take a normal `project(":preview-discovery")` dep without round-tripping
// through Maven Local on every dev iteration. The publish coordinate is set explicitly
// below so the artifact lands in Maven Central under a clean module name.

dependencies {
  api(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "preview-discovery",
    displayName = "Compose Preview — Discovery",
    description =
      "Schema types for `previews.json`, the manifest format consumed by the compose-preview daemon. Lets non-Gradle build systems produce conforming manifests without depending on Gradle or AGP.",
  )
  inceptionYear.set("2026")
}
