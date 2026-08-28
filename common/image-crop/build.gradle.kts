// Content-crop geometry for preview renders: where the component actually is inside its canvas.
//
// A render PNG is often much larger than the component it shows — a Wear sticker is drawn on a
// fixed 454x454 watch canvas with the component centred and small. Deciding the crop window is
// pure arithmetic over an SVG `viewBox`, a PNG's alpha bounds, and a recorded gutter, and both
// halves of #3824's split need the *same* arithmetic: the preview server crops catalog thumbnails
// at page build, and the CLI's `bundle split` crops the same renders on the way into a bundle.
//
// It lived in `:cli:serve` as `ServeThumbCrop.kt`, which made a CLI command depend on the server
// for arithmetic. Published, and deliberately tiny: `javax.imageio` and `kotlin.math`, nothing
// else — no Okio, no serve types, no bundle format.
plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
}

dependencies {
  testImplementation(libs.junit)
  testImplementation(kotlin("test"))
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "common-image-crop",
    displayName = "Compose Preview — Image Crop Geometry",
    description =
      "Content-crop geometry for compose-preview renders: SVG content boxes, PNG alpha bounds, " +
        "recorded gutters, and the thumbnail crop windows derived from them.",
  )
  inceptionYear.set("2026")
}

kotlin {
  // `explicitApi()` — this is a published contract both halves of the #3824 split compile against
  // across a repo boundary, so an implicitly-public declaration is an API decision nobody made.
  explicitApi()

  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
