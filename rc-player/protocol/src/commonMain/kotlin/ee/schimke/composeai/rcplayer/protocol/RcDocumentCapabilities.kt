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
 * colour state a palette override can move and 11 declare none — so no single answer for
 * "themeProvider" is correct for that catalog, let alone across catalogs. All 164
 * `homeassistant-remotecompose` documents declare none today.
 *
 * **The two colour axes are not the same question**, and the reason is the route each takes to the
 * pixels rather than anything about colour:
 * - a **palette** (`themeProvider`) reaches a replay only as named colour seeds —
 *   `ServeThemeReplay.expand` turns the provider into `rc.<name>` values and
 *   `setNamedColorOverride` applies them — so it needs colour-typed named slots to land on, and
 *   nothing else will do;
 * - **light/dark** (`uiMode`) is resolved by the document against the *requested* theme, with no
 *   host-supplied colours at all.
 *
 * A document can have either without the other, so they are reported separately. Everything here is
 * derived from a single pass over [RcDocument.operations]; construct once per document and query
 * per override.
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
   * `colorGroupId`s of the document's [RcColorTheme] operations — one of the two ways a document
   * answers a light/dark request. A `ColorTheme` op holds both colours and picks between them from
   * the requested paint theme.
   *
   * Deliberately **not** evidence of palette support. The colours are captured in the op; there is
   * no named slot for a provider's colour to be written into, so a `themeProvider` seeded onto such
   * a document applies nothing and returns unchanged pixels.
   */
  public val colorThemeGroups: Set<Int>,
  /**
   * Specific [RcTheme] values (`LIGHT` / `DARK`) that gate at least one following operation — the
   * other way a document answers a light/dark request.
   *
   * `Theme` is a **section marker, not a document-wide pin**: the player carries a running
   * `currentTheme` and skips subsequent operations whose section disagrees with the requested theme
   * (`isThemeVisible`: visible when either side is `UNSPECIFIED`, or the two match). So a document
   * that brackets a light run and a dark run responds to `uiMode` with no [RcColorTheme] anywhere,
   * and reading only the last marker as a document-wide pin misclassifies exactly the documents
   * that do respond.
   *
   * `UNSPECIFIED` / `SYSTEM` markers are excluded: an `UNSPECIFIED` section is visible under every
   * request, so it gates nothing.
   */
  public val themeGatedSections: Set<Int>,
) {
  /** Colour-typed entries of [namedValues] — the slots a palette override can overwrite. */
  public val colorNamedValues: Set<String>
    get() = namedValues.filterValues { it == RcNamedVariable.COLOR_TYPE }.keys

  /**
   * Whether a `themeProvider` palette can reach this document, i.e. whether it declares colour
   * slots for the provider's colours to be seeded into. False means a replay renders the captured
   * colours whatever palette the request names.
   */
  public val supportsThemeProvider: Boolean
    get() = colorNamedValues.isNotEmpty()

  /**
   * Whether a light/dark (`uiMode`) request changes anything — by either mechanism: theme-gated
   * operation sections, or [RcColorTheme] ops selecting between captured colours.
   *
   * Palette slots deliberately do not count. Overwriting a named colour is a swap the host drives;
   * a `uiMode` request carries no colours to swap in.
   */
  public val supportsUiMode: Boolean
    get() = themeGatedSections.isNotEmpty() || colorThemeGroups.isNotEmpty()

  /** The [RcNamedVariable] type declared for [name], or null when the document declares no such. */
  public fun namedValueType(name: String): Int? = namedValues[name]

  /** Whether a seed for [name] has anything in this document to land on. */
  public fun supportsNamedValue(name: String): Boolean = name in namedValues

  public companion object {
    /** Read the capability surface off an already-decoded [document]. */
    public fun of(document: RcDocument): RcDocumentCapabilities {
      val named = LinkedHashMap<String, Int>()
      val groups = LinkedHashSet<Int>()
      val gated = LinkedHashSet<Int>()
      // The player's own running section theme. Starts unset: operations before any marker are
      // visible under every request, exactly as an `UNSPECIFIED` section is.
      var section: Int? = null
      for (op in document.operations) {
        if (op is RcTheme) {
          section = op.theme
          continue
        }
        when (op) {
          is RcNamedVariable -> named[op.name] = op.type
          is RcColorTheme -> groups += op.colorGroupId
          else -> Unit
        }
        // Any operation that isn't a marker is content the current section gates — which is what
        // makes a trailing marker with nothing after it correctly report no gating.
        section?.takeIf { it.gatesContent() }?.let { gated += it }
      }
      return RcDocumentCapabilities(named, groups, gated)
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

    /** A section only filters anything when it names a specific theme. */
    private fun Int.gatesContent(): Boolean = this != RcTheme.UNSPECIFIED && this != RcTheme.SYSTEM
  }
}
