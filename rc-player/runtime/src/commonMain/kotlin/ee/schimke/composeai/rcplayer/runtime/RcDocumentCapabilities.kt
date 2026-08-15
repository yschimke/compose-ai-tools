package ee.schimke.composeai.rcplayer.runtime

import ee.schimke.composeai.rcplayer.protocol.RcColorTheme
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcNamedVariable

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
 * **Scope: named state and the palette axis.** A palette (`themeProvider`) reaches a replay only as
 * named colour seeds — `ServeThemeReplay.expand` turns the provider into `rc.<name>` values and
 * `setNamedColorOverride` applies them — so it needs colour-typed named slots to land on, and
 * nothing else will do. That is decidable from the declarations alone, and is what this class
 * answers.
 *
 * **`uiMode` (light/dark) is deliberately absent, and callers must not infer it from
 * [colorThemeGroups].** Deciding it needs the player's *execution* semantics, not just the
 * declarations, and those differ per path in ways that are easy to get wrong:
 * - in the canvas path, `drawOperations` gates a container on the section in force *before* it and
 *   then runs the container's children with `filterTheme = false`, so a marker inside a container
 *   does not filter while the container itself does;
 * - constants and data operations are preloaded unconditionally by `RcPlayerState.init` and are a
 *   no-op in `drawOperations`, so a section containing only those changes nothing observable;
 * - the layout path resolves theme through `applyScope` instead, on different rules again.
 *
 * A capability that is wrong here is worse than one that is missing: it would tell a consumer an
 * override is inert when it is not, or the reverse. It is tracked separately rather than guessed.
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
   * `colorGroupId`s of the document's [RcColorTheme] operations. Reported as **data, not as a
   * capability** — see the class note on `uiMode`.
   *
   * Deliberately not evidence of palette support: the colours are captured in the op, so there is
   * no named slot for a provider's colour to be written into, and a `themeProvider` seeded onto
   * such a document applies nothing and returns unchanged pixels.
   */
  public val colorThemeGroups: Set<Int>,
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
   * The [RcNamedVariable] type declared for [name], or null when the document declares no such.
   *
   * An unqualified name is also tried in the `USER:` domain, because that is the round trip a
   * request actually makes: `ServeOverrides` parses `rc.shaderColor` to the bare key `shaderColor`,
   * the replay applies it through `setUserLocal*`, and the player prefixes `USER:` on the way in —
   * so the captured declaration reads `USER:shaderColor`. Matching only the exact string would
   * report every ordinary `rc.` override as unsupported, which is the false refusal this class
   * exists to prevent. An explicitly namespaced name is left alone.
   */
  public fun namedValueType(name: String): Int? =
    namedValues[name] ?: namedValues[userQualified(name)]

  /** Whether a seed for [name] has anything in this document to land on. */
  public fun supportsNamedValue(name: String): Boolean = namedValueType(name) != null

  public companion object {
    /** Read the capability surface off an already-decoded [document]. */
    public fun of(document: RcDocument): RcDocumentCapabilities {
      val named = LinkedHashMap<String, Int>()
      val groups = LinkedHashSet<Int>()
      // Walks the linked tree rather than the raw stream so nested declarations are found and
      // structural delimiters never appear as operations. Declaration collection is
      // scope-independent — `RcPlayerState.init` preloads every declaration in the document
      // regardless of nesting — so no per-container state is carried.
      fun walk(nodes: List<RcLinkedNode>) {
        for (node in nodes) {
          when (node) {
            is RcLinkedNode.Container -> walk(node.children)
            is RcLinkedNode.Operation ->
              when (val op = node.operation) {
                is RcNamedVariable -> named[op.name] = op.type
                is RcColorTheme -> groups += op.colorGroupId
                else -> Unit
              }
          }
        }
      }
      walk(RcDocumentLinker.link(document).operations)
      return RcDocumentCapabilities(named, groups)
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

    /** Put a bare name in the `USER:` domain; leave an explicitly namespaced one alone. */
    private fun userQualified(name: String): String =
      if (name.contains(':')) name else "USER:" + name
  }
}
