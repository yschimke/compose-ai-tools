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

// Phase A2 of the contrib refactor (see `contrib/README.md`): the `previews.json` schema and
// the ClassGraph-driven scan that produces it lift out of `:gradle-plugin`'s root subproject
// into a pure-JVM library so non-Gradle consumers (Bazel rules, Amper task definitions in
// `yschimke/compose-ai-contrib`) can pull `ee.schimke.composeai:preview-discovery` from
// Maven Central and produce conforming `previews.json` manifests by running the same scan the
// gradle plugin uses — without dragging :gradle-plugin or AGP onto their classpath.
//
// A2c (next PR) adds a `java -jar` CLI main so a Bazel `genrule` or Amper task can wrap a
// shell call to drive discovery; the library API exposed here is enough for in-process
// Kotlin/JVM consumers today.
//
// Lives inside the `gradle-plugin` composite build (rather than the outer build) so the
// gradle plugin can take a normal `project(":preview-discovery")` dep without round-tripping
// through Maven Local on every dev iteration. The publish coordinate is set explicitly
// below so the artifact lands in Maven Central under a clean module name.

dependencies {
  api(libs.kotlinx.serialization.json)
  // ClassGraph drives `PreviewDiscovery.discover(...)`: scans class dirs + dependency jars for
  // `@Preview`-annotated methods, fans out multi-preview meta-annotations via
  // `scanResult.getClassInfo(...)`. Same coord as :gradle-plugin (and matched at runtime so the
  // adapter doesn't drag a second copy of ClassGraph onto its classpath).
  api(libs.classgraph)
  // ASM walks the preview method's bytecode to extract @Composable call targets — ClassGraph
  // only surfaces annotations + signatures, not method-body invocations. Used by
  // `PreviewTargetInference`.
  api(libs.asm)

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

// CLI entry point (`PreviewDiscoveryCli`) is what Bazel rules and Amper tasks shell out to —
// stamping the `Main-Class` attribute lets a build system invoke the artifact via
// `java -cp <classpath> ee.schimke.composeai.discovery.PreviewDiscoveryCli ...` or
// `java -jar preview-discovery-<v>.jar ...` (the latter only when all transitive jars sit
// next to the artifact, which is the shape a Bazel `runtime_jars` provider produces). The
// transitive deps (classgraph, asm, kotlinx-serialization) are `api` so consumers resolving
// the pom get the full classpath without extra work.
tasks.named<Jar>("jar").configure {
  manifest {
    attributes("Main-Class" to "ee.schimke.composeai.discovery.PreviewDiscoveryCli")
  }
}
