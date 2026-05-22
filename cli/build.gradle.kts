import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Sync
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
  // The Tooling API loads gradle-dist's native-platform jar into our JVM, which
  // calls `System.load`. On JDK 24+ that prints a 4-line restricted-method
  // warning on every CLI invocation. Pre-declaring native access for the
  // unnamed module (where Tooling API + native-platform live) silences it.
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
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

// Sidecar configuration carrying the desktop renderer + its Compose Multiplatform runtime. Lives
// OUTSIDE `runtimeClasspath` so [CheckCliDaemonLibraryBoundary] keeps holding: the CLI's own JVM
// never loads renderer classes (no version-skew risk against consumer Compose), and the renderer
// only runs in the subprocess spawned by `compose-preview bundle render`.
//
// Resolved files are copied into `cli/build/install/compose-preview/lib-renderer/` by the
// distribution wiring below, and located at runtime via `APP_HOME/lib-renderer/` (the same env
// var the generated `bin/compose-preview` script exports for its own classpath).
val composePreviewRenderer =
  configurations.create("composePreviewRenderer") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

// Sidecar configuration carrying the desktop daemon module (`:daemon:desktop`) plus its
// Compose Multiplatform runtime. Same isolation story as `composePreviewRenderer` above —
// never on the CLI's own classpath; only loaded by the subprocess JVM that
// `compose-preview bundle daemon` spawns.
//
// Resolved into `cli/build/install/compose-preview/lib-daemon-desktop/` and located at
// runtime via `APP_HOME/lib-daemon-desktop/`.
val composePreviewDaemonDesktop =
  configurations.create("composePreviewDaemonDesktop") {
    isCanBeResolved = true
    isCanBeConsumed = false
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
  // Public render-session library — the CLI consumes its own published API for daemon-driven
  // commands (`compose-preview a11y` etc.) instead of touching DaemonClient directly. We eat
  // our own dog food: anything the CLI can do, a third-party tooling consumer can do via the
  // same API.
  implementation(project(":render-session-api"))
  implementation(project(":render-session-subprocess"))

  // `compose-preview bundle render` ships the desktop renderer + its full Compose Multiplatform
  // runtime in `lib-renderer/`. Subprocess only; never on the CLI's own classpath.
  add("composePreviewRenderer", project(":renderer-desktop"))

  // `compose-preview bundle daemon` ships the desktop daemon in `lib-daemon-desktop/`. Same
  // subprocess-only isolation. The Compose Multiplatform runtime (incl. Skiko) is *not*
  // bundled here — the subprocess classpath joins `lib-daemon-desktop/*` + `lib-renderer/*`
  // at launch time, and the renderer sidecar already carries the per-OS Compose stack.
  add("composePreviewDaemonDesktop", project(":daemon:desktop"))

  testImplementation(kotlin("test"))
}

// Multiple JetBrains Compose Multiplatform `components-*-desktop` artifacts ship as
// `library-desktop-<version>.jar` (e.g. `components-resources-desktop` and
// `components-ui-tooling-preview-desktop`), so a flat copy into `lib-daemon-desktop/` collides on
// filename. Stage the resolved artifacts to a build directory first, disambiguating colliding
// filenames by Maven `module-version.jar`, so both end up on the daemon's classpath at runtime.
val stageDaemonDesktopLibs =
  tasks.register<Sync>("stageDaemonDesktopLibs") {
    description = "Stages :daemon:desktop runtime artifacts, renaming filename collisions."
    destinationDir = layout.buildDirectory.dir("staged-daemon-desktop-libs").get().asFile
    val artifactsProvider = composePreviewDaemonDesktop.incoming.artifacts.resolvedArtifacts
    from(artifactsProvider.map { it.map(ResolvedArtifactResult::getFile) })
    val nameByPath = artifactsProvider.map { resolved ->
      val counts = resolved.groupingBy { it.file.name }.eachCount()
      resolved.associate { artifact ->
        val original = artifact.file.name
        val mapped =
          if (counts.getValue(original) > 1) {
            val id = artifact.id.componentIdentifier
            if (id is ModuleComponentIdentifier) "${id.module}-${id.version}.jar" else original
          } else original
        artifact.file.absolutePath to mapped
      }
    }
    inputs.property("nameByPath", nameByPath)
    eachFile {
      val mapped = nameByPath.get()[file.absolutePath]
      if (mapped != null) name = mapped
    }
  }

distributions {
  named("main") {
    contents {
      into("lib-renderer") { from(composePreviewRenderer) }
      into("lib-daemon-desktop") { from(stageDaemonDesktopLibs) }
    }
  }
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
