import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
  alias(libs.plugins.android.library)
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

android {
  namespace = "ee.schimke.composeai.data.layoutinspector.connector"
  compileSdk = 36
  defaultConfig { minSdk = 24 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
  api(project(":data-layoutinspector-core"))
  api(project(":daemon:core"))
  compileOnly(platform(libs.compose.bom.compat))
  compileOnly(libs.compose.ui)
  testImplementation(platform(libs.compose.bom.compat))
  testImplementation(libs.compose.ui)
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
    AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true)
  )
  if (!version.toString().endsWith("SNAPSHOT")) {
    signAllPublications()
  }
  coordinates("ee.schimke.composeai", "data-layoutinspector-connector", version.toString())
  pom {
    name.set("Compose Preview - Layout Inspector Data Product Connector")
    description.set("Daemon-side layout inspector data-product connector for Compose Preview.")
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
