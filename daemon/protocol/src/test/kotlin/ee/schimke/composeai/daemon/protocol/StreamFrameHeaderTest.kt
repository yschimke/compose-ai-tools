package ee.schimke.composeai.daemon.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the binary `composestream/1` frame header. The header is what a future
 * WebSocket data plane (or a `.cstream1` fixture file) consumes — keeping it in lockstep with the
 * JSON-tunneled `streamFrame` notifications is the regression we care about.
 */
class StreamFrameHeaderTest {

  @Test
  fun pack_then_parse_roundtrips_every_field() {
    val original =
      StreamFrameHeader(
        codec = StreamCodec.WEBP,
        seq = 12345L,
        ptsMillis = 67890L,
        widthPx = 480,
        heightPx = 800,
        keyframe = true,
        final = false,
        payloadLen = 4096,
      )
    val bytes = original.pack()
    assertEquals(StreamFrameHeader.HEADER_BYTES, bytes.size)
    val parsed = StreamFrameHeader.parse(bytes)
    assertEquals(original, parsed)
  }

  @Test
  fun unchanged_heartbeat_uses_codec_null_and_zero_payload() {
    val heartbeat =
      StreamFrameHeader(
        codec = null,
        seq = 7L,
        ptsMillis = 99L,
        widthPx = 100,
        heightPx = 200,
        keyframe = false,
        final = false,
        payloadLen = 0,
      )
    val parsed = StreamFrameHeader.parse(heartbeat.pack())
    assertNull("unchanged heartbeat must round-trip with codec=null", parsed.codec)
    assertEquals(0, parsed.payloadLen)
  }

  @Test
  fun final_flag_survives_roundtrip() {
    val finalFrame =
      StreamFrameHeader(
        codec = null,
        seq = 99L,
        ptsMillis = 100L,
        widthPx = 0,
        heightPx = 0,
        keyframe = false,
        final = true,
        payloadLen = 0,
      )
    val parsed = StreamFrameHeader.parse(finalFrame.pack())
    assertTrue(parsed.final)
    assertFalse(parsed.keyframe)
  }

  @Test
  fun encodeTo_appends_payload_after_header() {
    val payload = ByteArray(8) { (it + 1).toByte() }
    val header =
      StreamFrameHeader(
        codec = StreamCodec.PNG,
        seq = 1L,
        ptsMillis = 0L,
        widthPx = 1,
        heightPx = 1,
        keyframe = true,
        final = false,
        payloadLen = payload.size,
      )
    val combined = header.encodeTo(payload)
    assertEquals(StreamFrameHeader.HEADER_BYTES + payload.size, combined.size)
    val tail = combined.copyOfRange(StreamFrameHeader.HEADER_BYTES, combined.size)
    assertArrayEquals(payload, tail)
    val parsedHeader = StreamFrameHeader.parse(combined)
    assertEquals(StreamCodec.PNG, parsedHeader.codec)
    assertEquals(payload.size, parsedHeader.payloadLen)
  }

  @Test
  fun parse_rejects_bad_magic_byte() {
    val good =
      StreamFrameHeader(
          codec = StreamCodec.PNG,
          seq = 1L,
          ptsMillis = 0L,
          widthPx = 1,
          heightPx = 1,
          keyframe = false,
          final = false,
          payloadLen = 0,
        )
        .pack()
    good[0] = 0xAB.toByte()
    assertThrows(IllegalArgumentException::class.java) { StreamFrameHeader.parse(good) }
  }

  @Test
  fun parse_rejects_unsupported_version() {
    val good =
      StreamFrameHeader(
          codec = StreamCodec.PNG,
          seq = 1L,
          ptsMillis = 0L,
          widthPx = 1,
          heightPx = 1,
          keyframe = false,
          final = false,
          payloadLen = 0,
        )
        .pack()
    good[1] = 99
    assertThrows(IllegalArgumentException::class.java) { StreamFrameHeader.parse(good) }
  }

  @Test
  fun parse_rejects_unknown_codec_byte() {
    val good =
      StreamFrameHeader(
          codec = StreamCodec.PNG,
          seq = 1L,
          ptsMillis = 0L,
          widthPx = 1,
          heightPx = 1,
          keyframe = false,
          final = false,
          payloadLen = 0,
        )
        .pack()
    // codec byte lives at offset 2 — see StreamFrameHeader doc.
    good[2] = 0x42
    assertThrows(IllegalArgumentException::class.java) { StreamFrameHeader.parse(good) }
  }

  @Test
  fun construction_rejects_unchanged_heartbeat_with_payload() {
    assertThrows(IllegalArgumentException::class.java) {
      StreamFrameHeader(
        codec = null,
        seq = 1L,
        ptsMillis = 0L,
        widthPx = 1,
        heightPx = 1,
        keyframe = false,
        final = false,
        payloadLen = 16,
      )
    }
  }

  @Test
  fun parse_rejects_short_buffer() {
    val short = ByteArray(5)
    assertThrows(IllegalArgumentException::class.java) { StreamFrameHeader.parse(short) }
  }

  @Test
  fun encodeTo_rejects_payload_size_mismatch() {
    val header =
      StreamFrameHeader(
        codec = StreamCodec.PNG,
        seq = 1L,
        ptsMillis = 0L,
        widthPx = 1,
        heightPx = 1,
        keyframe = false,
        final = false,
        payloadLen = 4,
      )
    assertThrows(IllegalArgumentException::class.java) { header.encodeTo(ByteArray(5)) }
  }
}
