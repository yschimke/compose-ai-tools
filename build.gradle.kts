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

// Convenience entrypoint for issue #733's `AccessibilityAndroidFunctionalTest`. The test runs
// against the AAR resolved from `~/.m2`, so the renderer-android AAR + every transitively-pulled
// internal module must land in mavenLocal before `:gradle-plugin:functionalTest`. Wiring it
// from the *parent* build (rather than from inside the gradle-plugin included build) lets the
// child stay decoupled — the `dependsOn` chain only flows parent → child, the standard
// direction.
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
    "Publishes renderer-android (+ transitive internal modules) and the gradle plugin itself to " +
      "mavenLocal, then runs gradle-plugin's functionalTest (including the Android-flavour " +
      "AccessibilityAndroidFunctionalTest)."
  androidFunctionalTestPublishTargets.forEach { dependsOn("$it:publishToMavenLocal") }
  // The synthetic Android-library project resolves our plugin through its own
  // `plugins { id("ee.schimke.composeai.preview") version "<v>" }` block (so AGP and our plugin
  // share one classloader hierarchy — the fix for the loader-split blocker tracked in #733).
  // That requires the plugin to be in mavenLocal before the functional test starts.
  dependsOn(gradle.includedBuild("gradle-plugin").task(":publishToMavenLocal"))
  dependsOn(gradle.includedBuild("gradle-plugin").task(":functionalTest"))
}
