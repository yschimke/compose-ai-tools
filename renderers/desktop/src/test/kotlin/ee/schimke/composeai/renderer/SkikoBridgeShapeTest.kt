package ee.schimke.composeai.renderer

import java.io.File
import java.net.URLClassLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bind against the `Image.encodeToData` of a skiko this repo does not otherwise resolve.
 *
 * [SkiaPngEncoderTest] proves the binding rule against hand-written stand-ins, which is the only
 * way to exercise both shapes in one JVM — but a stand-in only ever demonstrates that the rule
 * matches what its author BELIEVED skiko looks like. The belief is the part worth checking: #4190
 * was precisely a wrong belief about a signature.
 *
 * So this reads the shape off the real artifact. `skiko-awt` 0.150.1 is resolved into
 * `:renderer-desktop`'s `skikoEncodeProbe` configuration — never a compile, runtime or test
 * classpath, because a second skiko on any of those would be conflict-resolved down to one and
 * destroy the thing being measured — and handed here as a path.
 *
 * The class is loaded **uninitialized** (`Class.forName(…, initialize = false, …)`) under a
 * bootstrap-parented loader. Uninitialized because `Image`'s static initializer calls
 * `Library.staticLoad()`, which would drag in the platform `libskiko`; reflection over the method
 * table needs none of that. Bootstrap-parented because a normal parent would delegate
 * `org.jetbrains.skia.Image` straight back to the 0.144 copy already on this test's classpath, and
 * the test would silently measure the wrong jar.
 */
class SkikoBridgeShapeTest {

  private fun probeJars(): List<File> {
    val path = System.getProperty("composeai.test.skikoEncodeProbe").orEmpty()
    assertTrue(
      "composeai.test.skikoEncodeProbe was not set — run this through Gradle, which resolves the " +
        "skikoEncodeProbe configuration and passes it in",
      path.isNotBlank(),
    )
    return path.split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)
  }

  private fun probeImageClass(): Class<*> {
    val urls = probeJars().map { it.toURI().toURL() }.toTypedArray()
    // Null parent: bootstrap only, so nothing resolves back to this module's own skiko.
    val isolated = URLClassLoader(urls, null)
    return Class.forName("org.jetbrains.skia.Image", false, isolated)
  }

  @Test
  fun `the probe jar really is the version whose signature changed`() {
    // Guards the guard: if the probe silently resolved to the same skiko the module compiles
    // against, every assertion below would pass while proving nothing.
    val probeMethod =
      probeImageClass().methods.single { it.name == "encodeToData" }.parameterTypes.size
    val compiledMethod =
      org.jetbrains.skia.Image::class
        .java
        .methods
        .single { it.name == "encodeToData" }
        .parameterTypes
        .size
    assertEquals("the probe jar should carry the three-parameter form", 3, probeMethod)
    assertEquals(
      "this module should still compile against the two-parameter form",
      2,
      compiledMethod,
    )
  }

  @Test
  fun `binds the real three-parameter bridge, not just the stand-in modelled on it`() {
    val binding = SkiaPngEncoder.bind(probeImageClass())
    assertEquals("Image.encodeToData(EncodedImageFormat, int, int)", binding.description)
  }

  @Test
  fun `binds the shape this module compiles against too, from the same rule`() {
    val binding = SkiaPngEncoder.bind(org.jetbrains.skia.Image::class.java)
    assertEquals("Image.encodeToData(EncodedImageFormat, int)", binding.description)
  }
}
