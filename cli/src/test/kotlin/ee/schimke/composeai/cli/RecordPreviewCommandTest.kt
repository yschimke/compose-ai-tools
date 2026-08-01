package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RecordingFormat
import ee.schimke.composeai.daemon.protocol.RecordingScriptEvent
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordPreviewCommandTest {
  @Test
  fun `record format inference covers every supported artifact extension`() {
    val command = RecordPreviewCommand(emptyList())

    assertEquals(RecordingFormat.GIF, command.call("resolveFormat", null, "demo.GIF"))
    assertEquals(RecordingFormat.APNG, command.call("resolveFormat", null, "demo.apng"))
    assertEquals(RecordingFormat.MP4, command.call("resolveFormat", null, "demo.mp4"))
    assertEquals(RecordingFormat.WEBM, command.call("resolveFormat", null, "demo.webm"))
    assertEquals(RecordingFormat.GIF, command.call("resolveFormat", "gif", "demo.apng"))
  }

  @Test
  fun `record overrides parse typed values into the protocol model`() {
    val command = RecordPreviewCommand(emptyList())
    val overrides =
      command.call<PreviewOverrides>(
        "parseOverrides",
        listOf(
          "touchOverlay=yes",
          "talkBack=off",
          "fontScale=1.25",
          "density=2",
          "widthPx=320",
          "heightPx=480",
          "clockEpochMillis=1234",
          "localeTag=fr-FR",
        ),
      )

    assertEquals(true, overrides.touchOverlay)
    assertEquals(false, overrides.talkBack)
    assertEquals(1.25f, overrides.fontScale)
    assertEquals(2f, overrides.density)
    assertEquals(320, overrides.widthPx)
    assertEquals(480, overrides.heightPx)
    assertEquals(1234, overrides.clockEpochMillis)
    assertEquals("fr-FR", overrides.localeTag)
    assertNull(
      RecordPreviewCommand(emptyList())
        .call<PreviewOverrides?>("parseOverrides", emptyList<String>())
    )
  }

  @Test
  fun `pixel baselines are made absolute without rewriting other events`() {
    val baselineDir = Files.createTempDirectory("record-baselines").toFile()
    val command = RecordPreviewCommand(listOf("--baseline-dir", baselineDir.path))
    val pixel = RecordingScriptEvent(10, "assert.pixels", inputText = "cards/home.png")
    val click = RecordingScriptEvent(20, "input.click", inputText = "unchanged")

    val resolved =
      command.call<List<RecordingScriptEvent>>("resolveBaselines", listOf(pixel, click))

    assertEquals(File(baselineDir, "cards/home.png").absolutePath, resolved[0].inputText)
    assertEquals(click, resolved[1])
    assertTrue(File(resolved[0].inputText!!).isAbsolute)
  }

  @Suppress("UNCHECKED_CAST")
  private fun <T> Any.call(name: String, vararg args: Any?): T {
    val method =
      javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
    method.isAccessible = true
    return method.invoke(this, *args) as T
  }
}
