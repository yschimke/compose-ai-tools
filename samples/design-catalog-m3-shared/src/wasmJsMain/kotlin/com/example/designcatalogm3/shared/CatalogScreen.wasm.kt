package com.example.designcatalogm3.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Browser tier: the knob wrappers read [LocalWasmCatalogKnobs] directly, so seeding a screen is
 * providing that map. This is what makes the composition editable **in the browser** with no daemon
 * and no server round-trip — the whole reason the M3 catalog is the one a wasm builder can host.
 *
 * The screen's seeds are merged **over** whatever the embedding viewer already pushed, not swapped
 * for it: a `serve` viewer may be supplying its own `knob.*` values around this composition, and a
 * screen should override the ones it names without discarding the rest.
 */
@Composable
actual fun CatalogKnobSeeds(seeds: Map<String, String>, content: @Composable () -> Unit) {
  val merged = LocalWasmCatalogKnobs.current + seeds
  CompositionLocalProvider(LocalWasmCatalogKnobs provides merged, content = content)
}
