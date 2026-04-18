// Gradle init script for integration tests.
//
// Adds mavenLocal() to two repository lists in the consumer's settings so
// everything the plugin needs at runtime resolves from the bundle seeded into
// $HOME/.m2 by the build-plugin CI job:
//
//   1. pluginManagement.repositories — for the `ee.schimke.composeai.preview`
//      plugin marker itself, resolved through the consumer's plugin classpath.
//      Actually applying the plugin happens via a `plugins { }` entry in the
//      consumer module's build.gradle(.kts), patched in by
//      .github/ci/patch-consumer.py before Gradle runs.
//
//   2. dependencyResolutionManagement.repositories — for the
//      `ee.schimke.composeai:renderer-android:<version>` AAR that the plugin
//      wires into a `composePreviewAndroidRenderer<Variant>` configuration in
//      the consumer project at render time. Consumer projects often set
//      `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, which blocks
//      `allprojects { repositories { mavenLocal() } }`, so the addition has to
//      happen at the settings level here. Without this, `renderPreviews`
//      fails to resolve the renderer AAR even though the plugin itself loads
//      fine. Adding mavenLocal at the settings level is compatible with
//      FAIL_ON_PROJECT_REPOS (the ban is on project-level `repositories {}`
//      blocks, not settings-level additions).
//
// Loading the plugin through the consumer's plugin classpath (rather than via
// this init script's classpath) is important: it puts the plugin's classes in
// the same classloader scope as the consumer's AGP, so Class identity for
// types like `AndroidComponentsExtension` matches. When the plugin was applied
// via an init-script classpath, our AGP copy and the consumer's AGP copy were
// distinct Class objects with the same FQN, and `getByType<…>()` failed.

gradle.settingsEvaluated {
    pluginManagement.repositories.mavenLocal()
    dependencyResolutionManagement.repositories.mavenLocal()
}
