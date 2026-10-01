package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The CLI's reading of the shared `~/.compose-preview/settings.json` (compose-preview-server#1242):
 * flag > setting > built-in default, unknown keys ignored, a malformed file warns and defaults.
 */
class CliPreviewSettingsTest {
  private val dir: File = Files.createTempDirectory("cli-settings").toFile()

  private fun settingsFile(text: String): File =
    File(dir, "settings.json").apply { writeText(text) }

  private fun read(text: String, warnings: MutableList<String> = mutableListOf()) =
    CliPreviewSettingsFile.read(settingsFile(text), warn = { warnings += it })

  @Test
  fun `missing file is the defaults, silently`() {
    val warnings = mutableListOf<String>()
    val settings = CliPreviewSettingsFile.read(File(dir, "absent.json"), warn = { warnings += it })
    assertEquals(CliPreviewSettings(), settings)
    assertTrue(settings.isDefault)
    assertEquals(emptyList(), warnings)
  }

  @Test
  fun `reads the server's file shape`() {
    val settings =
      read(
        """
        {
          "schema": "compose-preview-settings/v1",
          "values": {
            "device": "id:pixel_5",
            "darkTheme": true,
            "fontScale": 1.5,
            "locale": "ja-JP",
            "renderResult": "file"
          }
        }
        """
      )
    assertEquals(CliPreviewSettings("id:pixel_5", true, 1.5, "ja-JP"), settings)
  }

  @Test
  fun `unknown and server-only keys are ignored without a warning`() {
    val warnings = mutableListOf<String>()
    val settings =
      read(
        """{"values":{"darkTheme":true,"imageToModel":false,"replicasPerDaemon":2,
          "uiBuilderMcpAppLayout":"full","someFutureKey":{"nested":1}},"extra":true}""",
        warnings,
      )
    assertEquals(CliPreviewSettings(darkTheme = true), settings)
    assertEquals(emptyList(), warnings)
  }

  @Test
  fun `malformed file warns once and uses the defaults`() {
    for (text in listOf("{not json", "[1,2]", """{"values": 3}""")) {
      val warnings = mutableListOf<String>()
      assertEquals(CliPreviewSettings(), read(text, warnings), text)
      assertEquals(1, warnings.size, "$text -> $warnings")
      assertTrue(warnings.single().contains("using defaults"), warnings.single())
    }
  }

  @Test
  fun `a bad value keeps its default and every other setting`() {
    val warnings = mutableListOf<String>()
    val settings =
      read(
        """{"values":{"darkTheme":"yes","fontScale":9,"locale":"not a locale",
          "device":"id:no_such_device","renderResult":"file"}}""",
        warnings,
      )
    assertEquals(CliPreviewSettings(), settings)
    assertEquals(4, warnings.size, warnings.toString())
    val keep = read("""{"values":{"darkTheme":1,"locale":"fr"}}""")
    assertEquals(CliPreviewSettings(locale = "fr"), keep)
  }

  @Test
  fun `the settings file env var wins over the home directory`() {
    val home = File(dir, "home")
    assertEquals(
      File(home, ".compose-preview/settings.json"),
      CliPreviewSettingsFile.defaultFile(emptyMap(), home),
    )
    assertEquals(
      File("/elsewhere/s.json"),
      CliPreviewSettingsFile.defaultFile(
        mapOf(CliPreviewSettingsFile.FILE_ENV to "/elsewhere/s.json"),
        home,
      ),
    )
  }

  @Test
  fun `overrides - an explicit flag beats a setting, which beats the default`() {
    val settings = CliPreviewSettings("id:pixel_5", darkTheme = true, fontScale = 2.0, "fr")
    // Default: nothing set anywhere -> no overrides at all.
    assertNull(CliPreviewSettings().fillOverrides(null))
    // Setting fills a silent call.
    assertEquals(
      PreviewOverrides(
        device = "id:pixel_5",
        uiMode = UiMode.DARK,
        fontScale = 2.0f,
        localeTag = "fr",
      ),
      settings.fillOverrides(null),
    )
    // An explicit value wins, key by key; other keys still come from the settings.
    val explicit =
      PreviewOverrides(device = "id:pixel_tablet", localeTag = "ar", touchOverlay = true)
    assertEquals(
      PreviewOverrides(
        device = "id:pixel_tablet",
        uiMode = UiMode.DARK,
        fontScale = 2.0f,
        localeTag = "ar",
        touchOverlay = true,
      ),
      settings.fillOverrides(explicit),
    )
    // An explicit light mode is not overridden by darkTheme.
    assertEquals(
      UiMode.LIGHT,
      settings.fillOverrides(PreviewOverrides(uiMode = UiMode.LIGHT))?.uiMode,
    )
    // Explicit overrides with default settings pass through untouched.
    assertEquals(explicit, CliPreviewSettings().fillOverrides(explicit))
  }

  @Test
  fun `matrix axes - a flag beats a setting, which beats not varying the axis`() {
    val settings = CliPreviewSettings("id:pixel_5", darkTheme = true, fontScale = 1.5, "fr")
    assertEquals(
      listOf("en", "ar"),
      settings.matrixAxis(CliPreviewSettings.LOCALE, listOf("en", "ar")),
    )
    assertEquals(listOf("fr"), settings.matrixAxis(CliPreviewSettings.LOCALE, null))
    assertEquals(listOf("id:pixel_5"), settings.matrixAxis(CliPreviewSettings.DEVICE, null))
    assertEquals(listOf("dark"), settings.matrixAxis(CliPreviewSettings.DARK_THEME, null))
    assertEquals(listOf("1.5"), settings.matrixAxis(CliPreviewSettings.FONT_SCALE, null))
    assertEquals(
      listOf("2.0"),
      CliPreviewSettings(fontScale = 2.0).matrixAxis(CliPreviewSettings.FONT_SCALE, null),
    )
    val defaults = CliPreviewSettings()
    for (axis in
      listOf(
        CliPreviewSettings.DEVICE,
        CliPreviewSettings.DARK_THEME,
        CliPreviewSettings.FONT_SCALE,
        CliPreviewSettings.LOCALE,
      )) {
      assertNull(defaults.matrixAxis(axis, null), axis)
    }
  }

  @Test
  fun `show and render name the settings they cannot apply`() {
    assertNull(unappliedSettingsNote("show", CliPreviewSettings()))
    val note = unappliedSettingsNote("show", CliPreviewSettings(darkTheme = true, locale = "fr"))
    requireNotNull(note)
    assertTrue(note.contains("darkTheme=true, locale=fr"), note)
    assertTrue(note.contains("'show' does not apply"), note)
  }
}
