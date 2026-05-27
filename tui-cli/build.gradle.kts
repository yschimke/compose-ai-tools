plugins {
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

dependencies {
  implementation(project(":preview-data-api"))
  implementation(project(":gradle-preview-driver"))
  implementation(project(":render-session-api"))
  implementation(project(":render-session-subprocess"))

  implementation(libs.mosaic.runtime)

  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

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
