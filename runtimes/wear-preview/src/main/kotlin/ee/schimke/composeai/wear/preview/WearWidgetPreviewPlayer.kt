package ee.schimke.composeai.wear.preview

/**
 * Which Remote Compose player draws a [CapturingWearWidgetPreview].
 *
 * A Wear widget is **played, not composed**: the preview captures the widget's encoded
 * `RemoteDocument` and hands the bytes to a player. Which player replays them is a genuine choice —
 * they are different renderers of the same document, not skins over one engine — and it is visible
 * in two places a preview author cares about:
 * * **pixels**, because the two rasterise the document independently; and
 * * **accessibility**, because [VIEW] plays into a single Android `View` that the a11y lane can
 *   only see as one unlabelled item, while [CMP] interprets the document into Compose layout/draw
 *   nodes and labels itself from the document's own root content description.
 *
 * The second is why [CMP] is the default (issue #5259). Under [VIEW] every widget preview reported
 * the same `SpeakableTextPresentCheck` error against `RemoteComposePlayer` — one per preview,
 * identical across widgets whose content differed completely, and unfixable from the design, since
 * anything the author writes lives *inside* the played document while the check looks at the `View`
 * hosting it. Under [CMP] there is no such view: the widget composes, and a document that carries a
 * root content description labels the player through it.
 *
 * Select the other lane per build with the `composeai.render.rcPlayer` system property
 * ([PROPERTY]), which the Gradle plugin wires from `-PcomposePreview.rcPlayer=view` onto every
 * render / daemon JVM — the same knob that moves every other Remote Compose preview. That is the
 * escape hatch for a widget whose fidelity depends on the framework `Canvas` — glyph hinting is the
 * usual one — and for reproducing a pre-#5259 render.
 */
enum class WearWidgetPreviewPlayer(
  /** Stable spelling of this lane, as the system property and the Gradle property take it. */
  val wire: String
) {
  /**
   * The Compose Multiplatform player — the vendored AndroidX embedded `RcPlayer`
   * (`:third-party-rc-embedded-player`, reached through `ExperimentalRemoteDocumentPlayer`), which
   * interprets the document's operation tree into Compose layout and draw nodes. The default, and
   * the same player the rest of the pipeline defaults to: what a capture bakes through, what an
   * unqualified bundle replay uses, and what `serve`'s viewer opens on.
   *
   * Requires the embedded player on the render classpath. Where it is absent this degrades to
   * [VIEW] rather than failing the render — see `embeddedWearWidgetPlayerAvailable`.
   */
  CMP("cmp"),

  /**
   * The View-backed player: upstream `WearWidgetPreview`, which hands the document to
   * `RemoteComposePlayer` inside an `AndroidView`. What every widget preview drew before #5259, and
   * still the lane to pin when the framework `Canvas` is the point.
   */
  VIEW("view");

  companion object {
    /**
     * System property naming the player, read once per render JVM.
     *
     * Not Wear-specific: it is the **same** build-wide property every Remote Compose preview reads
     * (`RemoteComposePlayerSelection.PROPERTY` in `:data-remotecompose-connector`), so one
     * `-PcomposePreview.rcPlayer=view` moves widget previews and ordinary `RemotePreview` stickers
     * together rather than leaving a consumer to discover a second knob. This module cannot depend
     * on the connector — it is a standalone runtime with `compileOnly` alpha deps — so the name is
     * spelled twice and pinned by a test on each side.
     */
    const val PROPERTY: String = "composeai.render.rcPlayer"

    /** The lane a preview draws through when nothing selects one. */
    val DEFAULT: WearWidgetPreviewPlayer = CMP

    /**
     * The lane [raw] names, or null when it names none — blank, unset, or a value neither lane
     * answers to.
     *
     * Accepts each lane's own [wire] id, the canonical implementation names, and every historical
     * spelling the rest of the pipeline uses, so a value copied from a `?rcPlayer=` link or from
     * `RemoteComposePlayerKind` selects what it looks like it selects. Case- and
     * whitespace-insensitive.
     *
     * `androidx-embedded` is the canonical name for [CMP], and `cmp-android` / `cmp` / `embedded`
     * are historical. The old name is misleading and kept only because it is on the wire: it does
     * **not** mean "the CMP player on Android" but the vendored AndroidX embedded player
     * (`third-party-rc-embedded-player`). The genuine CMP player is `rc-player-compose`, a
     * different codebase that this lane never runs. Likewise `androidx-view` is canonical for
     * [VIEW], with `java` / `view` historical.
     *
     * Keep this set identical to `RemoteComposePlayerSelection.fromWire` in
     * `:data-remotecompose-connector` — the vocabulary is spelled twice for the dependency reason
     * in [PROPERTY], and a divergence means one property selects two different players.
     */
    fun fromWire(raw: String?): WearWidgetPreviewPlayer? =
      when (raw?.trim()?.lowercase()) {
        // Canonical first, historical after — the order is documentation, not behaviour.
        "androidx-embedded",
        "cmp",
        "cmp-android",
        "embedded" -> CMP
        "androidx-view",
        "view",
        "java" -> VIEW
        else -> null
      }

    /**
     * The lane to draw through given [raw] — [DEFAULT] when it names none.
     *
     * An unrecognised value is reported on stderr rather than silently ignored: it is nearly always
     * a typo in a `-PcomposePreview.rcPlayer=` invocation, and a silent fallback would draw the
     * default lane while the author believes they pinned the other one. It is not fatal, because a
     * preview render should not die over a player selection.
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
