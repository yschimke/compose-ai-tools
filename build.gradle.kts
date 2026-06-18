plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.compose.multiplatform) apply false
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  // ktfmt is no longer declared here: `ComposeAiBaseConventionsPlugin` (build-logic) applies and
  // configures it on every project from settings.gradle.kts via `gradle.lifecycle.beforeProject` —
  // the Isolated Projects-safe replacement for the old `allprojects {}` block. ktfmt + the Kotlin
  // Gradle plugin it links against ride on the build-logic classpath, so declaring the alias here
  // too would put a second ktfmt on a different classloader.
  // Loaded into the root scope so :renderer-android and :daemon:android (and
  // any future sibling) share the plugin's ClassLoader. Without this, each
  // sibling instantiates its own MavenCentralBuildService class and Gradle
  // refuses to share the build service across them — fails configuration
  // with "Cannot set the value of task ':daemon:android:dropMavenCentral
  // Deployment' property 'buildService'".
  alias(libs.plugins.maven.publish) apply false
}

// The per-project conventions that used to live here in an `allprojects {}` block (ktfmt +
// googleStyle + the history-gate test system property) now live in `ComposeAiBaseConventionsPlugin`
// (build-logic), applied by each module via `plugins { id("composeai.base-conventions") }`.
// Isolated Projects forbids the root build reaching across project boundaries, so the configuration
// is pushed down to each project instead of pulled in from the root.

// `./gradlew ktfmtCheck` already fans out to every project that applies the
// plugin via Gradle's task-name matching. The aggregate tasks below add the
// `gradle-plugin` included build, whose tasks aren't reachable that way.
//
// Under Isolated Projects the root can't iterate `allprojects` to discover its siblings, so the
// ktfmt-carrying project paths (every project except the root) are gathered in `settings.gradle.kts`
// and handed over via a system property (read through a configuration-cache-tracked provider).
// Depending on a sibling task *by path* is an ordinary (lazy) task-graph edge and stays IP-clean —
// it never touches the sibling Project object at configuration time.
// Every path here is a non-root project (the root carries no ktfmt — see settings.gradle.kts), so
// `:$path:task` is always well-formed.
val ktfmtProjectPaths =
  providers.systemProperty("composeai.ktfmtProjectPaths").get().split(",")

tasks.register("ktfmtCheckAll") {
  group = "verification"
  description = "Runs ktfmtCheck across this build and the gradle-plugin included build."
  dependsOn(gradle.includedBuild("gradle-plugin").task(":ktfmtCheck"))
  ktfmtProjectPaths.forEach { dependsOn("$it:ktfmtCheck") }
}

tasks.register("ktfmtFormatAll") {
  group = "formatting"
  description = "Runs ktfmtFormat across this build and the gradle-plugin included build."
  dependsOn(gradle.includedBuild("gradle-plugin").task(":ktfmtFormat"))
  ktfmtProjectPaths.forEach { dependsOn("$it:ktfmtFormat") }
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
//     implementation :data-scroll-android
//       api :data-scroll-core
val androidFunctionalTestPublishTargets =
  listOf(
    ":renderer-android",
    ":data-a11y-core",
    ":data-render-core",
    ":data-render-compose",
    ":data-scroll-core",
    ":data-scroll-android",
    // The renderer + data modules read/write files through Okio's `:common-io` (its file-IO
    // foundation), so it's part of the closure the synthetic project must resolve from mavenLocal.
    ":common-io",
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

// Mirrors `androidFunctionalTestPublishTargets` for the desktop renderer path. After #1472 the
// plugin auto-adds `composePreviewRenderer("ee.schimke.composeai:renderer-desktop:<v>")` for
// out-of-tree consumers (no more stub fallback), so the synthetic Compose Desktop project the
// bundle-render e2e drives needs the artifact + its publishable transitive project-deps in
// `~/.m2` to resolve. The list is the closure of `:renderer-desktop`'s `implementation`/`api`
// project deps:
//   :renderer-desktop
//     implementation :data-scroll-core
//       api :data-render-core
//       api :data-render-compose
//     implementation :data-pseudolocale-core
//     implementation :data-displayfilter-connector
//       api :data-displayfilter-core
//       api :daemon:core
//         api :renderer-xr-client (daemon-core fronts the native XR render server — RENDERER_SERVICE)
//         api :data-layoutinspector-core (semantics models + differ for `history/diff mode=semantics`, #1785)
//         api :data-theme-core (theme-token models + differ for `history/diff mode=data`, #1873)
//     implementation :lottie-preview-runtime (Compottie-backed kind=LOTTIE render path)
//   plus :common-io (the Okio file-IO foundation those modules read/write through).
val bundleRenderFunctionalTestPublishTargets =
  listOf(
    ":renderer-desktop",
    ":data-render-core",
    ":data-render-compose",
    ":data-scroll-core",
    ":data-pseudolocale-core",
    ":data-displayfilter-connector",
    ":data-displayfilter-core",
    ":daemon:core",
    ":renderer-xr-client",
    ":data-layoutinspector-core",
    ":data-theme-core",
    ":lottie-preview-runtime",
    ":common-io",
  )

tasks.register("functionalTestWithBundleRender") {
  group = "verification"
  description =
    "Publishes renderer-desktop (+ transitive internal modules) and the gradle plugin itself " +
      "to mavenLocal, builds the compose-preview CLI binary via `:cli:installDist`, then runs " +
      "gradle-plugin's functionalTest with the opt-in `bundle.render.e2e=true` flag set so " +
      "`BundleRenderEndToEndFunctionalTest` actually fires."
  bundleRenderFunctionalTestPublishTargets.forEach { dependsOn("$it:publishToMavenLocal") }
  // Synthetic Compose Desktop project resolves the plugin from mavenLocal via the same
  // `id(...) version "<v>"` block the a11y e2e uses; pre-publish or `BUILD FAILED`.
  dependsOn(gradle.includedBuild("gradle-plugin").task(":publishToMavenLocal"))
  // CLI binary at `cli/build/install/compose-preview/bin/compose-preview`, plus the
  // `lib-renderer/` sibling dir the renderer subprocess loads.
  dependsOn(":cli:installDist")
  dependsOn(gradle.includedBuild("gradle-plugin").task(":functionalTest"))
}

tasks.register("functionalTestWithAndroidBundleDaemon") {
  group = "verification"
  description =
    "Builds the compose-preview CLI install dist (now shipping `lib-daemon-android/`) plus the " +
      "Android sample bundles (`:samples:wear` Wear-tile/Compose, `:samples:remotecompose` Remote " +
      "Compose), then runs gradle-plugin's functionalTest with `bundle.daemon.android.e2e=true` so " +
      "`AndroidBundleDaemonRenderFunctionalTest` drives `compose-preview bundle daemon` against " +
      "each bundle and renders protolayout / remotecompose / classic previews to PNG. Needs a " +
      "local Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT) for android.jar + the Robolectric build."
  // Same publish targets as the desktop e2e — running the full `functionalTest` also exercises
  // tests that resolve the plugin (and renderer-desktop transitives) from mavenLocal.
  bundleRenderFunctionalTestPublishTargets.forEach { dependsOn("$it:publishToMavenLocal") }
  dependsOn(gradle.includedBuild("gradle-plugin").task(":publishToMavenLocal"))
  // The CLI install dist provides the `compose-preview` binary; #1685 moved the Android daemon
  // runtime OUT of it into a standalone archive, so those jars now come from the staged dir
  // produced by `:cli:stageDaemonAndroidLibs`. The test points the CLI at that dir via
  // `-Dcomposeai.cli.libDaemonAndroidDir`.
  dependsOn(":cli:installDist")
  dependsOn(":cli:stageDaemonAndroidLibs")
  // The Android sample bundles the test renders. Each `composePreviewBundle` runs the plugin's
  // render (Robolectric) + pack against the real sample, emitting an `backend="android"` bundle
  // with non-empty `intermediateRepresentations` (Wear tile + Remote Compose IR) alongside classic
  // Compose previews.
  dependsOn(":samples:wear:composePreviewBundle")
  dependsOn(":samples:remotecompose:composePreviewBundle")
  dependsOn(gradle.includedBuild("gradle-plugin").task(":functionalTest"))
}

// `:cli:installDist` and the included build's `functionalTest` would otherwise run in parallel
// (Gradle's parallel scheduler doesn't serialise cross-build deps automatically). The functional
// test invokes the CLI via `ProcessBuilder`, so it crashes with `NoClassDefFoundError` against a
// half-populated `lib/` dir.
//
// This used to be enforced with a `gradle.taskGraph.whenReady { allTasks.… mustRunAfter … }`
// hook, but Isolated Projects forbids it twice over: reading the global `allTasks` graph and
// annotating a task that belongs to another project / build. The ordering is fundamentally
// cross-build (the producer `:cli:installDist` lives in this build; the consumer `functionalTest`
// lives in the `gradle-plugin` included build, which can't be handed a back-reference), so it
// can't be re-expressed as a normal dependency edge under IP.
//
// CI already sidesteps the race the robust way — it pre-builds the install dir in a *separate*
// Gradle invocation before running the e2e task (see `.github/workflows/ci.yml`):
//
//     ./gradlew :cli:installDist :gradle-plugin:publishToMavenLocal
//     ./gradlew functionalTestWithBundleRender -Pbundle.render.e2e=true
//
// Run these two opt-in aggregates the same way locally (install dist first, then the e2e task);
// the second invocation sees the install up-to-date, so the only cost is dodging the race. The
// functionalTest also self-checks the binary's presence past its opt-in gate and fails with a
// useful message rather than a bare `NoClassDefFoundError`.
