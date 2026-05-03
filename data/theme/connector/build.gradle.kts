plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":daemon:core"))
  implementation(compose.runtime)
  implementation(compose.ui)
  implementation(compose.material3)
  testImplementation(libs.junit)
  testImplementation(compose.desktop.currentOs)
  testImplementation(compose.runtime)
  testImplementation(compose.ui)
  testImplementation(compose.foundation)
  testImplementation(compose.material3)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

tasks.withType<Test>().configureEach { useJUnit() }

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-theme-connector",
    displayName = "Compose Preview - Theme Data Product Connector",
    description = "Daemon-side theme data-product connector for Compose Preview.",
  )
  inceptionYear.set("2026")
}
