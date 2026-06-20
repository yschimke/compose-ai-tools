package ee.schimke.composeai.clients

/**
 * One decoded server→client `frame` message: the rendered preview as encoded image bytes, its pixel
 * size, and the per-connection monotonic [seq]. The app turns [bytes] into a platform bitmap
 * (`BitmapFactory` / `ImageBitmap`) and paints it; [seq] lets the session drop a frame that arrives
 * out of order (newest wins), matching the daemon's `streamFrame` dedup contract.
 */
data class StreamFrame(
  val seq: Long,
  val codec: String,
  val widthPx: Int,
  val heightPx: Int,
  val bytes: ByteArray,
) {
  // Data class over a ByteArray: override equality so two frames with identical pixels compare
  // equal
  // (the default would compare array identity). Cheap to keep correct; used in tests + dedup.
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is StreamFrame) return false
    return seq == other.seq &&
      codec == other.codec &&
      widthPx == other.widthPx &&
      heightPx == other.heightPx &&
      bytes.contentEquals(other.bytes)
  }

  override fun hashCode(): Int {
    var result = seq.hashCode()
    result = 31 * result + codec.hashCode()
    result = 31 * result + widthPx
    result = 31 * result + heightPx
    result = 31 * result + bytes.contentHashCode()
    return result
  }
}
