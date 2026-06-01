package ee.schimke.composeai.data.render

/**
 * Process-static hand-off for a preview's captured **intermediate representation** (IR): the Remote
 * Compose document byte stream, or the Wear protolayout `Layout` (+ `Resources`) protos.
 *
 * Mirrors `LauncherWidgetMetadataChannel`'s shape — a `ThreadLocal` "current preview id" the render
 * harness sets/clears around each composition, plus a `ConcurrentHashMap` the producer offers into
 * and the harness drains post-render. The producer side lives where the IR is built
 * (`TilePreviewComposable` for protolayout, `RemoteOverridablePreview` for Remote Compose); the
 * consumer side is the render harness (`RobolectricRenderTest` / the daemon), which writes the
 * bytes next to the rendered PNG as the `renders/<stem>.<ext>` sidecar that
 * `BundlePreviewTask.resolvePreviewIr` picks up.
 *
 * Keeping only raw `ByteArray`s here means this module needs no protolayout / alpha
 * `compose-remote` types on its classpath — the producers serialise to bytes before offering.
 * Best-effort: an offer outside a render (no current preview id) is a no-op, matching the
 * launcher-widget channel.
 */
object IrSidecarChannel {

  /**
   * [format] values — kept in lockstep with `IR_FORMAT_*` in `:gradle-plugin`'s
   * `PreviewBundleFormat`.
   */
  const val FORMAT_REMOTECOMPOSE: String = "remotecompose"

  const val FORMAT_PROTOLAYOUT: String = "protolayout"

  /**
   * One captured IR. [resourcesBytes] is non-null only for [FORMAT_PROTOLAYOUT] (the tile
   * `Resources` proto, written as the companion `.tileresources` sidecar); Remote Compose carries
   * everything in [bytes].
   */
  data class IrCapture(
    val format: String,
    val bytes: ByteArray,
    val resourcesBytes: ByteArray? = null,
  ) {
    // ByteArray needs structural equals/hashCode for data-class semantics to be meaningful.
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is IrCapture) return false
      return format == other.format &&
        bytes.contentEquals(other.bytes) &&
        (resourcesBytes?.contentEquals(other.resourcesBytes ?: ByteArray(0))
          ?: (other.resourcesBytes == null))
    }

    override fun hashCode(): Int {
      var result = format.hashCode()
      result = 31 * result + bytes.contentHashCode()
      result = 31 * result + (resourcesBytes?.contentHashCode() ?: 0)
      return result
    }
  }

  private val pending = java.util.concurrent.ConcurrentHashMap<String, IrCapture>()
  private val currentPreviewIdHolder = ThreadLocal<String?>()

  /**
   * Render-side: set the preview id for the current render thread before invoking the preview's
   * composition; clear in a `finally`. Producers read it via [offer] so they don't need an explicit
   * `previewId` parameter.
   */
  fun setCurrentPreviewId(previewId: String?) {
    if (previewId == null) currentPreviewIdHolder.remove()
    else currentPreviewIdHolder.set(previewId)
  }

  /** Current render thread's preview id, or `null` outside a render. */
  fun currentPreviewId(): String? = currentPreviewIdHolder.get()

  /**
   * Producer-side: stash the captured IR for the current render's preview. No-op when no current
   * preview id is set (running outside a daemon/test render — bare unit tests, IDE preview pane). A
   * later offer within the same render replaces the previous entry.
   */
  fun offer(format: String, bytes: ByteArray, resourcesBytes: ByteArray? = null) {
    val previewId = currentPreviewIdHolder.get() ?: return
    pending[previewId] = IrCapture(format, bytes, resourcesBytes)
  }

  /** Drain (read + remove) the IR captured for [previewId] during the just-finished render. */
  fun consume(previewId: String): IrCapture? = pending.remove(previewId)
}
