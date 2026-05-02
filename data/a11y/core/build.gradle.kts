@file:Suppress(
  "DEPRECATION"
) // AndroidSingleVariantLibrary(Boolean, Boolean) is deprecated; the replacement

// types (SourcesJar/JavadocJar) vary between plugin versions. Re-visit when bumping.

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import tapmoc.TapmocExtension
import tapmoc.configureKotlinCompatibility

// D2.2 — `:data-a11y-core` is the **published** generic-Android piece of the
// accessibility data product: the ATF wrapper (`AccessibilityChecker`), the
// Paparazzi-style overlay generator (`AccessibilityOverlay`), and the JSON
// model classes (`AccessibilityFinding` / `AccessibilityNode` / `AccessibilityEntry`
// / `AccessibilityReport`). Anything that depends only on AndroidX, Compose,
// AndroidX-test or ATF lives here; daemon coupling lives in
// `:data-a11y-connector` (which is unpublished).
//
// Pairs with `:data-a11y-connector` — see docs/daemon/DATA-PRODUCTS.md §
// "Module split (D2.2)".

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  `maven-publish`
  alias(libs.plugins.maven.publish)
  alias(libs.plugins.tapmoc)
}

// See preview-annotations/build.gradle.kts for the rationale.
configureKotlinCompatibility(version = libs.versions.kotlinCoreLibraries.get())

// Mirror preview-annotations: silence the 2.3.x compiler's
// `Language version 2.0 is deprecated …` warning while we hold the floor
// at 2.0 (see `kotlinCoreLibraries` in libs.versions.toml).
tasks.withType<KotlinCompilationTask<*>>().configureEach {
  compilerOptions.freeCompilerArgs.add("-Xsuppress-version-warnings")
}

extensions.configure<TapmocExtension> { checkDependencies() }

group = "ee.schimke.composeai"

// Same versioning rule as `:renderer-android`. CI sets PLUGIN_VERSION; local builds derive a
// SNAPSHOT version from `.release-please-manifest.json`.
version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

android {
  namespace = "ee.schimke.composeai.data.a11y.core"
  compileSdk = 36

  defaultConfig { minSdk = 24 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
  api(libs.kotlinx.serialization.json)

  // ATF (`accessibility-test-framework`) — directly referenced by `AccessibilityChecker.kt`.
  // Same artifact `:renderer-android` previously pulled transitively via
  // `roborazzi-accessibility-check`; pinned here so consumers of this published artifact
  // pick it up without dragging Roborazzi.
  implementation(libs.roborazzi.accessibility.check)

  // ShadowBuild fingerprint swap inside `AccessibilityChecker.analyze` — sidesteps ATF's
  // `Build.FINGERPRINT == "robolectric"` bail-out. Same workaround the standalone path used
  // before D2.2.
  implementation(libs.robolectric)

  // ATF nullable annotations — see `:renderer-android`'s own kdoc for the full reasoning;
  // marker-only at runtime, present at compile time so KT-80247 doesn't fire.
  compileOnly(libs.checker.qual)

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
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

  coordinates("ee.schimke.composeai", "data-a11y-core", version.toString())

  pom {
    name.set("Compose Preview — Accessibility Data Product (Core)")
    description.set(
      "Generic Android accessibility data-product primitives: ATF check wrapper, " +
        "Paparazzi-style overlay PNG generator, and the JSON model classes the " +
        "Gradle plugin / CLI / daemon all read. Pairs with the connector module that " +
        "wires this into the compose-preview daemon's data/* JSON-RPC surface."
    )
    url.set("https://github.com/yschimke/compose-ai-tools")
    inceptionYear.set("2026")
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
