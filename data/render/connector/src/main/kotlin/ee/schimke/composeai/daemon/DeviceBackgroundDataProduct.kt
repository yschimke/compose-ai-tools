package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductFacet
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.pipeline.SamplingPolicy
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Metadata product describing the background a client should place behind a rendered device frame.
 *
 * Preview annotation settings win because they are an explicit author signal. Otherwise, a render
 * can provide captured Material3 theme colors through [PreviewContext.inspection]; when no render
 * has happened yet, the product falls back to Material3's light background color so transparent
 * component previews remain readable.
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

  override fun onRender(previewId: String, result: RenderResult) {
    result.previewContext?.let { context ->
      val current = backgrounds[previewId]
      if (current?.previewExplicit == true) return
      backgrounds[previewId] = context.material3Background() ?: current ?: fallbackBackground()
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

private data class DeviceBackground(
  val color: String,
  val source: String,
  val previewExplicit: Boolean = false,
)

private fun PreviewInfoDto.background(): DeviceBackground {
  val previewParams = params
  val backgroundColor = previewParams?.backgroundColor
  return when {
    backgroundColor != null && backgroundColor != 0L ->
      DeviceBackground(
        color = backgroundColor.hexArgb(),
        source = "preview.backgroundColor",
        previewExplicit = true,
      )
    previewParams?.showBackground == true ->
      DeviceBackground(
        color = "#FFFFFFFF",
        source = "preview.showBackground",
        previewExplicit = true,
      )
    else -> fallbackBackground()
  }
}

private fun PreviewContext.material3Background(): DeviceBackground? {
  val colorScheme = material3ColorScheme() ?: return null
  val background =
    colorScheme["background"]?.takeUnless(::isTransparentColor)
      ?: colorScheme["surface"]?.takeUnless(::isTransparentColor)
      ?: return null
  val source =
    if (colorScheme["background"]?.equals(background, ignoreCase = true) == true) {
      "material3.background"
    } else {
      "material3.surface"
    }
  return DeviceBackground(background.uppercase(), source)
}

@Suppress("UNCHECKED_CAST")
private fun PreviewContext.material3ColorScheme(): Map<String, String>? {
  val payload = inspection.values["compose.material3.themePayload"] ?: return null
  val resolvedTokens =
    runCatching { payload.javaClass.getMethod("getResolvedTokens").invoke(payload) }.getOrNull()
      ?: return null
  return runCatching {
      resolvedTokens.javaClass.getMethod("getColorScheme").invoke(resolvedTokens)
        as? Map<String, String>
    }
    .getOrNull()
}

private fun fallbackBackground(): DeviceBackground =
  DeviceBackground("#FFFFFBFE", "material3.lightBackgroundFallback")

private fun Long.hexArgb(): String = "#%08X".format(this and 0xFFFFFFFFL)

private fun isTransparentColor(color: String): Boolean =
  color.length == 9 && color.startsWith("#") && color.substring(1, 3).equals("00", true)
