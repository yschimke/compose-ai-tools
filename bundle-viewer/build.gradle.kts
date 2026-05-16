@file:Suppress("DEPRECATION")

plugins {
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  application
}

// Version derivation mirrors `:cli/build.gradle.kts` — CI sets `PLUGIN_VERSION` from the git
// tag (e.g. `0.10.15`), local builds compute the next-patch SNAPSHOT from
// `.release-please-manifest.json`. Keeping the schemes aligned means the viewer's release
// artefact ships at the same version as the CLI / plugin / MCP server, so `compose-preview
// $X` and `compose-preview-viewer $X` always refer to mutually compatible builds.
version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

// `archivesName` drives the distZip / distTar file name (`<archivesName>-<version>.<ext>`) AND
// the root directory inside the archive. Pinning here gives us `compose-preview-viewer-<ver>.zip`
// / `compose-preview-viewer-<ver>.tar.gz` on disk and a matching root dir inside — same shape
// the CLI uses and what the `gh release upload` glob in `.github/workflows/release.yml` expects.
base { archivesName.set("compose-preview-viewer") }

application {
  applicationName = "compose-preview-viewer"
  mainClass.set("ee.schimke.composeai.viewer.MainKt")
  // Compose Multiplatform Desktop's Skiko loader uses `System.load` for native libs. JDK 24+
  // would otherwise print a 4-line warning on every launch; pre-declaring native access for the
  // unnamed module silences it.
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Match the CLI's archive-extension trick. `archiveExtension = "tar.gz"` lets Gradle compute
// the file name as `<archivesName>-<version>.tar.gz` while keeping the extracted-archive root
// dir at `<archivesName>-<version>/`. Setting `archiveFileName` directly would leak the
// `.tar.gz` suffix into the extracted folder name — see the warning in `:cli/build.gradle.kts`.
tasks.named<Tar>("distTar") {
  archiveExtension.set("tar.gz")
  compression = Compression.GZIP
}

dependencies {
  // Full Compose Desktop runtime — the viewer composes the bundle's `@Preview` composable LIVE
  // inside its own Window, so every Compose API the bundle's classes resolve against has to be
  // on the parent classloader. Bundle's classes load via a child URLClassLoader (see
  // `BundleLoader.kt`); parent-loader Compose wins on every shared symbol.
  implementation(compose.desktop.currentOs)
  implementation(compose.runtime)
  implementation(compose.ui)
  implementation(compose.foundation)
  implementation(compose.material3)
  // `androidx.compose.ui.tooling.preview.Preview` lives here. Bundles compiled against the
  // standard Compose `@Preview` annotation only resolve when this artifact is on the classpath.
  implementation(compose.components.uiToolingPreview)

  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
