// Gradle init script for integration tests.
//
// Adds mavenLocal() to the settings-level pluginManagement repositories so the
// `ee.schimke.composeai.preview` plugin marker published by the build-plugin
// CI job can be resolved. This is the ONLY responsibility of the init script:
// actually applying the plugin happens via a `plugins { }` entry in the
// consumer module's build.gradle(.kts), patched in by .github/ci/patch-consumer.py
// before Gradle runs.
//
// Loading the plugin through the consumer's plugin classpath (rather than via
// this init script's classpath) is important: it puts the plugin's classes in
// the same classloader scope as the consumer's AGP, so Class identity for
// types like `AndroidComponentsExtension` matches. When the plugin was applied
// via an init-script classpath, our AGP copy and the consumer's AGP copy were
// distinct Class objects with the same FQN, and `getByType<…>()` failed.

gradle.settingsEvaluated {
    pluginManagement.repositories.mavenLocal()
}
