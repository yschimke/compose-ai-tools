package ee.schimke.composeai.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DataCommandsTest {
  private val json = Json {
    prettyPrint = false
    encodeDefaults = true
    ignoreUnknownKeys = true
  }

  @Test
  fun `data-products response pins schema and lists emitted JSON products`() {
    val projectDir = tempModule()
    writeProduct(
      projectDir,
      previewId = "com.example.Foo",
      fileName = "a11y-atf.json",
      text = """{"findings":[]}""",
    )

    val response = DataProductsResponse(modules = listOf(scanDataProducts("app", projectDir)))
    val encoded = json.encodeToString(DataProductsResponse.serializer(), response)
    val parsed = json.decodeFromString(DataProductsResponse.serializer(), encoded)

    assertEquals(DATA_PRODUCTS_SCHEMA, parsed.schema)
    assertTrue(""""schema":"$DATA_PRODUCTS_SCHEMA"""" in encoded)

    val product = parsed.modules.single().products.single()
    assertEquals("a11y/atf", product.kind)
    assertEquals(1, product.schemaVersion)
    assertEquals(listOf("com.example.Foo"), product.previews)
  }

  @Test
  fun `data get response includes parsed JSON payload and absolute path`() {
    val projectDir = tempModule()
    val file =
      writeProduct(
        projectDir,
        previewId = "com.example.Foo",
        fileName = "resources-used.json",
        text =
          """{"references":[{"resourceType":"string","resourceName":"title","packageName":"app","consumers":[]}]}""",
      )

    val product =
      assertNotNull(
        findDataProduct(
          PreviewModule("app", projectDir),
          previewId = "com.example.Foo",
          kind = "resources/used",
        )
      )
    val response = buildDataGetResponse(product)
    val encoded = json.encodeToString(DataGetResponse.serializer(), response)
    val parsed = json.decodeFromString(DataGetResponse.serializer(), encoded)

    assertEquals(DATA_GET_SCHEMA, parsed.schema)
    assertEquals(file.absolutePath, parsed.path)
    assertEquals("resources/used", parsed.kind)
    assertEquals(
      "title",
      parsed.payload!!
        .jsonObject["references"]!!
        .jsonArray
        .single()
        .jsonObject["resourceName"]!!
        .jsonPrimitive
        .content,
    )
  }

  @Test
  fun `unknown JSON filenames follow slash dash convention`() {
    val projectDir = tempModule()
    writeProduct(
      projectDir,
      previewId = "P",
      fileName = "custom-kind.json",
      text = """{"ok":true}""",
    )

    val module = scanDataProducts("app", projectDir)

    assertEquals("custom/kind", module.products.single().kind)
  }

  @Test
  fun `manifest data products are discoverable by kind and preview id`() {
    val projectDir = tempModule()
    val file = projectDir.resolve("build/compose-previews/data/render-scroll-long/P.png")
    file.parentFile.mkdirs()
    file.writeBytes(byteArrayOf(1, 2, 3))
    projectDir
      .resolve("build/compose-previews/previews.json")
      .writeText(
        """
        {
          "module": "app",
          "variant": "debug",
          "previews": [
            {
              "id": "P",
              "functionName": "P",
              "className": "Previews",
              "captures": [{"renderOutput": "renders/P.png"}],
              "dataProducts": [
                {"kind": "render/scroll/long", "output": "data/render-scroll-long/P.png"}
              ]
            }
          ]
        }
        """
          .trimIndent()
      )

    val summary = scanDataProducts("app", projectDir).products.single()
    val product = findDataProduct(PreviewModule("app", projectDir), "P", "render/scroll/long")

    assertEquals("render/scroll/long", summary.kind)
    assertEquals(listOf("P"), summary.previews)
    assertEquals(file.absolutePath, product!!.file.path)
  }

  private fun tempModule(): File = Files.createTempDirectory("data-command-test").toFile()

  private fun writeProduct(
    projectDir: File,
    previewId: String,
    fileName: String,
    text: String,
  ): File {
    val file = projectDir.resolve("build/compose-previews/data/$previewId/$fileName")
    file.parentFile.mkdirs()
    file.writeText(text)
    return file
  }
}
