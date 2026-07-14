package ee.schimke.composeai.overrides

import ee.schimke.composeai.data.overrides.PreviewOverrideType
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import ee.schimke.composeai.data.overrides.isResourceOverride
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the resource-string half of the controller: [PreviewOverrideController.resolveResourceString]
 * (record + seed), the separate resource-declaration bucket surviving the explicit-knob
 * [PreviewOverrideController.clearDeclarations], and the merge + dedupe in
 * [PreviewOverrideController.declarations].
 */
class ResourceOverrideControllerTest {

  @Before fun reset() = PreviewOverrideController.resetForNewSession()

  @After fun tearDown() = PreviewOverrideController.resetForNewSession()

  @Test
  fun `resolveResourceString returns the default and records a resource knob when unseeded`() {
    val effective = PreviewOverrideController.resolveResourceString("res:strings.cvr#0", "Hello")
    assertEquals("Hello", effective)

    val declarations = PreviewOverrideController.declarations()
    assertEquals(1, declarations.size)
    val declaration = declarations.single()
    assertTrue(declaration.isResourceOverride())
    assertEquals("res:strings.cvr#0", declaration.key)
    assertEquals(PreviewOverrideValue.StringValue("Hello"), declaration.default)
    assertEquals(PreviewOverrideValue.StringValue("Hello"), declaration.current)
  }

  @Test
  fun `a seeded value replaces the default and rides current`() {
    PreviewOverrideController.set(
      mapOf("res:strings.cvr#0" to PreviewOverrideValue.StringValue("Bonjour"))
    )
    val effective = PreviewOverrideController.resolveResourceString("res:strings.cvr#0", "Hello")
    assertEquals("Bonjour", effective)
    assertEquals(
      PreviewOverrideValue.StringValue("Bonjour"),
      PreviewOverrideController.declarations().single().current,
    )
  }

  @Test
  fun `resource declarations survive the explicit-knob clearDeclarations`() {
    PreviewOverrideController.resolveResourceString("res:strings.cvr#0", "Hello")
    // The named-override extension calls this post-composition; it must not wipe resource knobs.
    PreviewOverrideController.clearDeclarations()
    assertEquals(1, PreviewOverrideController.declarations().size)

    // …but the per-render resource reset does drop them.
    PreviewOverrideController.resetResourceDeclarations()
    assertTrue(PreviewOverrideController.declarations().isEmpty())
  }

  @Test
  fun `declarations lists explicit knobs first then resource knobs, deduped`() {
    // Explicit knob whose default equals a resource string → the resource duplicate is dropped.
    PreviewOverrideController.record(
      declaration("title", "Shopping list")
    )
    PreviewOverrideController.resolveResourceString("res:strings.cvr#0", "Shopping list")
    PreviewOverrideController.resolveResourceString("res:strings.cvr#1", "Standalone label")

    val declarations = PreviewOverrideController.declarations()
    assertEquals(
      listOf("title", "res:strings.cvr#1"),
      declarations.map { it.key },
    )
  }

  private fun declaration(key: String, default: String) =
    ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration(
      key = key,
      type = PreviewOverrideType.STRING,
      default = PreviewOverrideValue.StringValue(default),
      current = PreviewOverrideValue.StringValue(default),
    )
}
