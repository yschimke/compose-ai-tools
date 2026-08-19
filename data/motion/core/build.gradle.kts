plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
}

dependencies { testImplementation(libs.junit) }

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-motion-core",
    displayName = "Compose Preview — Motion Capture (Core)",
    description =
      "Backend-agnostic motion-capture primitives: the APNG encoder, the `@InteractionPreview` " +
        "script expansion (gesture timeline + recording window), and the APNG frame-delay " +
        "rationals. Shared by the desktop and Robolectric renderers so a capture's recording " +
        "window is derived once rather than per backend.",
  )
  inceptionYear.set("2026")
}
