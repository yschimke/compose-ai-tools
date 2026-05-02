// Renderer-agnostic daemon core — see docs/daemon/DESIGN.md § 4
// ("Renderer-agnostic surface").
//
// Plain JVM module: holds the JSON-RPC server, the @Serializable protocol
// types, and the abstract `RenderHost` interface. Both
// `:daemon:android` (Robolectric backend) and `:daemon:desktop` depend on
// this module and contribute their own concrete `RenderHost` implementation.
//
// **Published to Maven Central** as `ee.schimke.composeai:daemon-core` —
// public surface for embedders (Gradle plugin, future Maven/IntelliJ plugins,
// third-party tooling) so the daemon JAR can be consumed by coordinate rather
// than included-build wiring. Pre-1.0; API may break across minor versions —
// see DESIGN.md § 17 (decisions log).

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  `maven-publish`
  alias(libs.plugins.maven.publish)
}

group = "ee.schimke.composeai"

version =
  providers.environmentVariable("PLUGIN_VERSION").orNull
    ?: run {
      val manifest = rootDir.resolve(".release-please-manifest.json").readText()
      val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest)!!.groupValues[1]
      val (major, minor, patch) = current.split(".").map { it.toInt() }
      "$major.$minor.${patch + 1}-SNAPSHOT"
    }

dependencies {
  api(project(":data-render-core"))

  // Protocol message types are @Serializable. Exposed as `api` so downstream
  // daemon modules (e.g. :daemon:android) get
  // kotlinx-serialization-json on their compile classpath without re-declaring
  // it — they instantiate `Json {}` and reference protocol types directly.
  api(libs.kotlinx.serialization.json)

  // B2.2 phase 2 — IncrementalDiscovery's scoped @Preview scan uses ClassGraph,
  // mirroring the gradle-plugin's DiscoverPreviewsTask. Layered as `implementation`
  // because it's an internal detail of the daemon-side discovery pass; not part of
  // the renderer-agnostic protocol surface that downstream :daemon:android /
  // :daemon:desktop modules consume from this module's `api`.
  implementation(libs.classgraph)

  testImplementation(libs.junit)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

tasks.withType<Test>().configureEach { useJUnit() }

// GitHub Packages mirror — same shape as `:renderer-android` and `:preview-annotations`.
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
  if (!version.toString().endsWith("SNAPSHOT")) {
    signAllPublications()
  }

  coordinates("ee.schimke.composeai", "daemon-core", version.toString())

  pom {
    name.set("Compose Preview — Daemon Core")
    description.set(
      "Renderer-agnostic core of the compose-preview daemon: JSON-RPC server, " +
        "protocol types (@Serializable), and the RenderHost abstraction. " +
        "Pre-1.0; consumed by daemon-android and daemon-desktop."
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
