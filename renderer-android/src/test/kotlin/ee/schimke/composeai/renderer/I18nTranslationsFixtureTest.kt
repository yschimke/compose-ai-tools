package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.daemon.DataProductRegistry
import ee.schimke.composeai.daemon.I18nTranslationsDataProducer
import ee.schimke.composeai.daemon.I18nTranslationsDataProductRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Drives `I18nTranslationsDataProducer.writeArtifacts` + `I18nTranslationsDataProductRegistry`
 * against a Compose `Text` rendered with a known string, paired with a fixture `strings.xml`
 * catalog (`values/` + `values-fr/`). Asserts the producer:
 *   - reverse-matches the rendered text back to its `R.string.*` resource via the catalog,
 *   - lists every supported locale found in the catalog,
 *   - reports `renderedLocale` straight through from the call site,
 *   - surfaces the per-locale translations on the matched entry.
 *
 * Companion to `TextStringsTruncationTest` and `ComposeSemanticsCoreFieldsTest` — same
 * fixture-backed pattern for a different `text/strings`-adjacent producer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class I18nTranslationsFixtureTest {

  @Suppress("DEPRECATION")
  @get:Rule
  val composeRule = createAndroidComposeRule<ComponentActivity>()

  private lateinit var rootDir: File
  private lateinit var resDir: File

  @Before
  fun setUp() {
    rootDir = Files.createTempDirectory("i18n-translations-fixture").toFile()
    resDir = Files.createTempDirectory("i18n-res-fixture").toFile()
    writeStrings("values", "greeting" to "Hello")
    writeStrings("values-fr", "greeting" to "Bonjour")
  }

  @After
  fun tearDown() {
    rootDir.deleteRecursively()
    resDir.deleteRecursively()
  }

  @Test
  fun `English render resolves rendered text to R_string and lists fr as a translation`() {
    val payload = payloadFor(previewId = "english", renderedLocale = "en") {
      Box(modifier = Modifier.size(200.dp, 32.dp).background(Color.White)) {
        Text(text = "Hello")
      }
    }

    assertEquals("en", payload["renderedLocale"]!!.jsonPrimitive.content)
    assertEquals("en", payload["defaultLocale"]!!.jsonPrimitive.content)
    val supportedLocales =
      payload["supportedLocales"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
    assertEquals(setOf("en", "fr"), supportedLocales)

    val entry =
      payload["strings"]!!.jsonArray.map { it.jsonObject }.firstOrNull {
        it["rendered"]?.jsonPrimitive?.content == "Hello"
      } ?: error("expected a string entry for rendered=Hello; got $payload")
    assertEquals("R.string.greeting", entry["resourceName"]!!.jsonPrimitive.content)
    val translations = entry["translations"]!!.jsonObject
    assertEquals("Hello", translations["en"]!!.jsonPrimitive.content)
    assertEquals("Bonjour", translations["fr"]!!.jsonPrimitive.content)
  }

  @Test
  fun `French render reports renderedLocale=fr-FR and resolves the French value back to R_string`() {
    val payload = payloadFor(previewId = "french", renderedLocale = "fr-FR") {
      Box(modifier = Modifier.size(200.dp, 32.dp).background(Color.White)) {
        Text(text = "Bonjour")
      }
    }

    assertEquals("fr-FR", payload["renderedLocale"]!!.jsonPrimitive.content)
    val entry =
      payload["strings"]!!.jsonArray.map { it.jsonObject }.firstOrNull {
        it["rendered"]?.jsonPrimitive?.content == "Bonjour"
      } ?: error("expected a string entry for rendered=Bonjour; got $payload")
    assertEquals("R.string.greeting", entry["resourceName"]!!.jsonPrimitive.content)
  }

  @Test
  fun `Untranslated rendered text leaves resourceName null and the entry unmatched`() {
    val payload = payloadFor(previewId = "unmatched", renderedLocale = "en") {
      Box(modifier = Modifier.size(200.dp, 32.dp).background(Color.White)) {
        Text(text = "Untranslated literal")
      }
    }

    val entry =
      payload["strings"]!!.jsonArray.map { it.jsonObject }.firstOrNull {
        it["rendered"]?.jsonPrimitive?.content == "Untranslated literal"
      } ?: error("expected a string entry for rendered=Untranslated literal; got $payload")
    assertEquals(null, entry["resourceName"]?.jsonPrimitive?.content)
    assertEquals(null, entry["translations"]?.jsonObject?.get("en")?.jsonPrimitive?.content)
  }

  private fun payloadFor(
    previewId: String,
    renderedLocale: String,
    content: @Composable () -> Unit,
  ): JsonObject {
    composeRule.setContent { content() }
    composeRule.waitForIdle()
    val semanticsRoot = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
    I18nTranslationsDataProducer.writeArtifacts(
      rootDir = rootDir,
      previewId = previewId,
      root = semanticsRoot,
      renderedLocale = renderedLocale,
      resDirs = listOf(resDir),
      defaultLocale = "en",
    )
    val registry = I18nTranslationsDataProductRegistry(rootDir)
    val outcome =
      registry.fetch(
        previewId = previewId,
        kind = I18nTranslationsDataProducer.KIND,
        params = null,
        inline = true,
      )
    assertTrue("registry should produce a payload", outcome is DataProductRegistry.Outcome.Ok)
    val payload = (outcome as DataProductRegistry.Outcome.Ok).result.payload
    assertNotNull(payload)
    return payload!!.jsonObject
  }

  private fun writeStrings(valuesDirName: String, vararg entries: Pair<String, String>) {
    val valuesDir = resDir.resolve(valuesDirName).also { it.mkdirs() }
    val xml =
      buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
        for ((name, value) in entries) {
          append("  <string name=\"$name\">$value</string>\n")
        }
        append("</resources>\n")
      }
    valuesDir.resolve("strings.xml").writeText(xml)
  }
}
