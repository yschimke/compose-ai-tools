// The root project's aggregate and release tasks, applied from `build.gradle.kts`.
//
// Kept separate because the publish plan treats `build.gradle.kts` as a shared build input and
// republishes every module when it changes; this file is outside that set. Anything that changes
// what a module builds belongs in `build.gradle.kts` or build-logic instead.

// Per-project conventions live in `ComposeAiBaseConventionsPlugin` (build-logic): Isolated Projects
// forbids the root configuring its siblings.

// `./gradlew ktfmtCheck` already fans out by task-name matching; these aggregates add the
// `gradle-plugin` included build. Under Isolated Projects the root can't iterate `allprojects`, so
// the non-root ktfmt project paths come from `settings.gradle.kts` via a system property.
val ktfmtProjectPaths =
  providers.systemProperty("composeai.ktfmtProjectPaths").get().split(",")

// `includedBuild(...).task(":ktfmtCheck")` addresses only that build's root project, so its
// subprojects are passed the same way from `gradle-plugin/settings.gradle.kts`.
val gradlePluginKtfmtProjectPaths =
  providers
    .systemProperty("composeai.gradlePluginKtfmtProjectPaths")
    .get()
    .split(",")
    .filter { it.isNotEmpty() }

tasks.register("ktfmtCheckAll") {
  group = "verification"
  description = "Runs ktfmtCheck across this build and the gradle-plugin included build."
  val gradlePlugin = gradle.includedBuild("gradle-plugin")
  dependsOn(gradlePlugin.task(":ktfmtCheck"))
  gradlePluginKtfmtProjectPaths.forEach { dependsOn(gradlePlugin.task("$it:ktfmtCheck")) }
  ktfmtProjectPaths.forEach { dependsOn("$it:ktfmtCheck") }
}

tasks.register("ktfmtFormatAll") {
  group = "formatting"
  description = "Runs ktfmtFormat across this build and the gradle-plugin included build."
  val gradlePlugin = gradle.includedBuild("gradle-plugin")
  dependsOn(gradlePlugin.task(":ktfmtFormat"))
  gradlePluginKtfmtProjectPaths.forEach { dependsOn(gradlePlugin.task("$it:ktfmtFormat")) }
  ktfmtProjectPaths.forEach { dependsOn("$it:ktfmtFormat") }
}

// Convenience entrypoint for `CliA11yEndToEndFunctionalTest`. Needs the plugin in mavenLocal (the
// synthetic project resolves it by coordinate) and the CLI from `:cli:installDist`. Renderers come
// from compose-preview-daemon via Maven Central, so only the plugin has to reach mavenLocal.
tasks.register("functionalTestWithAndroid") {
  group = "verification"
  description =
    "Publishes the gradle plugin to mavenLocal, builds the compose-preview CLI binary via `:cli:installDist`, then runs " +
      "gradle-plugin's functionalTest with the opt-in `cli.a11y.e2e=true` flag set so " +
      "`CliA11yEndToEndFunctionalTest` actually fires."
  // The synthetic project resolves our plugin by version so AGP and the plugin share a classloader.
  dependsOn(gradle.includedBuild("gradle-plugin").task(":publishToMavenLocal"))
  dependsOn(":cli:installDist")
  dependsOn(gradle.includedBuild("gradle-plugin").task(":functionalTest"))
}

tasks.register("functionalTestWithBundleRender") {
  group = "verification"
  description =
    "Publishes the gradle plugin to mavenLocal, builds the compose-preview CLI binary via `:cli:installDist`, then runs " +
      "gradle-plugin's functionalTest with the opt-in `bundle.render.e2e=true` flag set so " +
      "`BundleRenderEndToEndFunctionalTest` actually fires."
  // The synthetic Compose Desktop project resolves the plugin from mavenLocal.
  dependsOn(gradle.includedBuild("gradle-plugin").task(":publishToMavenLocal"))
  // The renderer sidecar is deliberately absent: the E2E exercises first-use provisioning.
  dependsOn(":cli:installDist")
  dependsOn(gradle.includedBuild("gradle-plugin").task(":functionalTest"))
}

tasks.register("functionalTestWithAndroidBundleDaemon") {
  group = "verification"
  description =
    "Builds the compose-preview CLI install dist plus the " +
      "Android sample bundles (`:samples:wear` Wear-tile/Compose, `:samples:remotecompose` Remote " +
      "Compose), then runs gradle-plugin's functionalTest with `bundle.daemon.android.e2e=true` so " +
      "`AndroidBundleDaemonRenderFunctionalTest` drives `compose-preview bundle daemon` against " +
      "each bundle and renders protolayout / remotecompose / classic previews to PNG. Needs a " +
      "local Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT) for android.jar + the Robolectric build."
  dependsOn(gradle.includedBuild("gradle-plugin").task(":publishToMavenLocal"))
  // The CLI fetches the Android daemon runtime on first use, which is the path the e2e exercises.
  dependsOn(":cli:installDist")
  // Each `composePreviewBundle` emits an android bundle with intermediate representations (Wear
  // tile + Remote Compose IR) alongside classic Compose previews.
  dependsOn(":samples:wear:composePreviewBundle")
  dependsOn(":samples:remotecompose:composePreviewBundle")
  dependsOn(gradle.includedBuild("gradle-plugin").task(":functionalTest"))
}

// `:cli:installDist` and the included build's `functionalTest` can run in parallel, and the test
// then crashes against a half-populated `lib/`. Isolated Projects forbids expressing that
// cross-build ordering, so run the install in a separate invocation first (as CI does):
//
//     ./gradlew :cli:installDist :gradle-plugin:publishToMavenLocal
//     ./gradlew functionalTestWithBundleRender -Pbundle.render.e2e=true

// Prints the Gradle task list `release.yml` runs to publish. Generated rather than hand-kept
// because module directories and project paths are deliberately decoupled in `settings.gradle.kts`,
// and a stale mapping would publish a module twice (Central rejects that) or drop one.
val printPublishTasks by
  tasks.registering {
    group = "publishing"
    description = "Print the publish task path for each module this build publishes."
    notCompatibleWithConfigurationCache("Inspects the project tree at execution time")
    val rootDirPath = rootDir
    // `-Pcomposeai.publishSet` (from `maven-publish-plan.sh`) names the modules to upload. Absent
    // means publish everything; empty means the plan found nothing — keep them distinct. Mirrors
    // `PublishedVersions.parsePublishSet`, which this script cannot see.
    val publishSet =
      providers
        .gradleProperty("composeai.publishSet")
        .orNull
        ?.split(",")
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toSet()
    val rows =
      subprojects
        .filter {
          it.plugins.hasPlugin("composeai.maven-publishing") ||
            it.plugins.hasPlugin("composeai.maven-publishing-platform")
        }
        .filter { p ->
          // An empty set leaves every BOM constraint unchanged, so a new BOM would only burn
          // Central quota.
          publishSet == null ||
            (p.path == ":bom" && publishSet.isNotEmpty()) ||
            (p.path != ":bom" && p.path.removePrefix(":").replace(':', '-') in publishSet)
        }
        .map { p ->
          val dir = p.projectDir.relativeTo(rootDirPath).invariantSeparatorsPath
          "${p.path}:publishAndReleaseToMavenCentral" to dir
        }
    doLast {
      // `gradle-plugin` is an includeBuild, so its publishing modules aren't `subprojects` here;
      // its root task publishes all of them, included whenever any is in the set (or no set is
      // given).
      val includedBuildIds =
        setOf(
          "compose-preview-config",
          "compose-preview-plugin",
          "daemon-launch-builder",
          "preview-discovery",
        )
      val all =
        if (publishSet == null || publishSet.any { it in includedBuildIds }) {
          rows + (":gradle-plugin:publishAndReleaseToMavenCentral" to "gradle-plugin")
        } else {
          rows
        }
      all.sortedBy { (task, _) -> task }.forEach { (task, dir) -> println("$task\t$dir") }
    }
  }
