// Issue #1201 — migrated from `android.library` to Compose Multiplatform JVM. See the parity
// note in `:data-layoutinspector-connector`'s build script; same rationale applies. The two
// I18nTranslations/TextStrings registries are file-based readers and the producer-side imports
// are `androidx.compose.ui.semantics.*` which CMP exposes on JVM.

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
  api(project(":data-strings-core"))
  api(project(":daemon:core"))
  api(project(":data-layoutinspector-core"))
  compileOnly(compose.ui)
  testImplementation(compose.ui)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.serialization.json)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-strings-connector",
    displayName = "Compose Preview - Strings Data Product Connector",
    description = "Daemon-side strings data-product connector for Compose Preview.",
  )
  inceptionYear.set("2026")
}
