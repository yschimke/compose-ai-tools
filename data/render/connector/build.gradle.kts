plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":data-render-core"))
  api(project(":daemon:core"))
  testImplementation(libs.junit)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

tasks.withType<Test>().configureEach { useJUnit() }

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-render-connector",
    displayName = "Compose Preview - Render Data Product Connector",
    description = "Daemon-side render data-product connector for Compose Preview.",
  )
  inceptionYear.set("2026")
}
