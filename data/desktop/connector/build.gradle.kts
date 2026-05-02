plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

group = "ee.schimke.composeai"

version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
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
