// `:slot-preview-runtime` — composable-helper authoring path for **structured-screen slots**.
//
// `PreviewSlot("leadingIcon") { … }` marks a named region of a preview so a structured-screen
// builder (the design-parity Figma plugin) can fill it with a child component. It is a **no-op in a
// normal render** — it just draws its content, tagged with `testTag = "dp-slot:<name>"` so the
// region is captured into the semantics tree with its bounds (which the `/render/<id>.slots` route
// reads). Under `LocalSlotMode` (the daemon's `slotMode` render override, follow-up) it renders a
// translucent labelled `SlotPlaceholder` instead, so a designer sees exactly where each slot is and
// drops a composable into that precise box.
//
// Standalone on purpose — no compile dep on `:renderer-desktop`. The renderer/daemon depend *onto*
// this module to `CompositionLocalProvider(LocalSlotMode provides …)` around the rendered content,
// exactly as they do for `:lottie-preview-runtime`'s `LocalLottieProgress`.

plugins {
  id("composeai.base-conventions")
  id("composeai.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose.multiplatform)
  alias(libs.plugins.compose.compiler)
}

dependencies {
  api(libs.jetbrains.compose.runtime)
  api(libs.jetbrains.compose.foundation)
  api(libs.jetbrains.compose.ui)

  // The `dp-slot:` tag prefix has a single source of truth in the reader
  // (`PreviewSlots.SLOT_TAG_PREFIX`); a test asserts this module's copy agrees so the two can't
  // drift. Test-only so the marker's runtime classpath stays serialization-free.
  testImplementation(project(":data-layoutinspector-core"))
  testImplementation(libs.junit)
}

composeAiMavenPublishing {
  coordinates(
    artifactId = "slot-preview-runtime",
    displayName = "Compose Preview — Slot Runtime",
    description =
      "Composable helper that marks a named slot region of a Compose `@Preview` (a `dp-slot:` " +
        "testTag captured with its bounds) so a structured-screen builder can fill it with a child " +
        "component; under a slot-mode override it renders a labelled placeholder for the designer.",
  )
  inceptionYear.set("2026")
}
