package ee.schimke.composeai.renderer.xr.client

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XrCompositeBinaryTest {

  @Test
  fun resolvesExplicitEnvBinary() {
    val bin = File.createTempFile("xr-composite", "").apply { deleteOnExit() }
    val env = mapOf("XR_COMPOSITE_BIN" to bin.path)
    assertEquals(bin, XrCompositeBinary.resolve(env = env::get))
  }

  @Test
  fun returnsNullWhenNothingResolves() {
    assertNull(XrCompositeBinary.resolve(env = { null }, version = "0.0.0"))
  }

  @Test
  fun ignoresEnvBinaryThatDoesNotExist() {
    val env = mapOf("XR_COMPOSITE_BIN" to "/no/such/xr-composite")
    assertNull(XrCompositeBinary.resolve(env = env::get))
  }

  @Test
  fun materialsFallBackToSiblingDir() {
    val dir = kotlin.io.path.createTempDirectory("xr").toFile().apply { deleteOnExit() }
    val bin = File(dir, "xr-composite").apply { createNewFile() }
    val materials = File(dir, "materials").apply { mkdirs() }
    assertEquals(materials, XrCompositeBinary.resolveMaterials(bin, env = { null }))
  }

  @Test
  fun platformKeyHasOsAndArch() {
    val key = XrCompositeBinary.currentPlatform()
    assertTrue(key.matches(Regex("(linux|macos|windows)-(x86_64|arm64)")), "unexpected key: $key")
  }
}
