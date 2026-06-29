package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for [ServeCatalogStore] — fetching a published `design-artifacts/<system>` catalog and
 * registering it as a read-only session, trusted-by-origin when the branch is in the trust store.
 * The network is stubbed via the injected fetcher.
 */
class ServeCatalogStoreTest {

  private fun tempRoot(): File =
    Files.createTempDirectory("catalog").toFile().also { it.deleteOnExit() }

  private val registered = LinkedHashMap<String, ServeBundleHost>()

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  private val catalogJson =
    """
    {"schema":"design-parity-catalog/v1","system":"compose-m3","components":[
      {"componentId":"Button/Filled","images":[
        {"path":"images/button-filled/ideal__default__dark.png","theme":"dark"},
        {"path":"images/button-filled/ideal__default__light.png","theme":"light"}]},
      {"componentId":"Evil","images":[{"path":"../../etc/passwd.png"}]}]}
    """
      .trimIndent()

  /** Serves catalog.json + a PNG for any image URL; nothing else. */
  private fun fetcher(): (String) -> ByteArray? = { url ->
    when {
      url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalogJson.toByteArray()
      url.endsWith(".png") -> png()
      else -> null
    }
  }

  private fun store(
    trust: TrustStore,
    fetch: (String) -> ByteArray? = fetcher(),
  ): ServeCatalogStore =
    ServeCatalogStore(
      root = tempRoot(),
      register = { n, h -> registered[n] = h },
      trust = trust,
      fetch = fetch,
    )

  @Test
  fun `a catalog from a trusted branch is served and attributed by origin`() {
    val trust =
      TrustStore(
        branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*"))
      )
    val result = store(trust).load("compose-m3")

    assertEquals(
      ServeCatalogStore.Result.Ok(
        "compose-m3",
        2,
        "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3",
      ),
      result,
    )
    val host = registered.getValue("compose-m3")
    assertTrue(host.trust is BundleVerifier.Verdict.Trusted)
    // The traversal entry (../../etc/passwd.png) is rejected; only the two image-dir PNGs land, and
    // their ids are flattened to a single route-safe segment (the subdir '/' → '__') so /p/{name}
    // and /render/{name}.png can actually open them.
    assertEquals(
      setOf("button-filled__ideal__default__dark", "button-filled__ideal__default__light"),
      host.previews.map { it.id }.toSet(),
    )
  }

  @Test
  fun `preview ids are flattened to a single route-safe segment`() {
    assertEquals(
      "button-filled__ideal__default__dark",
      ServeCatalogStore.previewIdFor("images/button-filled/ideal__default__dark.png"),
    )
  }

  @Test
  fun `an untrusted branch still serves the catalog but unverified`() {
    val result = store(TrustStore.EMPTY).load("compose-m3")
    assertEquals(ServeCatalogStore.Result.Ok("compose-m3", 2, "unverified"), result)
    assertTrue(registered.getValue("compose-m3").trust is BundleVerifier.Verdict.Unverified)
  }

  @Test
  fun `a missing catalog reports a failure`() {
    val result = store(TrustStore.EMPTY, fetch = { null }).load("compose-m3")
    assertTrue(result is ServeCatalogStore.Result.Failed)
    assertTrue(registered.isEmpty())
  }
}
