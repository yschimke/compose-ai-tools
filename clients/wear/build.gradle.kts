// The Wear OS "session viewer" client. Same engine as the mobile app (`:clients:core`), shrunk to
// the watch: tapping a `composeai://` link (forwarded from the phone, or a Wear deep link) connects
// to the `compose-preview serve` streamed-frame lane and paints the preview on the round display,
// forwarding taps and rotary-bezel scrolls as input. Wear Compose Material 3 throughout.
import com.github.triplet.gradle.androidpublisher.ReleaseStatus

plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.play.publisher)
  id("ee.schimke.composeai.preview")
}

composePreview {
  // Robolectric SDK 35; see the matching note in `:samples:wear`.
  sdkVersion.set(35)
}

// Version source of truth; `versionCode` packs MAJOR.MINOR.PATCH into a monotonic int.
val appVersionName = "0.2.1" // x-release-please-version
val appVersionCode =
  appVersionName
    .split(".", "-")
    .mapNotNull { it.toIntOrNull() }
    .let {
      ((it.getOrNull(0) ?: 0) * 10_000 + (it.getOrNull(1) ?: 0) * 100 + (it.getOrNull(2) ?: 0))
        .coerceAtLeast(1)
    }

android {
  namespace = "ee.schimke.composeai.clients.wear"
  // wear-compose 1.7.0-alpha requires `compileSdk = 37`; override the conventions `compileSdk =
  // 36`.
  compileSdk = 37

  defaultConfig {
    applicationId = "ee.schimke.composeai.clients.wear"
    minSdk = 30
    targetSdk = 36
    versionCode = appVersionCode
    versionName = appVersionName
  }

  // Release signing from env (see `:clients:mobile` for the full rationale + the CI job that
  // exports
  // these). Unsigned + Play-disabled when the keystore isn't configured.
  val releaseKeystorePath: String? = System.getenv("COMPOSEAI_KEYSTORE_PATH")
  signingConfigs {
    if (releaseKeystorePath != null) {
      create("release") {
        storeFile = file(releaseKeystorePath)
        storePassword = System.getenv("COMPOSEAI_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("COMPOSEAI_KEY_ALIAS")
        keyPassword = System.getenv("COMPOSEAI_KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    getByName("release") {
      // R8 shrink + resource shrink; shared keep rules in ../proguard-rules.pro (see
      // :clients:mobile).
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        file("../proguard-rules.pro"),
      )
      if (releaseKeystorePath != null) signingConfig = signingConfigs.getByName("release")
    }
  }

  buildFeatures { compose = true }
}

// Publishes the Wear AAB to the Play internal track as a draft; no-ops without Play credentials.
play {
  track.set("internal")
  defaultToAppBundles.set(true)
  releaseStatus.set(ReleaseStatus.DRAFT)
  enabled.set(System.getenv("ANDROID_PUBLISHER_CREDENTIALS") != null)
}

dependencies {
  implementation(project(":clients:core"))

  implementation(platform(libs.compose.bom.stable))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.activity.compose)
  implementation(libs.wear.compose.material3)
  implementation(libs.wear.compose.foundation)
  implementation(libs.wear.compose.ui.tooling)
  implementation(libs.wear.tooling.preview)
  debugImplementation("androidx.compose.ui:ui-tooling")

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
