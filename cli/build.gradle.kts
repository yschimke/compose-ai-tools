import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

plugins {
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

// See gradle-plugin/build.gradle.kts for how CI sets PLUGIN_VERSION. Local
// builds derive the SNAPSHOT version from `.release-please-manifest.json`.
version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

base { archivesName.set("compose-preview") }

application {
  applicationName = "compose-preview"
  mainClass.set("ee.schimke.composeai.cli.MainKt")
}

// Note: don't set `archiveFileName` directly — Gradle's distribution plugin
// uses it to derive the root directory inside the archive, so a full filename
// like `compose-preview-<version>.tar.gz` leaks the `.tar.gz` suffix into the
// extracted folder name. Setting `archiveExtension` instead lets Gradle compute
// the file name as `<archivesName>-<version>.<extension>` while keeping the
// internal root as `<archivesName>-<version>/`.
tasks.named<Tar>("distTar") {
  archiveExtension.set("tar.gz")
  compression = Compression.GZIP
}

dependencies {
  implementation(libs.kotlinx.serialization.json)
  implementation("org.gradle:gradle-tooling-api:9.3.1")
  runtimeOnly("org.slf4j:slf4j-nop:2.0.16")

  // Bundle the MCP server so `compose-preview mcp serve` can invoke it in-process —
  // the consumer install story stays a single tarball + a single launcher.
  implementation(project(":mcp"))
  // Renderer-agnostic daemon core helpers that are safe to use as a local library from CLI
  // commands. Keep renderer backends (`:daemon:android`, `:daemon:desktop`) out of this module.
  implementation(project(":daemon:core"))

  testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }

abstract class CheckCliDaemonLibraryBoundary : DefaultTask() {
  @get:Classpath abstract val runtimeClasspath: ConfigurableFileCollection

  @get:Input abstract val forbiddenProjectDirs: ListProperty<String>

  @TaskAction
  fun checkBoundary() {
    val forbiddenDirs = forbiddenProjectDirs.get()
    val forbidden =
      runtimeClasspath.files
        .filter { file ->
          val path = file.invariantSeparatorsPath
          forbiddenDirs.any { forbiddenDir -> path.startsWith("$forbiddenDir/") }
        }
        .map { it.path }
        .sorted()

    check(forbidden.isEmpty()) {
      "CLI may depend on renderer-agnostic :daemon:core only; forbidden renderer artifacts on " +
        "runtimeClasspath: ${forbidden.joinToString(", ")}"
    }
  }
}

tasks.register<CheckCliDaemonLibraryBoundary>("checkCliDaemonLibraryBoundary") {
  description = "Fails if renderer implementations leak onto the CLI runtime classpath."
  group = "verification"

  runtimeClasspath.from(configurations.named("runtimeClasspath"))
  forbiddenProjectDirs.set(
    listOf(":daemon:android", ":daemon:desktop", ":renderer-android", ":renderer-desktop").map {
      project(it).projectDir.invariantSeparatorsPath
    }
  )
}

tasks.named("check") { dependsOn("checkCliDaemonLibraryBoundary") }

// Bake the resolved Gradle build version into a properties resource the CLI reads at runtime
// (see `Version.kt#BUNDLE_VERSION`). Avoids the previous hand-edited literal in source — which
// drifted out of sync with the release manifest and made `compose-preview show` advertise a
// nonexistent v0.9.0 release. Mirrors `gradle-plugin/build.gradle.kts`'s
// `generatePluginVersionResource`.
val generateCliVersionResource by tasks.registering {
  val outputDir = layout.buildDirectory.dir("generated/cli-version-resource")
  val cliVersion = project.version.toString()
  inputs.property("version", cliVersion)
  outputs.dir(outputDir)
  doLast {
    val file = outputDir.get().file("ee/schimke/composeai/cli/cli-version.properties").asFile
    file.parentFile.mkdirs()
    file.writeText("version=$cliVersion\n")
  }
}

sourceSets.main.get().resources.srcDir(generateCliVersionResource)
