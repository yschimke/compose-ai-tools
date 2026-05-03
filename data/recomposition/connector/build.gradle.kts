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
  testImplementation(libs.junit)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

tasks.withType<Test>().configureEach { useJUnit() }

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-recomposition-connector",
    displayName = "Compose Preview - Recomposition Data Product Connector",
    description = "Daemon-side recomposition data-product connector for Compose Preview.",
  )
  inceptionYear.set("2026")
}
