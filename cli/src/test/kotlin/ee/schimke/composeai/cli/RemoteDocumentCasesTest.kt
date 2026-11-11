package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.protocol.RemoteComposeProfile
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.protocol.UiMode
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cases file `render-cases` reads: one Remote Compose document per case with its named values,
 * size and mode, as Livery's `cases.json` writes it.
 */
class RemoteDocumentCasesTest {
  private val dir: File =
    Files.createTempDirectory("cases").toFile().apply {
      File(this, "documents/banner").mkdirs()
      File(this, "documents/banner/in-app.rc").writeBytes(byteArrayOf(1, 2, 3))
    }

  private fun read(json: String): List<DocumentCase> =
    RemoteDocumentCases.read(File(dir, "cases.json").apply { writeText(json) })

  @Test
  fun `a Livery case becomes a carried document with its values, size and mode`() {
    val case =
      read(
          """
          { "cases": [ {
            "design": "banner", "template": "in-app", "example": "default", "mode": "dark",
            "id": "banner/in-app/default/dark",
            "target": { "surface": "in-app", "rcApiLevel": 7, "profiles": ["androidx"] },
            "document": "documents/banner/in-app.rc",
            "widthDp": 360, "heightDp": 160, "dark": true, "background": 4279374354,
            "values": {
              "headline": { "type": "string", "value": "Hello" },
              "count": { "type": "long", "value": 3 },
              "level": { "type": "float", "value": 0.5 },
              "is_on": { "type": "bool", "value": true },
              "theme.primary": { "type": "color", "argb": 4284960932 },
              "hero": { "type": "image", "ref": "https://example.com/hero.png" }
            }
          } ] }
          """,
        )
        .single()
    assertEquals("banner/in-app/default/dark", case.id)
    assertEquals(RemoteComposeProfile.ANDROIDX7, case.profile)
    assertEquals(RemoteNamedValue.StringValue("Hello"), case.namedValues["headline"])
    assertEquals(RemoteNamedValue.IntValue(3), case.namedValues["count"])
    assertEquals(RemoteNamedValue.FloatValue(0.5f), case.namedValues["level"])
    assertEquals(RemoteNamedValue.BooleanValue(true), case.namedValues["is_on"])
    assertEquals(RemoteNamedValue.ColorValue("#FF6750A4"), case.namedValues["theme.primary"])
    // No daemon type for an image reference: reported, and left at the document's default.
    assertEquals(listOf("hero"), case.skipped)

    val overrides = case.toOverrides(density = 2f, player = RemoteComposePlayerKind.EMBEDDED)
    assertEquals(720, overrides.widthPx)
    assertEquals(320, overrides.heightPx)
    assertEquals(2f, overrides.density)
    assertEquals(UiMode.DARK, overrides.uiMode)
    val rc = overrides.remoteCompose!!
    assertEquals(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)), rc.documentBase64)
    assertEquals(RemoteComposeProfile.ANDROIDX7, rc.profile)
    assertEquals(RemoteComposePlayerKind.EMBEDDED, rc.player)
    assertEquals(case.namedValues, rc.namedValues)
  }

  @Test
  fun `a target's profiles pick the platform player's profile`() {
    fun profile(profiles: String, level: Int = 7) =
      read(
          """{ "cases": [ { "document": "documents/banner/in-app.rc", "widthDp": 1, "heightDp": 1,
            "target": { "profiles": [$profiles], "rcApiLevel": $level } } ] }""",
        )
        .single()
        .profile
    assertEquals(RemoteComposeProfile.WIDGETS_V7, profile("\"widgets\""))
    assertEquals(RemoteComposeProfile.WIDGETS_V6, profile("\"widgets\"", level = 6))
    assertEquals(RemoteComposeProfile.WEAR_WIDGETS, profile("\"glance-wear\""))
    assertNull(profile("\"core\""))
  }

  @Test
  fun `an explicit profile, a kind key and a hex colour are read too`() {
    val case =
      read(
          """{ "cases": [ { "document": "documents/banner/in-app.rc", "widthDp": 10, "heightDp": 20,
            "profile": "wearWidgets",
            "values": { "c": { "kind": "color", "argb": "#336699" }, "w": { "kind": "dp", "value": 4 } } } ] }""",
        )
        .single()
    assertEquals("case-0", case.id)
    assertEquals(RemoteComposeProfile.WEAR_WIDGETS, case.profile)
    assertEquals(RemoteNamedValue.ColorValue("#FF336699"), case.namedValues["c"])
    assertEquals(RemoteNamedValue.DpValue(4f), case.namedValues["w"])
    assertNull(case.dark)
    assertNull(case.toOverrides(1f).uiMode)
  }

  @Test
  fun `a case without its document or a size is refused`() {
    assertFailsWith<RemoteDocumentCases.Invalid> {
      read("""{ "cases": [ { "document": "missing.rc", "widthDp": 1, "heightDp": 1 } ] }""")
    }
    assertFailsWith<RemoteDocumentCases.Invalid> {
      read("""{ "cases": [ { "document": "documents/banner/in-app.rc", "widthDp": 1 } ] }""")
    }
    assertFailsWith<RemoteDocumentCases.Invalid> { read("""{ "not": "cases" }""") }
  }

  @Test
  fun `a case id is a PNG path that stays inside the output`() {
    assertEquals(
      "banner/in-app/default/dark.png",
      RemoteDocumentCases.pngPath("banner/in-app/default/dark"),
    )
    assertEquals("_/_/x.png", RemoteDocumentCases.pngPath("../../x"))
    assertEquals("case_1.png", RemoteDocumentCases.pngPath("case#1"))
  }

  @Test
  fun `cases that would share an image, or overwrite the contact sheet, are found`() {
    fun case(id: String) =
      DocumentCase(id, byteArrayOf(), 1, 1, null, null, emptyMap(), emptyList())
    val clashes =
      RemoteDocumentCases.pathCollisions(
        listOf(case("case#1"), case("case_1"), case("contact-sheet"), case("ok")),
        reserved = setOf("contact-sheet.png"),
      )
    assertEquals(2, clashes.size, clashes.toString())
    assertTrue(clashes.any { "'case#1', 'case_1'" in it })
    assertTrue(clashes.any { "contact-sheet.png" in it })
    assertEquals(
      emptyList(),
      RemoteDocumentCases.pathCollisions(listOf(case("a"), case("b")), setOf("contact-sheet.png")),
    )
  }
}
