@file:Suppress(
  "DEPRECATION"
) // AndroidSingleVariantLibrary(Boolean, Boolean) is deprecated; the replacement

// types (SourcesJar/JavadocJar) vary between plugin versions. Re-visit when bumping.

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import tapmoc.TapmocExtension
import tapmoc.configureKotlinCompatibility

plugins {
  alias(libs.plugins.android.library)
  `maven-publish`
  alias(libs.plugins.maven.publish)
  alias(libs.plugins.tapmoc)
}

// See preview-annotations/build.gradle.kts for the rationale.
configureKotlinCompatibility(version = libs.versions.kotlinCoreLibraries.get())

tasks.withType<KotlinCompilationTask<*>>().configureEach {
  compilerOptions.freeCompilerArgs.add("-Xsuppress-version-warnings")
}

extensions.configure<TapmocExtension> { checkDependencies() }

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
  namespace = "ee.schimke.composeai.data.scroll.core"
  compileSdk = 36

  defaultConfig { minSdk = 24 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
  api(project(":data-render-core"))

  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  compileOnly("androidx.compose.ui:ui-test-junit4")

  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
}

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

  coordinates("ee.schimke.composeai", "data-scroll-core", version.toString())

  pom {
    name.set("Compose Preview — Scroll Data Product (Core)")
    description.set(
      "Generic Android scroll data-product primitives: Compose test-rule scroll drivers, " +
        "long-screenshot stitching, Wear pill clipping, and GIF encoding."
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
