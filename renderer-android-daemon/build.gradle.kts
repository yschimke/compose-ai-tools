// Daemon module — see docs/daemon/DESIGN.md § 6 for layout, § 9 for the
// sandbox-holder pattern (B1.3), and § 11 for SandboxScope discipline.
//
// Mirrors :renderer-android's plugin and dependency choices intentionally.
// The daemon needs the same Robolectric / Compose / Roborazzi stack on its
// classpath as the existing JUnit render path, because B1.4 (later) duplicates
// the render body into this module. For B1.1–B1.3 the runtime deps are present
// but only the protocol types and the dummy-@Test sandbox holder are used.
//
// NOT published to Maven. The daemon is consumed only by the Gradle plugin's
// DaemonBootstrapTask (Stream A) which builds a launch descriptor pointing at
// the local module's classpath.

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
}

// The daemon is launched as a local JVM process by the Gradle plugin, never
// shipped as a published artifact, so the older-Kotlin-runtime ABI dance done
// in :renderer-android (via tapmoc.configureKotlinCompatibility) is not
// required here. We get the project's default Kotlin stdlib at runtime.

group = "ee.schimke.composeai"

version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

android {
  namespace = "ee.schimke.composeai.daemon"
  compileSdk = 36

  defaultConfig { minSdk = 24 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
  // Renderer-agnostic protocol types, JsonRpcServer, RenderHost interface,
  // and RenderRequest/RenderResult data classes — see DESIGN.md § 4. The
  // core module re-exposes kotlinx-serialization-json as `api`, so we don't
  // re-declare it here.
  implementation(project(":renderer-daemon-core"))

  // Inherit the renderer's Compose/Roborazzi helpers (AccessibilityChecker,
  // GoogleFontInterceptor, AnimationInspector, ScrollDriver,
  // PixelSystemFontAliases, RenderManifest, PreviewRenderStrategy, etc.) —
  // see DESIGN.md § 7.
  implementation(project(":renderer-android"))

  // The daemon process holds a Robolectric sandbox open via a dummy @Test
  // (DESIGN.md § 9), so JUnit + Robolectric must be on the *main* classpath,
  // not just test. DaemonHost in src/main runs the JUnit core programmatically.
  implementation(libs.robolectric)
  implementation(libs.junit)

  // Compose UI test deps — needed by the per-preview render body that B1.4
  // will copy in. Listed compileOnly to mirror :renderer-android's contract
  // (consumer module supplies the actual runtime versions).
  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  compileOnly(libs.compose.foundation)
  compileOnly(libs.compose.material3)
  compileOnly(libs.compose.runtime)
  compileOnly(libs.compose.ui.tooling.preview)
  compileOnly(libs.activity.compose)
  compileOnly("androidx.compose.ui:ui-test-junit4")
  compileOnly("androidx.compose.ui:ui-test-manifest")

  // Test classpath: real runtime versions for the RobolectricHost
  // sandbox-reuse assertion. Protocol round-trip and JsonRpcServer
  // framing/integration tests now live in :renderer-daemon-core.
  testImplementation(platform(libs.compose.bom.compat))
  testImplementation(libs.compose.ui)
  testImplementation(libs.compose.foundation)
  testImplementation(libs.compose.material3)
  testImplementation(libs.compose.runtime)
  testImplementation(libs.compose.ui.tooling.preview)
  testImplementation(libs.activity.compose)
  testImplementation("androidx.compose.ui:ui-test-junit4")
  testImplementation("androidx.compose.ui:ui-test-manifest")
  testImplementation(libs.robolectric)
  testImplementation(libs.junit)
}
