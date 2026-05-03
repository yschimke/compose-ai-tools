plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
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
  api(project(":daemon:core"))
  implementation(compose.runtime)
  implementation(compose.ui)
  implementation(compose.material3)
  testImplementation(libs.junit)
  testImplementation(compose.desktop.currentOs)
  testImplementation(compose.runtime)
  testImplementation(compose.ui)
  testImplementation(compose.foundation)
  testImplementation(compose.material3)
}

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

tasks.withType<Test>().configureEach { useJUnit() }

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
  coordinates("ee.schimke.composeai", "data-theme-connector", version.toString())
  pom {
    name.set("Compose Preview - Theme Data Product Connector")
    description.set("Daemon-side theme data-product connector for Compose Preview.")
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
