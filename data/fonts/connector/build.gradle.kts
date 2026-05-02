plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

group = "ee.schimke.composeai"

version = providers.environmentVariable("PLUGIN_VERSION").orNull ?: "0.0.0-SNAPSHOT"

dependencies {
  api(project(":data-fonts-core"))
  api(project(":daemon:core"))
  testImplementation(libs.junit)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

tasks.withType<Test>().configureEach { useJUnit() }
