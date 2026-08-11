// Signature-only placeholders for the proprietary Wear gesture SDK. These classes exist solely on
// the off-device Robolectric/render classpath so wear-compose-material3's internal bridge can be
// inspected and shadowed. They must never be packaged into data-gestures-connector, which is also
// consumed by production Wear applications.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  `java-library`
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-gestures-robolectric-stubs",
    displayName = "Compose Preview - Wear Gesture Robolectric Stubs",
    description = "Robolectric-only signature placeholders for the proprietary Wear gesture SDK.",
  )
  inceptionYear.set("2026")
}
