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
  private val registeredWasm = LinkedHashMap<String, File>()

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
      registerWasm = { s, d -> registeredWasm[s] = d },
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

  private fun wasmCatalog(files: String): String =
    """
    {"schema":"design-parity-catalog/v1","system":"compose-m3","components":[
      {"componentId":"Button/Filled","images":[
        {"path":"images/button-filled/ideal__default__dark.png","theme":"dark"}]}],
     "webRender":{"kind":"compose-wasm","path":"web/wasm/","files":[$files]}}
    """
      .trimIndent()

  private fun wasmFetcher(
    catalog: String,
    missing: Set<String> = emptySet(),
  ): (String) -> ByteArray? = { url ->
    when {
      url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.toByteArray()
      url.endsWith(".png") -> png()
      url.contains("/web/wasm/") ->
        if (missing.any { url.endsWith(it) }) null else "x".toByteArray()
      else -> null
    }
  }

  @Test
  fun `a complete catalog webRender fetches the wasm app and registers its dir`() {
    val catalog = wasmCatalog("\"index.html\",\"composeApp.wasm\",\"skiko.wasm\"")
    store(TrustStore.EMPTY, wasmFetcher(catalog)).load("compose-m3")

    val wasmDir = registeredWasm.getValue("compose-m3")
    assertTrue(File(wasmDir, "index.html").isFile, "index.html landed")
    assertTrue(File(wasmDir, "composeApp.wasm").isFile && File(wasmDir, "skiko.wasm").isFile)
  }

  @Test
  fun `a webRender with a failed required-file fetch registers nothing (fail closed)`() {
    val catalog = wasmCatalog("\"index.html\",\"composeApp.wasm\",\"skiko.wasm\"")
    // composeApp.wasm 404s → the app is incomplete → don't advertise a tier whose iframe would 404.
    store(TrustStore.EMPTY, wasmFetcher(catalog, missing = setOf("composeApp.wasm")))
      .load("compose-m3")
    assertTrue(registeredWasm.isEmpty(), "incomplete app must not register")
  }

  @Test
  fun `a webRender with a traversal entry fails closed and writes nothing outside the dir`() {
    val catalog = wasmCatalog("\"index.html\",\"composeApp.wasm\",\"../../escape.html\"")
    val root = tempRoot()
    ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = TrustStore.EMPTY,
        fetch = wasmFetcher(catalog),
        registerWasm = { s, d -> registeredWasm[s] = d },
      )
      .load("compose-m3")
    assertTrue(registeredWasm.isEmpty(), "malformed manifest must not register")
    assertTrue(!File(root, "compose-m3/escape.html").exists(), "traversal write rejected")
  }

  @Test
  fun `no webRender means no wasm dir is registered`() {
    store(TrustStore.EMPTY).load("compose-m3")
    assertTrue(registeredWasm.isEmpty())
  }
}
