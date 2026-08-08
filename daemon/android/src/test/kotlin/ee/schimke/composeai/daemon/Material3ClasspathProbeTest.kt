package ee.schimke.composeai.daemon

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daemon's Material 3 usage — the theme capture and the `compose/theme` payload — is optional
 * metadata, and Material 3 is `compileOnly` here so the consumer's own copy wins at runtime (see
 * `docs/RENDERER_COMPATIBILITY.md`). A consumer may have none at all: a Wear app themes with
 * `androidx.wear.compose.material3`, and once Rule 3 stopped smuggling our own Compose
 * Multiplatform transitives onto the render graph
 * (`AndroidPreviewSupport.applyRenderGraphResolutionRules`) nothing else puts
 * `androidx.compose.material3` there. Rendering such a module then died in the renderer's own
 * `CaptureMaterialTheme`, before user code ran:
 * ```
 * NoClassDefFoundError: androidx/compose/material3/ColorScheme
 *     at ee.schimke.composeai.daemon.RenderEngine…evaluate$lambda$1$0$2(RenderEngine.kt:442)
 * ```
 *
 * So `render` probes first. This pins the probe's two answers; the render-path guard it feeds is
 * covered end-to-end by the `wear-os-samples` legs of the Integration workflow, which are the
 * Material-3-less consumers that regressed.
 */
class Material3ClasspathProbeTest {

  @Test
  fun `reports material3 present on a loader that has it`() {
    // This module's own test classpath does carry Material 3.
    assertTrue(material3OnClasspath(javaClass.classLoader!!))
  }

  @Test
  fun `reports material3 absent on a loader that hides it`() {
    // Stands in for a Wear / foundation-only consumer's render classpath.
    val hidden =
      object : ClassLoader(javaClass.classLoader) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
          if (name.startsWith("androidx.compose.material3.")) throw ClassNotFoundException(name)
          return super.loadClass(name, resolve)
        }
      }
    assertFalse(material3OnClasspath(hidden))
  }
}
