plugins {
  id("composeai.maven-publishing")
  // No version on `kotlin("jvm")` — `kotlin-dsl` in the parent (root) build script of this
  // composite already supplies the embedded Kotlin plugin, and re-specifying the version
  // here trips Gradle's "plugin already on the classpath with an unknown version" check.
  // Same pattern as `:preview-discovery`.
  kotlin("jvm")
  kotlin("plugin.serialization")
  alias(libs.plugins.ktfmt)
  alias(libs.plugins.tapmoc)
}

ktfmt { googleStyle() }

// Phase A of the contrib refactor (see `contrib/README.md`): the `daemon-launch.json` schema
// + a typed builder that takes pre-resolved classpath / sysprops / JVM args and emits the
// canonical JSON. Non-Gradle build systems (Bazel rules, Amper task definitions in
// `yschimke/compose-ai-contrib`) pull `ee.schimke.composeai:daemon-launch-builder` from
// Maven Central and produce conforming descriptors without depending on Gradle or AGP.
//
// **Generic by design.** The Android-specific classpath layering (AGP `artifactView`
// resolution, R.jar appending, the `--add-opens` set required by Robolectric on JDK 17+)
// stays in `:gradle-plugin`'s `AndroidPreviewClasspath`. This library's contract is "given
// these resolved jar lists + sysprops + JVM args, emit a valid `daemon-launch.json`" — the
// build system that drives it is the one that knows how to walk its own dep graph.
//
// Lives inside the `gradle-plugin` composite build (rather than the outer build) so the
// gradle plugin can take a normal `project(":daemon-launch-builder")` dep without
// round-tripping through Maven Local on every dev iteration. Publish coordinate is set
// explicitly below so the artifact lands in Maven Central under a clean module name.

dependencies {
  api(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "daemon-launch-builder",
    displayName = "Compose Preview — Daemon Launch Builder",
    description =
      "Wire-stable `daemon-launch.json` schema and a typed builder that emits the canonical JSON. Lets non-Gradle build systems produce daemon launch descriptors without depending on Gradle or AGP.",
  )
  inceptionYear.set("2026")
}

// CLI entry point (`DaemonLaunchBuilderCli`) is what Bazel rules and Amper tasks shell out
// to. Same pattern as `:preview-discovery` — stamp the `Main-Class` so a build system can
// invoke the artifact via `java -cp <classpath> ...DaemonLaunchBuilderCli ...` or
// `java -jar` when transitive jars sit alongside.
tasks.named<Jar>("jar").configure {
  manifest {
    attributes("Main-Class" to "ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilderCli")
  }
}
