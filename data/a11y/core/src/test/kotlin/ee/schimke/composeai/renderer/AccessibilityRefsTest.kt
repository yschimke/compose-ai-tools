package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccessibilityRefsTest {

  private fun node(
    label: String,
    role: String? = null,
    boundsInScreen: String = "0,0,1,1",
  ): AccessibilityNode =
    AccessibilityNode(label = label, role = role, boundsInScreen = boundsInScreen)

  @Test
  fun stampsRoleAnchoredRefDisambiguatedByIndex() {
    val refs =
      AccessibilityRefs.assign(
          listOf(
            node("Submit", role = "Button"),
            node("Cancel", role = "Button"),
            node("Heading", role = "TextView"),
          )
        )
        .map { it.ref }

    assertEquals(listOf("a/role:Button[0]", "a/role:Button[1]", "a/role:TextView[0]"), refs)
  }

  @Test
  fun rolelessNodesFallBackToGenericAnchor() {
    val refs = AccessibilityRefs.assign(listOf(node("one"), node("two"))).map { it.ref }
    assertEquals(listOf("a/node[0]", "a/node[1]"), refs)
  }

  @Test
  fun refIsContentIndependent_labelEditDoesNotMoveRef() {
    val before = AccessibilityRefs.assign(listOf(node("Submit", role = "Button")))
    val after = AccessibilityRefs.assign(listOf(node("SUBMIT NOW", role = "Button")))
    assertEquals(before.single().ref, after.single().ref)
  }

  @Test
  fun roleChangeMovesRef() {
    val asButton = AccessibilityRefs.assign(listOf(node("x", role = "Button"))).single().ref
    val asImage = AccessibilityRefs.assign(listOf(node("x", role = "Image"))).single().ref
    assertNotEquals(asButton, asImage)
  }

  @Test
  fun assignmentIsIdempotent() {
    val once = AccessibilityRefs.assign(listOf(node("a", role = "Button"), node("b")))
    val twice = AccessibilityRefs.assign(once)
    assertEquals(once.map { it.ref }, twice.map { it.ref })
  }

  @Test
  fun sanitizesAnchorCharactersThatCollideWithRefGrammar() {
    val ref = AccessibilityRefs.assign(listOf(node("x", role = "Tab [2]/main"))).single().ref
    assertEquals("a/role:Tab_2_main[0]", ref)
  }
}
