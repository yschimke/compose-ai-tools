plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
}

dependencies { testImplementation(libs.junit) }

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-pseudolocale-core",
    displayName = "Compose Preview - Pseudolocale Core",
    description =
      "Pure-JVM accent / bidi pseudolocale text transforms (en-XA, ar-XB) — no Android or Compose dependency.",
  )
  inceptionYear.set("2026")
}
