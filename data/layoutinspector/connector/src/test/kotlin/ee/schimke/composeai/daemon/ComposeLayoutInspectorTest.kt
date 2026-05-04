package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeLayoutInspectorTest {
  @Test
  fun inspectsLayoutTreeThroughDomainApi() {
    val child = LayoutNodeLike(id = 7, width = 12, height = 8)
    val root = LayoutNodeLike(id = 42, width = 100, height = 50, children = listOf(child))
    val semanticsRoot = SemanticsNodeLike(layoutNode = root)

    val node = ComposeLayoutInspector.inspect(semanticsRoot, slotTables = emptyList())

    requireNotNull(node)
    assertEquals("42", node.nodeId)
    assertEquals("LayoutNodeLike", node.component)
    assertEquals(100, node.size.width)
    assertEquals(50, node.size.height)
    assertTrue(node.placed)
    assertTrue(node.attached)
    assertEquals(listOf("7"), node.children.map { it.nodeId })
  }

  @Test
  fun supportsLayoutInfoFallbackThroughDomainApi() {
    val layout = LayoutNodeLike(id = 9, width = 24, height = 16)
    val semanticsRoot = SemanticsInfoLike(layoutInfo = layout)

    val node = ComposeLayoutInspector.inspect(semanticsRoot, slotTables = emptyList())

    assertEquals("9", node?.nodeId)
  }

  @Test
  fun returnsNullWhenNoLayoutRootIsAvailable() {
    val node = ComposeLayoutInspector.inspect(Any(), slotTables = emptyList())

    assertNull(node)
  }

  @Suppress("unused")
  private class SemanticsNodeLike(private val layoutNode: LayoutNodeLike) {
    fun `getLayoutNode$ui_release`(): LayoutNodeLike = layoutNode
  }

  @Suppress("unused")
  private class SemanticsInfoLike(private val layoutInfo: LayoutNodeLike) {
    fun getLayoutInfo(): LayoutNodeLike = layoutInfo
  }

  @Suppress("unused")
  private class LayoutNodeLike(
    private val id: Int,
    private val width: Int,
    private val height: Int,
    private val children: List<LayoutNodeLike> = emptyList(),
  ) {
    fun getSemanticsId(): Int = id

    fun getWidth(): Int = width

    fun getHeight(): Int = height

    fun isPlaced(): Boolean = true

    fun isAttached(): Boolean = true

    fun getZSortedChildren(): List<LayoutNodeLike> = children
  }
}
