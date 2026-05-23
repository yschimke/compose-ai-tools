// Reference implementation of contrib's `compose-preview-scripting` binary.
//
// Consumes only the published surface — `:preview-data-api` for the wire DTOs,
// `:gradle-preview-driver` for the render pipeline, `:data-a11y-core` for the
// per-extension typed decoder. No dependency on `:cli`. The
// `yschimke/compose-ai-contrib` repo lifts this code wholesale into its own
// published module; the copy here gets deleted once contrib's copy job has run.
//
// Builds a standalone `compose-preview-scripting` binary via `installDist` —
// same shape as `:render-cli`, `:preview-discovery`, `:daemon-launch-builder`,
// the other published-API contrib consumers.

plugins {
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

base { archivesName.set("compose-preview-scripting") }

application {
  applicationName = "compose-preview-scripting"
  mainClass.set("ee.schimke.composeai.scripting.MainKt")
}

dependencies {
  // Published wire-format DTOs — `PreviewResult`, `ExtensionPayload`, the v1 a11y mirror types.
  // The a11y mirror types (`AccessibilityFinding` / `AccessibilityEntry` / `AccessibilityReport`)
  // are exactly what contrib needs during the v1 → v2 deprecation window — they're JVM-only
  // (`:data-a11y-core` is an android-library and can't be consumed by a plain JVM module).
  // After the wire-format bump to v2, contrib switches to `:data-a11y-core`'s typed entries
  // directly; until then the mirror is the contract.
  api(project(":preview-data-api"))

  // Render pipeline as a library — opens Gradle, runs `composePreviewRenderAll`, returns
  // `PreviewResult`s with PNG sha256s populated.
  api(project(":gradle-preview-driver"))

  // JSR-223-style scripting host. Pulls `kotlin-compiler-embeddable` transitively (~50 MB);
  // ships inside the standalone scripting binary only, not in `:cli`'s tarball.
  implementation(libs.kotlin.scripting.common)
  implementation(libs.kotlin.scripting.jvm)
  implementation(libs.kotlin.scripting.jvm.host)

  testImplementation(kotlin("test"))
  testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
