package ee.schimke.composeai.cli

import ee.schimke.composeai.buildhost.BuildHostCodec
import ee.schimke.composeai.buildhost.BuildHostEnvelope
import ee.schimke.composeai.buildhost.BuildHostProtocol
import ee.schimke.composeai.buildhost.BuildHostRequest
import ee.schimke.composeai.buildhost.BuildHostResponse
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Drives [BuildHostCommand.serve] over string channels rather than a process.
 *
 * These cover the protocol behaviour — handshake gating, version skew, malformed input — which is
 * the part that has to be right before a server in another repository depends on it. The
 * Gradle-backed operations are exercised by the CLI's existing render tests through the same
 * [Command] members this delegates to; re-driving a real build through the pipe here would test
 * Gradle, not the adapter.
 */
class BuildHostCommandTest {

  private fun exchange(vararg requests: BuildHostEnvelope): List<BuildHostEnvelope> {
    val input = requests.joinToString("\n") { BuildHostCodec.encode(it) } + "\n"
    return exchangeRaw(input)
  }

  private fun exchangeRaw(input: String): List<BuildHostEnvelope> {
    val out = StringWriter()
    BuildHostCommand(listOf(BuildHostProtocol.STDIO_FLAG)).serve(input.reader().buffered(), out)
    return out.toString().lines().filter { it.isNotBlank() }.map(BuildHostCodec::decode)
  }

  private fun handshake(id: Long = 1) =
    BuildHostEnvelope(id, request = BuildHostRequest.Handshake())

  @Test
  fun `a matching handshake is accepted and names the host version`() {
    val replies = exchange(handshake())

    val response = assertIs<BuildHostResponse.Handshake>(replies.single().response)
    assertEquals(BuildHostProtocol.VERSION, response.protocolVersion)
    assertTrue(response.hostVersion.isNotBlank(), "host version was blank")
  }

  /**
   * The skew case the version exists for. It must be reported as a failure rather than tolerated:
   * two ends that disagree about meaning and proceed anyway produce a wrong build, not an error.
   */
  @Test
  fun `a protocol mismatch fails and names both versions`() {
    val replies =
      exchange(BuildHostEnvelope(1, request = BuildHostRequest.Handshake(protocolVersion = 9999)))

    val failure = assertIs<BuildHostResponse.Failure>(replies.single().response)
    assertContains(failure.message, "9999")
    assertContains(failure.message, BuildHostProtocol.VERSION.toString())
  }

  /** A mismatch must not leave the connection usable, or the gate bought nothing. */
  @Test
  fun `a request after a failed handshake is still refused`() {
    val replies =
      exchange(
        BuildHostEnvelope(1, request = BuildHostRequest.Handshake(protocolVersion = 9999)),
        BuildHostEnvelope(2, request = BuildHostRequest.GradleVariantArgs),
      )

    assertIs<BuildHostResponse.Failure>(replies[0].response)
    val second = assertIs<BuildHostResponse.Failure>(replies[1].response)
    assertContains(second.message, "handshake first")
  }

  @Test
  fun `an operation before any handshake is refused`() {
    val replies = exchange(BuildHostEnvelope(1, request = BuildHostRequest.GradleVariantArgs))

    val failure = assertIs<BuildHostResponse.Failure>(replies.single().response)
    assertContains(failure.message, "handshake first")
  }

  @Test
  fun `responses carry the id of the request they answer`() {
    val replies =
      exchange(
        handshake(id = 41),
        BuildHostEnvelope(42, request = BuildHostRequest.GradleVariantArgs),
      )

    assertEquals(listOf(41L, 42L), replies.map { it.id })
  }

  /**
   * One unparseable line must not kill the host. A server that spawned it would otherwise see the
   * pipe close and have to decide whether the build failed — from a line it never sent correctly.
   */
  @Test
  fun `a malformed line is answered and the host keeps serving`() {
    val replies =
      exchangeRaw(
        BuildHostCodec.encode(handshake()) +
          "\n" +
          "{ not json\n" +
          BuildHostCodec.encode(
            BuildHostEnvelope(3, request = BuildHostRequest.GradleVariantArgs)
          ) +
          "\n"
      )

    assertEquals(3, replies.size, "the host stopped serving after the bad line")
    assertIs<BuildHostResponse.Handshake>(replies[0].response)
    assertIs<BuildHostResponse.Failure>(replies[1].response)
    assertEquals(0L, replies[1].id, "a line that failed to parse has no id to answer on but 0")
    assertIs<BuildHostResponse.Strings>(replies[2].response)
  }

  /**
   * Same reasoning as above, for a message whose *kind* is unknown rather than whose JSON is bad.
   */
  @Test
  fun `an unknown message kind is answered and the host keeps serving`() {
    val replies =
      exchangeRaw(
        BuildHostCodec.encode(handshake()) +
          "\n" +
          """{"id":2,"request":{"kind":"somethingElse"}}""" +
          "\n" +
          BuildHostCodec.encode(
            BuildHostEnvelope(3, request = BuildHostRequest.GradleVariantArgs)
          ) +
          "\n"
      )

    assertEquals(3, replies.size)
    assertIs<BuildHostResponse.Failure>(replies[1].response)
    assertIs<BuildHostResponse.Strings>(replies[2].response)
  }

  @Test
  fun `an envelope carrying no request is refused rather than ignored`() {
    val replies = exchangeRaw(BuildHostCodec.encode(handshake()) + "\n" + """{"id":2}""" + "\n")

    val failure = assertIs<BuildHostResponse.Failure>(replies[1].response)
    assertContains(failure.message, "no request")
  }

  @Test
  fun `blank lines are skipped`() {
    val replies = exchangeRaw("\n\n" + BuildHostCodec.encode(handshake()) + "\n\n")

    assertIs<BuildHostResponse.Handshake>(replies.single().response)
  }

  /** Closing stdin is the shutdown signal, and v1's cancellation. It must return, not block. */
  @Test
  fun `end of input ends the loop`() {
    assertEquals(emptyList(), exchangeRaw(""))
  }

  /**
   * The project root crosses the wire absolute or not at all — the server need not share this
   * process's working directory. Null is a legitimate answer (no `gradlew` above us) and is what
   * this test sees when the suite runs outside a Gradle project; both outcomes are asserted rather
   * than one being assumed.
   */
  @Test
  fun `the project root is absolute when there is one`() {
    val replies =
      exchange(handshake(), BuildHostEnvelope(2, request = BuildHostRequest.GradleProjectRoot))

    val path = assertIs<BuildHostResponse.Path>(replies[1].response).path
    if (path != null) {
      assertTrue(java.io.File(path).isAbsolute, "project root crossed the wire relative: $path")
    }
  }
}

class LineSplittingOutputStreamTest {

  private fun capture(text: String, flush: Boolean = true): List<String> {
    val lines = mutableListOf<String>()
    val stream = LineSplittingOutputStream { lines += it }
    stream.write(text.toByteArray(Charsets.UTF_8))
    if (flush) stream.flushPartialLine()
    return lines
  }

  @Test
  fun `each terminated line is emitted once, without the terminator`() {
    assertEquals(listOf("one", "two"), capture("one\ntwo\n"))
  }

  /** Gradle output captured on Windows must not arrive with a carriage return baked into it. */
  @Test
  fun `a CRLF terminator does not leave a carriage return on the line`() {
    assertEquals(listOf("one", "two"), capture("one\r\ntwo\r\n"))
  }

  /** The last line of a build that did not end in a newline would otherwise be lost silently. */
  @Test
  fun `an unterminated trailing line is emitted on flush`() {
    assertEquals(listOf("done"), capture("done"))
  }

  @Test
  fun `an unterminated trailing line is withheld until flush`() {
    assertEquals(emptyList(), capture("partial", flush = false))
  }

  @Test
  fun `an empty line is preserved`() {
    assertEquals(listOf("one", "", "two"), capture("one\n\ntwo\n"))
  }

  @Test
  fun `flushing with nothing buffered emits nothing`() {
    assertEquals(emptyList(), capture(""))
  }

  /** Multi-byte characters must survive being written a byte at a time. */
  @Test
  fun `utf8 survives byte-wise writes`() {
    assertEquals(listOf("héllo — ✓"), capture("héllo — ✓\n"))
  }
}
