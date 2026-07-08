package ee.schimke.composeai.renderer

/**
 * Composes the single "what the user hears" announcement string for an [AccessibilityNode] —
 * issue #1956, Phase 2.
 *
 * Until now the a11y data product surfaced `label` / `role` / `states` as *separate* fields and let
 * each consumer (the overlay legend, the CLI table) lay them out however it liked. Nothing
 * assembled the one continuous utterance a screen reader would actually speak. The TalkBack focus
 * overlay ([the caption card] and the exploratory TTS track both need exactly that string, so it
 * lives here as a single pure function the live overlay, the post-capture overlay, and any future
 * spoken-audio track all share — one definition, no drift between the caption you see and the audio
 * you'd hear.
 *
 * **Ordering** follows modern Android TalkBack's node announcement shape:
 * ```
 * <label>, <role>, <state…>, <usage hint…>
 * ```
 *
 * e.g. `"Buy now, button, double-tap to activate"` or `"Remember me, checkbox, not checked,
 * double-tap to toggle"`. Concretely:
 *
 * 1. **label** — the node's visible text / contentDescription, spoken first. (Emitted a11y nodes
 *    always carry a non-blank label; a blank one is tolerated and simply dropped so the utterance
 *    starts with the role.)
 * 2. **role** — TalkBack speaks the control type after the name, lower-cased (`Button` → `button`,
 *    `CheckBox` → `checkbox`). `null` roles (plain text nodes) contribute nothing — TalkBack
 *    doesn't say "text view" for every label.
 * 3. **state words** — `checked` / `not checked`, `disabled`, `edit box`, plus any verbatim
 *    `stateDescription` the extractor captured (a slider's `"70%"`, an expandable's `"Expanded"`).
 *    These map from the [AccessibilityNode.states] tokens the extractors emit
 *    ([AccessibilityChecker] on Android, the desktop node extractor on CMP).
 * 4. **usage hints** — the "how do I operate this" affix TalkBack appends from the node's actions:
 *    `double-tap to activate` (clickable), `double-tap to toggle` (a clickable that's also
 *    checkable), `double-tap and hold to long press` (long-clickable), and an explicit `hint:
 *    <text>` (the node's accessibility hint). Hints are **suppressed when the node is disabled** —
 *    TalkBack doesn't offer "double-tap to activate" on a control you can't operate.
 *
 * The function is intentionally pure and dependency-free (no Compose, no Android) so it unit-tests
 * trivially and runs anywhere the [AccessibilityNode] model does.
 */
object TalkBackUtterance {

  /**
   * Builds the spoken/caption announcement for [node]. Returns an empty string only for a node with
   * no label, role, or announceable state (which the extractors never emit, but callers may
   * hand-build).
   */
  fun compose(node: AccessibilityNode): String {
    val checkable = node.states.any { it == CHECKED || it == UNCHECKED }
    val disabled = node.states.any { it == DISABLED }

    val stateWords = mutableListOf<String>()
    val hints = mutableListOf<String>()
    var hintText: String? = null

    for (state in node.states) {
      when {
        state == CHECKED -> stateWords += "checked"
        state == UNCHECKED -> stateWords += "not checked"
        state == DISABLED -> stateWords += "disabled"
        state == EDITABLE -> stateWords += "edit box"
        state == HEADING -> stateWords += "heading"
        state == CLICKABLE ->
          hints += if (checkable) "double-tap to toggle" else "double-tap to activate"
        state == LONG_CLICKABLE -> hints += "double-tap and hold to long press"
        state == SCROLLABLE -> hints += "swipe with two fingers to scroll"
        state.startsWith(HINT_PREFIX) -> hintText = state.removePrefix(HINT_PREFIX).trim()
        // Anything else is a verbatim getStateDescription() string (a slider's "70%", a
        // collapsible's "Expanded") — TalkBack reads it as-is, so we do too.
        else -> stateWords += state.trim()
      }
    }

    val parts = mutableListOf<String>()
    node.label.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
    // Heading is announced as a role even when ATF can't surface it as a state — let an explicit
    // "Heading" role flow through the role slot too (lower-cased like any other role).
    node.role?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it.lowercase() }
    parts += stateWords
    // Usage hints only make sense on an operable control.
    if (!disabled) parts += hints
    hintText?.takeIf { it.isNotEmpty() }?.let { parts += it }

    return parts.joinToString(", ")
  }

  // State tokens as emitted by AccessibilityChecker.extractStates / the desktop node extractor.
  private const val CHECKED = "checked"
  private const val UNCHECKED = "unchecked"
  private const val DISABLED = "disabled"
  private const val CLICKABLE = "clickable"
  private const val LONG_CLICKABLE = "long-clickable"
  private const val SCROLLABLE = "scrollable"
  private const val EDITABLE = "editable"
  private const val HEADING = "heading"
  private const val HINT_PREFIX = "hint: "
}
