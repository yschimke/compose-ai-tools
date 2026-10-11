import ee.schimke.composeai.buildlogic.PublishedVersions

plugins {
  // Order matters: `base-conventions` puts a BOM on `api`, which a platform rejects. It skips
  // projects that already have `java-platform`, so the platform plugin must be applied first.
  id("composeai.maven-publishing-platform")
  // `base-conventions` is what gives this project its ktfmt task, which the root build's
  // `ktfmtFormatAll` / `ktfmtCheckAll` aggregates expect of every project with a build script.
  id("composeai.base-conventions")
}

// The BOM for everything this repository publishes, so consumers name one version:
//
//     implementation(platform("ee.schimke.composeai:compose-ai-tools-bom:<version>"))
//     implementation("ee.schimke.composeai:render-host")
//     implementation("ee.schimke.composeai:bundle-format")
//
// Main-build constraints are derived from the project paths `settings.gradle.kts` hands over
// (artifact id = flattened path; pinned by `PublishedArtifactIdTest`). The `gradle-plugin` included
// build's modules are listed by hand — they aren't subprojects and their ids don't follow their
// paths — and the same test pins this list against its build files.
private val includedBuildArtifactIds =
  listOf(
    "compose-preview-plugin",
    "compose-preview-config",
    "daemon-launch-builder",
    "preview-discovery",
  )

// Each constraint takes the module's effective version via `PublishedVersions.resolve`, the same
// function that sets `project.version`, so BOM and POMs can't disagree on a partial release.
val publishedProjectPaths =
  providers.systemProperty("composeai.publishedProjectPaths").get().split(",").filter {
    it.isNotBlank()
  }

val publishSet =
  PublishedVersions.parsePublishSet(providers.gradleProperty("composeai.publishSet").orNull)

val manifestText = providers.provider {
  rootProject.layout.projectDirectory
    .file("publishing-manifest.json")
    .asFile
    .takeIf { it.isFile }
    ?.readText() ?: "{}"
}

// Also imports the daemon (layer 1a) and contracts (layer 0) BOMs at the versions this build uses,
// so a Maven consumer importing only this BOM aligns all three layers. In Gradle they're floors
// (highest wins); in Maven the first import wins, so a consumer wanting newer lower-layer BOMs must
// list them before this one.
//
// `allowDependencies()` lets a platform carry other platforms; the guard below fails configuration
// if anything other than these two `platform(...)` imports is added.
javaPlatform { allowDependencies() }

configurations.named("api") {
  dependencies.configureEach {
    val category =
      (this as? ModuleDependency)?.attributes?.getAttribute(Category.CATEGORY_ATTRIBUTE)?.name
    if (category != Category.REGULAR_PLATFORM && category != Category.ENFORCED_PLATFORM) {
      throw GradleException(
        "compose-ai-tools-bom may only import other platforms; '$group:$name:$version' is a " +
          "regular dependency and would be published as one. Put a version in `constraints {}`."
      )
    }
  }
}

dependencies {
  api(platform(libs.composeai.daemon.bom))
  api(platform(libs.composeai.contracts.bom))

  constraints {
    (publishedProjectPaths.map { it.removePrefix(":").replace(':', '-') } +
        includedBuildArtifactIds)
      .distinct()
      .sorted()
      .forEach { artifactId ->
        val version =
          PublishedVersions.resolve(
            artifactId = artifactId,
            tagVersion = project.version.toString(),
            publishSet = publishSet,
            manifestText = manifestText.get(),
          )
        api("ee.schimke.composeai:$artifactId:$version")
      }
  }
}

composeAiPlatformPublishing {
  coordinates(
    artifactId = "compose-ai-tools-bom",
    displayName = "Compose AI Tools - Bill of Materials",
    description =
      "Version constraints for every Compose AI Tools artifact, so a consumer aligns the Gradle " +
        "plugin, the render host, the CLI and the preview runtimes with one coordinate.",
  )
  inceptionYear.set("2026")
}
