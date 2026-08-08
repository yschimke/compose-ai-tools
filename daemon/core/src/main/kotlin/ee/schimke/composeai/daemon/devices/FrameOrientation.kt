package ee.schimke.composeai.daemon.devices

import ee.schimke.composeai.daemon.protocol.Orientation

/**
 * The one place a `portrait` / `landscape` request turns into a `width ↔ height` swap.
 *
 * A device frame carries its **natural** orientation in the catalog geometry — `id:pixel_tablet` is
 * 1280×800 dp, i.e. landscape, and `id:pixel_9` is 411×923, i.e. portrait. `orientation` asks for
 * the *other* one, exactly as rotating the device in Android Studio's preview does: the frame keeps
 * its dp, the axes trade places. Without this the request only ever reached the resource qualifier
 * (`port` / `land`), so a portrait Pixel Tablet rendered a landscape 2560×1600 bitmap that then
 * claimed `port` in its Configuration — a frame contradicting its own qualifiers (#3547).
 *
 * Four call sites make this decision — both `PreviewManifestRouter`s, `JsonRpcServer`'s `renderNow`
 * payload encoder, and `mergePreviewOverrides` for the live-session lane — and they each reach it
 * with a different spelling of the same request (payload token, protocol enum). They share this
 * object so a rotation can't be honoured on one lane and silently dropped on another, which is
 * precisely how the device lane lost it.
 *
 * **Idempotent by construction.** [orientedPx] swaps only when the request *contradicts* the
 * current aspect ratio, so applying it twice — or applying it to a frame that already satisfies the
 * request — is a no-op. That property is what makes it safe to call at every layer without tracking
 * whether an earlier layer already rotated the frame. A square frame is never swapped (both
 * orientations are already satisfied), and a null / unrecognised request never swaps.
 *
 * **Precedence.** This is a transform on the *device- or manifest-derived* frame only. An explicit
 * `widthPx` / `heightPx` override is a caller stating the exact pixels it wants, and per
 * PROTOCOL.md § 5 it outranks every derived value — so callers must not run the swap when either
 * axis was given explicitly. [orientedPx] can't see that context; the guard belongs at the call
 * site, next to the override it is guarding.
 */
object FrameOrientation {

  /**
   * [widthPx] and [heightPx] rotated to satisfy [orientation], or unchanged when they already do.
   *
   * Pixels rather than dp because every caller has already resolved density by this point, and a
   * swap of the two is density-independent anyway.
   */
  fun orientedPx(widthPx: Int, heightPx: Int, orientation: Orientation?): Pair<Int, Int> =
    if (shouldSwap(widthPx, heightPx, orientation)) heightPx to widthPx else widthPx to heightPx

  /**
   * [orientedPx] for the payload lane, where the request arrives as the raw `orientation=` token
   * (`portrait` / `landscape`, case-insensitive). An unrecognised token is treated as absent — the
   * payload parsers already tolerate unknown values rather than failing a render over one.
   */
  fun orientedPx(widthPx: Int, heightPx: Int, orientation: String?): Pair<Int, Int> =
    orientedPx(widthPx, heightPx, parse(orientation))

  /** The [Orientation] a payload token names, or null when absent, blank, or unrecognised. */
  fun parse(token: String?): Orientation? =
    when (token?.trim()?.lowercase()) {
      "portrait" -> Orientation.PORTRAIT
      "landscape" -> Orientation.LANDSCAPE
      else -> null
    }

  private fun shouldSwap(widthPx: Int, heightPx: Int, orientation: Orientation?): Boolean =
    when (orientation) {
      Orientation.PORTRAIT -> widthPx > heightPx
      Orientation.LANDSCAPE -> heightPx > widthPx
      null -> false
    }
}
