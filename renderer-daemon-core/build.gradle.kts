// Renderer-agnostic daemon core — see docs/daemon/DESIGN.md § 4
// ("Renderer-agnostic surface").
//
// Plain JVM module: holds the JSON-RPC server, the @Serializable protocol
// types, and the abstract `RenderHost` interface. Both
// `:renderer-android-daemon` (Robolectric backend) and the future
// `:renderer-desktop-daemon` depend on this module and contribute their own
// concrete `RenderHost` implementation.
//
// NOT published to Maven on its own. Bundled into the daemon launch
// descriptor's classpath via `:renderer-android-daemon` (which depends on
// this module).

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
  // Protocol message types are @Serializable. Exposed as `api` so downstream
  // daemon modules (e.g. :renderer-android-daemon) get
  // kotlinx-serialization-json on their compile classpath without re-declaring
  // it — they instantiate `Json {}` and reference protocol types directly.
  api(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

tasks.withType<Test>().configureEach { useJUnit() }
