plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  // The compositor draws the captured PNG into a device-art frame via java.awt.image.BufferedImage
  // / Graphics2D — no Compose, no Android, no Okio — so the same module works under the Android
  // (Robolectric, host JVM) and Desktop renderers. The catalog is pure data. Layer fetching and
  // disk IO live in the connector, not here.
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-deviceframe-core",
    displayName = "Compose Preview - Device Frame Data Product Core",
    description =
      "Post-capture device-art compositing for Compose Preview: draws a rendered PNG into a real device bezel (round Wear watch, phone) with hardware buttons. Operates on rendered PNGs; renderer-agnostic.",
  )
  inceptionYear.set("2026")
}
