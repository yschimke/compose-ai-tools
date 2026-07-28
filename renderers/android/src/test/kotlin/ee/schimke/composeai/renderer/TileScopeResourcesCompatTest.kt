package ee.schimke.composeai.renderer

import androidx.wear.protolayout.ResourceBuilders.ImageResource
import androidx.wear.protolayout.ResourceBuilders.Resources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TileScopeResourcesCompatTest {
  @Test
  fun `classic resources remain unchanged when TileRequest has no scope API`() {
    val requested = resources("classic")

    val merged = TileScopeResourcesCompat.merge(requested, LegacyTileRequest())

    assertSame(requested, merged)
  }

  @Test
  fun `empty modern scope leaves classic resources unchanged`() {
    val requested = resources("classic")
    val request = ModernTileRequest(FakeScope(hasResources = false, resources = resources("scope")))

    val merged = TileScopeResourcesCompat.merge(requested, request)

    assertSame(requested, merged)
  }

  @Test
  fun `modern scope resources are merged with classic resources`() {
    val request = ModernTileRequest(FakeScope(hasResources = true, resources = resources("scope")))

    val merged = TileScopeResourcesCompat.merge(resources("classic"), request)

    assertEquals(setOf("classic", "scope"), merged.idToImageMapping.keys)
  }

  private fun resources(id: String): Resources =
    Resources.Builder()
      .setVersion("1")
      .addIdToImageMapping(id, ImageResource.Builder().build())
      .build()

  private class LegacyTileRequest

  private class ModernTileRequest(private val scope: FakeScope) {
    @Suppress("unused") fun getScope(): FakeScope = scope
  }

  private class FakeScope(
    private val hasResources: Boolean,
    private val resources: Resources,
  ) {
    @Suppress("unused") fun hasResources(): Boolean = hasResources

    @Suppress("unused") fun collectResources(): Resources = resources
  }
}
