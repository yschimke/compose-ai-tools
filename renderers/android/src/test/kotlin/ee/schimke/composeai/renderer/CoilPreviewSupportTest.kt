package ee.schimke.composeai.renderer

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [CoilLoadDiagnostics], [describeModel] and the `unresolvedImages` half of
 * [RenderWarningsSidecar] — the surfacing that turns a coil load which *can't* resolve off-device
 * into a diagnosable warning instead of a silently blank sticker (issue #2952).
 *
 * The other half of the fix — the inline-dispatcher `ImageLoader` swap itself — needs a real
 * Android context and a real composition, so it is covered end to end by `:samples:android`'s
 * `AsyncImagePixelTest` against a rendered PNG rather than mocked here. Pure JVM: no Robolectric,
 * no network.
 */
class CoilPreviewSupportTest {

  private lateinit var savedErr: PrintStream
  private lateinit var captured: ByteArrayOutputStream

  @Before
  fun setUp() {
    CoilLoadDiagnostics.resetForTest()
    savedErr = System.err
    captured = ByteArrayOutputStream()
    System.setErr(PrintStream(captured, true))
  }

  @After
  fun tearDown() {
    System.setErr(savedErr)
    CoilLoadDiagnostics.resetForTest()
  }

  @Test
  fun `a request that succeeds produces no warning`() {
    val key = Any()
    CoilLoadDiagnostics.onStart(key, "ByteArray(128 bytes)")
    CoilLoadDiagnostics.onSuccess(key)

    assertTrue(CoilLoadDiagnostics.drainPreview().isEmpty())
    assertEquals("", captured.toString())
  }

  @Test
  fun `a failed request is drained as FAILED with the throwable detail`() {
    val key = Any()
    CoilLoadDiagnostics.onStart(key, "https://artwork.invalid/a.png")
    CoilLoadDiagnostics.onFailure(key, "java.net.UnknownHostException: artwork.invalid")

    val drained = CoilLoadDiagnostics.drainPreview()
    assertEquals(1, drained.size)
    assertEquals("https://artwork.invalid/a.png", drained[0].model)
    assertEquals(CoilLoadDiagnostics.Outcome.FAILED, drained[0].outcome)
    assertTrue(drained[0].detail!!.contains("UnknownHostException"))
    assertTrue(captured.toString().contains("failed"))
  }

  @Test
  fun `a request still in flight at capture time is drained as PENDING`() {
    CoilLoadDiagnostics.onStart(Any(), "content://media/1")

    val drained = CoilLoadDiagnostics.drainPreview()
    assertEquals(1, drained.size)
    assertEquals(CoilLoadDiagnostics.Outcome.PENDING, drained[0].outcome)
    assertTrue(captured.toString().contains("had not completed"))
  }

  @Test
  fun `beginPreview drops the previous preview's in-flight requests`() {
    CoilLoadDiagnostics.onStart(Any(), "leaked-from-previous-preview")
    CoilLoadDiagnostics.beginPreview()

    assertTrue(CoilLoadDiagnostics.drainPreview().isEmpty())
  }

  @Test
  fun `the stderr note is emitted once per distinct model per process`() {
    val first = Any()
    CoilLoadDiagnostics.onStart(first, "https://artwork.invalid/a.png")
    CoilLoadDiagnostics.onFailure(first, "boom")
    CoilLoadDiagnostics.drainPreview()

    val second = Any()
    CoilLoadDiagnostics.onStart(second, "https://artwork.invalid/a.png")
    CoilLoadDiagnostics.onFailure(second, "boom")
    CoilLoadDiagnostics.drainPreview()

    // A catalog render asks for the same unreachable URL on every sticker; one line, not 200.
    assertEquals(1, captured.toString().lines().count { it.contains("artwork.invalid") })
  }

  @Test
  fun `describeModel gives a reproducible label for models without a useful toString`() {
    assertEquals("ByteArray(3 bytes)", describeModel(byteArrayOf(1, 2, 3)))
    assertEquals("https://example.com/a.png", describeModel("https://example.com/a.png"))
    assertEquals("resource 0x7f080001", describeModel(0x7f080001))
    assertEquals("null", describeModel(null))
    // An identity `toString()` would embed a hash that changes every run, which would make the
    // sidecar non-reproducible; the type name stands in for it.
    assertEquals("IdentityToString", describeModel(IdentityToString()))
  }

  private class IdentityToString

  @Test
  fun `the warnings sidecar carries unresolved images alongside font fallbacks`() {
    val json =
      RenderWarningsSidecar.encode(
        fallbacks = emptyList(),
        imageLoads =
          listOf(
            CoilLoadDiagnostics.UnresolvedLoad(
              model = "https://artwork.invalid/a.png",
              outcome = CoilLoadDiagnostics.Outcome.FAILED,
              detail = "java.net.UnknownHostException",
            ),
            CoilLoadDiagnostics.UnresolvedLoad(
              model = "ByteArray(12 bytes)",
              outcome = CoilLoadDiagnostics.Outcome.PENDING,
              detail = null,
            ),
          ),
      )

    assertTrue(json.contains("\"schema\":\"${RenderWarningsSidecar.SCHEMA}\""))
    // Additive: readers that predate #2952 still find the array they know about.
    assertTrue(json.contains("\"fontFallbacks\":[]"))
    assertTrue(json.contains("\"outcome\":\"failed\""))
    assertTrue(json.contains("\"outcome\":\"pending\""))
    assertTrue(json.contains("\"detail\":null"))
    assertTrue(json.contains("\"model\":\"https://artwork.invalid/a.png\""))
  }

  @Test
  fun `a clean render writes the empty array rather than omitting the field`() {
    val json = RenderWarningsSidecar.encode(fallbacks = emptyList())
    assertTrue(json.contains("\"unresolvedImages\":[]"))
  }

  @Test
  fun `the preview loader is on by default and can be turned off with a system property`() {
    val saved = System.getProperty(CoilPreviewSupport.ENABLED_PROPERTY)
    try {
      System.clearProperty(CoilPreviewSupport.ENABLED_PROPERTY)
      assertTrue(CoilPreviewSupport.enabled)

      // The escape hatch for a consumer whose loader misbehaves when its dispatchers are rebound:
      // previews go back to capturing blank, but the render doesn't break.
      System.setProperty(CoilPreviewSupport.ENABLED_PROPERTY, "false")
      assertFalse(CoilPreviewSupport.enabled)
      assertFalse(CoilPreviewSupport.active)

      // A junk value must not silently disable the fix.
      System.setProperty(CoilPreviewSupport.ENABLED_PROPERTY, "yes-please")
      assertTrue(CoilPreviewSupport.enabled)
    } finally {
      if (saved == null) System.clearProperty(CoilPreviewSupport.ENABLED_PROPERTY)
      else System.setProperty(CoilPreviewSupport.ENABLED_PROPERTY, saved)
    }
  }
}
