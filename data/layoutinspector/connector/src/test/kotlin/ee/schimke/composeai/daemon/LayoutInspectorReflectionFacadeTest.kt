package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutInspectorReflectionFacadeTest {
  @Test
  fun callsPrivateInheritedZeroArgMethods() {
    val node = DerivedLayoutNode()

    val value = LayoutInspectorReflectionFacade.call(node, "getWidth")

    assertEquals(42, value)
  }

  @Test
  fun returnsNullForMissingMethods() {
    val node = DerivedLayoutNode()

    val value = LayoutInspectorReflectionFacade.call(node, "missing")

    assertNull(value)
  }

  @Test
  fun resolvesLayoutNodeFallbackNames() {
    val primary = SemanticsNodeLike(layoutNode = "layout-node")
    val fallback = SemanticsInfoLike(layoutInfo = "layout-info")

    assertEquals("layout-node", LayoutInspectorReflectionFacade.layoutNodeOrNull(primary))
    assertEquals("layout-info", LayoutInspectorReflectionFacade.layoutNodeOrNull(fallback))
  }

  private open class BaseLayoutNode {
    @Suppress("unused") private fun getWidth(): Int = 42
  }

  private class DerivedLayoutNode : BaseLayoutNode()

  @Suppress("unused")
  private class SemanticsNodeLike(private val layoutNode: String) {
    fun `getLayoutNode$ui_release`(): String = layoutNode
  }

  @Suppress("unused")
  private class SemanticsInfoLike(private val layoutInfo: String) {
    fun getLayoutInfo(): String = layoutInfo
  }
}
