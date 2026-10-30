package ee.schimke.composeai.cli.device

import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceLogcatTest {

  private val entry = LogcatEntry("10-30 21:40:05.123", 'D', "Sample", "hello", 4242)

  @Test
  fun `no options follows the whole log in threadtime`() {
    val options = LogcatOptions.parse(emptyList())
    assertEquals(LogcatOptions.Op.FOLLOW, options.op)
    assertNull(options.tail)
    assertEquals("10-30 21:40:05.123  4242  4242 D Sample  : hello\n", options.print(entry))
  }

  @Test
  fun `dump, tail and clear`() {
    assertEquals(LogcatOptions.Op.DUMP, LogcatOptions.parse(listOf("-d")).op)
    val tail = LogcatOptions.parse(listOf("-t", "5"))
    assertEquals(LogcatOptions.Op.DUMP, tail.op)
    assertEquals(5, tail.tail)
    // -T prints the last N, then follows.
    val follow = LogcatOptions.parse(listOf("-T", "20"))
    assertEquals(LogcatOptions.Op.FOLLOW, follow.op)
    assertEquals(20, follow.tail)
    assertEquals(LogcatOptions.Op.CLEAR, LogcatOptions.parse(listOf("-c")).op)
  }

  @Test
  fun `android studio's long epoch format`() {
    // What Studio's Logcat window runs on an API 35 device: `logcat -v long -v epoch -T N`.
    val options = LogcatOptions.parse(listOf("-v", "long", "-v", "epoch", "-T", "100"))
    val stamp = LogcatOptions.parseStamp(entry.time)!!
    val epoch = stamp.atZone(ZoneId.systemDefault()).toInstant().epochSecond
    assertEquals("[ $epoch.123  4242: 4242 D/Sample   ]\nhello\n\n", options.print(entry))
  }

  @Test
  fun `long without epoch keeps the month and day`() {
    val options = LogcatOptions.parse(listOf("-v", "long"))
    assertEquals("[ 10-30 21:40:05.123  4242: 4242 D/Sample   ]\nhello\n\n", options.print(entry))
  }

  @Test
  fun `a multi-line message repeats the prefix outside long`() {
    val trace = entry.copy(message = "boom\n\tat A.b(A.kt:1)")
    assertEquals(
      "D/Sample  ( 4242): boom\nD/Sample  ( 4242): \tat A.b(A.kt:1)\n",
      LogcatOptions.parse(listOf("-v", "brief")).print(trace),
    )
  }

  @Test
  fun `combined and comma-separated formats`() {
    assertEquals("tag", LogcatOptions.parse(listOf("-vtag")).format)
    val options = LogcatOptions.parse(listOf("-v", "time,color"))
    assertEquals("time", options.format)
    assertFalse(options.epoch)
    assertFailsWith<IllegalArgumentException> { LogcatOptions.parse(listOf("-v", "binary")) }
  }

  @Test
  fun `filterspecs and silent`() {
    val options = LogcatOptions.parse(listOf("-s", "Sample:I"))
    assertFalse(options.matches(entry))
    assertTrue(options.matches(entry.copy(level = 'W')))
    assertFalse(options.matches(entry.copy(tag = "Other", level = 'E')))
    val everything = LogcatOptions.parse(listOf("*:E", "Sample:V"))
    assertTrue(everything.matches(entry))
    assertFalse(everything.matches(entry.copy(tag = "Other")))
  }

  @Test
  fun `a regex filters messages`() {
    val options = LogcatOptions.parse(listOf("-e", "hel+o"))
    assertTrue(options.matches(entry))
    assertFalse(options.matches(entry.copy(message = "bye")))
  }

  @Test
  fun `buffer and process options are accepted and ignored`() {
    val options = LogcatOptions.parse(listOf("-b", "main", "--pid=12", "--uid", "10001", "-d"))
    assertEquals(LogcatOptions.Op.DUMP, options.op)
    assertFailsWith<IllegalArgumentException> { LogcatOptions.parse(listOf("-f", "/sdcard/log")) }
  }

  @Test
  fun `a stamp without a year is this year unless that would be the future`() {
    val now = LocalDateTime.of(2027, 1, 1, 0, 5)
    assertEquals(2026, LogcatOptions.parseStamp("12-31 23:59:00.000", now)!!.year)
    assertEquals(2027, LogcatOptions.parseStamp("01-01 00:04:00.000", now)!!.year)
  }

  @Test
  fun `the hub takes log events only while something follows`() {
    val hub = LogcatHub()
    val line =
      """{"event":"log","time":"10-30 21:40:05.123","level":"I","tag":"T","message":"m","pid":7}"""
    assertFalse(hub.offer(line))
    val got = mutableListOf<LogcatEntry>()
    var armed = 0
    var disarmed = 0
    val subscription = hub.subscribe({ got += it }, { armed++ }, { disarmed++ })
    hub.subscribe({}, { armed++ }, { disarmed++ }).close()
    assertEquals(1, armed)
    assertEquals(0, disarmed)
    assertTrue(hub.offer(line))
    assertFalse(hub.offer("""{"event":"activity","state":"resumed"}"""))
    assertEquals(listOf(LogcatEntry("10-30 21:40:05.123", 'I', "T", "m", 7)), got)
    // The last follower leaving stops the simulator's follower, and its events go on to the client.
    subscription.close()
    assertEquals(1, disarmed)
    assertFalse(hub.offer(line))
  }

  @Test
  fun `proto records are a little-endian length then a LogcatEntryProto`() {
    val options = LogcatOptions.parse(listOf("--proto", "-T", "5"))
    assertTrue(options.proto)
    val bytes = options.bytes(entry, "com.example.app")
    val size = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
    assertEquals(bytes.size - 8L, size)
    val fields = mutableMapOf<Int, Any>()
    val input = CodedInputStream.newInstance(bytes, 8, bytes.size - 8)
    while (true) {
      val tag = input.readTag()
      if (tag == 0) break
      val field = WireFormat.getTagFieldNumber(tag)
      fields[field] =
        if (WireFormat.getTagWireType(tag) == WireFormat.WIRETYPE_LENGTH_DELIMITED)
          input.readBytes().toStringUtf8()
        else input.readInt64()
    }
    val stamp = LogcatOptions.parseStamp(entry.time)!!.atZone(ZoneId.systemDefault()).toInstant()
    assertEquals(stamp.epochSecond, fields[1])
    assertEquals(123_000_000L, fields[2])
    assertEquals(3L, fields[3]) // DEBUG
    assertEquals(4242L, fields[5])
    assertEquals("Sample", fields[7])
    assertEquals("hello", fields[8])
    assertEquals("com.example.app", fields[9])
  }
}
