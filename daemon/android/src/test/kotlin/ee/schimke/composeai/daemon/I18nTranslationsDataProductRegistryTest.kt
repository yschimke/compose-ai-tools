package ee.schimke.composeai.daemon

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

class I18nTranslationsDataProductRegistryTest {
  private lateinit var rootDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("i18n-translations-product-test").toFile()
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
  }

  @Test
  fun `capability advertises i18n translations as path transport`() {
    val registry = I18nTranslationsDataProductRegistry(rootDir)
    val cap = registry.capabilities.single()
    assertEquals("i18n/translations", cap.kind)
    assertEquals(1, cap.schemaVersion)
    assertTrue(cap.attachable)
    assertTrue(cap.fetchable)
    assertTrue(!cap.requiresRerender)
  }

  @Test
  fun `fetch returns path by default and payload when inline requested`() {
    val previewId = "com.example.I18nPreview"
    val file =
      rootDir
        .resolve(previewId)
        .also { it.mkdirs() }
        .resolve(I18nTranslationsDataProducer.FILE)
    file.writeText(
      """{"supportedLocales":["en","de"],"renderedLocale":"en","defaultLocale":"en","strings":[{"nodeId":"1","boundsInScreen":"0,0,10,10","resourceName":"R.string.sign_in","rendered":"Sign in","translations":{"en":"Sign in","de":"Anmelden"}}]}"""
    )
    val registry = I18nTranslationsDataProductRegistry(rootDir)

    val pathOutcome =
      registry.fetch(
        previewId = previewId,
        kind = "i18n/translations",
        params = null,
        inline = false,
      )
    assertTrue(pathOutcome is DataProductRegistry.Outcome.Ok)
    val pathResult = (pathOutcome as DataProductRegistry.Outcome.Ok).result
    assertEquals(file.absolutePath, pathResult.path)
    assertNull(pathResult.payload)

    val inlineOutcome =
      registry.fetch(
        previewId = previewId,
        kind = "i18n/translations",
        params = null,
        inline = true,
      )
    assertTrue(inlineOutcome is DataProductRegistry.Outcome.Ok)
    val inlineResult = (inlineOutcome as DataProductRegistry.Outcome.Ok).result
    assertNull(inlineResult.path)
    assertNotNull(inlineResult.payload)
    assertEquals(
      "R.string.sign_in",
      inlineResult
        .payload!!
        .jsonObject["strings"]!!
        .jsonArray
        .single()
        .jsonObject["resourceName"]!!
        .jsonPrimitive
        .content,
    )
  }

  @Test
  fun `catalog parses locale directories and marks missing translations`() {
    val resDir = rootDir.resolve("res")
    writeStrings(
      resDir.resolve("values/strings.xml"),
      """<resources><string name="sign_in">Sign in</string><string name="settings">Settings</string></resources>""",
    )
    writeStrings(
      resDir.resolve("values-de/strings.xml"),
      """<resources><string name="sign_in">Anmelden</string></resources>""",
    )
    writeStrings(
      resDir.resolve("values-fr-rCA/strings.xml"),
      """<resources><string name="sign_in">Connexion</string></resources>""",
    )

    val catalog = AndroidStringCatalog.load(resDirs = listOf(resDir), defaultLocale = "en")
    assertEquals(listOf("en", "de", "fr-CA"), catalog.supportedLocales)

    val match = catalog.match("Anmelden", renderedLocale = "de")
    assertNotNull(match)
    assertEquals("R.string.sign_in", match!!.resourceName)
    assertEquals("Connexion", match.translations["fr-CA"])
    assertEquals(emptyList<String>(), match.untranslatedLocales)

    val fallback = catalog.match("Settings", renderedLocale = "de")
    assertNotNull(fallback)
    assertEquals("R.string.settings", fallback!!.resourceName)
    assertEquals(listOf("de", "fr-CA"), fallback.untranslatedLocales)
  }

  @Test
  fun `attachmentsFor emits path only after render wrote translations file`() {
    val previewId = "com.example.I18nPreview"
    val registry = I18nTranslationsDataProductRegistry(rootDir)
    assertEquals(emptyList<Any>(), registry.attachmentsFor(previewId, setOf("i18n/translations")))

    val file =
      rootDir
        .resolve(previewId)
        .also { it.mkdirs() }
        .resolve(I18nTranslationsDataProducer.FILE)
    file.writeText("""{"supportedLocales":["en"],"renderedLocale":"en","defaultLocale":"en","strings":[]}""")

    val attachment = registry.attachmentsFor(previewId, setOf("i18n/translations")).single()
    assertEquals("i18n/translations", attachment.kind)
    assertEquals(file.absolutePath, attachment.path)
    assertNull(attachment.payload)
  }

  private fun writeStrings(file: File, content: String) {
    file.parentFile?.mkdirs()
    file.writeText(content)
  }
}
