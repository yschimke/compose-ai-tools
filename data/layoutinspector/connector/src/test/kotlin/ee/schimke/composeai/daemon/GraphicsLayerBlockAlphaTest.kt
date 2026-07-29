package ee.schimke.composeai.daemon

import androidx.compose.ui.graphics.GraphicsLayerScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #2853: the **lambda** form of `graphicsLayer` hides its alpha inside an opaque block.
 *
 * `graphicsLayer(alpha = 0f)` keeps `alpha` as a field, so the previous synthetic fixtures passed
 * while Jetchat's `RecordButton` — which writes `graphicsLayer { alpha = containerAlpha.value }` —
 * kept exporting an opaque circle its PNG doesn't show. The coordinator fallback that was supposed
 * to cover the lambda case reads a *shared static* scope belonging to whichever layer Compose
 * updated last, so it answered with some other node's alpha.
 *
 * These drive the block evaluator directly with real `GraphicsLayerScope` lambdas, which is the
 * shape the app code actually uses.
 */
class GraphicsLayerBlockAlphaTest {

  /** Stands in for a lambda-form `graphicsLayer` element: it holds only the block. */
  private class BlockLayerElement(@JvmField val block: GraphicsLayerScope.() -> Unit)

  /** …and for the named-parameter form, which carries a real `alpha` field. */
  @Suppress("unused") private class FieldLayerElement(@JvmField val alpha: Float)

  @Test
  fun `a zero alpha assigned inside the block is recovered`() {
    val element = BlockLayerElement { alpha = 0f }
    assertEquals(0f, ModifierTokenResolver.evaluateLayerBlockAlpha(element))
  }

  @Test
  fun `a partial alpha assigned alongside other layer properties is recovered`() {
    // The real RecordButton shape: alpha and scale set together from animation state.
    val containerAlpha = 0.35f
    val scale = 0.8f
    val element = BlockLayerElement {
      alpha = containerAlpha
      scaleX = scale
      scaleY = scale
    }
    assertEquals(0.35f, ModifierTokenResolver.evaluateLayerBlockAlpha(element))
  }

  @Test
  fun `a block that sets no alpha reports none rather than inventing one`() {
    val element = BlockLayerElement { scaleX = 0.5f }
    assertNull(ModifierTokenResolver.evaluateLayerBlockAlpha(element))
  }

  @Test
  fun `a block that reads a property back mid-evaluation still yields its alpha`() {
    // The proxy answers getters with whatever was already assigned, so a block that computes from
    // its own earlier writes evaluates instead of throwing.
    val element = BlockLayerElement {
      scaleX = 0.5f
      alpha = scaleX
    }
    assertEquals(0.5f, ModifierTokenResolver.evaluateLayerBlockAlpha(element))
  }

  @Test
  fun `a relative assignment starts from Compose's default alpha, not zero`() {
    // `alpha *= fade` reads alpha before writing it. Answering 0 there would record 0 for every
    // non-zero fade and blank the node — the same class of bug this evaluator exists to fix.
    val fade = 0.4f
    val element = BlockLayerElement { alpha *= fade }
    assertEquals(0.4f, ModifierTokenResolver.evaluateLayerBlockAlpha(element))
  }

  @Test
  fun `scale defaults to unity when read before assignment`() {
    val element = BlockLayerElement { alpha = scaleX }
    assertEquals(1f, ModifierTokenResolver.evaluateLayerBlockAlpha(element))
  }

  @Test
  fun `an element with no block is left to the field path`() {
    assertNull(ModifierTokenResolver.evaluateLayerBlockAlpha(FieldLayerElement(0f)))
  }
}
