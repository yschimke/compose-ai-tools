plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

tasks.withType<Test>().configureEach { useJUnit() }

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-resources-core",
    displayName = "Compose Preview - Resources Data Product Core",
    description = "Shared resources data-product model classes for Compose Preview.",
  )
  inceptionYear.set("2026")
}
