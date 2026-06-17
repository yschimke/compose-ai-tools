@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package ee.schimke.composeai.daemon

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the resolved design-token extraction (issue #1897) against a real desktop
 * [ImageComposeScene]: `Modifier.background` → container colour, the background/clip shape → corner
 * radius, and `Modifier.padding` → per-edge insets. These are the tokens design-parity's
 * token-compliance check compares against — before this the desktop sidecar carried only the text
 * foreground colour + bounds, so colour/spacing/radius tokens degraded to "missing from candidate".
 */
class DesktopSemanticsTokensTest {

  private fun buildTree(
    density: Float = 1.0f,
    content: @Composable () -> Unit,
  ): ComposeSemanticsNode {
    val scene =
      ImageComposeScene(width = 400, height = 400, density = Density(density), content = content)
    try {
      scene.render()
      val root: SemanticsNode = scene.semanticsOwners.first().unmergedRootSemanticsNode
      return ComposeSemanticsDataProducer.buildPayload(root, density).root
    } finally {
      scene.close()
    }
  }

  private fun ComposeSemanticsNode.find(tag: String): ComposeSemanticsNode? {
    if (testTag == tag) return this
    return children.firstNotNullOfOrNull { it.find(tag) }
  }

  @Test
  fun resolves_background_colour_corner_radius_and_uniform_padding() {
    val root = buildTree {
      Box(
        Modifier.testTag("card")
          .background(Color(0xFF006A60), RoundedCornerShape(12.dp))
          .padding(16.dp)
      ) {
        Text("Card body")
      }
    }

    val card = root.find("card")
    assertNotNull("expected a node tagged 'card'", card)
    val tokens = card!!.tokens
    assertNotNull("card must carry resolved tokens", tokens)
    assertEquals("#FF006A60", tokens!!.backgroundColor)
    assertEquals("12.0dp", tokens.cornerRadius)
    assertEquals("16.0dp", tokens.padding?.start)
    assertEquals("16.0dp", tokens.padding?.top)
    assertEquals("16.0dp", tokens.padding?.end)
    assertEquals("16.0dp", tokens.padding?.bottom)
  }

  @Test
  fun resolves_per_edge_padding() {
    val root = buildTree {
      Box(Modifier.testTag("row").padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 4.dp))
    }

    val padding = root.find("row")?.tokens?.padding
    assertNotNull("row must carry resolved padding", padding)
    assertEquals("8.0dp", padding!!.start)
    assertEquals("4.0dp", padding.top)
    assertEquals("8.0dp", padding.end)
    assertEquals("4.0dp", padding.bottom)
  }

  @Test
  fun pixel_corner_radius_is_not_reported_as_dp() {
    // `RoundedCornerShape(Float)` is a pixel corner (PxCornerSize); it can't be expressed as a
    // fixed dp radius, so cornerRadius must stay null rather than mislabel pixels as dp (#1901).
    val root = buildTree {
      Box(Modifier.testTag("px").background(Color(0xFF006A60), RoundedCornerShape(12f)))
    }

    val tokens = root.find("px")?.tokens
    assertNotNull("background colour should still resolve", tokens)
    assertEquals("#FF006A60", tokens!!.backgroundColor)
    assertNull("pixel corner radius must not be emitted as dp", tokens.cornerRadius)
  }

  @Test
  fun node_without_container_tokens_emits_null() {
    // Plain text carries text-layout fields (layoutForegroundColor etc.) but no container tokens.
    val root = buildTree { Text("just text", modifier = Modifier.testTag("label")) }

    val label = root.find("label")
    assertNotNull(label)
    assertNull("a node with no background / shape / padding must omit tokens", label!!.tokens)
  }

  @Test
  fun resolves_row_arrangement_spacing_as_gap() {
    // #1908 ask 1: `Arrangement.spacedBy` is a measure-policy property, not a `Modifier.padding`;
    // it must surface as `gap` so spacing tokens (`cardGap` / `rowGap`) evaluate.
    val root = buildTree {
      Row(Modifier.testTag("row"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("a")
        Text("b")
      }
    }

    assertEquals("8.0dp", root.find("row")?.tokens?.gap)
  }

  @Test
  fun resolves_column_arrangement_spacing_as_gap() {
    val root = buildTree {
      Column(Modifier.testTag("col"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("a")
        Text("b")
      }
    }

    assertEquals("12.0dp", root.find("col")?.tokens?.gap)
  }

  @Test
  fun layout_without_arrangement_spacing_omits_gap() {
    val root = buildTree {
      Column(Modifier.testTag("col")) {
        Text("a")
        Text("b")
      }
    }

    assertNull("a layout with no arrangement spacing must omit gap", root.find("col")?.tokens?.gap)
  }

  @Test
  fun resolves_circle_shape_descriptor_and_effective_radius() {
    // #1908 ask 3: `CircleShape` is a percent (`CornerSize(50%)`) shape — no dp corner — so the
    // radius was silently dropped. It now emits a `"circle"` descriptor and the effective dp radius
    // resolved against the node's measured size (36dp square → 18dp).
    val root = buildTree {
      Box(Modifier.testTag("avatar").size(36.dp).clip(CircleShape).background(Color(0xFF006A60)))
    }

    val tokens = root.find("avatar")?.tokens
    assertNotNull("avatar must carry resolved tokens", tokens)
    assertEquals("circle", tokens!!.shape)
    assertEquals("18.0dp", tokens.cornerRadius)
  }

  @Test
  fun resolves_circle_radius_against_render_density() {
    // The percent → dp resolution must divide out the render density: a 36dp circle at density 2.5
    // measures 90px, whose 50% corner (45px) is still 18dp.
    val root =
      buildTree(density = 2.5f) { Box(Modifier.testTag("avatar").size(36.dp).clip(CircleShape)) }

    assertEquals("18.0dp", root.find("avatar")?.tokens?.cornerRadius)
  }

  @Test
  fun resolves_text_typography_identity() {
    // #1934: a text node must surface *which face* it's drawn in — family, weight, style — plus
    // letter spacing and line height, not just `layoutFontSize`. `FontFamily.Monospace` is a
    // GenericFontFamily, so its stable declared name (`"monospace"`) is what's emitted.
    val root = buildTree {
      Text(
        "Heading",
        modifier = Modifier.testTag("h"),
        style =
          TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontStyle = FontStyle.Italic,
            letterSpacing = 0.5.sp,
            lineHeight = 24.sp,
          ),
      )
    }

    val node = root.find("h")
    assertNotNull("expected a node tagged 'h'", node)
    assertEquals("monospace", node!!.layoutFontFamily)
    assertEquals(700, node.layoutFontWeight)
    assertEquals("italic", node.layoutFontStyle)
    assertEquals("0.5sp", node.layoutLetterSpacing)
    assertEquals("24.0sp", node.layoutLineHeight)
  }

  @Test
  fun mixed_span_weights_omit_the_ambiguous_value() {
    // #1934 (review): per-range typography lives in `AnnotatedString.spanStyles`. When the ranges
    // disagree — a normal run and a bold run — the node isn't uniform, so the weight must be
    // omitted
    // rather than reporting the paragraph style as if it were the whole node.
    val root = buildTree {
      Text(
        buildAnnotatedString {
          withStyle(SpanStyle(fontWeight = FontWeight.Normal)) { append("normal ") }
          withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("bold") }
        },
        modifier = Modifier.testTag("mixed"),
      )
    }

    val node = root.find("mixed")
    assertNotNull(node)
    assertNull("disagreeing span weights must omit the weight", node!!.layoutFontWeight)
  }

  @Test
  fun span_only_typography_is_captured() {
    // The other half of the review: when the whole run's weight comes from a span (not the
    // paragraph
    // style), it must still be read — folding span styles in is what surfaces it.
    val root = buildTree {
      Text(
        buildAnnotatedString {
          withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("all bold") }
        },
        modifier = Modifier.testTag("span"),
      )
    }

    assertEquals(700, root.find("span")?.layoutFontWeight)
  }

  @Test
  fun text_omits_typographic_identity_when_inherited() {
    // Plain text with no explicit typography inherits an empty TextStyle — the identity fields must
    // stay null rather than emit defaults, so the projection carries signal only (#1934).
    val root = buildTree { Text("plain", modifier = Modifier.testTag("p")) }

    val node = root.find("p")
    assertNotNull(node)
    assertNull("inherited family must be omitted", node!!.layoutFontFamily)
    assertNull("inherited weight must be omitted", node.layoutFontWeight)
    assertNull("inherited style must be omitted", node.layoutFontStyle)
    assertNull("inherited letter spacing must be omitted", node.layoutLetterSpacing)
  }

  @Test
  fun resolves_border_outline_colour() {
    // #1908 ask 2: outline / role colours come from `Modifier.border`, not `Modifier.background`,
    // so `backgroundColor` never saw them. They now surface as `borderColor`.
    val root = buildTree {
      Box(
        Modifier.testTag("chip")
          .border(BorderStroke(1.dp, Color(0xFFCAC4D0)), CircleShape)
          .size(24.dp)
      )
    }

    val tokens = root.find("chip")?.tokens
    assertNotNull("chip must carry resolved tokens", tokens)
    assertEquals("#FFCAC4D0", tokens!!.borderColor)
  }
}
