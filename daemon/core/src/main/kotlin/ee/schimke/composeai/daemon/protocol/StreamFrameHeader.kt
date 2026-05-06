package ee.schimke.composeai.daemon.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary header for a `composestream/1` frame.
 *
 * The JSON-tunnelled wire today is [StreamFrameParams]; this struct is the equivalent for clients
 * that prefer a self-describing binary blob (a future WebSocket data plane, a fixture file, the
 * `recording/encode` follow-up that bakes a stream into a single `.cstream1` file). Same fields,
 * same semantics. Layout, little-endian:
 * ```
 *  off  type  field
 *   0   u8    magic     = 0xCF
 *   1   u8    version   = 1
 *   2   u8    codec     // 0=PNG, 1=WEBP, 0xFF=unchanged-heartbeat
 *   3   u8    flags     // bit0=keyframe, bit1=final
 *   4   u32   seq
 *   8   u32   ptsMillisLow      // wall-clock millis & 0xFFFFFFFF
 *  12   u16   widthPx
 *  14   u16   heightPx
 *  16   u32   payloadLen
 * (20…) payload
 * ```
 *
 * `ptsMillis` is truncated to 32 bits. Clients only ever need it to compute frame deltas; the top
 * bits are constant within a single stream's lifetime and reconstructable by adding the server's
 * start-pts back in. Keeping the header at 20 bytes lets a busy stream amortise the header cost
 * down to ~2% of payload for typical 1 KB+ WebP frames.
 */
data class StreamFrameHeader(
  val codec: StreamCodec?,
  val seq: Long,
  val ptsMillis: Long,
  val widthPx: Int,
  val heightPx: Int,
  val keyframe: Boolean,
  val final: Boolean,
  val payloadLen: Int,
) {
  init {
    require(seq in 0..0xFFFF_FFFFL) { "seq out of range: $seq" }
    require(widthPx in 0..0xFFFF) { "widthPx out of range: $widthPx" }
    require(heightPx in 0..0xFFFF) { "heightPx out of range: $heightPx" }
    require(payloadLen >= 0) { "payloadLen negative: $payloadLen" }
    if (codec == null) {
      require(payloadLen == 0) { "unchanged-heartbeat must carry payloadLen=0, got $payloadLen" }
    }
  }

  /**
   * Pack to the canonical 20-byte header. Use [encodeTo] when you want header+payload back-to-back.
   */
  fun pack(): ByteArray {
    val buf = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    buf.put(MAGIC)
    buf.put(VERSION)
    buf.put(codecByte(codec))
    buf.put(flagsByte(keyframe, final))
    buf.putInt((seq and 0xFFFF_FFFFL).toInt())
    buf.putInt((ptsMillis and 0xFFFF_FFFFL).toInt())
    buf.putShort((widthPx and 0xFFFF).toShort())
    buf.putShort((heightPx and 0xFFFF).toShort())
    buf.putInt(payloadLen)
    return buf.array()
  }

  /** Convenience: returns header bytes immediately followed by [payload]. */
  fun encodeTo(payload: ByteArray): ByteArray {
    require(payload.size == payloadLen) {
      "payload size ${payload.size} != header.payloadLen $payloadLen"
    }
    val header = pack()
    val out = ByteArray(header.size + payload.size)
    System.arraycopy(header, 0, out, 0, header.size)
    System.arraycopy(payload, 0, out, header.size, payload.size)
    return out
  }

  companion object {
    const val MAGIC: Byte = 0xCF.toByte()
    const val VERSION: Byte = 1
    const val HEADER_BYTES: Int = 20
    private const val FLAG_KEYFRAME: Int = 0x01
    private const val FLAG_FINAL: Int = 0x02
    private const val CODEC_PNG: Byte = 0
    private const val CODEC_WEBP: Byte = 1
    private const val CODEC_UNCHANGED: Byte = 0xFF.toByte()

    /**
     * Parse the canonical 20-byte header from [bytes] starting at [offset]. Throws
     * [IllegalArgumentException] when the magic byte / version / codec byte don't match — caller is
     * expected to surface as a wire-protocol error.
     */
    fun parse(bytes: ByteArray, offset: Int = 0): StreamFrameHeader {
      require(bytes.size - offset >= HEADER_BYTES) {
        "stream header needs $HEADER_BYTES bytes, only ${bytes.size - offset} available"
      }
      val buf = ByteBuffer.wrap(bytes, offset, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
      val magic = buf.get()
      require(magic == MAGIC) {
        "bad stream-frame magic: 0x${magic.toInt().and(0xFF).toString(16)}"
      }
      val version = buf.get()
      require(version == VERSION) { "unsupported stream-frame version: $version" }
      val codecRaw = buf.get()
      val flags = buf.get().toInt()
      val seq = buf.int.toLong() and 0xFFFF_FFFFL
      val pts = buf.int.toLong() and 0xFFFF_FFFFL
      val width = buf.short.toInt() and 0xFFFF
      val height = buf.short.toInt() and 0xFFFF
      val payloadLen = buf.int
      require(payloadLen >= 0) { "negative payloadLen in stream header: $payloadLen" }
      val codec =
        when (codecRaw) {
          CODEC_PNG -> StreamCodec.PNG
          CODEC_WEBP -> StreamCodec.WEBP
          CODEC_UNCHANGED -> null
          else -> throw IllegalArgumentException("unknown stream-frame codec byte: $codecRaw")
        }
      return StreamFrameHeader(
        codec = codec,
        seq = seq,
        ptsMillis = pts,
        widthPx = width,
        heightPx = height,
        keyframe = (flags and FLAG_KEYFRAME) != 0,
        final = (flags and FLAG_FINAL) != 0,
        payloadLen = payloadLen,
      )
    }

    private fun codecByte(codec: StreamCodec?): Byte =
      when (codec) {
        StreamCodec.PNG -> CODEC_PNG
        StreamCodec.WEBP -> CODEC_WEBP
        null -> CODEC_UNCHANGED
      }

    private fun flagsByte(keyframe: Boolean, final: Boolean): Byte {
      var v = 0
      if (keyframe) v = v or FLAG_KEYFRAME
      if (final) v = v or FLAG_FINAL
      return v.toByte()
    }
  }
}
