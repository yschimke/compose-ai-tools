// Issue #1201 — migrated from `android.library` to Compose Multiplatform JVM so
// `:daemon:desktop` can depend on the registries (they're file-based JSON readers — pure JVM —
// and their compile-time Compose types resolve cleanly against CMP's `compose.ui` on desktop
// consumers). `:daemon:android` and `:renderer-android` (the existing Android consumers) keep
// working because Android library modules can depend on JVM jars transparently.
//
// **Published artifact change**: AAR → JAR. Pre-1.0 so acceptable; the only external coordinate
// for this module is `ee.schimke.composeai:data-layoutinspector-connector` and there's no public
// dependency stability promise yet (see DESIGN.md § 17).

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  implementation(project(":common-io"))
  api(project(":data-layoutinspector-core"))
  api(project(":data-render-compose"))
  api(project(":daemon:core"))
  // Compose Multiplatform's `compose.ui` resolves to the AndroidX variant on Android consumers
  // and to the desktop/skiko variant on JVM consumers via Gradle variant attributes. `compileOnly`
  // keeps it out of the published POM — the consumer (`:daemon:android` / `:daemon:desktop`)
  // supplies its own at runtime, same shape as the pre-migration `compileOnly(libs.compose.ui)`.
  compileOnly(libs.jetbrains.compose.ui)
  testImplementation(libs.jetbrains.compose.ui)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.serialization.json)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-layoutinspector-connector",
    displayName = "Compose Preview - Layout Inspector Data Product Connector",
    description = "Daemon-side layout inspector data-product connector for Compose Preview.",
  )
  inceptionYear.set("2026")
}
