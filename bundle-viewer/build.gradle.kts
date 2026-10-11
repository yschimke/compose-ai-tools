plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

// Version derivation mirrors `:cli/build.gradle.kts` (`PLUGIN_VERSION` or the next-patch SNAPSHOT),
// keeping viewer builds aligned with the CLI and plugin.
version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

base { archivesName.set("compose-preview-viewer") }

// A Compose Desktop application, packaged two ways:
//   - `packageUberJarForCurrentOS` — one self-contained jar (Compose Desktop + Skiko natives), run
//     with `java -jar … foo.png` on any JDK 17+.
//   - `packageDistributionForCurrentOS` — a native installer via `jpackage` with an embedded JDK.
// The uber jar is Isolated-Projects-clean (unlike the Shadow plugin). The Windows `.msi` is not:
// CMP registers its WiX tasks on the root project, so build it with Isolated Projects disabled.
// The JVM `application` plugin is dropped (its `run` task would collide).
compose.desktop {
  application {
    mainClass = "ee.schimke.composeai.viewer.MainKt"
    // Compose Multiplatform Desktop's Skiko loader uses `System.load` for native libs. JDK 24+
    // would otherwise print a 4-line warning on every launch; pre-declaring native access for the
    // unnamed module silences it (parity with the old applicationDefaultJvmArgs).
    jvmArgs += "--enable-native-access=ALL-UNNAMED"
    nativeDistributions {
      // Native installers per OS (jpackage builds only the current OS's format). No Rpm: the usual
      // Linux host is Ubuntu, and `.deb` plus the uber jar cover Linux.
      targetFormats(
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
      )
      packageName = "compose-preview-viewer"
      // jpackage rejects non-numeric versions, so use the numeric core; the uber jar keeps the full
      // version.
      val numericVersion = project.version.toString().substringBefore("-")
      packageVersion = numericVersion
      description = "Compose Preview Viewer — opens a packed preview bundle and renders it live."
      vendor = "compose-ai-tools"
      macOS {
        // macOS's CFBundleShortVersionString needs MAJOR >= 1, so a 0.x.y version is coerced to
        // 1.x.y for the DMG's internal metadata only.
        packageVersion = numericVersion.replaceFirst(Regex("^0\\."), "1.")
      }
    }
  }
}

dependencies {
  // The full Compose Desktop runtime on the parent classloader: bundle classes load in a child
  // URLClassLoader (`BundleLoader.kt`), and parent-loader Compose wins on shared symbols.
  implementation(compose.desktop.currentOs)
  implementation(libs.jetbrains.compose.runtime)
  implementation(libs.jetbrains.compose.ui)
  implementation(libs.jetbrains.compose.foundation)
  implementation(libs.jetbrains.compose.material3)
  // `androidx.compose.ui.tooling.preview.Preview` lives here. Bundles compiled against the
  // standard Compose `@Preview` annotation only resolve when this artifact is on the classpath.
  implementation(libs.jetbrains.compose.components.ui.tooling.preview)

  implementation(libs.kotlinx.serialization.json)

  // Okio-based file/IO foundation (synchronous): bundle reads/extraction/coordinate resolution.
  implementation(libs.composeai.common.io)

  // Ktor client (OkHttp engine) for opening a bundle from a URL. Explicit okhttp dep pins the
  // engine to OkHttp 5.x over ktor-client-okhttp's transitive 4.12.0.
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.okhttp)

  testImplementation(libs.junit)
  testImplementation(libs.truth)
  testImplementation(libs.okio.fakefilesystem)
}
