package ee.schimke.composeai.wear.preview

/**
 * Which Remote Compose player replays a [CapturingWearWidgetPreview]'s document. [ANDROIDX_VIEW] is
 * one unlabelled `View` to the a11y lane; [ANDROIDX_EMBEDDED] composes the document and labels
 * itself from its root content description, hence the default. Select with [PROPERTY]
 * (`-PcomposePreview.rcPlayer=androidx-view`).
 */
enum class WearWidgetPreviewPlayer(
  /** Canonical spelling of this lane: the implementation that draws. */
  val wire: String
) {
  /**
   * The vendored AndroidX embedded `RcPlayer`, interpreting the document into Compose nodes. Falls
   * back to [ANDROIDX_VIEW] when absent from the classpath (`embeddedWearWidgetPlayerAvailable`).
   */
  ANDROIDX_EMBEDDED("androidx-embedded"),

  /** Upstream `WearWidgetPreview`: `RemoteComposePlayer` inside an `AndroidView`. */
  ANDROIDX_VIEW("androidx-view");

  companion object {
    /**
     * System property naming the player: the same build-wide property every Remote Compose preview
     * reads (`RemoteComposePlayerSelection.PROPERTY`). Spelled twice because this runtime can't
     * depend on the connector; a test pins each side.
     */
    const val PROPERTY: String = "composeai.render.rcPlayer"

    /** The lane a preview draws through when nothing selects one. */
    val DEFAULT: WearWidgetPreviewPlayer = ANDROIDX_EMBEDDED

    /**
     * The lane [raw] names (case- and whitespace-insensitive), or null. `cmp-android` names a
     * different, replay-only player and is not accepted. Keep identical to
     * `RemoteComposePlayerSelection.fromWire`.
     */
    fun fromWire(raw: String?): WearWidgetPreviewPlayer? =
      when (raw?.trim()?.lowercase()) {
        "androidx-embedded",
        "embedded" -> ANDROIDX_EMBEDDED
        "androidx-view",
        "java",
        "view" -> ANDROIDX_VIEW
        else -> null
      }

    /**
     * The lane [raw] names, else [DEFAULT]. An unrecognised value (usually a typo) is reported on
     * stderr, not fatal.
     */
    fun resolve(raw: String?): WearWidgetPreviewPlayer {
      val selected = fromWire(raw)
      if (selected == null && !raw.isNullOrBlank()) {
        System.err.println(
          "CapturingWearWidgetPreview: -D$PROPERTY=$raw names no player; " +
            "drawing with ${DEFAULT.wire}. Valid values: " +
            entries.joinToString(", ") { it.wire } +
            "."
        )
      }
      return selected ?: DEFAULT
    }
  }
}
