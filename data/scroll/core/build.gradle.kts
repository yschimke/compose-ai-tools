plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.tapmoc)
}

dependencies {
  api(project(":data-render-core"))
  api(project(":data-render-compose"))

  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-scroll-core",
    displayName = "Compose Preview — Scroll Data Product (Core)",
    description =
      "Pure-JVM scroll data-product primitives: slice-stitcher, GIF encoder, axis primitives, " +
        "and the long / GIF scroll-frame planner extensions. Platform-specific scroll drivers " +
        "live in their renderer module (`:data-scroll-android` for Robolectric).",
  )
  inceptionYear.set("2026")
}
