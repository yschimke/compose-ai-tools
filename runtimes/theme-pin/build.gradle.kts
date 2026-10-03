plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  id("org.jetbrains.kotlin.multiplatform")
  alias(libs.plugins.compose.multiplatform)
  id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
  jvm {
    compilations.configureEach {
      compileTaskProvider.configure {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
      }
    }
  }

  sourceSets {
    commonMain.dependencies {
      api(libs.jetbrains.compose.runtime)
      api(libs.jetbrains.compose.material3)
    }
    getByName("jvmTest") {
      dependencies {
        implementation(compose.desktop.currentOs)
        implementation(libs.jetbrains.compose.ui.test)
        implementation(libs.junit)
      }
    }
  }
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "theme-pin-runtime",
    displayName = "Compose Preview — Theme Pin Runtime",
    description =
      "Drop-in for Material 3's `MaterialTheme` that prefers a colour scheme a preview catalog has " +
        "pinned, so a theme selected outside a preview recolours it even when the app installs its " +
        "own theme further in. Calls reach it through the compose-preview compiler plugin.",
  )
  inceptionYear.set("2026")
}
