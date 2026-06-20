package ee.schimke.composeai.cli

/**
 * JVM mirror of `:data-a11y-core`'s pure TalkBack helpers (issue #1956), operating on the
 * wire-format [AccessibilityNode] in this module. The desktop daemon is plain JVM and deliberately
 * doesn't depend on the Android `:data-a11y-core`, so — exactly like [AccessibilityNode] mirrors
 * `ee.schimke.composeai.renderer.AccessibilityNode` field-for-field — these mirror
 * `TalkBackUtterance` / `TalkBackTraversal` / `TalkBackOverlayFrames`. The two copies are kept in
 * lock-step by identical unit-test suites pinning the same expected strings / boundaries on both
 * sides. See the a11y-core originals for the full rationale.
 */
object TalkBackUtterance {

  /** The single "what TalkBack speaks" string for [node]: `label, role, state…, usage-hint`. */
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
        else -> stateWords += state.trim()
      }
    }

    val parts = mutableListOf<String>()
    node.label.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
    node.role?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it.lowercase() }
    parts += stateWords
    if (!disabled) parts += hints
    hintText?.takeIf { it.isNotEmpty() }?.let { parts += it }

    return parts.joinToString(", ")
  }

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

/** JVM mirror of `:data-a11y-core`'s `TalkBackTraversal` (focus-stop walk). */
object TalkBackTraversal {

  /** The focus stops in TalkBack traversal order — the merged nodes, in extraction order. */
  fun focusStops(nodes: List<AccessibilityNode>): List<AccessibilityNode> = nodes.filter {
    it.merged
  }

  fun next(nodes: List<AccessibilityNode>, currentRef: String?): AccessibilityNode? {
    val stops = focusStops(nodes)
    if (stops.isEmpty()) return null
    val idx = indexOfRef(stops, currentRef)
    if (idx < 0) return stops.first()
    return stops.getOrNull(idx + 1)
  }

  fun previous(nodes: List<AccessibilityNode>, currentRef: String?): AccessibilityNode? {
    val stops = focusStops(nodes)
    if (stops.isEmpty()) return null
    val idx = indexOfRef(stops, currentRef)
    if (idx < 0) return stops.last()
    return stops.getOrNull(idx - 1)
  }

  private fun indexOfRef(stops: List<AccessibilityNode>, ref: String?): Int {
    if (ref == null) return -1
    return stops.indexOfFirst { it.ref == ref }
  }
}

/** JVM mirror of `:data-a11y-core`'s `TalkBackOverlayFrames` (frame index → focus stop). */
object TalkBackOverlayFrames {

  const val DEFAULT_DWELL_MS: Long = 900L

  fun focusedStopForFrame(
    frameIndex: Int,
    fps: Int,
    stopCount: Int,
    dwellMs: Long = DEFAULT_DWELL_MS,
  ): Int {
    if (stopCount <= 0) return -1
    if (fps <= 0 || dwellMs <= 0L) return 0
    val tMs = frameIndex.toLong() * 1000L / fps.toLong()
    val stop = (tMs / dwellMs).toInt()
    return stop.coerceIn(0, stopCount - 1)
  }

  fun totalFrames(fps: Int, stopCount: Int, dwellMs: Long = DEFAULT_DWELL_MS): Int {
    if (stopCount <= 0 || fps <= 0 || dwellMs <= 0L) return 0
    val durationMs = stopCount.toLong() * dwellMs
    return (durationMs * fps / 1000L).toInt() + 1
  }
}
