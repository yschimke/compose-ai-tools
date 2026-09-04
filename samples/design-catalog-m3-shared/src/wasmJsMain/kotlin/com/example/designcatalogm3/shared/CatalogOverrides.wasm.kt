package com.example.designcatalogm3.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// The in-browser tier never runs a daemon, so it can't seed a knob the way the desktop `@Preview`
// path does (`previewOverride*` ← `renderNow.overrides.namedOverrides`). Instead the embedding
// `serve` viewer pushes the current `knob.<key>` values into the sandboxed iframe (URL fragment +
// `postMessage`), the wasm entrypoint parses them, and provides them through
// [LocalWasmCatalogKnobs] around the catalog composition. These `actual`s read that map so an
// edited knob (a label, a count, a colour) is honoured live in the browser, matching what the
// daemon lanes (PNG / SVG / Live Compose) already do. An un-seeded knob still returns its author
// [default], so the baked sticker sheet stays pixel-unchanged.

/**
 * Current in-browser knob seeds, keyed by the runtime's `seedKey` scheme — the bare `key`, or
 * `key[index]` for an indexed (per-item) knob. Empty by default; the wasm entrypoint
 * ([com.example.cmpwasmcatalog] `main` / `applyOverrides`) re-provides it whenever the viewer
 * pushes a fresh `knob.*` set.
 */
val LocalWasmCatalogKnobs: ProvidableCompositionLocal<Map<String, String>> = compositionLocalOf {
  emptyMap()
}

/** Mirrors `PreviewOverrideHost.seedKey`: bare [key], or `key[index]` for an indexed knob. */
private fun wasmSeedKey(key: String, index: Int?): String =
  if (index == null) key else "$key[$index]"

/**
 * This knob's seeded value, resolving the **instance** it belongs to before the bare key.
 *
 * A catalog body asks for `label` without knowing whether it is a lone sticker or the third button
 * on an assembled screen. [LocalCatalogInstance] is what tells it apart: inside a `CatalogScreen`
 * each node composes under its own index, so `label` resolves `label[3]` and two buttons on one
 * screen carry different text — the thing a screen builder exists for and the thing a bare key
 * cannot express.
 *
 * The bare key remains the fallback, so a viewer pushing a plain `knob.label` still reaches every
 * instance, and a lone sticker (no instance provided) resolves exactly as it always has.
 */
@Composable
private fun seededKnob(key: String, index: Int?): String? {
  val knobs = LocalWasmCatalogKnobs.current
  val instance = index ?: LocalCatalogInstance.current
  if (instance != null)
    knobs[wasmSeedKey(key, instance)]?.let {
      return it
    }
  return knobs[key]
}

@Composable
actual fun catalogOverrideString(key: String, default: String, index: Int?): String =
  seededKnob(key, index) ?: default

@Composable
actual fun catalogOverrideInt(key: String, default: Int, index: Int?): Int =
  seededKnob(key, index)?.trim()?.toIntOrNull() ?: default

@Composable
actual fun catalogOverrideFloat(key: String, default: Float, index: Int?): Float =
  seededKnob(key, index)?.trim()?.toFloatOrNull() ?: default

@Composable
actual fun catalogOverrideBoolean(key: String, default: Boolean, index: Int?): Boolean =
  when (seededKnob(key, index)?.trim()?.lowercase()) {
    "true",
    "1" -> true
    "false",
    "0" -> false
    else -> default
  }

@Composable
actual fun catalogOverrideColor(key: String, default: Color, index: Int?): Color =
  seededKnob(key, index)?.let { parseKnobColorOrNull(it) } ?: default

/**
 * Parse `#AARRGGBB` / `#RRGGBB` (with or without the `#`) to a [Color]; null on malformed input so
 * the caller keeps its author default. Mirrors the JVM runtime's `parseColorOrNull` — reimplemented
 * here because that lives in the JVM-only `:data-preview-overrides-runtime` artifact, which has no
 * wasm klib.
 */
internal fun parseKnobColorOrNull(hex: String): Color? {
  val raw = hex.trim().removePrefix("#")
  val v = raw.toLongOrNull(16) ?: return null
  return when (raw.length) {
    8 -> Color((v and 0xFFFFFFFF).toInt())
    6 -> Color((0xFF000000 or v).toInt())
    else -> null
  }
}
