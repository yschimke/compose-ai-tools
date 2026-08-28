package ee.schimke.composeai.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM coverage of [findDefaultedComposableMethod] — the fallback that lets a preview whose own
 * parameters are all defaulted resolve at all.
 *
 * `getDeclaredComposableMethod(name)` can only match a parameterless composable, so
 * `@Preview @Composable fun Card(modifier: Modifier = Modifier)` — a component that is its own
 * preview, rendered happily by Studio — used to fail with a bare `NoSuchMethodException`.
 * Reflection over the *compiled* fixtures in `DefaultedComposableLookupFixtures.kt` is the only
 * honest way to assert the shape, so no Robolectric sandbox is involved.
 */
class DefaultedComposableLookupTest {

  private val fixtures =
    Class.forName("ee.schimke.composeai.renderer.DefaultedComposableLookupFixturesKt")

  @Test
  fun `finds a preview whose single parameter is defaulted`() {
    val method = findDefaultedComposableMethod(fixtures, "defaultedModifierPreviewFixture")
    assertNotNull("A defaulted @Composable must resolve for a no-argument invoke", method)
    assertEquals(1, method!!.parameterCount)
  }

  @Test
  fun `finds a preview with several defaulted parameters`() {
    val method = findDefaultedComposableMethod(fixtures, "multiDefaultPreviewFixture")
    assertNotNull(method)
    assertEquals(3, method!!.parameterCount)
  }

  @Test
  fun `skips a composable whose parameter is required`() {
    // Invoking it with nothing would pass a null `String` straight through as a real argument —
    // reporting the miss beats an NPE from inside the preview.
    assertNull(findDefaultedComposableMethod(fixtures, "requiredParameterFixture"))
  }

  @Test
  fun `skips a plain function that merely has defaults`() {
    assertNull(findDefaultedComposableMethod(fixtures, "plainFunctionFixture"))
  }

  @Test
  fun `skips a parameterless composable the supported lookup already handles`() {
    // No real parameters means no defaults mask; the primary
    // `getDeclaredComposableMethod` path resolves it, and the fallback stays out of the way.
    assertNull(findDefaultedComposableMethod(fixtures, "noParameterPreviewFixture"))
  }

  @Test
  fun `reports a miss for a name that is not there`() {
    assertNull(findDefaultedComposableMethod(fixtures, "noSuchPreviewFixture"))
  }
}
