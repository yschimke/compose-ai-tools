package com.example.designcatalogm3.shared

import androidx.compose.runtime.Composable

/**
 * Desktop tier: nothing to do.
 *
 * The `@Preview` sticker sheet is seeded by the daemon through the process-static
 * `PreviewOverrideController` that backs `previewOverride*`, so a screen rendered here reads its
 * knobs from the same place every other preview does. Providing the map a second time would give
 * the composition two sources of truth for one value, and the daemon's is the one the rest of the
 * pipeline (the overrides sidecar, `compose/overrides`) reports.
 */
@Composable
actual fun CatalogKnobSeeds(seeds: Map<String, String>, content: @Composable () -> Unit) {
  content()
}
