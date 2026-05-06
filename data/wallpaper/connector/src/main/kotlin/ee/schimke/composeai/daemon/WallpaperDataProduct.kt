package ee.schimke.composeai.daemon

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.WallpaperOverride
import ee.schimke.composeai.daemon.protocol.WallpaperPaletteStyle
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.extensions.DataExtension
import ee.schimke.composeai.data.render.extensions.DataExtensionCapability
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableExtension
import ee.schimke.composeai.data.render.extensions.compose.ComposeColorSpec
import ee.schimke.composeai.data.wallpaper.Material3WallpaperProduct
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

const val WALLPAPER_PAYLOAD_CONTEXT_KEY: String = "compose.wallpaper.payload"

// Source/binary-compat shim. WallpaperPayload moved to :data-wallpaper-core in the
// ee.schimke.composeai.data.wallpaper package; downstream consumers that imported it under the
// old ee.schimke.composeai.daemon name continue to resolve via this alias. Mirrors the same
// pattern used in :data-fonts-connector.
typealias WallpaperPayload = ee.schimke.composeai.data.wallpaper.WallpaperPayload

/**
 * Compose-side connector that wraps preview content in a Material 3 [MaterialTheme] whose color
 * scheme is derived from a single seed color.
 *
 * Wired in alongside [Material3ThemeOverrideExtension] on the desktop and Android render paths —
 * when both are present the wallpaper extension runs first so an explicit `material3Theme` override
 * still wins for any role the caller pinned.
 */
class WallpaperOverrideExtension(private val override: WallpaperOverride?) :
  AroundComposableExtension(
    id = ID,
    constraints =
      DataExtensionConstraints(
        phase = DataExtensionPhase.UserEnvironment,
        provides = setOf(DataExtensionCapability(WallpaperDataProductRegistry.KIND)),
      ),
  ) {
  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    if (override == null) {
      content()
      return
    }
    val seed = ComposeColorSpec.resolve(override.seedColor)
    val isDark = override.isDark ?: isMaterialThemeDark()
    val scheme =
      WallpaperColorScheme.from(
        seed = seed,
        isDark = isDark,
        style = override.paletteStyle ?: WallpaperPaletteStyle.TONAL_SPOT,
        contrastLevel = override.contrastLevel ?: 0.0,
      )
    MaterialTheme(
      colorScheme = scheme,
      typography = MaterialTheme.typography,
      shapes = MaterialTheme.shapes,
      content = content,
    )
  }

  /**
   * Heuristic for inheriting the host theme's brightness when the caller did not pin `isDark`.
   * Compares the captured `surface` luminance to a midpoint — anything darker than 0.5 luminance is
   * treated as a dark theme. Falls back to light when no surface color is present.
   */
  @Composable
  private fun isMaterialThemeDark(): Boolean {
    val surface = MaterialTheme.colorScheme.surface
    val luminance = (surface.red + surface.green + surface.blue) / 3f
    return luminance < 0.5f
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(WallpaperDataProductRegistry.KIND)
  }
}

/**
 * Planner that maps `renderNow.overrides.wallpaper` to a [WallpaperOverrideExtension].
 *
 * Registered in [PreviewOverrideExtensions]; the renderer doesn't need to know about the
 * `wallpaper` override field directly — it hands every merged [PreviewOverrides] to every planner
 * and threads the resulting list through the Compose data-extension pipeline.
 */
class WallpaperPreviewOverrideExtension : DataExtension<PreviewOverrides> {
  override val id: DataExtensionId = WallpaperOverrideExtension.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension? =
    request.wallpaper?.let(::WallpaperOverrideExtension)
}

/**
 * Daemon-side registry adapter for `compose/wallpaper`.
 *
 * The registry tracks the seed color last applied via `renderNow.overrides.wallpaper`. A
 * `data/fetch` after a wallpaper-aware render returns the captured payload; before any render or
 * after the override is dropped, it returns [DataProductRegistry.Outcome.NotAvailable]. There is no
 * re-render mode — clients update the seed by sending a fresh `renderNow.overrides.wallpaper`.
 */
class WallpaperDataProductRegistry : DataProductRegistry {
  private val latestPayloads = ConcurrentHashMap<String, WallpaperPayload>()

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
      )
    )

  fun capture(previewId: String?, payload: WallpaperPayload) {
    if (previewId == null) return
    latestPayloads[previewId] = payload
  }

  fun clear(previewId: String?) {
    if (previewId == null) return
    latestPayloads.remove(previewId)
  }

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    if (kind != KIND) return DataProductRegistry.Outcome.Unknown
    val payload = latestPayloads[previewId] ?: return DataProductRegistry.Outcome.NotAvailable
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        payload = json.encodeToJsonElement(WallpaperPayload.serializer(), payload),
      )
    )
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (KIND !in kinds) return emptyList()
    val payload = latestPayloads[previewId] ?: return emptyList()
    return listOf(
      DataProductAttachment(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        payload = json.encodeToJsonElement(WallpaperPayload.serializer(), payload),
      )
    )
  }

  override fun onRender(previewId: String, result: RenderResult) {
    onRender(previewId, result, overrides = null, previewContext = result.previewContext)
  }

  override fun onRender(
    previewId: String,
    result: RenderResult,
    overrides: ee.schimke.composeai.daemon.protocol.PreviewOverrides?,
    previewContext: PreviewContext?,
  ) {
    val applied = overrides?.wallpaper
    if (applied != null) {
      val seed = runCatching { ComposeColorSpec.resolve(applied.seedColor) }.getOrNull()
      if (seed != null) {
        val isDark = applied.isDark ?: previewContextIsDark(previewContext)
        capture(previewId, payloadFor(seed, applied, isDark))
        return
      }
    }
    val captured = previewContext?.inspection?.values?.get(WALLPAPER_PAYLOAD_CONTEXT_KEY)
    if (captured is WallpaperPayload) {
      capture(previewId, captured)
    } else {
      clear(previewId)
    }
  }

  private fun payloadFor(
    seed: Color,
    applied: WallpaperOverride,
    isDark: Boolean,
  ): WallpaperPayload {
    val style = applied.paletteStyle ?: WallpaperPaletteStyle.TONAL_SPOT
    val contrast = applied.contrastLevel ?: 0.0
    val scheme =
      WallpaperColorScheme.from(
        seed = seed,
        isDark = isDark,
        style = style,
        contrastLevel = contrast,
      )
    return WallpaperPayload(
      seedColor = canonicalSeedColor(applied.seedColor, seed),
      isDark = isDark,
      paletteStyle = style,
      contrastLevel = contrast,
      derivedColorScheme =
        linkedMapOf(
          "primary" to scheme.primary.toHexArgb(),
          "onPrimary" to scheme.onPrimary.toHexArgb(),
          "primaryContainer" to scheme.primaryContainer.toHexArgb(),
          "onPrimaryContainer" to scheme.onPrimaryContainer.toHexArgb(),
          "secondary" to scheme.secondary.toHexArgb(),
          "onSecondary" to scheme.onSecondary.toHexArgb(),
          "secondaryContainer" to scheme.secondaryContainer.toHexArgb(),
          "onSecondaryContainer" to scheme.onSecondaryContainer.toHexArgb(),
          "tertiary" to scheme.tertiary.toHexArgb(),
          "onTertiary" to scheme.onTertiary.toHexArgb(),
          "tertiaryContainer" to scheme.tertiaryContainer.toHexArgb(),
          "onTertiaryContainer" to scheme.onTertiaryContainer.toHexArgb(),
        ),
    )
  }

  private fun previewContextIsDark(context: PreviewContext?): Boolean {
    if (context == null) return false
    val payload = context.inspection.values[THEME_PAYLOAD_CONTEXT_KEY] ?: return false
    val resolved =
      runCatching { payload.javaClass.getMethod("getResolvedTokens").invoke(payload) }.getOrNull()
        ?: return false
    @Suppress("UNCHECKED_CAST")
    val colors =
      runCatching {
          resolved.javaClass.getMethod("getColorScheme").invoke(resolved) as? Map<String, String>
        }
        .getOrNull() ?: return false
    val surface = colors["surface"] ?: return false
    return surface.length == 9 && surface.startsWith("#") && surface.luminanceArgb() < 0.5f
  }

  private fun String.luminanceArgb(): Float {
    val raw = removePrefix("#")
    val argb = if (raw.length == 6) "FF$raw".toLong(16) else raw.toLong(16)
    val r = ((argb shr 16) and 0xFF).toInt() / 255f
    val g = ((argb shr 8) and 0xFF).toInt() / 255f
    val b = (argb and 0xFF).toInt() / 255f
    return (r + g + b) / 3f
  }

  private fun canonicalSeedColor(raw: String, parsed: Color): String =
    if (raw.startsWith("#") && (raw.length == 7 || raw.length == 9)) raw.uppercase()
    else parsed.toHexArgb()

  companion object {
    const val KIND: String = Material3WallpaperProduct.KIND
    const val SCHEMA_VERSION: Int = Material3WallpaperProduct.SCHEMA_VERSION

    /**
     * Inspection-context key the theme connector publishes its payload under. Hard-coded here to
     * avoid depending on `:data-theme-connector` just to read the dark/light brightness fallback;
     * matches `MATERIAL3_THEME_PAYLOAD_CONTEXT_KEY` in [ThemeDataProduct.kt][1].
     *
     * [1]:
     * ../../../../../../../../theme/connector/src/main/kotlin/ee/schimke/composeai/daemon/ThemeDataProduct.kt
     */
    private const val THEME_PAYLOAD_CONTEXT_KEY: String = "compose.material3.themePayload"

    private val json = Json {
      encodeDefaults = true
      prettyPrint = false
    }
  }
}
