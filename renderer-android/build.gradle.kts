@file:Suppress(
  "DEPRECATION"
) // AndroidSingleVariantLibrary(Boolean, Boolean) is deprecated; the replacement

// types (SourcesJar/JavadocJar) vary between plugin versions. Re-visit when bumping.

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import tapmoc.TapmocExtension
import tapmoc.configureKotlinCompatibility

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  `maven-publish`
  alias(libs.plugins.maven.publish)
  alias(libs.plugins.tapmoc)
}

// See preview-annotations/build.gradle.kts for the rationale.
configureKotlinCompatibility(version = libs.versions.kotlinCoreLibraries.get())

extensions.configure<TapmocExtension> { checkDependencies() }

group = "ee.schimke.composeai"

// See gradle-plugin/build.gradle.kts for how CI sets PLUGIN_VERSION. Local
// builds derive the SNAPSHOT version from `.release-please-manifest.json`.
version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

android {
  namespace = "ee.schimke.composeai.renderer"
  compileSdk = 36

  defaultConfig { minSdk = 24 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
  // `AndroidSingleVariantLibrary` in `mavenPublishing {}` below wires the
  // `singleVariant("release")` publication for us — don't declare it twice.
}

dependencies {
  implementation(libs.robolectric)
  implementation(libs.junit)
  implementation(libs.kotlinx.serialization.json)

  // Compose / Activity / Compose-UI-test libs are `compileOnly` on purpose:
  // they must match what the CONSUMER module declares, because AGP's
  // `process<Variant>Resources` builds the unit-test merged resource APK
  // (`apk-for-local-test.ap_`) from the consumer's dependency graph — NOT
  // from our custom `composePreviewAndroidRenderer` configuration. If these
  // are `implementation`, Gradle drags our newer activity-compose 1.13 onto
  // the test JVM classpath — which transitively brings `androidx.navigationevent`
  // — while the consumer's merged resource APK stays on their older
  // activity. The test JVM then loads the 1.13 Activity bootstrap and crashes
  // on `NoClassDefFoundError: androidx/navigationevent/R$id` because the
  // navigationevent resources were never merged. Same class of failure for
  // `androidx.core.R.tag_compat_insets_dispatch` (compose-ui 1.10 →
  // androidx.core 1.16 resources missing on older consumers).
  //
  // `testImplementation` mirrors `compileOnly` for our own unit tests, which
  // need actual runtime classes. The plugin separately injects ui-test-manifest
  // into the consumer's `testImplementation` in AndroidPreviewSupport so its
  // `ComponentActivity` entry lands in the consumer's merged test manifest.
  //
  // We compile against the OLDER `compose-bom-compat` rather than the
  // top-level `compose-bom`. Rationale: AndroidX honours binary-backward-
  // compatibility within a major version, so bytecode emitted against a
  // 1.9.x API surface runs unchanged on consumer apps at 1.10.x / 1.11.x
  // (where we've confirmed all referenced methods still exist). The
  // reverse — compiling against 1.10.x — emits calls like
  // `Updater.init-impl(Composer, Object, Function2)` that didn't exist
  // before 1.10.2, so consumers pinned to older Compose BOMs fail with
  // NoSuchMethodError at render time. Our own unit tests use the same
  // compat BOM (not the latest) so accidentally reaching for a 1.10-only
  // API fails at our compile step, not at a downstream consumer's.
  //
  // `LocalScrollCaptureInProgress` (compose-ui ≥ 1.7) is looked up
  // reflectively via [ScrollCaptureInProgressLocal] — a null return
  // degrades scroll-capture to a no-op for consumers on even older Compose.
  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  compileOnly(libs.compose.foundation)
  compileOnly(libs.compose.material3)
  compileOnly(libs.compose.runtime)
  compileOnly(libs.compose.ui.tooling.preview)
  compileOnly(libs.activity.compose)
  compileOnly("androidx.compose.ui:ui-test-junit4")
  compileOnly("androidx.compose.ui:ui-test-manifest")
  // `@AnimatedPreview(showCurves = true)` snapshots the active
  // composition's slot table via `currentComposer.compositionData`
  // (`@InternalComposeApi` from compose-runtime) and walks it via
  // `androidx.compose.ui.tooling.data.asTree` (compose-ui-tooling-data).
  // Renderer compile-classpath deps stop there — `PreviewAnimationClock`,
  // `AnimationSearch`, and `ComposeAnimation` / `ComposeAnimatedProperty`
  // are reached via reflection at runtime so consumers without curve
  // support never need ui-tooling on their classpath.
  compileOnly("androidx.compose.ui:ui-tooling-data")

  testImplementation(platform(libs.compose.bom.compat))
  testImplementation(libs.compose.ui)
  testImplementation(libs.compose.foundation)
  testImplementation(libs.compose.material3)
  testImplementation(libs.compose.runtime)
  testImplementation(libs.compose.ui.tooling.preview)
  testImplementation(libs.activity.compose)
  testImplementation("androidx.compose.ui:ui-test-junit4")
  testImplementation("androidx.compose.ui:ui-test-manifest")
  testImplementation("androidx.compose.ui:ui-tooling-data")
  // GoogleFont detector's unit test constructs a real
  // `Font(GoogleFont("Roboto"), provider)` so the reflective FQCN check
  // runs against an actual `GoogleFontImpl` instance. Test-only — the
  // main source deliberately stays off this artifact so consumers without
  // downloadable fonts don't get it transitively from our AAR.
  testImplementation("androidx.compose.ui:ui-text-google-fonts")

  implementation(libs.roborazzi)
  implementation(libs.roborazzi.compose)
  // ATF accessibility checks. Exercised only when consumers opt in via
  // `composePreview { accessibilityChecks { enabled = true } }`, but always
  // on the classpath because the renderer test references these types
  // unconditionally.
  //
  // The a11y-enabled path uses `createAndroidComposeRule<ComponentActivity>()`
  // + `onRoot().captureRoboImage(...)` + ATF against the `ViewRootForTest`
  // backing the SemanticsNode. That's the same plumbing roborazzi-
  // accessibility-check's `checkRoboAccessibility` extension uses — it's the
  // only combination where Robolectric populates the accessibility
  // hierarchy richly enough for ATF to surface findings. The composable-
  // form `captureRoboImage { @Composable }` closes its ActivityScenario
  // eagerly, which detaches the view before ATF gets to run.
  implementation(libs.roborazzi.accessibility.check)

  // Tiles rendering is reflection-driven at runtime (the consumer module
  // supplies the actual classes on the JUnit classpath), so we only need
  // these to compile TilePreviewRenderer — a consumer without tile deps
  // will never hit the TILE branch in RobolectricRenderTestBase.
  compileOnly(libs.wear.tiles)
  compileOnly(libs.wear.tiles.renderer)
  compileOnly(libs.wear.tiles.tooling.preview)
  compileOnly(libs.wear.protolayout)
  compileOnly(libs.wear.protolayout.expression)
}

// GitHub Packages repo kept alongside Maven Central for internal/CI convenience.
// Vanniktech's `AndroidSingleVariantLibrary` creates the release publication
// (with sources + javadoc jar) — do not create one manually; it clashes.
publishing {
  repositories {
    maven {
      name = "GitHubPackages"
      url =
        uri(
          providers
            .environmentVariable("GITHUB_REPOSITORY")
            .map { "https://maven.pkg.github.com/$it" }
            .orElse("https://maven.pkg.github.com/yschimke/compose-ai-tools")
        )
      credentials {
        username = providers.environmentVariable("GITHUB_ACTOR").orNull
        password = providers.environmentVariable("GITHUB_TOKEN").orNull
      }
    }
  }
}

mavenPublishing {
  publishToMavenCentral(automaticRelease = true)
  configure(
    AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true)
  )
  if (!version.toString().endsWith("SNAPSHOT")) {
    signAllPublications()
  }

  coordinates("ee.schimke.composeai", "renderer-android", version.toString())

  pom {
    name.set("Compose Preview — Android Renderer")
    description.set(
      "Robolectric-based renderer for Jetpack Compose @Preview functions, " +
        "used by the compose-preview Gradle plugin to produce PNGs off-device."
    )
    url.set("https://github.com/yschimke/compose-ai-tools")
    inceptionYear.set("2025")
    licenses {
      license {
        name.set("The Apache License, Version 2.0")
        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        distribution.set("repo")
      }
    }
    developers {
      developer {
        id.set("yschimke")
        name.set("Yuri Schimke")
        url.set("https://github.com/yschimke")
      }
    }
    scm {
      url.set("https://github.com/yschimke/compose-ai-tools")
      connection.set("scm:git:https://github.com/yschimke/compose-ai-tools.git")
      developerConnection.set("scm:git:ssh://git@github.com/yschimke/compose-ai-tools.git")
    }
  }
}
