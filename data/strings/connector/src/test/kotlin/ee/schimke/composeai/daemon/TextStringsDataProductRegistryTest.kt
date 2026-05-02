package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TextStringsDataProductRegistryTest {
  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("text-strings-product-test").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  @Test
  fun `fetch projects text and semantic label separately`() {
    val previewId = "com.example.TextPreview"
    writeSemantics(
      previewId,
      """
      {
        "root": {
          "nodeId": "1",
          "boundsInRoot": "0,0,200,200",
          "children": [
            {
              "nodeId": "2",
              "boundsInRoot": "10,20,90,40",
              "text": "Buy semantics",
              "layoutText": "Buy",
              "layoutFontSize": "18.0sp",
              "layoutForegroundColor": "#FF102030",
              "layoutBackgroundColor": "#FFE0E0E0",
              "label": "Purchase item"
            },
            {
              "nodeId": "3",
              "boundsInRoot": "10,50,90,70",
              "label": "Settings"
            }
          ]
        }
      }
      """
        .trimIndent(),
    )
    val registry =
      TextStringsDataProductRegistry(
        rootDir = rootDir,
        previewIndex =
          PreviewIndex.fromMap(
            path = null,
            byId =
              mapOf(
                previewId to
                  PreviewInfoDto(
                    id = previewId,
                    className = "com.example.TextKt",
                    methodName = "TextPreview",
                    params = PreviewParamsDto(locale = "fr-FR", fontScale = 1.3f),
                  )
              ),
          ),
      )

    val outcome =
      registry.fetch(
        previewId = previewId,
        kind = TextStringsDataProductRegistry.KIND,
        params = null,
        inline = true,
      )

    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val payload = (outcome as DataProductRegistry.Outcome.Ok).result.payload!!.jsonObject
    val texts = payload["texts"]!!.jsonArray
    assertEquals(2, texts.size)

    val first = texts[0].jsonObject
    assertEquals("Buy", first["text"]!!.jsonPrimitive.content)
    assertEquals("layout", first["textSource"]!!.jsonPrimitive.content)
    assertEquals("Buy semantics", first["semanticsText"]!!.jsonPrimitive.content)
    assertEquals("Purchase item", first["semanticsLabel"]!!.jsonPrimitive.content)
    assertEquals("18.0sp", first["fontSize"]!!.jsonPrimitive.content)
    assertEquals("#FF102030", first["foregroundColor"]!!.jsonPrimitive.content)
    assertEquals("#FFE0E0E0", first["backgroundColor"]!!.jsonPrimitive.content)
    assertEquals("2", first["nodeId"]!!.jsonPrimitive.content)
    assertEquals("10,20,90,40", first["boundsInScreen"]!!.jsonPrimitive.content)
    assertEquals("fr-FR", first["localeTag"]!!.jsonPrimitive.content)
    assertEquals("1.3", first["fontScale"]!!.jsonPrimitive.content)

    val second = texts[1].jsonObject
    assertNull("semantic-only entries omit text", second["text"])
    assertNull("semantic-only entries omit text source", second["textSource"])
    assertEquals("Settings", second["semanticsLabel"]!!.jsonPrimitive.content)
  }

  @Test
  fun `fetch reports locale and font scale from latest render overrides`() {
    val previewId = "com.example.TextPreview"
    writeSemantics(
      previewId,
      """{"root":{"nodeId":"1","boundsInRoot":"0,0,10,10","layoutText":"Hello"}}""",
    )
    val registry =
      TextStringsDataProductRegistry(
        rootDir = rootDir,
        previewIndex =
          PreviewIndex.fromMap(
            path = null,
            byId =
              mapOf(
                previewId to
                  PreviewInfoDto(
                    id = previewId,
                    className = "com.example.TextKt",
                    methodName = "TextPreview",
                    params = PreviewParamsDto(locale = "en-US", fontScale = 1.0f),
                  )
              ),
          ),
      )
    registry.onRender(
      previewId = previewId,
      result = RenderResult(id = 1L, classLoaderHashCode = 0, classLoaderName = "test"),
      overrides = PreviewOverrides(localeTag = "ja-JP", fontScale = 2.0f),
    )

    val outcome =
      registry.fetch(
        previewId = previewId,
        kind = TextStringsDataProductRegistry.KIND,
        params = null,
        inline = true,
      )

    assertTrue(outcome is DataProductRegistry.Outcome.Ok)
    val entry =
      (outcome as DataProductRegistry.Outcome.Ok)
        .result
        .payload!!
        .jsonObject["texts"]!!
        .jsonArray
        .single()
        .jsonObject
    assertEquals("ja-JP", entry["localeTag"]!!.jsonPrimitive.content)
    assertEquals("2.0", entry["fontScale"]!!.jsonPrimitive.content)
  }

  @Test
  fun `attachments mirror inline payload`() {
    val previewId = "com.example.TextPreview"
    writeSemantics(
      previewId,
      """{"root":{"nodeId":"1","boundsInRoot":"0,0,10,10","text":"Hello","label":"Hello"}}""",
    )
    val registry =
      TextStringsDataProductRegistry(rootDir = rootDir, previewIndex = PreviewIndex.empty())

    val attachment =
      registry.attachmentsFor(previewId, setOf(TextStringsDataProductRegistry.KIND)).single()

    assertEquals(TextStringsDataProductRegistry.KIND, attachment.kind)
    assertEquals(1, attachment.schemaVersion)
    assertNull(attachment.path)
    assertNotNull(attachment.payload)
    val entry = attachment.payload!!.jsonObject["texts"]!!.jsonArray.single().jsonObject
    assertEquals("Hello", entry["text"]!!.jsonPrimitive.content)
    assertEquals("Hello", entry["semanticsLabel"]!!.jsonPrimitive.content)
  }

  @Test
  fun `missing semantics artifact is not available`() {
    val registry =
      TextStringsDataProductRegistry(rootDir = rootDir, previewIndex = PreviewIndex.empty())

    assertEquals(
      DataProductRegistry.Outcome.NotAvailable,
      registry.fetch(
        previewId = "missing",
        kind = TextStringsDataProductRegistry.KIND,
        params = null,
        inline = true,
      ),
    )
    assertTrue(
      registry.attachmentsFor("missing", setOf(TextStringsDataProductRegistry.KIND)).isEmpty()
    )
  }

  private fun writeSemantics(previewId: String, json: String) {
    rootDir
      .resolve(previewId)
      .also { it.mkdirs() }
      .resolve(ComposeSemanticsProduct.FILE)
      .writeText(json)
  }
}
