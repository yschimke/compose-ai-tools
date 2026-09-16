import ee.schimke.composeai.buildlogic.PublishedVersions

plugins {
  // ORDER MATTERS, and not for style. `maven-publishing-platform` applies `java-platform`;
  // `base-conventions` puts the compose-preview-daemon BOM on every module's `api` configuration,
  // which a platform rejects ("Adding dependencies to platforms is not allowed by default"). It
  // skips a project that already has `java-platform`, so the platform has to go on first.
  //
  // Applying them the other way round does not just fail — it fails in a way that looks like a
  // guard bug rather than an ordering one, because the `api` configuration is created while
  // `JavaPlatformPlugin.apply` is still running and the plugin is not marked applied until that
  // returns.
  id("composeai.maven-publishing-platform")
  // `base-conventions` is what gives this project its ktfmt task, which the root build's
  // `ktfmtFormatAll` / `ktfmtCheckAll` aggregates expect of every project with a build script.
  id("composeai.base-conventions")
}

// The BOM for everything this repository publishes.
//
// `compose-ai-tools` publishes 26 coordinates on one version line. A consumer wanting three of them
// has to name three versions and keep them in step; get it wrong and the mismatch surfaces as a
// `NoSuchMethodError` at run time rather than at resolution — which is exactly what
// compose-preview-server#890 chased down between `render-host` and the daemon's `DesignPage`.
// Importing this platform replaces all of that with one coordinate:
//
//     implementation(platform("ee.schimke.composeai:compose-ai-tools-bom:<version>"))
//     implementation("ee.schimke.composeai:render-host")
//     implementation("ee.schimke.composeai:bundle-format")
//
// ## Where the constraints come from
//
// The main build's are derived, not listed. `settings.gradle.kts` collects every project path whose
// build script applies `composeai.maven-publishing` and hands them over as a system property; the
// artifact id is the path with its separators flattened (`:render-session:cli` -> `render-cli`
// only because `settings.gradle.kts` names that project `:render-cli`). That convention holds for
// all 22 main-build modules, verified rather than assumed, and `PublishedArtifactIdTest` in
// build-logic pins it so a module that breaks it fails the build rather than going missing here.
//
// The four in `gradle-plugin` are listed by hand, because they cannot be derived. It is an
// `includeBuild`, so its projects are not `subprojects` of this build and the settings walk above
// cannot see them — and their artifact ids do not follow their paths either
// (`:gradle-plugin-config` publishes as `compose-preview-config`). The same test pins this list
// against the included build's build files, so adding a published module there without adding it
// here fails rather than silently shipping a BOM that omits the plugin consumers actually apply.
private val includedBuildArtifactIds =
  listOf(
    "compose-preview-plugin",
    "compose-preview-config",
    "daemon-launch-builder",
    "preview-discovery",
  )

// Each constraint takes that module's EFFECTIVE version, which on a release where only some
// modules publish is not this project's version. `PublishedVersions.resolve` is the same function
// `ComposeAiMavenPublishingPlugin` uses to set `project.version`, so the versions the BOM promises
// and the versions the POMs name cannot disagree. Outside a release, and on a release that
// publishes everything, every module resolves to this project's version.
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

dependencies {
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
