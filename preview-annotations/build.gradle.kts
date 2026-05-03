import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import tapmoc.TapmocExtension
import tapmoc.configureKotlinCompatibility

plugins {
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.tapmoc)
}

// Pin the Kotlin language/api surface to `kotlinCoreLibraries` (lower than
// the compiler `kotlin` version) so consumers on older Kotlin can still link
// against this artifact. `checkDependencies()` fails the build if a
// transitive API dep raises the floor.
configureKotlinCompatibility(version = libs.versions.kotlinCoreLibraries.get())

// Silence `Language version 2.0 is deprecated …` from the 2.3.x compiler.
// We intentionally hold the floor at 2.0 (see `kotlinCoreLibraries` in
// libs.versions.toml); drop this when that version bumps to 2.1+.
tasks.withType<KotlinCompilationTask<*>>().configureEach {
  compilerOptions.freeCompilerArgs.add("-Xsuppress-version-warnings")
}

extensions.configure<TapmocExtension> { checkDependencies() }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

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
