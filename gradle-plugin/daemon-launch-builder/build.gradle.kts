import org.gradle.api.publish.maven.MavenPublication

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

// A typed builder that emits `daemon-launch.json` (the contracts' `DaemonLaunchDescriptor`) from
// pre-resolved classpath / sysprops / JVM args, so non-Gradle build systems (Bazel, Amper) can
// produce conforming descriptors. The shape is the contracts'; this module stamps the schema
// version
// and owns the canonical encoder. Generic by design: Android classpath layering stays in
// `:gradle-plugin`'s
// `AndroidPreviewClasspath`. Lives in this composite build so the plugin can depend on it as a
// project.

dependencies {
  api(libs.kotlinx.serialization.json)
  // The descriptor's shape is the contracts' `DaemonLaunchDescriptor`; this module adds the
  // schema-version stamp and the canonical encoder. The BOM supplies the version, declared here
  // because this module doesn't apply `composeai.base-conventions` (same as `:preview-discovery`).
  // `api` because the builder's public signatures return and accept the contracts types.
  api(platform(libs.composeai.contracts.bom))
  api(libs.composeai.daemon.protocol)

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

// `DaemonLaunchBuilderCli` is what Bazel / Amper shell out to, as `java -cp <resolved-classpath>
// ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilderCli ...`. Slim JAR with `api` deps:
// `Main-Class:` helps only when the runtime closure sits beside it; `java -jar` on the bare JAR
// won't work. See `docs/NON_GRADLE_INTEGRATION.md`.
tasks.named<Jar>("jar").configure {
  manifest {
    attributes("Main-Class" to "ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilderCli")
  }
}

// Publish the descriptor schema version as plain JSON so non-JVM consumers can verify the exact
// plugin release they pin without parsing a Kotlin class file. The same file rides inside the JAR
// for classpath consumers and beside it as daemon-launch-builder-<version>-schema.json for tools
// such as the TypeScript VS Code extension.
val daemonDescriptorSchemaVersion = 2
val daemonLaunchSchemaResourcesDir = layout.buildDirectory.dir("generated/daemon-launch-schema")
val daemonLaunchSchemaMetadata = daemonLaunchSchemaResourcesDir.map {
  it.file("META-INF/compose-preview/daemon-launch-schema.json")
}
val generateDaemonLaunchSchemaMetadata =
  tasks.register("generateDaemonLaunchSchemaMetadata") {
    // Read into locals before `doLast` captures them: a top-level script `val` would capture the
    // script object, which the configuration cache can't serialize.
    val schemaVersion = daemonDescriptorSchemaVersion
    val outputFile = daemonLaunchSchemaMetadata
    inputs.property("schemaVersion", schemaVersion)
    outputs.dir(daemonLaunchSchemaResourcesDir)
    doLast {
      val output = outputFile.get().asFile
      output.parentFile.mkdirs()
      output.writeText(
        """
        {
          "schema": "compose-preview-daemon-launch",
          "schemaVersion": $schemaVersion
        }
        """
          .trimIndent() + "\n"
      )
    }
  }

sourceSets.main.get().resources.srcDir(generateDaemonLaunchSchemaMetadata)

publishing {
  publications.withType<MavenPublication>().configureEach {
    artifact(daemonLaunchSchemaMetadata) {
      classifier = "schema"
      extension = "json"
      builtBy(generateDaemonLaunchSchemaMetadata)
    }
  }
}
