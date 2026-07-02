plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  // Kotlin Multiplatform so the annotations are usable from a KMP consumer's `commonMain` (e.g.
  // meshcore-mobile's `:meshcore-components`, whose design tokens live in shared code). The
  // annotations are pure Kotlin with zero deps, so everything lives in `commonMain` and each target
  // gets it verbatim — no `expect`/`actual`.
  id("org.jetbrains.kotlin.multiplatform")
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
  // JVM target only. The desktop renderer, the Gradle plugin, and every plain-JVM/Android consumer
  // that does `implementation(...preview-annotations)` resolve this variant via Gradle module
  // metadata. A KMP consumer's Android compilation also consumes the `jvm` variant (an `androidJvm`
  // target can depend on a plain-JVM library), so we deliberately do NOT declare an Android target:
  // it would stamp a `minSdk` floor into an annotation-only artifact and raise the floor of any
  // lower-`minSdk` consumer (Codex review, #2185), for zero benefit — the annotations reference no
  // Android APIs.
  jvm()
}
