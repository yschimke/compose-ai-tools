package ee.schimke.composeai.data.overrides

import kotlinx.serialization.Serializable

/**
 * Kind of a seeded `previewOverride*` value — the small JVM/Compose-native set, 1:1 with the
 * [PreviewOverrideValue] subtypes. Discovery keeps the seed stringly-typed (the plugin classpath
 * doesn't carry this runtime), so the kind rides alongside the raw string and the typed
 * [PreviewOverrideValue] is reconstructed here, once, for every render backend.
 */
@Serializable
enum class OverrideSeedKind {
  STRING,
  BOOLEAN,
  INT,
  FLOAT,
  COLOR,
}

/**
 * One seeded `previewOverride*` value for an [OverrideVariantSpec], from an `@OverrideVariant`
 * annotation entry (`"key=value"` / `"key#index=value"`). [raw] is the verbatim value string;
 * [toValueOrNull] parses it into a typed [PreviewOverrideValue] of [kind]. A raw that doesn't parse
 * to its kind yields `null` and is dropped — the read then falls back to the author default, which
 * is what the type-strict host would do with a mismatched seed anyway.
 *
 * This is the **canonical** seed model shared by every render backend (Android/Robolectric,
 * desktop, and the daemon). The Gradle plugin's `preview-discovery` module carries its own
 * wire-compatible copy because it must stay off this runtime's classpath.
 */
@Serializable
data class OverrideSeed(
  val key: String,
  val index: Int? = null,
  val kind: OverrideSeedKind,
  val raw: String,
) {
  /** The composite seed key the controller resolves against: `key` or `key[index]`. */
  val seedKey: String
    get() = if (index == null) key else "$key[$index]"

  /** Typed value for this seed, or `null` when [raw] doesn't parse to [kind]. */
  fun toValueOrNull(): PreviewOverrideValue? =
    when (kind) {
      OverrideSeedKind.STRING -> PreviewOverrideValue.StringValue(raw)
      OverrideSeedKind.BOOLEAN ->
        raw.trim().toBooleanStrictOrNull()?.let { PreviewOverrideValue.BooleanValue(it) }
      OverrideSeedKind.INT -> raw.trim().toIntOrNull()?.let { PreviewOverrideValue.IntValue(it) }
      OverrideSeedKind.FLOAT ->
        raw.trim().toFloatOrNull()?.let { PreviewOverrideValue.FloatValue(it) }
      OverrideSeedKind.COLOR -> normalizeArgbHex(raw)?.let { PreviewOverrideValue.ColorValue(it) }
    }
}

/**
 * A named override variant sourced from an `@OverrideVariant` annotation. Discovery emits one extra
 * synthetic preview per variant carrying this on `PreviewInfo.overrides`; each render backend seeds
 * [toNamedOverrides] onto the `PreviewOverrideController` (batch) or into `RenderSpec.overrides`
 * (daemon) before composing. [name] is the `_VARIANT_<name>` render-output tag and the variant's
 * catalog `state`.
 */
@Serializable
data class OverrideVariantSpec(val name: String, val seeds: List<OverrideSeed> = emptyList()) {
  /**
   * The seed map (keyed by [OverrideSeed.seedKey]) this variant applies — the exact shape
   * `PreviewOverrides.namedOverrides` / `PreviewOverrideController.set(...)` take. Unparseable
   * seeds are dropped; an all-unparseable variant yields an empty map (callers treat empty as "no
   * seed").
   */
  fun toNamedOverrides(): Map<String, PreviewOverrideValue> {
    val out = LinkedHashMap<String, PreviewOverrideValue>()
    for (seed in seeds) {
      val value = seed.toValueOrNull() ?: continue
      out[seed.seedKey] = value
    }
    return out
  }
}

/**
 * Normalises `#RRGGBB` / `#AARRGGBB` (with or without a leading `#`) to `#AARRGGBB`; null if not
 * hex.
 */
private fun normalizeArgbHex(raw: String): String? {
  val s = raw.trim().removePrefix("#")
  val hex =
    when (s.length) {
      6 -> "FF$s"
      8 -> s
      else -> return null
    }
  return if (hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) "#${hex.uppercase()}"
  else null
}
