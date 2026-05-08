plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  // The post-capture processor reads CommonDataProducts.ImageArtifact and emits its own
  // DataProductKey artifacts; both live in :data-render-core. No Compose, no Android — color
  // matrices apply to the captured PNG via java.awt.image.BufferedImage so the same module
  // works for the Android (Robolectric, host JVM) and Desktop renderers.
  api(project(":data-render-core"))
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-displayfilter-core",
    displayName = "Compose Preview - Display Filter Data Product Core",
    description =
      "Post-capture color-matrix filters (grayscale/bedtime, color inversion, daltonizer simulation) for Compose Preview. Operates on rendered PNGs; renderer-agnostic.",
  )
  inceptionYear.set("2026")
}
