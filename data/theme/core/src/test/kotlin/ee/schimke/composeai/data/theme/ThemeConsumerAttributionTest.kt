package ee.schimke.composeai.data.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeConsumerAttributionTest {
  private val colorScheme =
    linkedMapOf(
      "primary" to "#FF6750A4",
      "onPrimary" to "#FFFFFFFF",
      "onError" to "#FFFFFFFF",
      "error" to "#FFB3261E",
      "surface" to "#FFFEF7FF",
      "onSurface" to "#FF1D1B20",
      "onBackground" to "#FF1D1B20",
    )

  private val titleMedium =
    TypographyToken(
      fontFamily = "FontFamily.SansSerif",
      fontSize = 16f,
      fontSizeUnit = "Sp",
      fontWeight = "FontWeight(weight=500)",
      lineHeight = 24f,
      lineHeightUnit = "Sp",
      letterSpacing = 0.2f,
      letterSpacingUnit = "Sp",
    )

  private val bodyLarge =
    titleMedium.copy(fontWeight = "FontWeight(weight=400)", letterSpacing = 0.5f)

  // titleSmall and labelLarge share every metric in default M3 — a genuine typography collision.
  private val titleSmall =
    TypographyToken(
      fontSize = 14f,
      fontSizeUnit = "Sp",
      fontWeight = "FontWeight(weight=500)",
      lineHeight = 20f,
      lineHeightUnit = "Sp",
      letterSpacing = 0.1f,
      letterSpacingUnit = "Sp",
    )
  private val labelLarge = titleSmall.copy()

  private val typography =
    linkedMapOf(
      "titleMedium" to titleMedium,
      "bodyLarge" to bodyLarge,
      "titleSmall" to titleSmall,
      "labelLarge" to labelLarge,
    )

  private val resolved =
    ResolvedThemeTokens(colorScheme = colorScheme, typography = typography, shapes = emptyMap())

  private fun tokensFor(nodeId: String, nodes: List<NodeThemeFacts>): List<String> =
    ThemeConsumerAttribution.attribute(nodes, resolved).single { it.nodeId == nodeId }.tokens

  @Test
  fun `attributes unique colour and exact typography to one node`() {
    val node =
      NodeThemeFacts(
        nodeId = "7",
        foregroundColor = "#FFB3261E",
        // Resolved styles drop fontFamily to null; attribution must still match the token.
        textStyle = titleMedium.copy(fontFamily = null),
      )

    assertEquals(
      ThemeConsumer(nodeId = "7", tokens = listOf("error", "titleMedium")),
      ThemeConsumerAttribution.attribute(listOf(node), resolved).single(),
    )
  }

  @Test
  fun `ambiguous foreground colour emits every candidate role`() {
    val tokens = tokensFor("3", listOf(NodeThemeFacts(nodeId = "3", foregroundColor = "#FFFFFFFF")))

    assertEquals(setOf("onPrimary", "onError"), tokens.toSet())
  }

  @Test
  fun `known container background disambiguates the foreground to its on-role`() {
    val tokens =
      tokensFor(
        "9",
        listOf(
          NodeThemeFacts(
            nodeId = "9",
            foregroundColor = "#FFFFFFFF",
            backgroundColor = "#FF6750A4", // primary
          )
        ),
      )

    assertTrue("primary container should pin onPrimary", "onPrimary" in tokens)
    assertTrue("background role itself is attributed", "primary" in tokens)
    assertFalse("ambiguous onError must be dropped once disambiguated", "onError" in tokens)
  }

  @Test
  fun `typography matching ignores fontFamily noise`() {
    val tokens =
      tokensFor(
        "4",
        listOf(NodeThemeFacts(nodeId = "4", textStyle = titleMedium.copy(fontFamily = "Roboto"))),
      )

    assertEquals(listOf("titleMedium"), tokens)
  }

  @Test
  fun `colliding typography tokens are all reported`() {
    val tokens = tokensFor("5", listOf(NodeThemeFacts(nodeId = "5", textStyle = titleSmall.copy())))

    assertEquals(setOf("titleSmall", "labelLarge"), tokens.toSet())
  }

  @Test
  fun `nodes that read no theme token are dropped`() {
    val nodes =
      listOf(
        NodeThemeFacts(nodeId = "1", foregroundColor = "#FF010203"),
        NodeThemeFacts(nodeId = "2", textStyle = titleMedium.copy(fontSize = 99f)),
        NodeThemeFacts(nodeId = "3"),
      )

    assertEquals(emptyList<ThemeConsumer>(), ThemeConsumerAttribution.attribute(nodes, resolved))
  }
}
