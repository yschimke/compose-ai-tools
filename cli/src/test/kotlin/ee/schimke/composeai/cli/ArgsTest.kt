package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals

class ArgsTest {
  @Test
  fun `flagValue accepts space and equals forms`() {
    assertEquals("/repo", listOf("--project", "/repo").flagValue("--project"))
    assertEquals("/repo", listOf("--project=/repo").flagValue("--project"))
  }

  @Test
  fun `flagValuesAll preserves repeated space and equals forms`() {
    assertEquals(
      listOf(":app", ":wear"),
      listOf("--module", ":app", "--module=:wear").flagValuesAll("--module"),
    )
  }
}
