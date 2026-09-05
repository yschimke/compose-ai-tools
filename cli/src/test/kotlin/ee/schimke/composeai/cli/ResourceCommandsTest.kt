package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
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

  @Test
  fun `a skipped capture is tagged apart from a failed one`() {
    // Resource-side counterpart of issue #5174's `optional` captures: `skipped` is a known
    // degradation the renderer reports, not a render failure, so the row shouldn't read like one.
    val skipped = ResourceCaptureResult(errorStatus = "skipped", error = "adaptive icon")
    val failed = ResourceCaptureResult(errorStatus = "failed", error = "boom")
    val rendered = ResourceCaptureResult(pngPath = "/r/icon.png", changed = false)

    assertEquals(" [no PNG, skipped]", resourceCaptureStatusTag(skipped))
    assertEquals(" [no PNG]", resourceCaptureStatusTag(failed))
    assertEquals("", resourceCaptureStatusTag(rendered))
    assertEquals(" [changed]", resourceCaptureStatusTag(rendered.copy(changed = true)))

    assertEquals(
      " [no PNG, skipped]",
      resourceStatusTag(
        ResourcePreviewResult("id", ":app", "drawable", captures = listOf(skipped))
      ),
    )
    assertEquals(
      " [no PNG]",
      resourceStatusTag(
        ResourcePreviewResult("id", ":app", "drawable", captures = listOf(skipped, failed))
      ),
    )
    assertEquals(
      " [changed]",
      resourceStatusTag(
        ResourcePreviewResult(
          "id",
          ":app",
          "drawable",
          captures = listOf(rendered.copy(changed = true)),
          pngPath = rendered.pngPath,
        )
      ),
    )
  }
}
