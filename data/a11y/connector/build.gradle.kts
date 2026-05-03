// D2.2 — `:data-a11y-connector` glues `:data-a11y-core` (the generic Android piece) to the
// daemon's data-product API: `AccessibilityDataProducer` writes the per-render JSON
// artefacts, `AccessibilityDataProductRegistry` advertises kinds + serves
// fetch / attach paths, and `AccessibilityImageProcessor` wires the overlay PNG into the
// `ImageProcessor` seam. Published only so `:daemon:android`'s external POM can resolve its
// transitive daemon-side data-product implementation.
//
// See docs/daemon/DATA-PRODUCTS.md § "Module split (D2.2)" for the rationale.

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.tapmoc)
}

android { namespace = "ee.schimke.composeai.data.a11y.connector" }

dependencies {
  // Core a11y types + ATF wrapper + overlay generator. Re-exported via `api` so consumers
  // (`:daemon:android`) can refer to `AccessibilityFinding` / `AccessibilityNode` without
  // adding a second `project` dependency.
  api(project(":data-a11y-core"))

  // DataProductRegistry interface, DataProductCapability / DataProductExtra wire types,
  // ImageProcessor seam — re-exported via `api` for the same reason.
  api(project(":daemon:core"))

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.serialization.json)
}

mavenPublishing {
  configure(
    com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
      javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
      sourcesJar = com.vanniktech.maven.publish.SourcesJar.Sources(),
      variant = "release",
    )
  )
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-a11y-connector",
    displayName = "Compose Preview — Accessibility Data Product (Connector)",
    description =
      "Daemon-side accessibility data-product connector: wires data-a11y-core into the compose-preview daemon's data/* JSON-RPC surface and overlay image processor.",
  )
  inceptionYear.set("2026")
}
