plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.tapmoc)
}

// Annotation-only artifact — deliberately no runtime deps so adding it to a
// Compose app classpath never drags anything else in.

composeAiMavenPublishing {
  coordinates(
    artifactId = "preview-annotations",
    displayName = "Compose Preview — Annotations",
    description =
      "Annotations consumed by the compose-preview Gradle plugin — e.g. @ScrollingPreview for opting @Preview composables into scrolling screenshot capture.",
  )
  inceptionYear.set("2025")
}
