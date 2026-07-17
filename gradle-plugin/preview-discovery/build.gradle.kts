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
// A2c (next PR) adds a `java -cp` CLI main (`PreviewDiscoveryCli`) so a Bazel `genrule` or
// Amper task can wrap a shell call to drive discovery once it has resolved the runtime
// closure through its own dep system; the library API exposed here is enough for in-process
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
  // Parses a target composable's `@kotlin.Metadata` to recover its real Kotlin parameter list
  // (names / types / defaults) for the Code Connect template — `implementation`, not `api`: the
  // metadata types stay an internal detail of `ComposableSignature`, off the published API.
  implementation(libs.kotlin.metadata.jvm)

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

// CLI entry point (`PreviewDiscoveryCli`) is what Bazel rules and Amper tasks shell out to.
// The published artifact is a slim library JAR — the transitive deps (classgraph, asm,
// kotlinx-serialization) are exposed as `api` so consumers resolving the POM through their
// own dep system (Bazel `rules_jvm_external`, Amper m2 cache, etc.) get the full classpath,
// and the intended invocation is:
//
//     java -cp <resolved-classpath> ee.schimke.composeai.discovery.PreviewDiscoveryCli ...
//
// The `Main-Class:` stamp is a convenience for build systems that have already materialised
// the full runtime closure next to the artifact (e.g. Bazel's `runtime_jars` provider, or a
// hand-rolled `lib/` directory); in that shape `java -jar preview-discovery-<v>.jar ...`
// will work because the JVM happens to find every transitive class on the search path.
// `java -jar` against the bare published JAR will NOT work — there is no `Class-Path:`
// manifest entry and no shaded uber-JAR; it will fail with `NoClassDefFoundError`. See the
// "CLI invocation" section in `docs/NON_GRADLE_INTEGRATION.md` for the consumer-facing
// contract.
tasks.named<Jar>("jar").configure {
  manifest { attributes("Main-Class" to "ee.schimke.composeai.discovery.PreviewDiscoveryCli") }
}
