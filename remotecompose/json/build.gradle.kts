// The Remote Compose JSON codec: authoring JSON → `.rc` bytes, and `.rc` bytes → document JSON.
//
// A layer-1 module by `docs/design/REPOSITORY_LAYERS.md`'s test — it is behaviour, and it opens no
// socket — even though its first two consumers are the Gradle plugin's IR resolution here and the
// server's playground in compose-preview-server. The server consumes it as a published coordinate,
// which is the same shape `:render-matrix` and `:daemon-client` settled on.
//
// It is its own module rather than a package inside `:render-host` for one reason worth stating:
// the dependency. `remote-core` + `remote-creation-core` + `org.json` are 1.6 MB of Remote Compose
// runtime, and `:render-host` is the module whose whole point is that an offline caller does not
// link what it does not use. A caller that wants to compile a JSON document takes them; a caller
// rendering a plain Compose bundle does not, and `RemoteComposePairing` already has enough to
// reason about without this repository adding a fourth place the family can appear from.
//
// Why the Remote Compose classpath here is only `-core`: both artifacts upstream publishes as a
// plain `java-library`. Nothing in this module touches `remote-creation`, `remote-player-*` or
// `remote-tooling-preview`, all of which are Android AARs, so the module compiles and tests on the
// JVM toolchain with no Robolectric and no `compileSdk`. That is load-bearing — it is what lets the
// Gradle plugin compile a JSON sidecar during configuration-time IR resolution rather than inside a
// render — and `RemoteComposeJsonJvmOnlyTest` pins it so an accidental AAR dependency fails here
// rather than in a consumer's daemon.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  // Published surface compiled against by compose-preview-server and by the Gradle plugin, so the
  // ABI is reviewed rather than discovered. Regenerate with
  // `./gradlew :remotecompose-json:updateKotlinAbi`.
  explicitApi()

  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

tasks.named("check") { dependsOn("checkKotlinAbi") }

dependencies {
  // `api`, not `implementation`: `RemoteComposeDocumentHeader` and the document-JSON projection are
  // built out of `remote-core`'s model, and a consumer that inflates a document itself — the
  // server's compare lane does — must resolve the same `CoreDocument` this module returns from POM
  // metadata. Two copies of `remote-core` on one classpath is the linkage failure
  // `RemoteComposePairing` in `:render-host` exists to name.
  api(libs.compose.remote.core)

  // The authoring parser. `implementation` because nothing it defines appears on this module's
  // surface — `compile()` takes a String and returns bytes, deliberately, so a consumer never names
  // `RemoteComposeJsonParser` and never has to care that it wants `org.json`.
  implementation(libs.compose.remote.creation.core)

  // `RemoteComposeJsonParser` is written against `org.json.JSONObject`. Android ships it in the
  // platform, a JVM does not, and `remote-creation-core` neither shades nor declares it — so this
  // module does. Without it the first `compile()` call dies with `NoClassDefFoundError:
  // org/json/JSONObject` at runtime rather than failing to resolve.
  implementation(libs.json.org)

  // `api`, not `implementation`: `dumpToJsonObject()` returns a `JsonObject` and
  // `RemoteComposeDocumentHeader.toJsonObject()` does too, so the type is on this module's ABI. A
  // consumer resolving from POM metadata would otherwise have no `kotlinx-serialization-json` on
  // its compile classpath and could not name the value it was handed.
  api(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "remotecompose-json",
    displayName = "Compose Preview — Remote Compose JSON",
    description =
      "The Remote Compose JSON codec: compiles AndroidX's authoring JSON (remote_compose_schema." +
        "json) to binary .rc document bytes, and projects a .rc document back out as " +
        "operation-level document JSON for inspection and diffing. Runs on a plain JVM — no " +
        "Android runtime, no Robolectric.",
  )
  inceptionYear.set("2026")
}
