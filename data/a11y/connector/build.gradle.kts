import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import tapmoc.TapmocExtension
import tapmoc.configureKotlinCompatibility

// D2.2 — `:data-a11y-connector` glues `:data-a11y-core` (the generic Android piece) to the
// daemon's data-product API: `AccessibilityDataProducer` writes the per-render JSON
// artefacts, `AccessibilityDataProductRegistry` advertises kinds + serves
// fetch / attach paths, and `AccessibilityImageProcessor` wires the overlay PNG into the
// `ImageProcessor` seam. Published only so `:daemon:android`'s external POM can resolve its
// transitive daemon-side data-product implementation.
//
// See docs/daemon/DATA-PRODUCTS.md § "Module split (D2.2)" for the rationale.

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  `maven-publish`
  alias(libs.plugins.maven.publish)
  alias(libs.plugins.tapmoc)
}

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
  namespace = "ee.schimke.composeai.data.a11y.connector"
  compileSdk = 36

  defaultConfig { minSdk = 24 }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
  // Core a11y types + ATF wrapper + overlay generator. Re-exported via `api` so consumers
  // (`:daemon:android`) can refer to `AccessibilityFinding` / `AccessibilityNode` without
  // adding a second `project` dependency.
  api(project(":data-a11y-core"))

  // DataProductRegistry interface, DataProductCapability / DataProductExtra wire types,
  // ImageProcessor seam — re-exported via `api` for the same reason.
  api(project(":daemon:core"))

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.serialization.json)
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
    com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
      javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
      sourcesJar = com.vanniktech.maven.publish.SourcesJar.Sources(),
      variant = "release",
    )
  )
  if (!version.toString().endsWith("SNAPSHOT")) {
    signAllPublications()
  }

  coordinates("ee.schimke.composeai", "data-a11y-connector", version.toString())

  pom {
    name.set("Compose Preview — Accessibility Data Product (Connector)")
    description.set(
      "Daemon-side accessibility data-product connector: wires data-a11y-core into the " +
        "compose-preview daemon's data/* JSON-RPC surface and overlay image processor."
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
