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
    artifactId = "data-strings-core",
    displayName = "Compose Preview - Strings Data Product Core",
    description = "Shared strings data-product model classes for Compose Preview.",
  )
  inceptionYear.set("2026")
}
