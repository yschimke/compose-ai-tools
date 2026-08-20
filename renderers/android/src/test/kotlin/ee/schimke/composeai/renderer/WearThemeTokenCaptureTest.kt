package ee.schimke.composeai.renderer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import ee.schimke.composeai.daemon.ComposeSemanticsDataProducer
import ee.schimke.composeai.daemon.ThemeConsumerCapture
import ee.schimke.composeai.daemon.themePayloadFromDuckTypedTheme
import ee.schimke.composeai.data.theme.ThemeConsumerAttribution
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the inspector can say about a **Wear** button's label — issue #4327.
 *
 * The viewer's typography layer drew Wear text as `15.0sp/18.0sp · 500` and nothing else: no
 * typeface, no Material role. Both halves of that are measurable here rather than by reasoning
 * about a rendered page, and both are Wear-specific, so a Material 3 test would keep passing
 * whatever happened to Wear:
 *
 * - **The typeface.** Wear Material 3 declares its ramp with `Font(DeviceFontFamilyName(…))`, the
 *   one `Font` shape that exposes neither a resource id nor a file identity — so the resolved-face
 *   lookup returned null and the annotation named no font at all.
 * - **The Material role.** Role attribution matches a node's resolved style against the theme's
 *   token table, and for a Wear render that table was empty: the daemon reads it through Material 3
 *   types a Wear app does not have on its classpath.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WearThemeTokenCaptureTest {

  @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

  private fun renderWearButton() {
    composeRule.setContent { MaterialTheme { Button(onClick = {}, label = { Text("Filled") }) } }
    composeRule.waitForIdle()
  }

  @Test
  fun `a Wear label reports the typeface its device family names`() {
    renderWearButton()
    val root = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
    val payload = ComposeSemanticsDataProducer.buildPayload(root, density = 1f)

    val families = mutableListOf<String>()
    fun walk(node: ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode) {
      node.typography?.fontFamily?.let(families::add)
      node.children.forEach(::walk)
    }
    walk(payload.root)

    // The catalog's design spec names this face `Roboto Flex`; reporting the raw `roboto-flex` slug
    // would read as a different family from the very spec it is compared against, so the producer
    // maps it through the same table the renderer seeds the system font map from.
    assertTrue(
      "expected a text node naming its typeface; got $families",
      families.contains("Roboto Flex"),
    )
  }

  @Test
  fun `Wear's own type ramp is readable without Material 3`() {
    var payload: ee.schimke.composeai.data.theme.ThemePayload? = null
    composeRule.setContent {
      MaterialTheme {
        // Exactly what the daemon composes for a Wear render: the theme handles read reflectively
        // through the provider's own loader, then turned into tokens without naming a Material 3
        // type — a Wear app has none to name.
        val loader = WearThemeTokenCaptureTest::class.java.classLoader
        val colors = WearMaterialTheme.colorSchemeOrNull(loader)
        val typography = WearMaterialTheme.typographyOrNull(loader)
        val shapes = WearMaterialTheme.shapesOrNull(loader)
        payload = themePayloadFromDuckTypedTheme(colors, typography, shapes)
        Button(onClick = {}, label = { Text("Filled") })
      }
    }
    composeRule.waitForIdle()

    val captured = payload
    assertNotNull("expected a theme payload read from Wear's MaterialTheme", captured)
    val typography = captured!!.resolvedTokens.typography
    assertTrue(
      "expected Wear type-scale roles; got ${typography.keys}",
      typography.keys.any { it.startsWith("label") } &&
        typography.keys.any { it.startsWith("body") },
    )
    assertTrue(
      "expected Wear colour roles; got ${captured.resolvedTokens.colorScheme.keys}",
      captured.resolvedTokens.colorScheme.containsKey("primary"),
    )

    // The whole point of the token table: a rendered node can be named by the role it read.
    val root = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
    val consumers =
      ThemeConsumerAttribution.attribute(
        ThemeConsumerCapture.extractFacts(root),
        captured.resolvedTokens,
      )
    assertTrue(
      "expected the button's label attributed to a type-scale role; got $consumers",
      consumers.any { consumer -> consumer.tokens.any { it.startsWith("label") } },
    )
  }
}
