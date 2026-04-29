// Renderer-agnostic daemon core — see docs/daemon/DESIGN.md § 4
// ("Renderer-agnostic surface").
//
// Plain JVM module: holds the JSON-RPC server, the @Serializable protocol
// types, and the abstract `RenderHost` interface. Both
// `:daemon:android` (Robolectric backend) and the future
// `:daemon:desktop` depend on this module and contribute their own
// concrete `RenderHost` implementation.
//
// NOT published to Maven on its own. Bundled into the daemon launch
// descriptor's classpath via `:daemon:android` (which depends on
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
  // daemon modules (e.g. :daemon:android) get
  // kotlinx-serialization-json on their compile classpath without re-declaring
  // it — they instantiate `Json {}` and reference protocol types directly.
  api(libs.kotlinx.serialization.json)

  // B2.2 phase 2 — IncrementalDiscovery's scoped @Preview scan uses ClassGraph,
  // mirroring the gradle-plugin's DiscoverPreviewsTask. Layered as `implementation`
  // because it's an internal detail of the daemon-side discovery pass; not part of
  // the renderer-agnostic protocol surface that downstream :daemon:android /
  // :daemon:desktop modules consume from this module's `api`.
  implementation(libs.classgraph)

  testImplementation(libs.junit)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

tasks.withType<Test>().configureEach { useJUnit() }
