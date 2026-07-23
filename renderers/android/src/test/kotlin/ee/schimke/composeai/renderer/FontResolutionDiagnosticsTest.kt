package ee.schimke.composeai.renderer

import androidx.compose.ui.text.font.FontWeight
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [FontResolutionDiagnostics], [FontFallbackException], and [RenderWarningsSidecar] —
 * the surfacing that turns a silent downloadable-font fallback into either a fatal per-preview error
 * or a `<png>.warnings.json` sidecar. Pure JVM: no Robolectric, no network.
 */
class FontResolutionDiagnosticsTest {

  @get:Rule val tempDir = TemporaryFolder()

  private val fontProps =
    listOf("composeai.fonts.failOnFallback", "composeai.fonts.cacheDir", "composeai.fonts.offline")
  private lateinit var savedProps: Map<String, String?>

  @Before
  fun setUp() {
    savedProps = fontProps.associateWith { System.getProperty(it) }
    fontProps.forEach { System.clearProperty(it) }
    FontResolutionDiagnostics.resetForTest()
  }

  @After
  fun tearDown() {
    savedProps.forEach { (k, v) -> if (v == null) System.clearProperty(k) else System.setProperty(k, v) }
    FontResolutionDiagnostics.resetForTest()
  }

  private fun key(name: String, weight: Int = 400, italic: Boolean = false) =
    GoogleFontKey(name, FontWeight(weight), italic)

  @Test
  fun `failOnFallback defaults to true and honours the opt-out`() {
    assertTrue(FontResolutionDiagnostics.failOnFallback)
    System.setProperty("composeai.fonts.failOnFallback", "false")
    assertFalse(FontResolutionDiagnostics.failOnFallback)
    System.setProperty("composeai.fonts.failOnFallback", "true")
    assertTrue(FontResolutionDiagnostics.failOnFallback)
    // A non-boolean value falls back to the safe default (fatal).
    System.setProperty("composeai.fonts.failOnFallback", "maybe")
    assertTrue(FontResolutionDiagnostics.failOnFallback)
  }

  @Test
  fun `currentFailureReason reflects the font config`() {
    assertTrue(FontResolutionDiagnostics.currentFailureReason().contains("cacheDir unset"))

    System.setProperty("composeai.fonts.cacheDir", "/tmp/fonts")
    System.setProperty("composeai.fonts.offline", "true")
    assertTrue(FontResolutionDiagnostics.currentFailureReason().contains("offline"))

    System.setProperty("composeai.fonts.offline", "false")
    assertTrue(FontResolutionDiagnostics.currentFailureReason().contains("download from Google Fonts"))
  }

  @Test
  fun `describe names the face weight italic and reason`() {
    val msg =
      FontResolutionDiagnostics.describe(
        FontResolutionDiagnostics.FontFallback("Orbitron", 500, italic = false, reason = "offline")
      )
    assertTrue(msg.contains("\"Orbitron\""))
    assertTrue(msg.contains("weight=500"))
    assertTrue(msg.contains("offline"))
    assertTrue(msg.contains("Roboto"))
  }

  @Test
  fun `beginPreview then recordFallback then drainPreview returns only this preview's faces`() {
    FontResolutionDiagnostics.beginPreview()
    FontResolutionDiagnostics.recordFallback(key("Orbitron", 500), "offline")
    FontResolutionDiagnostics.recordFallback(key("Space Grotesk", 400), "offline")
    val drained = FontResolutionDiagnostics.drainPreview()
    assertEquals(listOf("Orbitron", "Space Grotesk"), drained.map { it.family })
    // Drain clears the buffer — a fresh preview starts empty.
    assertTrue(FontResolutionDiagnostics.drainPreview().isEmpty())
  }

  @Test
  fun `stderr warning is emitted once per distinct face per process`() {
    val original = System.err
    val captured = ByteArrayOutputStream()
    System.setErr(PrintStream(captured, true))
    try {
      FontResolutionDiagnostics.beginPreview()
      FontResolutionDiagnostics.recordFallback(key("Orbitron", 500), "offline")
      FontResolutionDiagnostics.recordFallback(key("Orbitron", 500), "offline")
    } finally {
      System.setErr(original)
    }
    val lines = captured.toString().trim().lines().filter { it.isNotBlank() }
    assertEquals(1, lines.size)
    assertTrue(lines.single().contains("Orbitron"))
  }

  @Test
  fun `FontFallbackException message lists the unresolved faces and the opt-out`() {
    val e =
      FontFallbackException(
        listOf(FontResolutionDiagnostics.FontFallback("Orbitron", 500, false, "offline"))
      )
    assertTrue(e.message!!.contains("Orbitron"))
    assertTrue(e.message!!.contains("failOnFallback=false"))
  }

  @Test
  fun `warnings sidecar encodes each fallback`() {
    val json =
      RenderWarningsSidecar.encode(
        listOf(
          FontResolutionDiagnostics.FontFallback("Orbitron", 500, italic = false, reason = "offline")
        )
      )
    assertTrue(json.contains("\"schema\":\"compose-preview-warnings/v1\""))
    assertTrue(json.contains("\"family\":\"Orbitron\""))
    assertTrue(json.contains("\"weight\":500"))
    assertTrue(json.contains("\"italic\":false"))
  }

  @Test
  fun `writeOrDelete writes on fallbacks and removes a stale sidecar when clean`() {
    val png = File(tempDir.newFolder("renders"), "Foo.png").apply { writeBytes(byteArrayOf(1)) }
    val sidecar = RenderWarningsSidecar.pathFor(png)

    RenderWarningsSidecar.writeOrDelete(
      png,
      listOf(FontResolutionDiagnostics.FontFallback("Orbitron", 500, false, "offline")),
    )
    assertTrue(sidecar.exists())
    assertTrue(sidecar.readText().contains("Orbitron"))

    // A subsequent clean render drops the stale sidecar.
    RenderWarningsSidecar.writeOrDelete(png, emptyList())
    assertFalse(sidecar.exists())
  }

  @Test
  fun `deleteStale removes an existing sidecar and is a no-op otherwise`() {
    val png = File(tempDir.newFolder("renders"), "Bar.png")
    val sidecar = RenderWarningsSidecar.pathFor(png)
    RenderWarningsSidecar.deleteStale(png) // no-op, must not throw
    assertNull(sidecar.takeIf { it.exists() })
    sidecar.writeText("{}")
    RenderWarningsSidecar.deleteStale(png)
    assertFalse(sidecar.exists())
  }
}
