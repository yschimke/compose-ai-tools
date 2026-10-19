pluginManagement {
  repositories {
    // Google Maven first, scoped to its own groups, so AGP and androidx never reach the plugin
    // portal or Central: under load those answer a miss with HTTP 429, which fails the build,
    // instead of the 404 that would have moved on to Google.
    google {
      mavenContent {
        includeGroupAndSubgroups("androidx")
        includeGroupAndSubgroups("com.android")
        includeGroupAndSubgroups("com.google")
      }
    }
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    // `ComposeAiBaseConventionsPlugin` depends on the ktfmt plugin via its plugin marker, which is
    // published to the Gradle Plugin Portal (not to mavenCentral / google).
    gradlePluginPortal()
  }
  versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "compose-ai-tools-build-logic"
