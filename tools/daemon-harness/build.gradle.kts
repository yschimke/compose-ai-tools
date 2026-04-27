// Daemon end-to-end test harness — see docs/daemon/TEST-HARNESS.md and
// docs/daemon/TODO.md § D-harness.v0.
//
// The harness plays the role of VS Code against a real daemon JVM over
// JSON-RPC. Renderer-agnostic by construction: only depends on
// `:renderer-daemon-core` for protocol types + `RenderHost` interface +
// `JsonRpcServer`. **No** dependency on `:renderer-android-daemon` or
// `:renderer-desktop-daemon` — the v0 harness spawns its own
// `FakeDaemonMain` (in `src/main/kotlin/.../FakeDaemonMain.kt`) which wires
// `JsonRpcServer` onto a `FakeHost`. Once B-desktop.1.5 lands, v1.5 flips
// `-Pharness.host=real` and consumes the real launcher descriptor; that
// classpath continues to live in the bench module, never here.
//
// Plain `org.jetbrains.kotlin.jvm` — no Android plugins, no Compose. NOT
// published to Maven.

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

group = "ee.schimke.composeai"

version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

dependencies {
  // Protocol types, JsonRpcServer, RenderHost interface, RenderRequest/RenderResult — all the
  // wire-shaped seams the harness needs to play "VS Code". Core module re-exposes
  // kotlinx-serialization-json as `api`, so the harness picks it up transitively.
  implementation(project(":renderer-daemon-core"))

  testImplementation(libs.junit)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

tasks.withType<Test>().configureEach { useJUnit() }

// Convenience task — equivalent to `java -cp $(runtimeClasspath) ee.schimke.composeai.daemon
// .harness.FakeDaemonMain`. Mirrors `:renderer-desktop-daemon`'s `runDaemonMain` so the
// FakeDaemonMain entry point is locally runnable for sanity-checking without spinning up a JUnit
// scenario. Not used by CI — `HarnessClient` spawns its own subprocess via ProcessBuilder.
tasks.register<JavaExec>("runFakeDaemonMain") {
  group = "application"
  description = "Runs FakeDaemonMain against a fixture directory (-Dcomposeai.harness.fixtureDir)."
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("ee.schimke.composeai.daemon.harness.FakeDaemonMain")
}
