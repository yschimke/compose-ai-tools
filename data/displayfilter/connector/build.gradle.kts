plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  // Filter matrices, ColorMatrix4x5, PostCaptureProcessor implementation, shared constants.
  api(project(":data-displayfilter-core"))
  // DataProductRegistry, DataFetchResult / DataProductCapability wire types, the Extension class
  // DaemonMain wires this into. Re-exported so daemon:android can depend on data-displayfilter-
  // connector alone and still pick up DataProductRegistry transitively, mirroring data-a11y-
  // connector's `api(project(":daemon:core"))`.
  api(project(":daemon:core"))
  api(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-displayfilter-connector",
    displayName = "Compose Preview - Display Filter Data Product Connector",
    description =
      "Daemon-side display-filter data-product connector: writes per-render variant PNGs and a manifest JSON, and serves the displayfilter/variants kind via the compose-preview daemon's data/* JSON-RPC surface.",
  )
  inceptionYear.set("2026")
}
