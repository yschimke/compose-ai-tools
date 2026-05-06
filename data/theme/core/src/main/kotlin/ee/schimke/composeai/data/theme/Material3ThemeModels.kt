package ee.schimke.composeai.data.theme

import kotlinx.serialization.Serializable

/**
 * Stable identity of the `compose/theme` data product. Lifted out of `ThemeDataProductRegistry` so
 * MCP clients and other connectors can depend on the payload schema without pulling in the
 * daemon-side registry or Compose runtime.
 */
object Material3ThemeProduct {
  const val KIND: String = "compose/theme"
  const val SCHEMA_VERSION: Int = 1
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
