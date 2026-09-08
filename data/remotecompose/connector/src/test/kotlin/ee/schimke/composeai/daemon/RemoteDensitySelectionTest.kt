package ee.schimke.composeai.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the build-wide capture-density selection every Remote Compose capture falls back to.
 *
 * The default is the load-bearing case, and it points the *opposite* way to its `rcPlayer`
 * neighbour: flipping it to HOST rewrites the bytes of every captured document in every consuming
 * build and turns the replay density into a correctness input, so it stays opt-in until a catalog
 * re-bakes deliberately. A silent flip would move geometry on catalogs nobody touched.
 */
class RemoteDensitySelectionTest {

  @Test
  fun `nothing selected captures against the fixed density`() {
    assertEquals(RemoteCaptureDensity.FIXED, RemoteDensitySelection.DEFAULT)
    assertEquals(RemoteCaptureDensity.FIXED, RemoteDensitySelection.resolve(null))
    assertEquals(RemoteCaptureDensity.FIXED, RemoteDensitySelection.resolve(""))
    assertEquals(RemoteCaptureDensity.FIXED, RemoteDensitySelection.resolve("  "))
  }

  @Test
  fun `host is opt-in and reachable by every spelling`() {
    for (host in listOf("host", "deferred", "variable", "HOST", " Host ")) {
      assertEquals(host, RemoteCaptureDensity.HOST, RemoteDensitySelection.fromWire(host))
    }
  }

  @Test
  fun `fixed is nameable explicitly, so a build can pin the default rather than inherit it`() {
    for (fixed in listOf("fixed", "capture", "literal", "baked", "FIXED", " Baked ")) {
      assertEquals(fixed, RemoteCaptureDensity.FIXED, RemoteDensitySelection.fromWire(fixed))
    }
  }

  @Test
  fun `an unrecognised value names nothing and falls back rather than throwing`() {
    // `fromWire` reports "named nothing" so `resolve` can tell a typo from an unset property and
    // warn about the first; a render must not die over a capture setting either way.
    assertNull(RemoteDensitySelection.fromWire("hsot"))
    assertNull(RemoteDensitySelection.fromWire("2.0"))
    assertEquals(RemoteCaptureDensity.FIXED, RemoteDensitySelection.resolve("hsot"))
  }

  @Test
  fun `the property name matches what the Gradle plugin forwards`() {
    // `composeAiRcDensity` puts the value on the render / daemon JVM under this exact key. The two
    // spellings live in different modules and nothing links them, so pin it here — a rename on
    // either side silently stops the opt-in reaching the JVM that captures, which surfaces only as
    // a `?fontScale=` that quietly does nothing.
    assertEquals("composeai.render.rcDensity", RemoteDensitySelection.PROPERTY)
  }
}
