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

// ## The layers below
//
// This BOM also imports the two lower-layer BOMs, at the versions this build compiles against: the
// daemon line (layer 1a) and the wire contracts (layer 0). Every module here already imports both
// in its own POM (`ComposeAiBaseConventionsPlugin.applyPlatformBom`), so a Gradle consumer that
// depends on a module gets them anyway; a Maven consumer importing only this BOM in
// `<dependencyManagement>` did not, and could mix this release's tools with whatever daemon and
// contracts versions its other dependencies happened to name. Importing them here gives one
// coordinate that aligns all three layers. For Gradle consumers both are ordinary platform
// imports, so they are floors that a newer lower-layer BOM still raises (highest wins). Maven does
// not resolve that way: among imported BOMs the first declaration of a coordinate wins, so a Maven
// consumer that wants a newer daemon or contracts BOM than this one imports must list that BOM
// *before* compose-ai-tools-bom in `<dependencyManagement>`.
//
// They are republished with this BOM, which goes out on every release that publishes anything
// (`printPublishTasks`). A daemon bump always does: `gradle-plugin` bakes the daemon version in, so
// `maven-publish-plan.sh` publishes its four coordinates. A contracts bump on its own publishes
// nothing -- sibling coordinates are floors there -- so the published BOM keeps importing the
// contracts version the published modules were built against until the next release that moves
// one of them, which is also what their own POMs import.
//
// `allowDependencies()` is what lets a `java-platform` carry another platform. It would equally
// let a real dependency into the BOM, which is why `applyPlatformBom` refuses to put the BOMs on a
// platform automatically; here the only `api` entries are these two `platform(...)` imports, and
// the guard below fails configuration if anything else is ever added.
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
