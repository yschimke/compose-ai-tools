package ee.schimke.composeai.data.theme

/**
 * Per-node resolved facts pulled from the rendered tree, fed to [ThemeConsumerAttribution] to work
 * out which theme tokens a node read.
 *
 * [nodeId] is the same identity space as `compose/semantics` (the Compose `SemanticsNode` id) so a
 * consumer can join the two products directly. [foregroundColor] / [backgroundColor] are
 * `#AARRGGBB` strings matching [ResolvedThemeTokens.colorScheme] values; [textStyle] is the node's
 * resolved typography token (or `null` for a non-text node).
 */
data class NodeThemeFacts(
  val nodeId: String,
  val foregroundColor: String? = null,
  val backgroundColor: String? = null,
  val textStyle: TypographyToken? = null,
)

/**
 * Maps each rendered node's resolved values back to the Material theme tokens it read.
 *
 * This is *resolved-value* attribution, not compiler-level instrumentation. Two signals are used:
 *
 * - **Typography** — matched on the node's full resolved text style. That carries enough distinct
 *   metrics (size, weight, line height, letter spacing) that it usually pins a single token. It is
 *   matched *ignoring* `fontFamily`, which is noisy across resolution (`null` vs the platform
 *   default) and never the distinguishing field between the M3 styles.
 * - **Colour** — matched on the scheme value. This can be ambiguous: in default M3 light several
 *   roles share a value (e.g. `#FFFFFFFF` is `onPrimary` / `onSecondary` / `onTertiary` /
 *   `onError`, and `surface` == `background`). When a node's background resolves to a known
 *   container role the foreground is disambiguated to that container's `on*` counterpart; otherwise
 *   every candidate role is emitted so the consumer can decide rather than silently guess.
 *
 * A node is only emitted as a [ThemeConsumer] when it read at least one token — nodes that hardcode
 * non-theme values produce no entry.
 */
object ThemeConsumerAttribution {
  fun attribute(nodes: List<NodeThemeFacts>, resolved: ResolvedThemeTokens): List<ThemeConsumer> {
    if (nodes.isEmpty()) return emptyList()
    val rolesByColor: Map<String, List<String>> =
      buildMap<String, MutableList<String>> {
          resolved.colorScheme.forEach { (role, value) ->
            getOrPut(value.uppercase()) { mutableListOf() }.add(role)
          }
        }
        .mapValues { (_, roles) -> roles.toList() }
    return nodes.mapNotNull { node -> node.consumerOrNull(resolved, rolesByColor) }
  }

  private fun NodeThemeFacts.consumerOrNull(
    resolved: ResolvedThemeTokens,
    rolesByColor: Map<String, List<String>>,
  ): ThemeConsumer? {
    val tokens = LinkedHashSet<String>()
    val bgRoles = backgroundColor?.let { rolesByColor[it.uppercase()] }.orEmpty()
    val fgRoles = foregroundColor?.let { rolesByColor[it.uppercase()] }.orEmpty()
    tokens += disambiguateForeground(fgRoles, bgRoles)
    tokens += bgRoles
    textStyle?.let { style -> tokens += typographyTokensFor(style, resolved.typography) }
    return if (tokens.isEmpty()) null else ThemeConsumer(nodeId = nodeId, tokens = tokens.toList())
  }

  /**
   * Collapse an ambiguous foreground candidate set to a single `on*` role when the node's
   * background pins the container. Falls back to the full candidate set when the background is
   * unknown or doesn't resolve the ambiguity.
   */
  private fun disambiguateForeground(fgRoles: List<String>, bgRoles: List<String>): List<String> {
    if (fgRoles.size <= 1) return fgRoles
    val onForContainer = bgRoles.firstNotNullOfOrNull { CONTAINER_TO_ON[it] }
    return if (onForContainer != null && onForContainer in fgRoles) listOf(onForContainer)
    else fgRoles
  }

  /**
   * Token names whose resolved style matches [style], ignoring `fontFamily`. Requires a resolved
   * `fontSize` so a node that carries an empty/placeholder style can't spuriously match a token.
   */
  private fun typographyTokensFor(
    style: TypographyToken,
    typography: Map<String, TypographyToken>,
  ): List<String> {
    if (style.fontSize == null) return emptyList()
    val target = style.copy(fontFamily = null)
    return typography.filterValues { it.copy(fontFamily = null) == target }.keys.toList()
  }

  /** Material 3 container/surface role → the `on*` role whose value is its content colour. */
  private val CONTAINER_TO_ON: Map<String, String> =
    mapOf(
      "primary" to "onPrimary",
      "primaryContainer" to "onPrimaryContainer",
      "secondary" to "onSecondary",
      "secondaryContainer" to "onSecondaryContainer",
      "tertiary" to "onTertiary",
      "tertiaryContainer" to "onTertiaryContainer",
      "background" to "onBackground",
      "surface" to "onSurface",
      "surfaceVariant" to "onSurfaceVariant",
      "error" to "onError",
      "errorContainer" to "onErrorContainer",
      "inverseSurface" to "inverseOnSurface",
    )
}
