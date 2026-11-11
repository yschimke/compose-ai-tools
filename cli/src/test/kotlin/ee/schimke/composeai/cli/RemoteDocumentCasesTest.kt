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
 * The cases file `render-cases` reads: compose-preview-contracts' `RemoteDocumentCasesV1`, as
 * Livery's `remote-document-cases.json` writes it.
 */
class RemoteDocumentCasesTest {
  private val dir: File =
    Files.createTempDirectory("cases").toFile().apply {
      File(this, "documents/banner").mkdirs()
      File(this, "documents/banner/in-app.rc").writeBytes(byteArrayOf(1, 2, 3))
    }

  private fun read(json: String): List<DocumentCase> =
    RemoteDocumentCases.read(File(dir, "cases.json").apply { writeText(json) })

  private fun file(vararg cases: String) =
    """{ "schema": "compose-preview/remote-document-cases/v1", "cases": [ ${cases.joinToString()} ] }"""

  @Test
  fun `a case becomes a carried document with its values, size, mode and profile`() {
    val case =
      read(
          file(
            """{ "id": "banner/in-app/default/dark", "document": "documents/banner/in-app.rc",
              "widthDp": 360, "heightDp": 160, "dark": true, "profile": "androidx7",
              "background": "#FF121212", "design": "a producer's own field",
              "namedValues": {
                "headline": { "kind": "string", "value": "Hello" },
                "count": { "kind": "int", "value": 3 },
                "level": { "kind": "float", "value": 0.5 },
                "gap": { "kind": "dp", "value": 4 },
                "is_on": { "kind": "bool", "value": true },
                "theme.primary": { "kind": "color", "argb": "#FF6750A4" } } }""",
          ),
        )
        .single()
    assertEquals("banner/in-app/default/dark", case.id)
    assertEquals(RemoteComposeProfile.ANDROIDX7, case.profile)
    assertEquals(RemoteNamedValue.StringValue("Hello"), case.namedValues["headline"])
    assertEquals(RemoteNamedValue.IntValue(3), case.namedValues["count"])
    assertEquals(RemoteNamedValue.FloatValue(0.5f), case.namedValues["level"])
    assertEquals(RemoteNamedValue.DpValue(4f), case.namedValues["gap"])
    assertEquals(RemoteNamedValue.BooleanValue(true), case.namedValues["is_on"])
    assertEquals(RemoteNamedValue.ColorValue("#FF6750A4"), case.namedValues["theme.primary"])

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
  fun `optional fields default to the renderer's`() {
    val case =
      read(
          file(
            """{ "id": "a", "document": "documents/banner/in-app.rc", "widthDp": 10, "heightDp": 20 }""",
          ),
        )
        .single()
    assertNull(case.dark)
    assertNull(case.profile)
    assertEquals(emptyMap(), case.namedValues)
    assertNull(case.toOverrides(1f).uiMode)
  }

  @Test
  fun `a file that isn't the contract's shape is refused`() {
    fun refused(json: String) = assertFailsWith<RemoteDocumentCases.Invalid> { read(json) }
    // No schema, or another one.
    refused("""{ "cases": [] }""")
    refused("""{ "schema": "something/else", "cases": [] }""")
    // A case without its document, a size or an id.
    refused(file("""{ "id": "a", "document": "missing.rc", "widthDp": 1, "heightDp": 1 }"""))
    refused(file("""{ "id": "a", "document": "documents/banner/in-app.rc", "widthDp": 1 }"""))
    refused(file("""{ "document": "documents/banner/in-app.rc", "widthDp": 1, "heightDp": 1 }"""))
    // A value kind the daemon has no type for.
    refused(
      file(
        """{ "id": "a", "document": "documents/banner/in-app.rc", "widthDp": 1, "heightDp": 1,
          "namedValues": { "hero": { "kind": "image", "ref": "x" } } }""",
      ),
    )
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
    fun case(id: String) = DocumentCase(id, byteArrayOf(), 1, 1, null, null, emptyMap())
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
