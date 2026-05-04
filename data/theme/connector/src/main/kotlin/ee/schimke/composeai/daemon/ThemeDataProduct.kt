package ee.schimke.composeai.daemon

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.tooling.CompositionData
import androidx.compose.runtime.tooling.CompositionGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.Material3ThemeOverrides
import ee.schimke.composeai.daemon.protocol.Material3TypographyOverride
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.extensions.DataExtensionCapability
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableExtension
import ee.schimke.composeai.data.render.extensions.compose.ComposableExtractorExtension
import ee.schimke.composeai.data.render.extensions.compose.ComposeColorSpec
import ee.schimke.composeai.data.render.extensions.compose.ExtensionCompositionSink
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

const val MATERIAL3_THEME_PAYLOAD_CONTEXT_KEY: String = "compose.material3.themePayload"

@Composable
fun Material3ThemeOverride(overrides: Material3ThemeOverrides?, content: @Composable () -> Unit) {
  if (overrides == null || overrides.isEmpty()) {
    content()
    return
  }
  MaterialTheme(
    colorScheme = MaterialTheme.colorScheme.withOverrides(overrides.colorScheme),
    typography = MaterialTheme.typography.withOverrides(overrides.typography),
    shapes = MaterialTheme.shapes.withOverrides(overrides.shapes),
    content = content,
  )
}

/**
 * Clean Compose-facing connector for applying Material3 theme overrides.
 *
 * Hosts should plan this extension when a request carries Material3 override tokens instead of
 * hardcoding a renderer-side `MaterialTheme` wrapper.
 */
class Material3ThemeOverrideExtension(private val overrides: Material3ThemeOverrides?) :
  AroundComposableExtension(
    id = DataExtensionId("compose/material3ThemeOverride"),
    constraints =
      DataExtensionConstraints(
        phase = DataExtensionPhase.UserEnvironment,
        provides = setOf(DataExtensionCapability("compose/material3ThemeOverride")),
      ),
  ) {
  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    Material3ThemeOverride(overrides, content)
  }
}

/**
 * Compose daemon producer for `compose/theme`.
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
    onRender(previewId, result, overrides = null, previewContext = result.previewContext)
  }

  override fun onRender(
    previewId: String,
    result: RenderResult,
    overrides: ee.schimke.composeai.daemon.protocol.PreviewOverrides?,
    previewContext: PreviewContext?,
  ) {
    previewContext
      ?.takeIf { shouldCapture(previewId, it.renderMode) }
      ?.let(::themePayloadFromPreviewContext)
      ?.let { capture(previewId, it) }
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

/**
 * Clean Compose-facing connector for `compose/theme` capture.
 *
 * This is the preferred implementation shape for theme data extensions: read public Material
 * CompositionLocals, build the product payload, and emit it through the extension sink. Slot-table
 * inspection remains only as a fallback facade for hosts that have not installed this extractor
 * yet.
 */
class ThemeCaptureExtension :
  ComposableExtractorExtension(
    id = DataExtensionId(ThemeDataProductRegistry.KIND),
    constraints =
      DataExtensionConstraints(
        phase = DataExtensionPhase.Capture,
        provides = setOf(DataExtensionCapability(ThemeDataProductRegistry.KIND)),
      ),
  ) {
  @Composable
  override fun Extract(sink: ExtensionCompositionSink) {
    val colorScheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val shapes = MaterialTheme.shapes
    SideEffect {
      sink.put(
        extensionId = id,
        key = PAYLOAD_KEY,
        value = themePayloadFromMaterialTheme(colorScheme, typography, shapes),
      )
    }
  }

  companion object {
    const val PAYLOAD_KEY: String = MATERIAL3_THEME_PAYLOAD_CONTEXT_KEY
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
  MaterialThemeTokenCapture.fromMaterialTheme(colorScheme, typography, shapes).payload()

fun themePayloadFromThemeObjects(
  colorSource: Any,
  typographySource: Any?,
  shapesSource: Any?,
  fallbackTypography: Typography?,
  fallbackShapes: Shapes?,
): ThemePayload? {
  return MaterialThemeTokenCapture.fromInspectionSources(
      colorSource = colorSource,
      typographySource = typographySource,
      shapesSource = shapesSource,
      fallbackTypography = fallbackTypography,
      fallbackShapes = fallbackShapes,
    )
    ?.payload()
}

fun themePayloadFromPreviewContext(
  context: PreviewContext,
  fallbackTypography: Typography?,
  fallbackShapes: Shapes?,
): ThemePayload? {
  MaterialThemeInspectionSnapshot.payload(
      slotTables = context.inspection.slotTables,
      fallbackTypography = fallbackTypography,
      fallbackShapes = fallbackShapes,
    )
    ?.let {
      return it
    }
  return context.inspection.values[MATERIAL3_THEME_PAYLOAD_CONTEXT_KEY] as? ThemePayload
}

fun themePayloadFromPreviewContext(context: PreviewContext): ThemePayload? =
  themePayloadFromPreviewContext(context, fallbackTypography = null, fallbackShapes = null)

/**
 * Domain API for reading Material theme tokens from either public Material3 objects or backend
 * token objects captured from Compose inspection.
 *
 * The API intentionally returns stable token maps. If a backend object needs reflective access,
 * that implementation detail stays behind this facade.
 */
internal data class MaterialThemeTokenCapture(
  val colorScheme: Map<String, String>,
  val typography: Map<String, TypographyToken>,
  val shapes: Map<String, String>,
) {
  fun payload(): ThemePayload =
    ThemePayload(
      resolvedTokens =
        ResolvedThemeTokens(colorScheme = colorScheme, typography = typography, shapes = shapes),
      consumers = emptyList(),
    )

  companion object {
    fun fromMaterialTheme(
      colorScheme: ColorScheme,
      typography: Typography,
      shapes: Shapes,
    ): MaterialThemeTokenCapture =
      MaterialThemeTokenCapture(
        colorScheme = MaterialThemeTokenReader.colorScheme(colorScheme),
        typography = MaterialThemeTokenReader.typography(typography),
        shapes = MaterialThemeTokenReader.shapes(shapes),
      )

    fun fromInspectionSources(
      colorSource: Any,
      typographySource: Any?,
      shapesSource: Any?,
      fallbackTypography: Typography?,
      fallbackShapes: Shapes?,
    ): MaterialThemeTokenCapture? {
      val colorScheme =
        MaterialThemeTokenReader.colorScheme(colorSource).takeIf { it.isNotEmpty() } ?: return null
      val typography =
        MaterialThemeTokenReader.typography(typographySource).takeIf { it.isNotEmpty() }
          ?: fallbackTypography?.let(MaterialThemeTokenReader::typography)
          ?: emptyMap()
      val shapes =
        MaterialThemeTokenReader.shapes(shapesSource).takeIf { it.isNotEmpty() }
          ?: fallbackShapes?.let(MaterialThemeTokenReader::shapes)
          ?: emptyMap()
      return MaterialThemeTokenCapture(
        colorScheme = colorScheme,
        typography = typography,
        shapes = shapes,
      )
    }

    fun hasColorScheme(source: Any?): Boolean =
      MaterialThemeTokenReader.colorScheme(source).isNotEmpty()

    fun hasTypography(source: Any?): Boolean =
      MaterialThemeTokenReader.typography(source).isNotEmpty()

    fun hasShapes(source: Any?): Boolean = MaterialThemeTokenReader.shapes(source).isNotEmpty()
  }
}

private object MaterialThemeTokenReader {
  fun colorScheme(source: Any?): Map<String, String> =
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
      else ->
        TokenObjectAccess.colorProperties(source).mapValues { (_, value) ->
          when (value) {
            is Color -> value.hexArgb()
            is Long -> Color(value.toULong()).hexArgb()
            else -> error("Unsupported color token value ${value::class.java.name}")
          }
        }
    }

  fun typography(source: Any?): Map<String, TypographyToken> =
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
        TokenObjectAccess.textStyleProperties(source).mapValues { (_, value) -> value.token() }
    }

  fun shapes(source: Any?): Map<String, String> =
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
      else -> TokenObjectAccess.shapeProperties(source).mapValues { (_, value) -> value.toString() }
    }
}

private object TokenObjectAccess {
  fun colorProperties(source: Any): Map<String, Any> =
    linkedMapOf<String, Any>().also { tokens ->
      for (method in source.javaClass.readableNoArgMethods()) {
        val name = method.propertyName()
        val value = method.invokeOrNull(source)
        when (value) {
          is Color -> tokens[name] = value
          is Long -> if (method.returnType == java.lang.Long.TYPE) tokens[name] = value
        }
      }
    }

  fun textStyleProperties(source: Any): Map<String, TextStyle> =
    linkedMapOf<String, TextStyle>().also { tokens ->
      for (method in source.javaClass.readableNoArgMethods()) {
        val value = method.invokeOrNull(source)
        if (value is TextStyle) tokens[method.propertyName()] = value
      }
    }

  fun shapeProperties(source: Any): Map<String, Any> =
    linkedMapOf<String, Any>().also { tokens ->
      for (method in source.javaClass.readableNoArgMethods()) {
        val value = method.invokeOrNull(source) ?: continue
        if (value.javaClass.name.contains("Shape", ignoreCase = true)) {
          tokens[method.propertyName()] = value
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
}

/**
 * Facade for Compose slot-table inspection used as a fallback when a composable extractor did not
 * publish [MATERIAL3_THEME_PAYLOAD_CONTEXT_KEY]. New extensions should prefer composable extractors
 * that read CompositionLocals directly.
 */
internal object MaterialThemeInspectionSnapshot {
  fun payload(
    slotTables: List<Any>,
    fallbackTypography: Typography?,
    fallbackShapes: Shapes?,
  ): ThemePayload? {
    val groups =
      slotTables
        .asSequence()
        .filterIsInstance<CompositionData>()
        .flatMap { data -> data.compositionGroups.asSequence() }
        .flatMap { group -> group.flattenGroups().asSequence() }
        .toList()
    val materialThemeCalls = groups.filter { group ->
      group.sourceInfo?.startsWith("C(MaterialTheme)") == true
    }
    val namedMaterialThemeCalls = groups.filter { group ->
      group.sourceInfo?.contains("MaterialTheme") == true &&
        group.sourceInfo?.contains("CaptureMaterialTheme") != true
    }

    payloadFromGroups(materialThemeCalls, fallbackTypography, fallbackShapes)?.let {
      return it
    }
    payloadFromGroups(namedMaterialThemeCalls, fallbackTypography, fallbackShapes)?.let {
      return it
    }
    return null
  }

  private fun payloadFromGroups(
    groups: List<CompositionGroup>,
    fallbackTypography: Typography?,
    fallbackShapes: Shapes?,
  ): ThemePayload? {
    for (group in groups.asReversed()) {
      val values = group.data.toList()
      val colorSource = values.lastOrNull(MaterialThemeTokenCapture::hasColorScheme) ?: continue
      val typographySource = values.lastOrNull(MaterialThemeTokenCapture::hasTypography)
      val shapesSource = values.lastOrNull(MaterialThemeTokenCapture::hasShapes)
      return MaterialThemeTokenCapture.fromInspectionSources(
          colorSource = colorSource,
          typographySource = typographySource,
          shapesSource = shapesSource,
          fallbackTypography = fallbackTypography,
          fallbackShapes = fallbackShapes,
        )
        ?.payload()
    }
    return null
  }

  private fun CompositionGroup.flattenGroups(): List<CompositionGroup> =
    listOf(this) + compositionGroups.flatMap { it.flattenGroups() }
}

private fun Material3ThemeOverrides.isEmpty(): Boolean =
  colorScheme.isEmpty() && typography.isEmpty() && shapes.isEmpty()

private fun ColorScheme.withOverrides(overrides: Map<String, String>): ColorScheme {
  if (overrides.isEmpty()) return this
  fun color(name: String): Color? = overrides[name]?.parseComposeColor()
  return copy(
    primary = color("primary") ?: primary,
    onPrimary = color("onPrimary") ?: onPrimary,
    primaryContainer = color("primaryContainer") ?: primaryContainer,
    onPrimaryContainer = color("onPrimaryContainer") ?: onPrimaryContainer,
    inversePrimary = color("inversePrimary") ?: inversePrimary,
    secondary = color("secondary") ?: secondary,
    onSecondary = color("onSecondary") ?: onSecondary,
    secondaryContainer = color("secondaryContainer") ?: secondaryContainer,
    onSecondaryContainer = color("onSecondaryContainer") ?: onSecondaryContainer,
    tertiary = color("tertiary") ?: tertiary,
    onTertiary = color("onTertiary") ?: onTertiary,
    tertiaryContainer = color("tertiaryContainer") ?: tertiaryContainer,
    onTertiaryContainer = color("onTertiaryContainer") ?: onTertiaryContainer,
    background = color("background") ?: background,
    onBackground = color("onBackground") ?: onBackground,
    surface = color("surface") ?: surface,
    onSurface = color("onSurface") ?: onSurface,
    surfaceVariant = color("surfaceVariant") ?: surfaceVariant,
    onSurfaceVariant = color("onSurfaceVariant") ?: onSurfaceVariant,
    surfaceTint = color("surfaceTint") ?: surfaceTint,
    inverseSurface = color("inverseSurface") ?: inverseSurface,
    inverseOnSurface = color("inverseOnSurface") ?: inverseOnSurface,
    error = color("error") ?: error,
    onError = color("onError") ?: onError,
    errorContainer = color("errorContainer") ?: errorContainer,
    onErrorContainer = color("onErrorContainer") ?: onErrorContainer,
    outline = color("outline") ?: outline,
    outlineVariant = color("outlineVariant") ?: outlineVariant,
    scrim = color("scrim") ?: scrim,
  )
}

private fun Typography.withOverrides(
  overrides: Map<String, Material3TypographyOverride>
): Typography {
  if (overrides.isEmpty()) return this
  fun TextStyle.override(name: String): TextStyle =
    overrides[name]?.let { token ->
      copy(
        fontSize = token.fontSizeSp?.sp ?: fontSize,
        lineHeight = token.lineHeightSp?.sp ?: lineHeight,
        letterSpacing = token.letterSpacingSp?.sp ?: letterSpacing,
        fontWeight = material3FontWeightOverride(token.fontWeight) ?: fontWeight,
        fontStyle =
          token.italic?.let { if (it) FontStyle.Italic else FontStyle.Normal } ?: fontStyle,
      )
    } ?: this
  return copy(
    displayLarge = displayLarge.override("displayLarge"),
    displayMedium = displayMedium.override("displayMedium"),
    displaySmall = displaySmall.override("displaySmall"),
    headlineLarge = headlineLarge.override("headlineLarge"),
    headlineMedium = headlineMedium.override("headlineMedium"),
    headlineSmall = headlineSmall.override("headlineSmall"),
    titleLarge = titleLarge.override("titleLarge"),
    titleMedium = titleMedium.override("titleMedium"),
    titleSmall = titleSmall.override("titleSmall"),
    bodyLarge = bodyLarge.override("bodyLarge"),
    bodyMedium = bodyMedium.override("bodyMedium"),
    bodySmall = bodySmall.override("bodySmall"),
    labelLarge = labelLarge.override("labelLarge"),
    labelMedium = labelMedium.override("labelMedium"),
    labelSmall = labelSmall.override("labelSmall"),
  )
}

internal fun material3FontWeightOverride(weight: Int?): FontWeight? =
  weight?.takeIf { it in 1..1000 }?.let(::FontWeight)

private fun Shapes.withOverrides(overrides: Map<String, Float>): Shapes {
  if (overrides.isEmpty()) return this
  fun rounded(name: String) = overrides[name]?.let { RoundedCornerShape(it.dp) }
  return copy(
    extraSmall = rounded("extraSmall") ?: extraSmall,
    small = rounded("small") ?: small,
    medium = rounded("medium") ?: medium,
    large = rounded("large") ?: large,
    extraLarge = rounded("extraLarge") ?: extraLarge,
  )
}

private fun String.parseComposeColor(): Color? {
  return runCatching { ComposeColorSpec.resolve(this) }.getOrNull()
}

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
