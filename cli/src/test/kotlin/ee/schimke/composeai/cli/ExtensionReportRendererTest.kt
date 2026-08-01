package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class ExtensionReportRendererTest {
  @Test
  fun `built in registry exposes fresh stateful renderers`() {
    val reporters = builtInExtensionReporters()

    assertEquals(setOf("a11y"), reporters.keys)
    val first: ExtensionReportRenderer = reporters.getValue("a11y")()
    val second = reporters.getValue("a11y")()
    assertEquals("a11y", first.id)
    assertNotSame(first, second)
  }
}
