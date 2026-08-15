package ee.schimke.composeai.rcplayer.protocol

/**
 * What a captured Remote Compose document can be told to change **without recomposing it** — read
 * off the document's own operations rather than assumed from the catalog it came from.
 *
 * This exists because a replayed preview is redrawn from bytes, never by re-running the composable
 * that authored it, so an override whose only route to the pixels is a fresh composition is inert.
 * The alternative to reading it is a maintained allow-list of "axes replay can't honour", which is
 * wrong in both directions: too wide refuses renders that would have worked, too narrow reports
 * success while handing back the baked snapshot — a failure wearing a successful render's clothes.
 *
 * The list cannot be right in principle, because support is a property of the **document**, not of
 * the axis. Measured over the two published catalogs: of `remote-m3`'s 27 documents, 16 declare
 * colour state a theme override can move and 11 declare none — so no single answer for
 * "themeProvider" is correct for that catalog, let alone across catalogs. All 164
 * `homeassistant-remotecompose` documents declare none today.
 *
 * Everything here is derived from a single pass over [RcDocument.operations]; construct once per
 * document and query per override.
 */
public data class RcDocumentCapabilities(
  /**
   * Host-overridable named state, by name, to its [RcNamedVariable] type constant. The type matters
   * as much as the presence: float and int seeds reach the alpha player's `StateUpdater`, string
   * seeds currently do not, so a document whose only named state is string-typed is declared but
   * not yet drivable.
   */
  public val namedValues: Map<String, Int>,
  /**
   * `colorGroupId`s of the document's [RcColorTheme] operations — the *other* way a document can
   * carry colour theming. A `ColorTheme` op picks between a light and a dark colour at paint time
   * from the player's theme, where a colour-typed named variable exposes a slot the host overwrites
   * outright. A document may use either, both, or neither, so a capability check that knows only
   * about named state silently under-reports one whole mechanism.
   */
  public val colorThemeGroups: Set<Int>,
  /**
   * The theme the document pins itself to ([RcTheme.DARK] / [RcTheme.LIGHT]), or null when it
   * declares none and therefore defers to the player's.
   */
  public val declaredTheme: Int?,
) {
  /** Colour-typed entries of [namedValues] — the slots a palette override can overwrite. */
  public val colorNamedValues: Set<String>
    get() = namedValues.filterValues { it == RcNamedVariable.COLOR_TYPE }.keys

  /**
   * Whether a palette / theme-provider override can reach this document at all, by either
   * mechanism. False means a replay renders the captured colours whatever the request says.
   */
  public val supportsThemeOverride: Boolean
    get() = colorNamedValues.isNotEmpty() || colorThemeGroups.isNotEmpty()

  /**
   * Whether a light/dark (`uiMode`) request changes anything. Needs both halves: [RcColorTheme]
   * operations to select between, and no self-pinned [declaredTheme] to override that selection — a
   * document that pins `DARK` draws dark on a light request.
   *
   * Colour-typed named state deliberately does **not** count. Overwriting a named colour is a
   * palette swap the host drives; it is not the document choosing between two captured colours, and
   * a `uiMode` request carries no palette to swap in.
   */
  public val supportsUiMode: Boolean
    get() =
      colorThemeGroups.isNotEmpty() &&
        (declaredTheme == null ||
          declaredTheme == RcTheme.SYSTEM ||
          declaredTheme == RcTheme.UNSPECIFIED)

  /** The [RcNamedVariable] type declared for [name], or null when the document declares no such. */
  public fun namedValueType(name: String): Int? = namedValues[name]

  /** Whether a seed for [name] has anything in this document to land on. */
  public fun supportsNamedValue(name: String): Boolean = name in namedValues

  public companion object {
    /** Read the capability surface off an already-decoded [document]. */
    public fun of(document: RcDocument): RcDocumentCapabilities {
      val named = LinkedHashMap<String, Int>()
      val groups = LinkedHashSet<Int>()
      var theme: Int? = null
      for (op in document.operations) {
        when (op) {
          // Last declaration wins, matching the player's own apply order.
          is RcNamedVariable -> named[op.name] = op.type
          is RcColorTheme -> groups += op.colorGroupId
          is RcTheme -> theme = op.theme
          else -> Unit
        }
      }
      return RcDocumentCapabilities(named, groups, theme)
    }

    /**
     * Decode [bytes] and read its capability surface. Returns null when the bytes don't decode —
     * callers treat that as "cannot establish support", which keeps a truncated or
     * newer-than-we-parse document from being reported as supporting an override it may not.
     */
    public fun of(bytes: ByteArray): RcDocumentCapabilities? = runCatching {
      of(RcDocumentCodec.decode(bytes))
    }
      .getOrNull()
  }
}
