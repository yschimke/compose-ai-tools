plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  // Kotlin Multiplatform so the annotations are usable from a KMP consumer's `commonMain` (e.g.
  // meshcore-mobile's `:meshcore-components`, whose design tokens live in shared code). Applied by
  // id (no version) alongside the KMP-Android library plugin — same buildscript-classpath story as
  // the CMP samples (AGP 9 brings both onto the classpath for any module in an AGP build). The
  // annotations are pure Kotlin with zero deps, so everything lives in `commonMain` and each target
  // gets it verbatim — no `expect`/`actual`.
  id("org.jetbrains.kotlin.multiplatform")
  id("com.android.kotlin.multiplatform.library")
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

kotlin {
  // JVM target: the desktop renderer, the Gradle plugin, and every plain-JVM/Android consumer that
  // does `implementation(...preview-annotations)` today resolve this variant via Gradle module
  // metadata — so the KMP move stays source-compatible for them.
  jvm()

  // Android target so a KMP-Android-library consumer (`com.android.kotlin.multiplatform.library`)
  // can resolve the annotations for its `androidJvm` compilation. No resources — only annotations.
  // AGP 9 / KMP renamed the `androidLibrary { }` DSL block to `android { }`.
  android {
    namespace = "ee.schimke.composeai.preview"
    compileSdk = 36
    minSdk = 24
  }
}
