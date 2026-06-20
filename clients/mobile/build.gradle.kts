// The mobile "session viewer" client. Tapping a `composeai://` (or serve viewer) link opens this
// app, which connects to the `compose-preview serve` streamed-frame lane and presents the rendered
// preview full-screen — painting pushed frames and forwarding taps/keys back as input, so the
// remote composition behaves like a complete local app.
//
// UI chrome (connect screen, status overlays) carries `@Preview`s so the compose-preview pipeline
// gives the repo's required visual evidence for this surface.
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
  // Robolectric SDK 35 — the JDK-17 toolchain rationale matches `:samples:android`.
  sdkVersion.set(35)
}

// Single source of truth for the app version. Bump on release; `versionCode` packs
// MAJOR.MINOR.PATCH into a monotonic int (caps minor/patch at 99).
val appVersionName = "0.1.0"
val appVersionCode =
  appVersionName
    .split(".", "-")
    .mapNotNull { it.toIntOrNull() }
    .let {
      ((it.getOrNull(0) ?: 0) * 10_000 + (it.getOrNull(1) ?: 0) * 100 + (it.getOrNull(2) ?: 0))
        .coerceAtLeast(1)
    }

android {
  namespace = "ee.schimke.composeai.clients.mobile"

  defaultConfig {
    applicationId = "ee.schimke.composeai.clients.mobile"
    // 26+ so the frame decoder's `java.util.Base64` (API 26+) is available without core-library
    // desugaring — `:clients:core` is a plain-JVM module packaged into this app.
    minSdk = 26
    targetSdk = 36
    versionCode = appVersionCode
    versionName = appVersionName
  }

  // Release signing is driven entirely by env vars so no secrets live in the repo. The release CI
  // job (`.github/workflows/clients-release.yml`) decodes the keystore from `SIGNING_KEYSTORE` and
  // exports `COMPOSEAI_KEYSTORE_PATH`; absent (local / PR builds) the release variant stays
  // unsigned
  // and Play publishing is skipped.
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
      // Left unminified for now: the streamed-frame stack (kotlinx-serialization, Ktor) needs keep
      // rules before R8 can shrink safely. Enable with the matching rules in a follow-up.
      isMinifyEnabled = false
      if (releaseKeystorePath != null) signingConfig = signingConfigs.getByName("release")
    }
  }

  buildFeatures { compose = true }
}

// Gradle Play Publisher — publishes the AAB to the Play **internal** track as a draft. No-ops when
// `ANDROID_PUBLISHER_CREDENTIALS` (the service-account JSON) isn't set, so local / PR builds that
// assemble the release variant don't make Play API calls. Listing text + graphics live in
// `src/main/play/` and are uploaded alongside the bundle.
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
  implementation(libs.compose.material3)
  implementation(libs.compose.foundation)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.activity.compose)
  debugImplementation("androidx.compose.ui:ui-tooling")

  testImplementation(libs.junit)
  testImplementation(libs.truth)
}
