// Test fixture — minimal build.gradle.kts that includes the literal
// `id("ee.schimke.composeai.preview")` apply line so GradleService's
// findPreviewModules() / appliesPlugin() heuristic picks this directory up
// without actually evaluating Gradle. Nothing else here is loaded.
plugins {
    id("ee.schimke.composeai.preview")
}
