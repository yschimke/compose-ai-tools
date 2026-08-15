package com.example.samplecmp

import com.google.common.truth.Truth.assertThat
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * End-to-end guard for issue #3873: a `private` CMP `@Preview` must survive
 * `composePreviewRenderAll` — the standalone Desktop renderer the Gradle plugin, `compose-preview
 * bundle render`, and `compose-preview serve --module`'s bootstrap all run.
 *
 * The renderer used to resolve a private preview and then invoke it without opening the JVM method,
 * so every private preview in a module died with `IllegalAccessException` and left a `.error.json`
 * where its PNG should be. The daemon drew the same preview fine, which is what made the failure
 * confusing: MCP worked, Gradle and Serve didn't.
 *
 * The module's `test` task depends on `composePreviewRenderAll` (see this sample's
 * `build.gradle.kts`), so the renders exist by the time these assertions run.
 */
class PrivatePreviewRenderTest {

  private val rendersDir = File("build/compose-previews/renders")

  @Test
  fun `a private preview renders instead of failing with IllegalAccessException`() {
    val png = renderFile(rendersDir, "PrivateBadgePreview_Private_badge")
    assertNoErrorSidecar(png)
    assertThat(png.exists()).isTrue()
    assertThat(ImageIO.read(png)).isNotNull()
  }

  /**
   * Both provider rows, not just one: Serve reads a parameterized preview's rows off these
   * filenames, so a row that never rendered is a row the catalog can't show.
   */
  @Test
  fun `every row of a private parameterized preview renders`() {
    listOf("Indigo", "Moss").forEach { row ->
      val png = renderFile(rendersDir, "PrivateTonePreview_Private_tone", suffix = "_$row")
      assertNoErrorSidecar(png)
      assertThat(png.exists()).isTrue()
      assertThat(ImageIO.read(png)).isNotNull()
    }
  }

  private fun assertNoErrorSidecar(png: File) {
    val sidecar = File(png.parentFile, png.name + ".error.json")
    assertThat(if (sidecar.exists()) sidecar.readText() else "").isEmpty()
  }
}
