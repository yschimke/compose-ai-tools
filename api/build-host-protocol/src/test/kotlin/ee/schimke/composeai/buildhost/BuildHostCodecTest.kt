package ee.schimke.composeai.buildhost

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException

class BuildHostCodecTest {

  private fun roundTrip(envelope: BuildHostEnvelope): BuildHostEnvelope =
    BuildHostCodec.decode(BuildHostCodec.encode(envelope))

  @Test
  fun `every request survives a round trip`() {
    val requests =
      listOf(
        BuildHostRequest.Handshake(),
        BuildHostRequest.AutoInjectInitScriptArgs("/w/app"),
        BuildHostRequest.GradleProjectRoot,
        BuildHostRequest.GradleVariantArgs,
        BuildHostRequest.GradleBuildArgs(listOf("--offline")),
        BuildHostRequest.GradleProjects,
        BuildHostRequest.RunGradleTasks(listOf(":app:x"), listOf("-q"), silenceStdout = true),
        BuildHostRequest.DiscoverAndBuild(silenceStdout = true),
      )
    requests.forEachIndexed { index, request ->
      val envelope = BuildHostEnvelope(id = index.toLong(), request = request)
      assertEquals(envelope, roundTrip(envelope), "request $request did not survive")
    }
  }

  @Test
  fun `every response survives a round trip`() {
    val responses =
      listOf(
        BuildHostResponse.Handshake(BuildHostProtocol.VERSION, "1.77.0"),
        BuildHostResponse.Strings(listOf("--init-script", "/tmp/x.gradle")),
        BuildHostResponse.Path("/w"),
        BuildHostResponse.Path(null),
        BuildHostResponse.Modules(listOf(WireModule("app", "/w/app"))),
        BuildHostResponse.BuildResult(buildOk = true),
        BuildHostResponse.Discovery(buildOk = false, manifests = emptyList()),
        BuildHostResponse.Failure("nope"),
      )
    responses.forEachIndexed { index, response ->
      val envelope = BuildHostEnvelope(id = index.toLong(), response = response)
      assertEquals(envelope, roundTrip(envelope), "response $response did not survive")
    }
  }

  @Test
  fun `an event survives a round trip`() {
    val envelope = BuildHostEnvelope(id = 7, event = BuildHostEvent.Log("> Task :app:compile"))
    assertEquals(envelope, roundTrip(envelope))
  }

  /**
   * The framing is one message per line, so a message that contains a newline would be read as two.
   * Build output is the realistic source of one, which is why the payload here is a log line.
   */
  @Test
  fun `an encoded message never contains a newline`() {
    val encoded =
      BuildHostCodec.encode(
        BuildHostEnvelope(id = 1, event = BuildHostEvent.Log("first\nsecond\r\nthird"))
      )
    assertFalse(encoded.contains('\n'), "encoded form spans lines: $encoded")
    assertFalse(encoded.contains('\r'), "encoded form carries a bare CR: $encoded")
  }

  /** And survives it, rather than merely escaping it on the way out. */
  @Test
  fun `a log line containing newlines round trips intact`() {
    val line = "first\nsecond"
    val envelope = BuildHostEnvelope(id = 1, event = BuildHostEvent.Log(line))
    assertEquals(line, (roundTrip(envelope).event as BuildHostEvent.Log).line)
  }

  /**
   * Additive fields within a protocol version must not break the older end — otherwise every new
   * field is a version bump and nobody adds one. The version exists for changes of *meaning*.
   */
  @Test
  fun `an unknown field is ignored`() {
    val decoded =
      BuildHostCodec.decode(
        """{"id":3,"request":{"kind":"gradleBuildArgs","extra":["-q"],"whenAdded":"later"}}"""
      )
    assertEquals(BuildHostRequest.GradleBuildArgs(listOf("-q")), decoded.request)
  }

  /**
   * An unknown *message kind* must not be ignored. This is the case a string `op` field plus a
   * `when` would turn into a silent default at exactly the moment the two sides have skewed.
   */
  @Test
  fun `an unknown message kind fails loudly`() {
    assertFailsWith<SerializationException> {
      BuildHostCodec.decode("""{"id":1,"request":{"kind":"rmMinusRf"}}""")
    }
  }

  @Test
  fun `a malformed line fails rather than decoding to something`() {
    assertFailsWith<SerializationException> { BuildHostCodec.decode("not json") }
  }

  /**
   * Three of the four envelope slots are empty in every message; writing them as nulls is noise.
   */
  @Test
  fun `empty envelope slots are omitted`() {
    val encoded =
      BuildHostCodec.encode(BuildHostEnvelope(id = 1, request = BuildHostRequest.GradleProjects))
    assertFalse(encoded.contains("null"), "envelope wrote null slots: $encoded")
    assertContains(encoded, "gradleProjects")
  }

  /** Defaults are written, so the wire says what it means to anything reading it. */
  @Test
  fun `defaulted fields are written`() {
    val encoded =
      BuildHostCodec.encode(
        BuildHostEnvelope(id = 1, request = BuildHostRequest.DiscoverAndBuild())
      )
    assertContains(encoded, "silenceStdout")
  }
}

class WireModuleTest {

  @Test
  fun `a module round trips back to its in-process form`() {
    val module = WireModule("auth:composables", File("/w/auth/composables").absolutePath)
    val back = module.toPreviewModule()
    assertEquals("auth:composables", back.gradlePath)
    assertEquals(File("/w/auth/composables").absolutePath, back.projectDir.path)
  }

  /**
   * The property the wire depends on: a relative `projectDir` resolves against whichever process
   * reads it, and the two processes need not share a working directory. `from` resolves rather than
   * trusting its input, so a relative path cannot reach the wire even when one is constructed.
   */
  @Test
  fun `from makes the project directory absolute`() {
    val relative = ee.schimke.composeai.previewdata.PreviewModule("app", File("app"))
    assertFalse(relative.projectDir.isAbsolute, "fixture is not relative; the test proves nothing")

    val wire = WireModule.from(relative)

    assertTrue(File(wire.projectDir).isAbsolute, "projectDir reached the wire relative: $wire")
    assertEquals(File("app").absolutePath, wire.projectDir)
  }

  /**
   * Absolute is not sufficient. The CLI's project-root discovery really does produce `<root>/.`,
   * and a server keying anything by module directory would see that as a different directory from
   * `<root>`.
   */
  @Test
  fun `from normalises a dot segment away`() {
    val dotted = ee.schimke.composeai.previewdata.PreviewModule("app", File("/w/project/./app"))

    assertEquals(File("/w/project/app").absolutePath, WireModule.from(dotted).projectDir)
  }

  @Test
  fun `from normalises a parent segment away`() {
    val dotted =
      ee.schimke.composeai.previewdata.PreviewModule("app", File("/w/project/other/../app"))

    assertEquals(File("/w/project/app").absolutePath, WireModule.from(dotted).projectDir)
  }

  @Test
  fun `an already absolute project directory is unchanged`() {
    val absolute =
      ee.schimke.composeai.previewdata.PreviewModule("app", File("/w/app").absoluteFile)
    assertEquals(File("/w/app").absolutePath, WireModule.from(absolute).projectDir)
  }
}
