plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {

  // The wire shapes this module's differs/planners produce — SemanticsDelta, ThemeDelta,
  // PreviewOverrideValue, the pipeline and extension descriptors — are declared in the published
  // protocol, not here. A wire field's type belongs to the wire; this module owns the behaviour
  // that produces it. See compose-preview-contracts 2.1.0.
  api(libs.composeai.daemon.protocol)
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "data-layoutinspector-core",
    displayName = "Compose Preview - Layout Inspector Data Product Core",
    description = "Shared layout inspector data-product model classes for Compose Preview.",
  )
  inceptionYear.set("2026")
}

kotlin {
  // `explicitApi()` — every declaration states its visibility, every public one its return type.
  // This module is a published contract an extracted preview server compiles against across a repo
  // boundary (#3824), so an implicitly-public declaration is an API decision nobody made.
  //
  // Everything here was already public by default and is already in a shipped ABI, so the
  // annotations preserve the existing surface rather than changing it — narrowing any of these to
  // `internal` would be a breaking change and is deliberately not part of this pass.
  explicitApi()

  // ABI dump gate, following `:rc-player-*` and `:daemon-client`. `checkKotlinAbi` diffs the real
  // public ABI against the committed dump in `api/`, so a surface change is a diff in review rather
  // than a downstream break. Regenerate with `./gradlew
  // :data-layoutinspector-core:updateKotlinAbi`.
  @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class) abiValidation()
}

// `checkKotlinAbi` is not wired into `check` by the Kotlin Gradle plugin, so an unrecorded surface
// change would pass CI silently. Wire it explicitly — the gate is only worth having if it runs.
tasks.named("check") { dependsOn("checkKotlinAbi") }
