plugins {
  alias(libs.plugins.kotlin.jvm)
  `maven-publish`
  alias(libs.plugins.maven.publish)
}

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

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

// Annotation-only artifact — deliberately no runtime deps so adding it to a
// Compose app classpath never drags anything else in.

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

  coordinates("ee.schimke.composeai", "preview-annotations", version.toString())

  pom {
    name.set("Compose Preview — Annotations")
    description.set(
      "Annotations consumed by the compose-preview Gradle plugin — e.g. " +
        "@ScrollingPreview for opting @Preview composables into scrolling " +
        "screenshot capture."
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
