pluginManagement {
  includeBuild("../build-logic")
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "gradle-plugin"

include(":preview-discovery")

include(":daemon-launch-builder")

include(":gradle-plugin-config")

// Project paths in THIS included build that carry ktfmt, handed to the outer build's
// `ktfmtCheckAll` / `ktfmtFormatAll` aggregate tasks through a system property — the same
// closure-free channel the outer build's own `settings.gradle.kts` uses, for the same Isolated
// Projects reason. docs/build-scripts/SETTINGS.md#ktfmt-project-paths
//
// Only subprojects are listed. This build's ROOT project applies ktfmt too, but the outer build
// depends on `:ktfmtCheck` explicitly, so including it here would duplicate that edge.
val ktfmtProjectPaths = buildList {
  fun visit(descriptor: org.gradle.api.initialization.ProjectDescriptor) {
    // Mirrors the outer build: only projects with a build script apply ktfmt.
    if (descriptor.buildFile.exists()) add(descriptor.path)
    descriptor.children.forEach(::visit)
  }
  rootProject.children.forEach(::visit)
}

System.setProperty("composeai.gradlePluginKtfmtProjectPaths", ktfmtProjectPaths.joinToString(","))
