package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceCommandsTest {
  @Test
  fun `resource result is changed when any capture changed`() {
    val unchanged = ResourceCaptureResult(changed = false)
    val changed = ResourceCaptureResult(changed = true)

    assertFalse(ResourcePreviewResult("id", ":app", "drawable").anyChanged())
    assertFalse(
      ResourcePreviewResult("id", ":app", "drawable", captures = listOf(unchanged)).anyChanged()
    )
    assertTrue(
      ResourcePreviewResult("id", ":app", "drawable", captures = listOf(unchanged, changed))
        .anyChanged()
    )
  }

  @Test
  fun `show resources command accepts the resource-specific filter surface`() {
    ShowResourcesCommand(
      listOf("--json", "--changed-only", "--module", ":app", "--id", "drawable/icon")
    )
  }
}
