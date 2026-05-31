package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the opt-in `--bundle` flag on `compose-preview render`: it must append each module's
 * `composePreviewBundle` task after its `composePreviewRenderAll` (so both run in one Gradle build)
 * and forward `-PbundleEmbedDeps=true` only when `--embed-deps` is also present. Default (no flag)
 * must stay render-only so the fast iterate loop pays no bundling cost.
 */
class RenderBundleFlagTest {
  private val twoModules = listOf("app", "feature:home")

  @Test
  fun `default render emits only renderAll tasks`() {
    val cmd = RenderCommand(listOf("render"))
    assertEquals(
      listOf(":app:composePreviewRenderAll", ":feature:home:composePreviewRenderAll"),
      cmd.previewTasksFor(twoModules),
    )
    assertEquals(emptyList(), cmd.bundleGradleArgs())
  }

  @Test
  fun `--bundle appends composePreviewBundle per module after its renderAll`() {
    val cmd = RenderCommand(listOf("render", "--bundle"))
    assertEquals(
      listOf(
        ":app:composePreviewRenderAll",
        ":app:composePreviewBundle",
        ":feature:home:composePreviewRenderAll",
        ":feature:home:composePreviewBundle",
      ),
      cmd.previewTasksFor(twoModules),
    )
    // --bundle alone keeps the default coordinates resolution (no embed property).
    assertEquals(emptyList(), cmd.bundleGradleArgs())
  }

  @Test
  fun `--embed-deps forwards only when --bundle is set`() {
    // --embed-deps without --bundle is a no-op: no bundle tasks, no embed property.
    val embedOnly = RenderCommand(listOf("render", "--embed-deps"))
    assertEquals(listOf(":app:composePreviewRenderAll"), embedOnly.previewTasksFor(listOf("app")))
    assertEquals(emptyList(), embedOnly.bundleGradleArgs())

    val both = RenderCommand(listOf("render", "--bundle", "--embed-deps"))
    assertEquals(
      listOf(":app:composePreviewRenderAll", ":app:composePreviewBundle"),
      both.previewTasksFor(listOf("app")),
    )
    assertEquals(listOf("-PbundleEmbedDeps=true"), both.bundleGradleArgs())
  }
}
