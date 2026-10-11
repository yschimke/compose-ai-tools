// The Remote Compose JSON codec: authoring JSON → `.rc` bytes, and `.rc` bytes → document JSON.
// Layer 1 (behaviour, no socket); consumed by the Gradle plugin's IR resolution and, as a published
// coordinate, by the server's playground.
//
// Its own module so callers that don't compile JSON documents (e.g. `:render-host`) don't link the
// Remote Compose runtime. Only the plain-JVM `-core` artifacts are used, so it builds and tests
// with no Robolectric or `compileSdk` — which lets the plugin compile JSON sidecars at
// configuration time. `RemoteComposeJsonJvmOnlyTest` pins that.

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
  // `api`: the returned header and document model are `remote-core` types, and consumers must
  // resolve the same `CoreDocument` (two copies on one classpath fail to link).
  api(libs.compose.remote.core)

  // The authoring parser. `implementation` because nothing it defines appears on this module's
  // surface — `compile()` takes a String and returns bytes, deliberately, so a consumer never names
  // `RemoteComposeJsonParser` and never has to care that it wants `org.json`.
  implementation(libs.compose.remote.creation.core)

  // `RemoteComposeJsonParser` uses `org.json`, which Android provides but a JVM doesn't, and
  // `remote-creation-core` doesn't declare.
  implementation(libs.json.org)

  // `api`: `kotlinx.serialization.json.JsonObject` is in this module's return types.
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
