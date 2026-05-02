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
import java.lang.reflect.Method
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
        colorScheme = colorTokens(colorScheme),
        typography = typographyTokens(typography),
        shapes = shapeTokens(shapes),
      ),
    consumers = emptyList(),
  )

fun themePayloadFromThemeObjects(
  colorSource: Any,
  typographySource: Any?,
  shapesSource: Any?,
  fallbackTypography: Typography?,
  fallbackShapes: Shapes?,
): ThemePayload? {
  val colorScheme = colorTokens(colorSource).takeIf { it.isNotEmpty() } ?: return null
  val typography =
    typographyTokens(typographySource).takeIf { it.isNotEmpty() }
      ?: fallbackTypography?.let(::typographyTokens)
      ?: emptyMap()
  val shapes =
    shapeTokens(shapesSource).takeIf { it.isNotEmpty() }
      ?: fallbackShapes?.let(::shapeTokens)
      ?: emptyMap()
  return ThemePayload(
    resolvedTokens =
      ResolvedThemeTokens(colorScheme = colorScheme, typography = typography, shapes = shapes),
    consumers = emptyList(),
  )
}

fun colorTokens(source: Any?): Map<String, String> =
  when (source) {
    null -> emptyMap()
    is ColorScheme ->
      linkedMapOf(
        "primary" to source.primary.hexArgb(),
        "onPrimary" to source.onPrimary.hexArgb(),
        "primaryContainer" to source.primaryContainer.hexArgb(),
        "onPrimaryContainer" to source.onPrimaryContainer.hexArgb(),
        "inversePrimary" to source.inversePrimary.hexArgb(),
        "secondary" to source.secondary.hexArgb(),
        "onSecondary" to source.onSecondary.hexArgb(),
        "secondaryContainer" to source.secondaryContainer.hexArgb(),
        "onSecondaryContainer" to source.onSecondaryContainer.hexArgb(),
        "tertiary" to source.tertiary.hexArgb(),
        "onTertiary" to source.onTertiary.hexArgb(),
        "tertiaryContainer" to source.tertiaryContainer.hexArgb(),
        "onTertiaryContainer" to source.onTertiaryContainer.hexArgb(),
        "background" to source.background.hexArgb(),
        "onBackground" to source.onBackground.hexArgb(),
        "surface" to source.surface.hexArgb(),
        "onSurface" to source.onSurface.hexArgb(),
        "surfaceVariant" to source.surfaceVariant.hexArgb(),
        "onSurfaceVariant" to source.onSurfaceVariant.hexArgb(),
        "surfaceTint" to source.surfaceTint.hexArgb(),
        "inverseSurface" to source.inverseSurface.hexArgb(),
        "inverseOnSurface" to source.inverseOnSurface.hexArgb(),
        "error" to source.error.hexArgb(),
        "onError" to source.onError.hexArgb(),
        "errorContainer" to source.errorContainer.hexArgb(),
        "onErrorContainer" to source.onErrorContainer.hexArgb(),
        "outline" to source.outline.hexArgb(),
        "outlineVariant" to source.outlineVariant.hexArgb(),
        "scrim" to source.scrim.hexArgb(),
      )
    else -> reflectedColorTokens(source)
  }

fun typographyTokens(source: Any?): Map<String, TypographyToken> =
  when (source) {
    null -> emptyMap()
    is Typography ->
      linkedMapOf(
        "displayLarge" to source.displayLarge.token(),
        "displayMedium" to source.displayMedium.token(),
        "displaySmall" to source.displaySmall.token(),
        "headlineLarge" to source.headlineLarge.token(),
        "headlineMedium" to source.headlineMedium.token(),
        "headlineSmall" to source.headlineSmall.token(),
        "titleLarge" to source.titleLarge.token(),
        "titleMedium" to source.titleMedium.token(),
        "titleSmall" to source.titleSmall.token(),
        "bodyLarge" to source.bodyLarge.token(),
        "bodyMedium" to source.bodyMedium.token(),
        "bodySmall" to source.bodySmall.token(),
        "labelLarge" to source.labelLarge.token(),
        "labelMedium" to source.labelMedium.token(),
        "labelSmall" to source.labelSmall.token(),
      )
    else ->
      linkedMapOf<String, TypographyToken>().also { tokens ->
        for (method in source.javaClass.readableNoArgMethods()) {
          val value = method.invokeOrNull(source)
          if (value is TextStyle) tokens[method.propertyName()] = value.token()
        }
      }
  }

fun shapeTokens(source: Any?): Map<String, String> =
  when (source) {
    null -> emptyMap()
    is Shapes ->
      linkedMapOf(
        "extraSmall" to source.extraSmall.toString(),
        "small" to source.small.toString(),
        "medium" to source.medium.toString(),
        "large" to source.large.toString(),
        "extraLarge" to source.extraLarge.toString(),
      )
    else ->
      linkedMapOf<String, String>().also { tokens ->
        for (method in source.javaClass.readableNoArgMethods()) {
          val value = method.invokeOrNull(source) ?: continue
          if (value.javaClass.name.contains("Shape", ignoreCase = true)) {
            tokens[method.propertyName()] = value.toString()
          }
        }
      }
  }

private fun reflectedColorTokens(source: Any): Map<String, String> =
  linkedMapOf<String, String>().also { tokens ->
    for (method in source.javaClass.readableNoArgMethods()) {
      val name = method.propertyName()
      val value = method.invokeOrNull(source)
      when (value) {
        is Color -> tokens[name] = value.hexArgb()
        is Long ->
          if (method.returnType == java.lang.Long.TYPE)
            tokens[name] = Color(value.toULong()).hexArgb()
      }
    }
  }

private fun Class<*>.readableNoArgMethods(): List<Method> = methods.filter { method ->
  method.parameterCount == 0 &&
    method.name.startsWith("get") &&
    method.name != "getClass" &&
    method.returnType != java.lang.Void.TYPE
}

private fun Method.invokeOrNull(receiver: Any): Any? =
  runCatching {
      isAccessible = true
      invoke(receiver)
    }
    .getOrNull()

private fun Method.propertyName(): String =
  name.removePrefix("get").substringBefore("-").replaceFirstChar { it.lowercase() }

fun Color.hexArgb(): String = "#%08X".format(toArgb())

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
