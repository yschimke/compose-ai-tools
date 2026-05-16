plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.ktfmt) apply false
  // Loaded into the root scope so :renderer-android and :daemon:android (and
  // any future sibling) share the plugin's ClassLoader. Without this, each
  // sibling instantiates its own MavenCentralBuildService class and Gradle
  // refuses to share the build service across them — fails configuration
  // with "Cannot set the value of task ':daemon:android:dropMavenCentral
  // Deployment' property 'buildService'".
  alias(libs.plugins.maven.publish) apply false
}

allprojects {
  apply(plugin = "com.ncorti.ktfmt.gradle")
  extensions.configure<com.ncorti.ktfmt.gradle.KtfmtExtension>("ktfmt") { googleStyle() }
}

// `./gradlew ktfmtCheck` already fans out to every project that applies the
// plugin via Gradle's task-name matching. The aggregate tasks below add the
// `gradle-plugin` included build, whose tasks aren't reachable that way.
fun Project.taskPath(name: String) = if (path == ":") ":$name" else "$path:$name"

tasks.register("ktfmtCheckAll") {
  group = "verification"
  description = "Runs ktfmtCheck across this build and the gradle-plugin included build."
  dependsOn(gradle.includedBuild("gradle-plugin").task(":ktfmtCheck"))
  allprojects.forEach { dependsOn(it.taskPath("ktfmtCheck")) }
}

tasks.register("ktfmtFormatAll") {
  group = "formatting"
  description = "Runs ktfmtFormat across this build and the gradle-plugin included build."
  dependsOn(gradle.includedBuild("gradle-plugin").task(":ktfmtFormat"))
  allprojects.forEach { dependsOn(it.taskPath("ktfmtFormat")) }
}

// Convenience entrypoint for `CliA11yEndToEndFunctionalTest`. The test runs an Android-flavour
// synthetic project through the CLI's daemon-driven a11y flow, which needs:
//   1. The `renderer-android` AAR closure published to mavenLocal so the synthetic Android
//      library resolves it through its own `pluginManagement.repositories.mavenLocal()`.
//   2. The `:gradle-plugin` itself published to mavenLocal for the same reason (the test's
//      synthetic `plugins { id("ee.schimke.composeai.preview") version "<v>" }` block looks
//      it up by coordinate).
//   3. The `compose-preview` CLI binary built via `:cli:installDist` — the test shells out to
//      it as the actual `compose-preview a11y` subject.
//
// Wired from the *parent* build so the `dependsOn` chain flows parent → child (the standard
// direction); the included `gradle-plugin` build expresses the test's own data (the synthetic
// project's source files) inline.
//
// The publish set is the closure of renderer-android's compile/runtime project deps:
//   :renderer-android
//     api :data-a11y-core
//       api :data-render-core
//     implementation :data-render-core
//     implementation :data-scroll-core
//       api :data-render-core
//       api :data-render-compose
//         api :data-render-core
val androidFunctionalTestPublishTargets =
  listOf(
    ":renderer-android",
    ":data-a11y-core",
    ":data-render-core",
    ":data-render-compose",
    ":data-scroll-core",
  )

tasks.register("functionalTestWithAndroid") {
  group = "verification"
  description =
    "Publishes renderer-android (+ transitive internal modules) and the gradle plugin itself " +
      "to mavenLocal, builds the compose-preview CLI binary via `:cli:installDist`, then runs " +
      "gradle-plugin's functionalTest with the opt-in `cli.a11y.e2e=true` flag set so " +
      "`CliA11yEndToEndFunctionalTest` actually fires."
  androidFunctionalTestPublishTargets.forEach { dependsOn("$it:publishToMavenLocal") }
  // The synthetic Android-library project resolves our plugin through its own
  // `plugins { id("ee.schimke.composeai.preview") version "<v>" }` block (so AGP and our plugin
  // share one classloader hierarchy). That requires the plugin to be in mavenLocal before the
  // functional test starts.
  dependsOn(gradle.includedBuild("gradle-plugin").task(":publishToMavenLocal"))
  // The CLI binary the test invokes — built into `cli/build/install/compose-preview/bin/`.
  dependsOn(":cli:installDist")
  dependsOn(gradle.includedBuild("gradle-plugin").task(":functionalTest"))
}

tasks.register("functionalTestWithBundleRender") {
  group = "verification"
  description =
    "Publishes the gradle plugin to mavenLocal and builds the compose-preview CLI binary " +
      "(`:cli:installDist`), then runs gradle-plugin's functionalTest with the opt-in " +
      "`bundle.render.e2e=true` flag set so `BundleRenderEndToEndFunctionalTest` actually fires."
  // Synthetic Compose Desktop project resolves the plugin from mavenLocal via the same
  // `id(...) version "<v>"` block the a11y e2e uses; pre-publish or `BUILD FAILED`.
  dependsOn(gradle.includedBuild("gradle-plugin").task(":publishToMavenLocal"))
  // CLI binary at `cli/build/install/compose-preview/bin/compose-preview`, plus the
  // `lib-renderer/` sibling dir the renderer subprocess loads.
  dependsOn(":cli:installDist")
  dependsOn(gradle.includedBuild("gradle-plugin").task(":functionalTest"))
}

// `:cli:installDist` and the included build's `functionalTest` would otherwise run in parallel
// (Gradle's parallel scheduler doesn't serialise cross-build deps automatically). The functional
// test invokes the CLI via `ProcessBuilder`, so it crashes with `NoClassDefFoundError` against a
// half-populated `lib/` dir. Enforce ordering at task-graph-ready time — the test mustRunAfter the
// install. Applies to both `functionalTestWithAndroid` and `functionalTestWithBundleRender`; both
// drive the CLI binary out of the same `:cli:installDist` outputs.
gradle.taskGraph.whenReady {
  val installCli = allTasks.firstOrNull { it.path == ":cli:installDist" } ?: return@whenReady
  allTasks
    .filter { it.path.endsWith(":functionalTest") && it.path.contains("gradle-plugin") }
    .forEach { it.mustRunAfter(installCli) }
}
