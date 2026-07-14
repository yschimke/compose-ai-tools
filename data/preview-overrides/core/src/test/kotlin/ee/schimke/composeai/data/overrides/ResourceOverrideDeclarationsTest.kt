package ee.schimke.composeai.data.overrides

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceOverrideDeclarationsTest {

  private fun explicit(key: String, default: String) =
    PreviewOverrideDeclaration(
      key = key,
      type = PreviewOverrideType.STRING,
      default = PreviewOverrideValue.StringValue(default),
      current = PreviewOverrideValue.StringValue(default),
    )

  private fun resource(name: String, default: String) =
    PreviewOverrideDeclaration(
      key = "${RESOURCE_OVERRIDE_KEY_PREFIX}strings.cvr#$name",
      type = PreviewOverrideType.STRING,
      default = PreviewOverrideValue.StringValue(default),
      current = PreviewOverrideValue.StringValue(default),
    )

  @Test
  fun `resource key detection`() {
    assertTrue(resource("0", "Hi").isResourceOverride())
    assertFalse(explicit("title", "Hi").isResourceOverride())
    assertTrue(isResourceOverrideKey("res:foo#3"))
    assertFalse(isResourceOverrideKey("title"))
  }

  @Test
  fun `a resource knob duplicating an explicit knob's default is dropped`() {
    val explicit = explicit("title", "Shopping list")
    val duplicate = resource("0", "Shopping list")
    val distinct = resource("1", "Other text")

    val result = dedupeResourceOverrideDeclarations(listOf(explicit, duplicate, distinct))

    // Explicit knob kept, the resource knob shadowing it dropped, the distinct resource knob kept.
    assertEquals(listOf(explicit, distinct), result)
  }

  @Test
  fun `explicit knobs are never dropped even if two share a default`() {
    val a = explicit("a", "Same")
    val b = explicit("b", "Same")
    assertEquals(listOf(a, b), dedupeResourceOverrideDeclarations(listOf(a, b)))
  }

  @Test
  fun `nothing is dropped when there are no explicit string knobs`() {
    val r1 = resource("0", "One")
    val r2 = resource("1", "Two")
    assertEquals(listOf(r1, r2), dedupeResourceOverrideDeclarations(listOf(r1, r2)))
  }

  @Test
  fun `dedup matches on default not on current value`() {
    // The author default is what the two knobs share; a seeded `current` on either must not affect
    // the match (the explicit knob's authored text is still what the resource lookup returned).
    val explicit =
      PreviewOverrideDeclaration(
        key = "title",
        type = PreviewOverrideType.STRING,
        default = PreviewOverrideValue.StringValue("Hello"),
        current = PreviewOverrideValue.StringValue("Edited"),
      )
    val duplicate = resource("0", "Hello")
    assertEquals(listOf(explicit), dedupeResourceOverrideDeclarations(listOf(explicit, duplicate)))
  }
}
