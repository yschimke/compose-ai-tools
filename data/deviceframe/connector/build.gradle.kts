plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  implementation(project(":common-io"))
  // Catalog + compositor (pure java.awt). Re-exported so the renderer modules can depend on the
  // connector alone and still see DeviceArtCatalog / DeviceFrameCompositor, mirroring how
  // data-displayfilter-connector re-exports data-displayfilter-core.
  api(project(":data-deviceframe-core"))
  api(libs.kotlinx.serialization.json)
  // No HTTP client here on purpose: this connector rides on the render subprocess classpath, where
  // an HTTP client (Ktor/OkHttp) drags a kotlinx-coroutines version that skews Compose and triggers
  // `runBlockingK$default NoSuchMethodError` (docs/RENDERER_COMPATIBILITY.md). The renderer reads
  // the on-disk cache via CachedDeviceArtSource; the Gradle plugin's DeviceArtPrefetch
  // (Ktor/OkHttp)
  // fills that cache off the subprocess.

  testImplementation(libs.junit)
  testImplementation(libs.okio.fakefilesystem)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-deviceframe-connector",
    displayName = "Compose Preview - Device Frame Data Product Connector",
    description =
      "Renderer-side device-frame connector: fetches + caches Android device-art bezels, composites the captured PNG into a real device frame (round Wear watch, phone) with hardware buttons, and writes the framed PNG plus a manifest and CC-BY attribution alongside the base capture.",
  )
  inceptionYear.set("2026")
}
