package ee.schimke.composeai.daemon

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Desktop v1 producer for `compose/theme`.
 *
 * It snapshots the resolved Material 3 theme tokens when a render runs in `mode=theme` (or when a
 * subscribed preview renders). Per issue #449, node-level consumer tracking needs intrusive Compose
 * runtime/compiler instrumentation; v1 keeps the schema shape and returns `consumers: []`.
 */
class ThemeDataProductRegistry : DataProductRegistry {
  private val latestPayloads = ConcurrentHashMap<String, ThemePayload>()
  private val capturedThisRender = ConcurrentHashMap.newKeySet<String>()
  private val subscribed = ConcurrentHashMap.newKeySet<String>()

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        requiresRerender = true,
      )
    )

  fun shouldCapture(previewId: String?, renderMode: String?): Boolean =
    renderMode == RENDER_MODE || (previewId != null && previewId in subscribed)

  fun capture(previewId: String?, payload: ThemePayload) {
    if (previewId == null) return
    latestPayloads[previewId] = payload
    capturedThisRender.add(previewId)
  }

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    if (kind != KIND) return DataProductRegistry.Outcome.Unknown
    val payload = latestPayloads[previewId]
    return if (payload == null) {
      DataProductRegistry.Outcome.RequiresRerender(RENDER_MODE)
    } else {
      DataProductRegistry.Outcome.Ok(
        DataFetchResult(
          kind = KIND,
          schemaVersion = SCHEMA_VERSION,
          payload = json.encodeToJsonElement(ThemePayload.serializer(), payload),
        )
      )
    }
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (KIND !in kinds) return emptyList()
    val payload = latestPayloads[previewId] ?: return emptyList()
    return listOf(
      DataProductAttachment(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        payload = json.encodeToJsonElement(ThemePayload.serializer(), payload),
      )
    )
  }

  override fun onRender(previewId: String, result: RenderResult) {
    if (!capturedThisRender.remove(previewId)) {
      latestPayloads.remove(previewId)
    }
  }

  override fun onSubscribe(previewId: String, kind: String, params: JsonElement?) {
    if (kind == KIND) subscribed.add(previewId)
  }

  override fun onUnsubscribe(previewId: String, kind: String) {
    if (kind == KIND) subscribed.remove(previewId)
  }

  companion object {
    const val KIND = "compose/theme"
    const val SCHEMA_VERSION = 1
    const val RENDER_MODE = "theme"

    private val json = Json {
      encodeDefaults = true
      prettyPrint = false
    }
  }
}

@Serializable
data class ThemePayload(
  val resolvedTokens: ResolvedThemeTokens,
  val consumers: List<ThemeConsumer> = emptyList(),
)

@Serializable
data class ResolvedThemeTokens(
  val colorScheme: Map<String, String>,
  val typography: Map<String, TypographyToken>,
  val shapes: Map<String, String>,
)

@Serializable data class ThemeConsumer(val nodeId: String, val tokens: List<String>)

@Serializable
data class TypographyToken(
  val fontFamily: String? = null,
  val fontSize: Float? = null,
  val fontSizeUnit: String? = null,
  val fontWeight: String? = null,
  val fontStyle: String? = null,
  val lineHeight: Float? = null,
  val lineHeightUnit: String? = null,
  val letterSpacing: Float? = null,
  val letterSpacingUnit: String? = null,
)

fun themePayloadFromMaterialTheme(
  colorScheme: ColorScheme,
  typography: Typography,
  shapes: Shapes,
): ThemePayload =
  ThemePayload(
    resolvedTokens =
      ResolvedThemeTokens(
        colorScheme =
          linkedMapOf(
            "primary" to colorScheme.primary.hexArgb(),
            "onPrimary" to colorScheme.onPrimary.hexArgb(),
            "primaryContainer" to colorScheme.primaryContainer.hexArgb(),
            "onPrimaryContainer" to colorScheme.onPrimaryContainer.hexArgb(),
            "inversePrimary" to colorScheme.inversePrimary.hexArgb(),
            "secondary" to colorScheme.secondary.hexArgb(),
            "onSecondary" to colorScheme.onSecondary.hexArgb(),
            "secondaryContainer" to colorScheme.secondaryContainer.hexArgb(),
            "onSecondaryContainer" to colorScheme.onSecondaryContainer.hexArgb(),
            "tertiary" to colorScheme.tertiary.hexArgb(),
            "onTertiary" to colorScheme.onTertiary.hexArgb(),
            "tertiaryContainer" to colorScheme.tertiaryContainer.hexArgb(),
            "onTertiaryContainer" to colorScheme.onTertiaryContainer.hexArgb(),
            "background" to colorScheme.background.hexArgb(),
            "onBackground" to colorScheme.onBackground.hexArgb(),
            "surface" to colorScheme.surface.hexArgb(),
            "onSurface" to colorScheme.onSurface.hexArgb(),
            "surfaceVariant" to colorScheme.surfaceVariant.hexArgb(),
            "onSurfaceVariant" to colorScheme.onSurfaceVariant.hexArgb(),
            "surfaceTint" to colorScheme.surfaceTint.hexArgb(),
            "inverseSurface" to colorScheme.inverseSurface.hexArgb(),
            "inverseOnSurface" to colorScheme.inverseOnSurface.hexArgb(),
            "error" to colorScheme.error.hexArgb(),
            "onError" to colorScheme.onError.hexArgb(),
            "errorContainer" to colorScheme.errorContainer.hexArgb(),
            "onErrorContainer" to colorScheme.onErrorContainer.hexArgb(),
            "outline" to colorScheme.outline.hexArgb(),
            "outlineVariant" to colorScheme.outlineVariant.hexArgb(),
            "scrim" to colorScheme.scrim.hexArgb(),
          ),
        typography =
          linkedMapOf(
            "displayLarge" to typography.displayLarge.token(),
            "displayMedium" to typography.displayMedium.token(),
            "displaySmall" to typography.displaySmall.token(),
            "headlineLarge" to typography.headlineLarge.token(),
            "headlineMedium" to typography.headlineMedium.token(),
            "headlineSmall" to typography.headlineSmall.token(),
            "titleLarge" to typography.titleLarge.token(),
            "titleMedium" to typography.titleMedium.token(),
            "titleSmall" to typography.titleSmall.token(),
            "bodyLarge" to typography.bodyLarge.token(),
            "bodyMedium" to typography.bodyMedium.token(),
            "bodySmall" to typography.bodySmall.token(),
            "labelLarge" to typography.labelLarge.token(),
            "labelMedium" to typography.labelMedium.token(),
            "labelSmall" to typography.labelSmall.token(),
          ),
        shapes =
          linkedMapOf(
            "extraSmall" to shapes.extraSmall.toString(),
            "small" to shapes.small.toString(),
            "medium" to shapes.medium.toString(),
            "large" to shapes.large.toString(),
            "extraLarge" to shapes.extraLarge.toString(),
          ),
      ),
    consumers = emptyList(),
  )

private fun Color.hexArgb(): String = "#%08X".format(toArgb())

private fun TextStyle.token(): TypographyToken =
  TypographyToken(
    fontFamily = fontFamily?.toString(),
    fontSize = fontSize.takeIf { it.isSpecified }?.value,
    fontSizeUnit = fontSize.takeIf { it.isSpecified }?.type?.toString(),
    fontWeight = fontWeight?.toString(),
    fontStyle = fontStyle?.toString(),
    lineHeight = lineHeight.takeIf { it.isSpecified }?.value,
    lineHeightUnit = lineHeight.takeIf { it.isSpecified }?.type?.toString(),
    letterSpacing = letterSpacing.takeIf { it.isSpecified }?.value,
    letterSpacingUnit = letterSpacing.takeIf { it.isSpecified }?.type?.toString(),
  )
