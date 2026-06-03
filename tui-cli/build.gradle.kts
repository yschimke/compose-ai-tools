import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult

plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  application
}

version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

base { archivesName.set("compose-preview-tui") }

application {
  applicationName = "compose-preview-tui"
  mainClass.set("ee.schimke.composeai.tui.MainKt")
  // Force UTF-8 on the streams Mosaic writes to: a JVM launched under POSIX/C locale picks
  // US-ASCII / ISO-8859-1 by default, which silently replaces every non-ASCII glyph the
  // composition emits (half-block `▀`, list-cursor `▶`, the long-dash in --help, etc.) with
  // `?`. `stdout.encoding` / `stderr.encoding` are honoured on JDK 18+; `file.encoding` is the
  // older umbrella that some libraries still read on JDK 17 paths. Setting all three is belt-
  // and-braces — none of them break a UTF-8-by-default locale and they fix it on POSIX/C.
  applicationDefaultJvmArgs =
    listOf(
      "--enable-native-access=ALL-UNNAMED",
      "-Dfile.encoding=UTF-8",
      "-Dstdout.encoding=UTF-8",
      "-Dstderr.encoding=UTF-8",
    )
}

tasks.named<Tar>("distTar") {
  archiveExtension.set("tar.gz")
  compression = Compression.GZIP
}

// Sidecar configurations carrying the desktop renderer + daemon and their Compose Multiplatform
// runtime. Project-less bundle mode (`compose-preview-tui <bundle.png>` outside a Gradle checkout)
// spawns the desktop daemon straight from the bundle's embedded classes, and the daemon needs these
// jars on its subprocess classpath. Same isolation story as `:cli`'s sidecars: never on the
// launcher's own classpath — only loaded by the spawned daemon JVM — so there's no version-skew
// risk against anything the launcher links. Resolved into `lib-renderer/` and `lib-daemon-desktop/`
// in the dist and located at runtime via `APP_HOME` (see `BundleSidecars`).
val composePreviewRenderer =
  configurations.create("composePreviewRenderer") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }
val composePreviewDaemonDesktop =
  configurations.create("composePreviewDaemonDesktop") {
    isCanBeResolved = true
    isCanBeConsumed = false
  }

dependencies {
  implementation(project(":common-io"))
  implementation(project(":preview-data-api"))
  implementation(project(":gradle-preview-driver"))
  implementation(project(":render-session-api"))
  implementation(project(":render-session-subprocess"))

  implementation(libs.mosaic.runtime)

  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  // Ktor client (OkHttp engine) for opening a bundle from a URL. Explicit okhttp dep pins the
  // engine to OkHttp 5.x over ktor-client-okhttp's transitive 4.12.0.
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.okhttp)

  // Subprocess-only sidecars shipped in the dist for project-less bundle mode. The daemon's
  // subprocess classpath joins `lib-daemon-desktop/jars` + `lib-renderer/jars` at launch; the
  // renderer
  // sidecar carries the per-OS Compose Multiplatform stack (incl. Skiko) so it isn't duplicated
  // in the daemon sidecar. Mirrors `:cli`'s `bundle daemon` / `bundle render` wiring.
  add("composePreviewRenderer", project(":renderer-desktop"))
  add("composePreviewDaemonDesktop", project(":daemon:desktop"))

  // `:gradle-preview-driver` pulls `org.gradle:gradle-tooling-api`, whose shaded variant
  // *strictly* requires `slf4j-api:2.0.17`. Ktor 3.5.0 (and friends) pull `slf4j-api:2.0.18`
  // transitively onto this classpath, which Gradle can't reconcile against the strict ceiling
  // — `:tui-cli`'s compile/runtime classpaths fail to resolve. Pin slf4j-api to the strictly-
  // required 2.0.17 so the soft 2.0.18 requests downgrade. Mirrors the same pin in `:cli`. Safe:
  // slf4j-api is a stable facade and 2.0.17↔2.0.18 are binary-compatible.
  constraints {
    implementation("org.slf4j:slf4j-api") {
      version { strictly("2.0.17") }
      because(
        "gradle-tooling-api 9.5.1 strictly requires slf4j-api 2.0.17; ktor 3.5.0 pulls 2.0.18"
      )
    }
  }

  testImplementation(kotlin("test"))
  // JUnit 5 — used directly for `@TempDir`, `Assumptions.assumeTrue`, `@DisplayName`
  // in the kitty e2e harness. `kotlin("test")` on its own resolves to the JUnit 4
  // engine here; this dep pulls in the Jupiter API + engine + parameter resolvers we
  // need for the harness assertions.
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

// The kitty-under-Xvfb e2e test invokes the assembled launcher in a real subprocess (kitty has
// to attach to a PTY, which means we can't drive the TUI in-process). Depend on `installDist`
// so the launcher script and its dependency tree are on disk by the time the test starts, and
// forward the install dir as a system property the test reads via `System.getProperty`.
val installLauncherForTests = tasks.named("installDist")
val installDirProvider: Provider<File> = installLauncherForTests.map { (it as Sync).destinationDir }

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  dependsOn(installLauncherForTests)
  systemProperty("tui-cli.install-dir", installDirProvider.get().absolutePath)
  // The e2e test is gated on `Xvfb`/`kitty`/`xdotool`/`import` being on PATH (it self-skips
  // when any of them is missing). On a CI runner without those binaries the test is a fast
  // no-op; locally with `apt install kitty xdotool imagemagick xvfb` it runs end-to-end.
}

// Multiple JetBrains Compose Multiplatform `components-*-desktop` artifacts ship as
// `library-desktop-<version>.jar`, so a flat copy into `lib-daemon-desktop/` collides on filename.
// Stage the resolved artifacts first, disambiguating colliding filenames by `module-version.jar`,
// so both end up on the daemon's classpath. Copied verbatim from `:cli`'s `stageDaemonDesktopLibs`.
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
