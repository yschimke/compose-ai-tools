package ee.schimke.composeai.daemon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.render.PreviewBackdrop
import ee.schimke.composeai.data.render.PreviewBackground
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.extensions.DataExtensionCapability
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableExtension
import ee.schimke.composeai.data.render.extensions.compose.ComposeColorSpec
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Metadata product describing the background a client should place behind a rendered device frame.
 *
 * **The precedence lives in [PreviewBackdrop], not here.** This product used to own its own chain,
 * which was the same question the catalog grid, the compare wall, the reference-compare page and
 * the fidelity scorer were each answering differently elsewhere; it now supplies the evidence it
 * has and lets the shared resolver rank it. What this class still owns is which evidence a *live
 * daemon* can offer: the preview's annotation params up front, the captured Material 3 theme colors
 * through [PreviewContext.inspection] once a render has produced them, and the light-background
 * fallback in the window before that — a live host has to draw something the moment a preview is
 * opened.
 *
 * It does **not** supply a catalog stage, because a daemon renders a module and does not know which
 * published catalog (if any) a preview belongs to. That rung is applied downstream, by the serve
 * host that mounted the catalog, via `PreviewBackdrop.withCatalogDefault`.
 */
class DeviceBackgroundDataProductRegistry(previewIndex: PreviewIndex) : DataProductRegistry {
  private val backgrounds: ConcurrentHashMap<String, DeviceBackground> =
    ConcurrentHashMap(previewIndex.snapshot().mapValues { (_, preview) -> preview.background() })

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
        displayName = "Device background",
        facets = listOf(DataProductFacet.STRUCTURED),
        sampling = SamplingPolicy.End,
      )
    )

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    if (kind != KIND) return DataProductRegistry.Outcome.Unknown
    val payload = payloadFor(previewId) ?: return DataProductRegistry.Outcome.NotAvailable
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(kind = KIND, schemaVersion = SCHEMA_VERSION, payload = payload)
    )
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (KIND !in kinds) return emptyList()
    val payload = payloadFor(previewId) ?: return emptyList()
    return listOf(
      DataProductAttachment(kind = KIND, schemaVersion = SCHEMA_VERSION, payload = payload)
    )
  }

  override fun onRender(
    previewId: String,
    result: RenderResult,
    overrides: PreviewOverrides?,
    previewContext: PreviewContext?,
  ) {
    result.previewContext?.let { context ->
      val current = backgrounds[previewId]
      if (current?.previewExplicit == true) return
      backgrounds[previewId] =
        DeviceBackgroundThemeCapture.from(context)?.background() ?: current ?: fallbackBackground()
    }
  }

  private fun payloadFor(previewId: String): JsonElement? =
    backgrounds[previewId]?.let { background ->
      buildJsonObject {
        putJsonBackground("background", background)
        put("color", background.color)
        put("source", background.source)
      }
    }

  private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonBackground(
    name: String,
    background: DeviceBackground,
  ) {
    put(
      name,
      buildJsonObject {
        put("color", background.color)
        put("source", background.source)
      },
    )
  }

  companion object {
    const val KIND: String = "render/deviceBackground"
    const val SCHEMA_VERSION: Int = 1
  }
}

/**
 * Clean Compose-facing connector for applying the selected device background.
 *
 * The metadata product still decides which color wins. Hosts that want the background applied in
 * composition can plan this extension instead of hardcoding a renderer-side `Box` wrapper.
 */
class DeviceBackgroundExtension(private val color: String) :
  AroundComposableExtension(
    id = DataExtensionId(DeviceBackgroundDataProductRegistry.KIND),
    constraints =
      DataExtensionConstraints(
        phase = DataExtensionPhase.OuterEnvironment,
        provides = setOf(DataExtensionCapability(DeviceBackgroundDataProductRegistry.KIND)),
      ),
  ) {
  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    Box(modifier = Modifier.background(ComposeColorSpec.resolve(color))) { content() }
  }
}

internal data class DeviceBackground(
  val color: String,
  val source: String,
  val previewExplicit: Boolean = false,
)

private fun PreviewInfoDto.background(): DeviceBackground =
  PreviewBackdrop.resolve(
      showBackground = params?.showBackground == true,
      backgroundColor = params?.backgroundColor ?: 0L,
      // The renderer paints M3's dark sheet — not white — for `showBackground` under a night
      // uiMode, so reporting white here would have named a colour the pixels contradict.
      night = PreviewBackground.isNight(params?.uiMode ?: 0),
      // A live host must put *something* behind a transparent preview the moment it is opened,
      // before any render has produced a theme capture. See `Source.M3_LIGHT_FALLBACK`.
      fallback = true,
    )
    .toDeviceBackground()!!

/**
 * Domain API for the captured theme colors that device background understands.
 *
 * Device background callers should ask this facade for the background candidate they need. The
 * current preview-context adapter understands the theme connector payload shape internally, but no
 * caller needs to know that shape or any reflective access details.
 */
internal data class DeviceBackgroundThemeCapture(private val colorScheme: Map<String, String>) {
  fun background(): DeviceBackground? =
    PreviewBackdrop.resolve(
        themeBackground = colorScheme["background"],
        themeSurface = colorScheme["surface"],
      )
      .toDeviceBackground()

  companion object {
    private const val THEME_PAYLOAD_KEY: String = "compose.material3.themePayload"

    fun from(context: PreviewContext): DeviceBackgroundThemeCapture? {
      val payload = context.inspection.values[THEME_PAYLOAD_KEY] ?: return null
      return colorSchemeFromThemePayload(payload)?.let(::DeviceBackgroundThemeCapture)
    }

    @Suppress("UNCHECKED_CAST")
    private fun colorSchemeFromThemePayload(payload: Any): Map<String, String>? {
      val resolvedTokens =
        runCatching { payload.javaClass.getMethod("getResolvedTokens").invoke(payload) }.getOrNull()
          ?: return null
      return runCatching {
        resolvedTokens.javaClass.getMethod("getColorScheme").invoke(resolvedTokens)
          as? Map<String, String>
      }
        .getOrNull()
    }
  }
}

private fun fallbackBackground(): DeviceBackground =
  PreviewBackdrop.resolve(fallback = true).toDeviceBackground()!!

/**
 * This product's view of a resolved [PreviewBackdrop.Backdrop], or null when the chain had nothing
 * to say (which this product never publishes — it always asks for the fallback).
 *
 * [DeviceBackground.previewExplicit] is derived from the source rather than passed alongside it:
 * "the preview stated this itself" is exactly "the answer came from one of the `@Preview` rungs",
 * and deriving it keeps the two from drifting apart the way two hand-set flags would.
 */
private fun PreviewBackdrop.Backdrop.toDeviceBackground(): DeviceBackground? = color?.let {
  DeviceBackground(
    color = it,
    source = source.wire,
    previewExplicit =
      source == PreviewBackdrop.Source.PREVIEW_BACKGROUND_COLOR ||
        source == PreviewBackdrop.Source.PREVIEW_SHOW_BACKGROUND,
  )
}
