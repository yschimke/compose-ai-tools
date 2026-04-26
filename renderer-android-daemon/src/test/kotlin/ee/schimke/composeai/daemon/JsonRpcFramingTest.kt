package ee.schimke.composeai.daemon

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [ContentLengthFramer] — covers the framing rules of
 * PROTOCOL.md § 1 (LSP-style `Content-Length` headers).
 *
 * Specifically asserts:
 * 1. A single well-formed message round-trips.
 * 2. Multiple back-to-back messages are read in order.
 * 3. A `Content-Type` header is accepted and ignored.
 * 4. Missing `Content-Length` is a [FramingException], not a silent skip.
 * 5. Non-integer `Content-Length` is a [FramingException].
 * 6. Bare `\n` (no `\r`) line endings are accepted — the spec mandates
 *    `\r\n`, but the canonical JSON-RPC LSP framer is lenient and we follow
 *    suit so a hand-typed test fixture doesn't need carriage returns.
 * 7. Clean EOF (no bytes at all) returns null.
 * 8. Mid-payload EOF is a [FramingException], not a silent truncation.
 */
class JsonRpcFramingTest {

  @Test
  fun reads_single_well_formed_frame() {
    val payload = """{"jsonrpc":"2.0","id":1,"method":"initialize"}"""
    val bytes = "Content-Length: ${payload.length}\r\n\r\n$payload".toByteArray(Charsets.UTF_8)
    val framer = ContentLengthFramer(ByteArrayInputStream(bytes))
    assertArrayEquals(payload.toByteArray(Charsets.UTF_8), framer.readFrame())
  }

  @Test
  fun reads_two_back_to_back_frames() {
    val a = """{"a":1}"""
    val b = """{"b":2}"""
    val bytes =
      ("Content-Length: ${a.length}\r\n\r\n$a" +
          "Content-Length: ${b.length}\r\n\r\n$b")
        .toByteArray(Charsets.UTF_8)
    val framer = ContentLengthFramer(ByteArrayInputStream(bytes))
    assertEquals(a, framer.readFrame()!!.toString(Charsets.UTF_8))
    assertEquals(b, framer.readFrame()!!.toString(Charsets.UTF_8))
    assertNull(framer.readFrame())
  }

  @Test
  fun ignores_content_type_header() {
    val payload = """{"x":1}"""
    val bytes =
      ("Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n" +
          "Content-Length: ${payload.length}\r\n\r\n$payload")
        .toByteArray(Charsets.UTF_8)
    val framer = ContentLengthFramer(ByteArrayInputStream(bytes))
    assertEquals(payload, framer.readFrame()!!.toString(Charsets.UTF_8))
  }

  @Test
  fun missing_content_length_throws() {
    val bytes = "Content-Type: x\r\n\r\n{}".toByteArray(Charsets.UTF_8)
    val framer = ContentLengthFramer(ByteArrayInputStream(bytes))
    try {
      framer.readFrame()
      fail("expected FramingException")
    } catch (e: FramingException) {
      assertEquals("missing Content-Length header", e.message)
    }
  }

  @Test
  fun non_integer_content_length_throws() {
    val bytes = "Content-Length: abc\r\n\r\n{}".toByteArray(Charsets.UTF_8)
    val framer = ContentLengthFramer(ByteArrayInputStream(bytes))
    try {
      framer.readFrame()
      fail("expected FramingException")
    } catch (e: FramingException) {
      assertEquals("non-integer Content-Length: 'abc'", e.message)
    }
  }

  @Test
  fun accepts_bare_lf_line_endings() {
    val payload = """{"x":1}"""
    val bytes = "Content-Length: ${payload.length}\n\n$payload".toByteArray(Charsets.UTF_8)
    val framer = ContentLengthFramer(ByteArrayInputStream(bytes))
    assertEquals(payload, framer.readFrame()!!.toString(Charsets.UTF_8))
  }

  @Test
  fun clean_eof_returns_null() {
    val framer = ContentLengthFramer(ByteArrayInputStream(ByteArray(0)))
    assertNull(framer.readFrame())
  }

  @Test
  fun mid_payload_eof_throws() {
    val bytes = "Content-Length: 100\r\n\r\n{}".toByteArray(Charsets.UTF_8)
    val framer = ContentLengthFramer(ByteArrayInputStream(bytes))
    try {
      framer.readFrame()
      fail("expected FramingException")
    } catch (e: FramingException) {
      assertEquals("EOF mid-payload after 2/100 bytes", e.message)
    }
  }
}
