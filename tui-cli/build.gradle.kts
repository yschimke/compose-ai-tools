plugins {
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  application
}

version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

base { archivesName.set("compose-preview-tui") }

application {
  applicationName = "compose-preview-tui"
  mainClass.set("ee.schimke.composeai.tui.MainKt")
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<Tar>("distTar") {
  archiveExtension.set("tar.gz")
  compression = Compression.GZIP
}

dependencies {
  implementation(project(":preview-data-api"))
  implementation(project(":gradle-preview-driver"))
  implementation(project(":render-session-api"))
  implementation(project(":render-session-subprocess"))

  implementation(libs.mosaic.runtime)

  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
