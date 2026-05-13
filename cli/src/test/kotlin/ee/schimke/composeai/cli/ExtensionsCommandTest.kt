package ee.schimke.composeai.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Smoke coverage for `compose-preview extensions list`. Pins the JSON envelope schema and the
 * minimum contents so agents and downstream tooling can grep for what's registered.
 */
class ExtensionsCommandTest {

  private lateinit var capturedOut: ByteArrayOutputStream
  private var savedOut: PrintStream? = null

  @BeforeTest
  fun setUp() {
    capturedOut = ByteArrayOutputStream()
    savedOut = System.out
    System.setOut(PrintStream(capturedOut))
  }

  @AfterTest
  fun tearDown() {
    savedOut?.let { System.setOut(it) }
  }

  @Test
  fun `list --json emits versioned envelope with a11y entry`() {
    ExtensionsCommand(listOf("list", "--json")).run()
    val output = capturedOut.toString()
    val payload = Json.parseToJsonElement(output).jsonObject

    assertEquals(JsonPrimitive(EXTENSIONS_LIST_SCHEMA), payload["schema"])
    val entries = payload["extensions"]?.jsonArray ?: error("missing extensions array")
    val ids = entries.map { it.jsonObject["id"]?.jsonPrimitive?.content }
    assertTrue("a11y" in ids, "expected built-in a11y extension, got $ids")

    val a11y = entries.first { it.jsonObject["id"]?.jsonPrimitive?.content == "a11y" }.jsonObject
    assertEquals(
      "composePreview.previewExtensions.a11y.enableAllChecks",
      a11y["enableProperty"]?.jsonPrimitive?.content,
    )
  }

  @Test
  fun `list human output lists each registered extension`() {
    ExtensionsCommand(listOf("list")).run()
    val output = capturedOut.toString()
    assertTrue("a11y" in output, "expected a11y id in human output:\n$output")
    assertTrue("--with-extension a11y" in output, "expected --with-extension hint:\n$output")
  }
}
