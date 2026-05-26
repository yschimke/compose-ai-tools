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
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
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
