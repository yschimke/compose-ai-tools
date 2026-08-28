package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.BundleVerifier
import ee.schimke.composeai.bundle.TrustStore
import ee.schimke.composeai.bundle.TrustedBranch
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Coverage for [ServeCatalogStore] — fetching a published `design-artifacts/<system>` catalog and
 * registering it as a read-only session, trusted-by-origin when the branch is in the trust store.
 * The network is stubbed via the injected fetcher.
 */
class ServeCatalogStoreTest {

  private fun tempRoot(): File =
    Files.createTempDirectory("catalog").toFile().also { it.deleteOnExit() }

  /**
   * Where a store rooted at [root] holds the blob whose sha256 is [sha] — the default
   * [CatalogBlobPool] location, which is the store root plus its own subdirectory.
   */
  private fun blobFile(root: File, sha: String): File =
    File(File(root, ServeCatalogStore.BLOB_CACHE_DIR), "${CatalogBlobPool.CONTENT_DIR}/$sha")

  /** A plausible delivery-branch head, so a stubbed feed pins a load to one immutable tree. */
  private val COMMIT = "0123456789abcdef0123456789abcdef01234567"

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

  @Test
  fun `failure-only catalog registers visible diagnostic cards`() {
    val broken =
      """
      {"schema":"design-parity-catalog/v1","system":"broken","components":[],"failures":[
        {"id":"render-failed--button-filled","componentId":"Button/Filled",
         "preview":"FilledButtonPreview","phase":"render",
         "errorClass":"java.lang.NoSuchMethodError","message":"boom","group":"Buttons"}]}
      """
        .trimIndent()
    val result =
      store(
          TrustStore.EMPTY,
          fetch = { url ->
            if (url.endsWith("/${ServeCatalogStore.CATALOG_FILE}")) broken.toByteArray() else null
          },
        )
        .load("broken")

    assertEquals(ServeCatalogStore.Result.Ok("broken", 1, "unverified", 1), result)
    val preview = registered.getValue("broken").previews.single()
    assertEquals("Button/Filled", preview.componentId)
    assertEquals("java.lang.NoSuchMethodError", preview.renderFailure?.errorClass)
    assertEquals("boom", preview.renderFailure?.message)
  }

  @Test
  fun `failure ids are route safe and collision safe`() {
    val broken =
      """
      {"schema":"design-parity-catalog/v1","system":"broken","components":[],"failures":[
        {"id":"../../outside","componentId":"Button/Filled","preview":"Button Preview",
         "errorClass":"First","message":"one"},
        {"id":"../outside","componentId":"Button/Filled","preview":"Button Preview",
         "errorClass":"Second","message":"two"}]}
      """
        .trimIndent()
    val result =
      store(
          TrustStore.EMPTY,
          fetch = { url ->
            if (url.endsWith("/${ServeCatalogStore.CATALOG_FILE}")) broken.toByteArray() else null
          },
        )
        .load("broken")

    assertEquals(ServeCatalogStore.Result.Ok("broken", 2, "unverified", 2), result)
    assertEquals(
      listOf(
        "render-failed--button-filled--button-preview",
        "render-failed--button-filled--button-preview--2",
      ),
      registered.getValue("broken").previews.map { it.id },
    )
  }

  /** Serves catalog.json + a PNG for any image URL; nothing else. */
  private fun fetcher(): (String) -> ByteArray? = { url ->
    when {
      url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalogJson.toByteArray()
      url.endsWith(".png") -> png()
      else -> null
    }
  }

  // `fetch` stays the LAST parameter so the trailing-lambda call sites below keep binding to it.
  private fun store(
    trust: TrustStore,
    maxImages: Int = 1000,
    // The baked vectors are filled off the publish path, so tests run that pass inline by default
    // and assert against a settled catalog exactly as they did when it was synchronous.
    figmaExecutor: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() },
    fetch: (String) -> ByteArray? = fetcher(),
  ): ServeCatalogStore =
    ServeCatalogStore(
      root = tempRoot(),
      register = { n, h -> registered[n] = h },
      trust = { trust },
      fetch = fetch,
      registerWasm = { s, d -> if (d == null) registeredWasm.remove(s) else registeredWasm[s] = d },
      maxImages = maxImages,
      figmaExecutor = figmaExecutor,
    )

  @Test
  fun `the image cap bounds the previews a catalog declares`() {
    // Fetching lazily makes the ceiling count DECLARED previews rather than successfully fetched
    // ones: whether an image can be had isn't known at load time any more, and finding out would
    // mean fetching everything — the thing lazy loading exists to avoid. So a cap of two publishes
    // the first two declarations, and a card whose image turns out to be missing reports NotFound
    // on request instead of being silently replaced by a later one. The default stays above the
    // largest published catalog so it remains a guard rather than truncating valid previews.
    val trust =
      TrustStore(
        branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*"))
      )
    val missing = "images/button-filled/ideal__default__dark.png"
    val result =
      store(
          trust,
          fetch = { url ->
            when {
              url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") ->
                threeImageCatalogJson.toByteArray()
              url.endsWith(missing) -> null
              url.endsWith(".png") -> png()
              else -> null
            }
          },
          maxImages = 2,
        )
        .load("compose-m3")

    assertEquals(2, (result as ServeCatalogStore.Result.Ok).previewCount)
    val host = registered.getValue("compose-m3")
    assertEquals(
      listOf("button-filled__ideal__default__dark", "button-filled__ideal__default__light"),
      host.previews.map { it.id },
    )
    assertTrue(
      host.previews.all { it.componentId == "Button/Filled" },
      "the original component id survives route slug generation",
    )
    // The declared-but-unfetchable card reports NotFound; its sibling still serves.
    assertEquals(
      RenderOutcome.NotFound,
      host.render("button-filled__ideal__default__dark", PreviewOverrides()),
    )
    assertTrue(
      host.render("button-filled__ideal__default__light", PreviewOverrides()) is RenderOutcome.Ok
    )
  }

  /** Eight baked images, comfortably more than the handful sampled before publishing. */
  private val eightImageCatalogJson =
    """
    {"schema":"design-parity-catalog/v1","system":"compose-m3","components":[
      {"componentId":"Button/Filled","images":[
        ${(1..8).joinToString(",") { """{"path":"images/button-filled/v$it.png"}""" }}]}]}
    """
      .trimIndent()

  @Test
  fun `a catalog publishes every declared preview before its images are fetched`() {
    val requested = CopyOnWriteArrayList<String>()
    val fetcher: (String) -> ByteArray? = { url ->
      requested += url
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> eightImageCatalogJson.toByteArray()
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    val result = store(TrustStore.EMPTY, fetch = fetcher).load("compose-m3")

    // Every declared card is published…
    assertEquals(8, (result as ServeCatalogStore.Result.Ok).previewCount)
    val host = registered.getValue("compose-m3")
    assertEquals(8, host.previews.size)
    // …on a small sample of images, not all eight. This is the whole point: `catalog.json` names
    // every card, so the grid is complete long before the pixels are.
    val imagesAtPublish = requested.count { it.endsWith(".png") }
    assertTrue(imagesAtPublish <= 3, "published after $imagesAtPublish image fetches")

    // A card whose pixels were never fetched still renders — the host fills it on first use.
    val cold = "button-filled__v8"
    assertTrue(host.previews.any { it.id == cold })
    assertTrue(host.render(cold, PreviewOverrides()) is RenderOutcome.Ok)
    assertEquals(imagesAtPublish + 1, requested.count { it.endsWith(".png") })

    // …and only once: the second read comes off disk.
    assertTrue(host.render(cold, PreviewOverrides()) is RenderOutcome.Ok)
    assertEquals(imagesAtPublish + 1, requested.count { it.endsWith(".png") })
  }

  /**
   * A catalog whose one component publishes a still, a capture beside it, and a second capture
   * whose path tries to climb out of the motion directory.
   */
  private val motionCatalogJson =
    """
    {
      "meta": {"system": "compose-m3"},
      "components": [
        {
          "componentId": "Switch/On",
          "images": [
            {"path": "images/switch-on/ideal__default__dark.png", "theme": "dark",
             "previewId": "SwitchOn_Dark"}
          ],
          "motion": [
            {"path": "motion/switch-on/ideal__default__dark.apng", "kind": "interaction",
             "caption": "Toggle off and back on.", "theme": "dark"},
            {"path": "motion/../../etc/passwd.apng", "kind": "interaction", "theme": "dark"}
          ]
        }
      ]
    }
    """
      .trimIndent()

  @Test
  fun `a published capture is offered on its card and fetched only when watched`() {
    val requested = CopyOnWriteArrayList<String>()
    val fetcher: (String) -> ByteArray? = { url ->
      requested += url
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> motionCatalogJson.toByteArray()
        url.endsWith(".png") -> png()
        url.endsWith(".apng") -> byteArrayOf(1, 2, 3)
        else -> null
      }
    }
    store(TrustStore.EMPTY, fetch = fetcher).load("compose-m3")
    val host = registered.getValue("compose-m3")

    val preview = host.previews.single { it.id == "switch-on__ideal__default__dark" }
    // The traversal attempt is gone; the legitimate capture is offered with its caption intact.
    val motion = preview.motion.single()
    assertEquals("switch-on__ideal__default__dark", motion.id)
    assertEquals("interaction", motion.kind)
    assertEquals("Toggle off and back on.", motion.caption)
    assertEquals(".apng", motion.extension)

    // Nothing was fetched to publish it. A capture is one to two orders of magnitude heavier than
    // the sticker beside it and most readers never open one, so paying for it at registration would
    // be the whole cost of the feature spent on nobody.
    assertEquals(0, requested.count { it.endsWith(".apng") })

    // It lands on first watch, and only once — the second read comes off disk.
    assertContentEquals(byteArrayOf(1, 2, 3), host.motionBytes(motion.id, ".apng"))
    assertEquals(1, requested.count { it.endsWith(".apng") })
    assertContentEquals(byteArrayOf(1, 2, 3), host.motionBytes(motion.id, ".apng"))
    assertEquals(1, requested.count { it.endsWith(".apng") })
  }

  @Test
  fun `a capture request cannot choose an id or a type the catalog never published`() {
    val fetcher: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> motionCatalogJson.toByteArray()
        url.endsWith(".png") -> png()
        else -> byteArrayOf(1, 2, 3)
      }
    }
    store(TrustStore.EMPTY, fetch = fetcher).load("compose-m3")
    val host = registered.getValue("compose-m3")
    val id = "switch-on__ideal__default__dark"

    // These bytes come off a delivery branch, so the suffix a request asks for must never be what
    // decides how they are typed — the declared extension is, and this id declared `.apng`.
    assertNull(host.motionBytes(id, ".gif"))
    // Nor may a request name a capture the catalog never declared, however plausible the id.
    assertNull(host.motionBytes("switch-on__ideal__default__light", ".apng"))
    assertNull(host.motionBytes("../../etc/passwd", ".apng"))
  }

  @Test
  fun `a catalog publishes before its baked vectors are fetched`() {
    // The vectors are the last bulk fetch on the publish path — one per image plus one per slug.
    // Publishing must not wait for them: the catalog serves (uncropped, briefly) and the pass fills
    // them behind it. Captured rather than run so the assertion is about ordering, not timing.
    val deferred = mutableListOf<Runnable>()
    val requested = CopyOnWriteArrayList<String>()
    val result =
      store(
          TrustStore.EMPTY,
          figmaExecutor = java.util.concurrent.Executor { deferred += it },
          fetch = { url ->
            requested += url
            when {
              url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") ->
                eightImageCatalogJson.toByteArray()
              url.endsWith(".svg") -> "<svg/>".toByteArray()
              url.endsWith(".png") -> png()
              else -> null
            }
          },
        )
        .load("compose-m3")

    assertTrue(result is ServeCatalogStore.Result.Ok)
    // One probe decided the lane exists; the other ~8 vectors have not been asked for yet.
    // The probe samples this catalog's single component — its per-variant vector plus the slug
    // fallback — and stops. The remaining ~8 vectors have not been asked for yet.
    assertEquals(
      2,
      requested.count { it.endsWith(".svg") },
      "only the probe runs before publishing",
    )
    assertEquals(1, deferred.size, "the rest is scheduled, not run")

    deferred.forEach { it.run() }

    assertTrue(
      requested.count { it.endsWith(".svg") } > 1,
      "the deferred pass fetches the remaining vectors",
    )
  }

  @Test
  fun `computing thumbnail crops never fetches a cold preview`() {
    // The landing page computes a crop for EVERY card while building its HTML. If that filled
    // missing pixels, the first page request would serially download a whole cold catalog on the
    // request thread — reintroducing the stall this lazy path exists to remove, just moved.
    val requested = CopyOnWriteArrayList<String>()
    store(
        TrustStore.EMPTY,
        fetch = { url ->
          requested += url
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") ->
              eightImageCatalogJson.toByteArray()
            url.endsWith(".png") -> png()
            else -> null
          }
        },
      )
      .load("compose-m3")
    val host = registered.getValue("compose-m3")
    val afterPublish = requested.count { it.endsWith(".png") }

    host.previews.forEach { host.contentCrop(it.id) }

    assertEquals(afterPublish, requested.count { it.endsWith(".png") })
  }

  @Test
  fun `a branch that cannot serve any image does not replace a healthy catalog`() {
    // Lazy images give up the old "declared images, none fetched" outage check, so the publish-time
    // sample is what keeps a 404ing branch from swapping over a working catalog.
    val result =
      store(
          TrustStore.EMPTY,
          fetch = { url ->
            if (url.endsWith("/${ServeCatalogStore.CATALOG_FILE}")) {
              eightImageCatalogJson.toByteArray()
            } else null
          },
        )
        .load("compose-m3")

    assertTrue(result is ServeCatalogStore.Result.Failed, "expected failure, got $result")
  }

  /** Three baked images across one component, so a cap of two leaves something behind it. */
  private val threeImageCatalogJson =
    """
    {"schema":"design-parity-catalog/v1","system":"compose-m3","components":[
      {"componentId":"Button/Filled","images":[
        {"path":"images/button-filled/ideal__default__dark.png","theme":"dark"},
        {"path":"images/button-filled/ideal__default__light.png","theme":"light"},
        {"path":"images/button-filled/ideal__hover__light.png","theme":"light"}]}]}
    """
      .trimIndent()

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
    // This catalog carries no liveBundle, so it's registered baked-only and records WHY — surfaced
    // by the viewer banner + /api/previews so a visitor sees it's snapshot-only, not guessing.
    assertEquals(listOf(ServeDegradation.CATALOG_BAKED_ONLY), host.degradations.map { it.code })
    // The traversal entry (../../etc/passwd.png) is rejected; only the two image-dir PNGs land, and
    // their ids are flattened to a single route-safe segment (the subdir '/' → '__') so /p/{name}
    // and /render/{name}.png can actually open them.
    assertEquals(
      setOf("button-filled__ideal__default__dark", "button-filled__ideal__default__light"),
      host.previews.map { it.id }.toSet(),
    )
  }

  @Test
  fun `catalog imports the published reference manifest and keeps source URLs inert`() {
    val root = tempRoot()
    val referencePng = png()
    // Assets are fetched on a pool (ASSET_FETCH_CONCURRENCY), so this capture is written from
    // several threads at once — a plain ArrayList throws ConcurrentModificationException here.
    val requested = CopyOnWriteArrayList<String>()
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[
         {"path":"images/button.png"}]}]}
      """
        .trimIndent()
    val manifest =
      """
      {"schema":"compose-preview-references/v1","references":[{
         "id":"button-figma","previewId":"button","label":"Figma button",
         "raster":{"path":"design-references/button.png","width":2,"height":2},
         "source":{"provider":"figma","uri":"https://api.figma.com/v1/files/private"},
         "artifact":{"kind":"html","path":"mocks/button.html"}
       }]}
      """
        .trimIndent()
    val fetch: (String) -> ByteArray? = { url ->
      requested += url
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.encodeToByteArray()
        url.endsWith("/references/index.json") -> manifest.encodeToByteArray()
        url.endsWith("/design-references/button.png") -> referencePng
        url.endsWith("/images/button.png") -> png()
        else -> null
      }
    }
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = fetch,
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)

    val host = registered.getValue("compose-m3")
    val reference = host.designReferencesFor("button").single()
    assertEquals("figma", reference.source.provider)
    assertEquals("https://api.figma.com/v1/files/private", reference.source.uri)
    assertContentEquals(referencePng, host.designReferenceRaster("button-figma"))
    assertTrue(requested.any { it.endsWith("/references/index.json") })
    assertFalse(requested.any { it.startsWith("https://api.figma.com") })
    assertFalse(requested.any { it.endsWith("mocks/button.html") })
  }

  /**
   * A served catalog is a fresh staging tree assembled from explicitly fetched parts, so a
   * published file nobody copies is invisible to the host no matter what the producer wrote. The
   * parity feed is exactly that kind of file, and getting this wrong is silent: the `/parity` view
   * still renders, just coverage-only, on every *published* catalog — which is every catalog the
   * feature exists for.
   */
  @Test
  fun `catalog stages the published parity activity feed`() {
    val root = tempRoot()
    // Assets are fetched on a pool (ASSET_FETCH_CONCURRENCY), so this capture is written from
    // several threads at once — a plain ArrayList throws ConcurrentModificationException here.
    val requested = CopyOnWriteArrayList<String>()
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[
         {"path":"images/button.png"}]}]}
      """
        .trimIndent()
    val activity =
      """
      {"schema":"compose-preview-activity/v1","generatedAt":"2026-08-06T09:12:00Z",
       "windowDays":30,
       "code":{"repo":"yschimke/m3-catalog","ref":"main","events":[
         {"sha":"4e73ec2b9f0a1c3d5e7f9a1b3c5d7e9f0a1b3c5d","subject":"fix: padding",
          "at":"2026-08-05T10:00:00Z","previewIds":["button"],"components":["Button/Filled"]}]},
       "figma":{"fileKey":"abc123","comments":[
         {"id":"c1","at":"2026-08-04T08:00:00Z","message":"2dp short","nodeId":"51592:4768"}]},
       "gaps":[{"kind":"unmapped-design-node","detail":"nothing maps to it"}]}
      """
        .trimIndent()
    val fetch: (String) -> ByteArray? = { url ->
      requested += url
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.encodeToByteArray()
        url.endsWith("/parity/activity.json") -> activity.encodeToByteArray()
        url.endsWith("/images/button.png") -> png()
        else -> null
      }
    }
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = fetch,
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)

    assertTrue(requested.any { it.endsWith("/parity/activity.json") }, "the feed is fetched")
    val loaded = registered.getValue("compose-m3").parityActivity()
    assertNotNull(loaded, "the feed reached the host through the staging tree")
    assertEquals("yschimke/m3-catalog", loaded.code?.repo)
    assertEquals(1, loaded.code?.events?.size)
    assertEquals("51592:4768", loaded.figma?.comments?.single()?.nodeId)
    assertEquals(1, loaded.gaps.size)
  }

  @Test
  fun `catalog stages the published parity issue index`() {
    val root = tempRoot()
    val requested = CopyOnWriteArrayList<String>()
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}]}
      """
        .trimIndent()
    val issues =
      """
      {"schema":"compose-preview-issues/v1","generatedAt":"2026-08-15T09:12:00Z",
       "issues":[{"repository":"yschimke/m3-catalog","number":40,"title":"Padding differs",
         "url":"https://github.com/yschimke/m3-catalog/issues/40","state":"open",
         "area":"component","parity":"known-difference","system":"compose-m3",
         "component":"Button/Filled","previewIds":["button"],"referenceIds":["button-figma"]}]}
      """
        .trimIndent()
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = { url ->
          requested += url
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.encodeToByteArray()
            url.endsWith("/parity/issues.json") -> issues.encodeToByteArray()
            url.endsWith("/images/button.png") -> png()
            else -> null
          }
        },
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)

    assertTrue(requested.any { it.endsWith("/parity/issues.json") }, "the index is fetched")
    val loaded = registered.getValue("compose-m3").parityIssues()
    assertNotNull(loaded, "the index reached the host through the staging tree")
    assertEquals(40, loaded.issues.single().number)
    assertEquals("button-figma", loaded.issues.single().referenceIds.single())
  }

  /**
   * A document the derivation refuses **whole** — an unknown document-level member. Using it is
   * what makes "the index was copied" distinguishable from "the list was re-derived": if the
   * artifacts arrive, only the index can have named them.
   */
  private val DERIVATION_REJECTS =
    """
    {"schema":"compose-preview-known-differences/v1","note":"unknown member","acceptances":[
      {"id":"glyph","issue":"https://github.com/yschimke/m3-catalog/issues/40",
       "mask":"mask.png","acceptedCandidate":"accepted-candidate.png"}]}
    """
      .trimIndent()

  /**
   * The same records in a document the derivation happily reads, for the mirror-image assertion.
   */
  private val DERIVATION_ACCEPTS =
    """
    {"schema":"compose-preview-known-differences/v1","acceptances":[
      {"id":"glyph","issue":"https://github.com/yschimke/m3-catalog/issues/40",
       "mask":"mask.png","acceptedCandidate":"accepted-candidate.png"}]}
    """
      .trimIndent()

  /**
   * Load a catalog whose known-difference document names two artifacts for `glyph`, with [index]
   * served (or not) as the published artifact list.
   */
  private fun loadWithArtifactIndex(
    index: String?,
    document: String = DERIVATION_REJECTS,
  ): Pair<ServeHost, List<String>> {
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}]}
      """
        .trimIndent()
    val requested = CopyOnWriteArrayList<String>()
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = { url ->
          requested += url
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.encodeToByteArray()
            url.endsWith("/parity/known-differences-index.json") -> index?.encodeToByteArray()
            url.endsWith("/parity/known-differences.json") -> document.encodeToByteArray()
            url.contains("/parity/known-differences/") -> "artifact".encodeToByteArray()
            url.endsWith("/images/button.png") -> png()
            else -> null
          }
        },
      )
    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    return registered.getValue("compose-m3") to requested.toList()
  }

  @Test
  fun `a published artifact index is copied, not derived from the contract`() {
    // The point of the index. Deriving the fetch list from the document means mirroring every
    // pre-read refusal the engine has, and a mirror that drifts stricter starves a legal record of
    // its artifacts. The producer wrote the files; it can simply say which.
    //
    // The document here carries an unknown member, which the derivation refuses whole — so if the
    // artifacts arrive, the list came from the index and nothing re-derived it.
    val (host, requested) =
      loadWithArtifactIndex(
        """
        {"schema":"compose-preview-known-difference-artifacts/v1",
         "artifacts":["glyph/mask.png","glyph/accepted-candidate.png"]}
        """
          .trimIndent()
      )

    assertTrue(
      host.knownDifferenceArtifact("glyph/mask.png") is ServeKnownDifferences.Artifact.Bytes,
      "the index's artifacts were not staged: $requested",
    )
    assertTrue(
      host.knownDifferenceArtifact("glyph/accepted-candidate.png")
        is ServeKnownDifferences.Artifact.Bytes
    )
    // The document still reaches the host verbatim — the index says what to copy, never what the
    // engine may read.
    assertTrue(host.knownDifferences() is ServeKnownDifferences.Document.Text)
  }

  @Test
  fun `a catalog without an index still derives the list`() {
    // Purely additive: a catalog published before the index existed behaves exactly as it did. The
    // same document, no index — and the derivation refuses it whole, so nothing is staged.
    val (host, _) = loadWithArtifactIndex(null)

    assertTrue(
      host.knownDifferenceArtifact("glyph/mask.png") !is ServeKnownDifferences.Artifact.Bytes,
      "the derivation must still refuse a document it rejects whole",
    )
  }

  @Test
  fun `without an index, that same document derives its artifacts`() {
    // The other half of the empty-index test: it only means something if this document really does
    // produce a fetch when the list is derived.
    val (_, requested) = loadWithArtifactIndex(index = null, document = DERIVATION_ACCEPTS)

    assertTrue(
      requested.any { it.endsWith("/parity/known-differences/glyph/mask.png") },
      "the derivation fetched nothing, so the empty-index test proves nothing: $requested",
    )
  }

  @Test
  fun `an index does not stage artifacts for a document past the byte ceiling`() {
    // The transport's envelope is 25x the contract's, so a document between the two arrives intact
    // and its index arrives with it — but the reader answers `TooLarge` and the engine refuses the
    // document whole, reading not one artifact. Preferring the index walked straight past the
    // length guard that made that cheap, so 512 individually legal files could be fetched and
    // staged on every refresh for a verdict that names none of them.
    val oversized =
      """{"schema":"compose-preview-known-differences/v1","acceptances":[],"pad":"""" +
        "x".repeat(ServeKnownDifferences.MAX_DOCUMENT_BYTES) +
        """"}"""
    val (_, requested) =
      loadWithArtifactIndex(
        """
        {"schema":"compose-preview-known-difference-artifacts/v1",
         "artifacts":["glyph/mask.png","glyph/accepted-candidate.png"]}
        """
          .trimIndent(),
        document = oversized,
      )

    assertTrue(
      requested.none { it.contains("/parity/known-differences/") },
      "artifacts were staged for a document the reader refuses whole: $requested",
    )
  }

  @Test
  fun `an index with a wrongly typed entry falls back rather than staging nothing`() {
    // Skipping a malformed entry looks harmless and is the opposite: the list reduces to a shorter
    // one — possibly empty — and an empty list is honoured as the producer saying it carried
    // nothing. A document naming perfectly good artifacts would then stage none of them and every
    // record would read as `artifact-unreadable`, which is the changed-verdict failure reached
    // through the fallback written to prevent it.
    // `[null]` rather than `["glyph/mask.png", null]`: with a surviving entry, skipping and
    // rejecting both end up fetching that entry, so the assertion would pass either way and prove
    // nothing. The list that reduces to *empty* is the one where the two behaviours diverge.
    val (_, requested) =
      loadWithArtifactIndex(
        """{"schema":"compose-preview-known-difference-artifacts/v1","artifacts":[null]}""",
        document = DERIVATION_ACCEPTS,
      )

    // Fell back to the derivation, which this document supports — so the artifacts still arrive.
    assertTrue(
      requested.any { it.endsWith("/parity/known-differences/glyph/mask.png") },
      "a wrongly-typed entry must reject the index, not silently empty it: $requested",
    )
  }

  @Test
  fun `an index naming two spellings of one file falls back`() {
    // `glyph/mask.png` and `glyph/MASK.PNG` are distinct strings, both portable, and on Windows or
    // a default macOS volume they are one file. The plan is executed concurrently, so staging both
    // schedules two workers writing the same path — last writer wins, and the canonical spelling
    // left behind may be the one the reader then rejects for case. A record's real artifact can be
    // overwritten by a sibling the document never named, differently on each refresh.
    //
    // Reachable through the index in particular, because it may carry siblings the document does
    // not name.
    val (_, requested) =
      loadWithArtifactIndex(
        """
        {"schema":"compose-preview-known-difference-artifacts/v1",
         "artifacts":["glyph/mask.png","glyph/MASK.PNG"]}
        """
          .trimIndent(),
        document = DERIVATION_ACCEPTS,
      )

    // Fell back to the derivation, which names one spelling per field — so the staging plan is
    // executable again.
    assertTrue(
      requested.none { it.contains("MASK.PNG") },
      "a case-folded collision must reject the list, not race two writes: $requested",
    )
    assertTrue(
      requested.any { it.endsWith("/parity/known-differences/glyph/mask.png") },
      "the fallback should still stage the document's own artifacts: $requested",
    )
  }

  @Test
  fun `the derivation stages one write per file, and keeps the rest of the document`() {
    // The same hazard one layer down, where the index's answer is not available. A record naming
    // `mask.png` and `MASK.PNG` derives two portable paths fetched from two URLs that are ONE file
    // on Windows and on a default macOS volume; the plan runs concurrently, so staging both leaves
    // behind whichever worker returned last — and the canonical spelling on disk may be the one the
    // reader then rejects for case.
    //
    // Rejecting the whole list the way `publishedArtifactIndex` does is NOT available here: an
    // index that is refused falls back to the derivation, while a derivation that is refused leaves
    // nothing, so every legal record in the document would lose its artifacts. First spelling wins
    // instead — which drops a path only when another path in the same plan already claims that
    // file, and makes the outcome a function of the document rather than of fetch timing.
    val document =
      """
      {"schema":"compose-preview-known-differences/v1","acceptances":[
        {"id":"glyph","issue":"https://github.com/yschimke/m3-catalog/issues/40",
         "mask":"mask.png","acceptedCandidate":"MASK.PNG"},
        {"id":"other","issue":"https://github.com/yschimke/m3-catalog/issues/41",
         "mask":"mask.png","acceptedCandidate":"accepted-candidate.png"}]}
      """
        .trimIndent()
    val (_, requested) = loadWithArtifactIndex(index = null, document = document)

    val staged = requested.filter { it.contains("/parity/known-differences/") }
    assertTrue(
      staged.none { it.contains("MASK.PNG") },
      "the second spelling of one file must not be scheduled: $staged",
    )
    assertTrue(
      staged.any { it.endsWith("/parity/known-differences/glyph/mask.png") },
      "the first spelling is the one that wins, not neither: $staged",
    )
    // The point of not rejecting outright: a colliding record must not cost its neighbours their
    // artifacts, which is exactly what returning an empty plan here would do.
    assertTrue(
      staged.any { it.endsWith("/parity/known-differences/other/mask.png") } &&
        staged.any { it.endsWith("/parity/known-differences/other/accepted-candidate.png") },
      "an unrelated record lost its artifacts to a sibling's collision: $staged",
    )
  }

  @Test
  fun `an index cannot name a path the reader would refuse to look up`() {
    // A fetch plan, not a licence. The producer's list goes through exactly the lexical rule the
    // document's paths do, so an index is never a way around it.
    val (_, requested) =
      loadWithArtifactIndex(
        """
        {"schema":"compose-preview-known-difference-artifacts/v1",
         "artifacts":["glyph/mask.png","../../secrets.png","glyph/../../escape.png"]}
        """
          .trimIndent()
      )

    assertTrue(
      requested.none { it.contains("secrets.png") || it.contains("escape.png") },
      "a traversal path from the index was fetched: $requested",
    )
    assertTrue(requested.any { it.endsWith("glyph/mask.png") }, "the legal path was skipped")
  }

  @Test
  fun `a malformed index falls back to deriving rather than staging nothing`() {
    // The fail-soft direction matters. Treating an unreadable index as an empty list would let one
    // bad file silently strip every record of its artifacts — the changed-verdict failure the whole
    // change exists to remove. So a wrong schema means "this producer published no usable index".
    val (host, _) =
      loadWithArtifactIndex("""{"schema":"something-else/v1","artifacts":["glyph/mask.png"]}""")

    // Falls back to the derivation, which refuses this document whole — the same answer a catalog
    // with no index at all gets.
    assertTrue(
      host.knownDifferenceArtifact("glyph/mask.png") !is ServeKnownDifferences.Artifact.Bytes
    )
  }

  @Test
  fun `an index that names nothing stages nothing`() {
    // An empty list is a statement, not an absence: the producer published an index and carried no
    // artifacts. Falling back to the derivation here would make a producer that says "nothing"
    // indistinguishable from one that says nothing at all.
    // The document here is one the derivation *accepts* and would derive two artifacts from, so a
    // fallback is visible: if anything under the artifact root is fetched, the empty list was
    // treated as an absence.
    val (_, requested) =
      loadWithArtifactIndex(
        """{"schema":"compose-preview-known-difference-artifacts/v1","artifacts":[]}""",
        document = DERIVATION_ACCEPTS,
      )

    assertTrue(
      requested.none { it.contains("/parity/known-differences/") },
      "an empty index must not fall back to the derivation: $requested",
    )
  }

  @Test
  fun `catalog stages the published known differences, document and artifacts`() {
    // The failure this guards is silent: `knownDifferences()` reads the staging tree, so a document
    // nobody copies makes the comparison band, the dashboard audit, the `/parity` availability lane
    // and the landing link all behave exactly as they do for a catalog that accepts nothing.
    val root = tempRoot()
    val requested = CopyOnWriteArrayList<String>()
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}]}
      """
        .trimIndent()
    val document =
      """
      {"schema":"compose-preview-known-differences/v1","acceptances":[
        {"id":"glyph","issue":"https://github.com/yschimke/m3-catalog/issues/40",
         "mask":"mask.png","acceptedCandidate":"accepted-candidate.png"},
        {"id":"escapes","issue":"https://github.com/yschimke/m3-catalog/issues/41",
         "mask":"../../../secrets.png"}]}
      """
        .trimIndent()
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = { url ->
          requested += url
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.encodeToByteArray()
            url.endsWith("/parity/known-differences.json") -> document.encodeToByteArray()
            url.endsWith("/parity/known-differences/glyph/mask.png") -> "mask".encodeToByteArray()
            url.endsWith("/parity/known-differences/glyph/accepted-candidate.png") ->
              "accepted".encodeToByteArray()
            url.endsWith("/images/button.png") -> png()
            else -> null
          }
        },
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    val host = registered.getValue("compose-m3")

    // The document reaches the host **verbatim** — the verdicts are the engine's, so the staging
    // path copies bytes rather than judging records.
    val staged = host.knownDifferences()
    assertTrue(staged is ServeKnownDifferences.Document.Text, "the document reached the host")
    assertEquals(document, (staged as ServeKnownDifferences.Document.Text).text)

    // …and so do the artifacts it names, at the paths it names them.
    val mask = host.knownDifferenceArtifact("glyph/mask.png")
    assertTrue(mask is ServeKnownDifferences.Artifact.Bytes, "the mask reached the host: $mask")
    assertEquals("mask", (mask as ServeKnownDifferences.Artifact.Bytes).bytes.decodeToString())
    assertTrue(
      host.knownDifferenceArtifact("glyph/accepted-candidate.png")
        is ServeKnownDifferences.Artifact.Bytes
    )

    // A path the reader would refuse to look up is never fetched, so it cannot be written either.
    assertTrue(
      requested.none { it.contains("secrets.png") },
      "a traversal path was fetched: $requested",
    )
  }

  @Test
  fun `a document past the acceptance cap stages alone, fetching none of its artifacts`() {
    // Past `maxAcceptances` the engine refuses the whole document before it reads one artifact, so
    // every byte fetched for one is held for a result that names no record. Truncating to the cap
    // would pull the first 256 records' artifacts — up to 4 GiB of individually legal files — on
    // every refresh, for a document nothing will ever evaluate.
    val root = tempRoot()
    val requested = CopyOnWriteArrayList<String>()
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}]}
      """
        .trimIndent()
    val records =
      (0..ServeKnownDifferences.MAX_ACCEPTANCES).joinToString(",") { index ->
        """{"id":"glyph-$index","issue":"https://github.com/yschimke/m3-catalog/issues/40",""" +
          """"mask":"mask.png"}"""
      }
    val document = """{"schema":"compose-preview-known-differences/v1","acceptances":[$records]}"""
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = { url ->
          requested += url
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.encodeToByteArray()
            url.endsWith("/parity/known-differences.json") -> document.encodeToByteArray()
            url.contains("/parity/known-differences/") -> "mask".encodeToByteArray()
            url.endsWith("/images/button.png") -> png()
            else -> null
          }
        },
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    assertTrue(
      requested.none { it.contains("/parity/known-differences/") },
      "artifacts were fetched for a document the engine refuses whole: $requested",
    )
    // The document itself is still staged: `too-many-acceptances` is the consumer's verdict to
    // voice, and it needs the bytes to voice it.
    assertTrue(
      registered.getValue("compose-m3").knownDifferences() is ServeKnownDifferences.Document.Text
    )
  }

  @Test
  fun `an over-sized document stages alone, fetching none of its artifacts`() {
    // The ceiling one step earlier than the acceptance cap, and the same reasoning: the reader
    // answers `TooLarge` from the file's length and the route serves 413 without evaluating a
    // record, so not one of the artifacts this document names can ever be read.
    val root = tempRoot()
    val requested = CopyOnWriteArrayList<String>()
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}]}
      """
        .trimIndent()
    // Legal in every other way — right schema, no unknown member, one record, one artifact — and
    // simply too big, so the byte ceiling is the only thing refusing it.
    val padding = "x".repeat(ServeKnownDifferences.MAX_DOCUMENT_BYTES)
    val document =
      """{"schema":"compose-preview-known-differences/v1","acceptances":[
        {"id":"glyph","issue":"https://github.com/yschimke/m3-catalog/issues/40$padding",
         "mask":"mask.png"}]}"""
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = { url ->
          requested += url
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.encodeToByteArray()
            url.endsWith("/parity/known-differences.json") -> document.encodeToByteArray()
            url.contains("/parity/known-differences/") -> "mask".encodeToByteArray()
            url.endsWith("/images/button.png") -> png()
            else -> null
          }
        },
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    assertTrue(
      requested.none { it.contains("/parity/known-differences/") },
      "artifacts were fetched for a document the reader refuses whole: $requested",
    )
    // Staged anyway, because `document-too-large` is a verdict the reader has to be able to voice.
    assertTrue(
      registered.getValue("compose-m3").knownDifferences()
        is ServeKnownDifferences.Document.TooLarge
    )
  }

  @Test
  fun `a document the transport refuses by size still reaches the reader as too-large`() {
    // #4521. The transport's envelope (25 MiB) sits far above the contract's document ceiling
    // (1 MiB), so a document big enough to be refused *by the transport* is one the reader would
    // refuse anyway — but it would refuse it as absent, because a read that brings back no bytes
    // and a branch that published no file were the same `null`. `too-large`/413 and
    // `unreadable`/404 are different verdicts, and the second one hides why.
    //
    // The marker is a length, not a payload: the reader answers from the file's metadata and never
    // opens it, so nothing here materialises the megabytes it stands for.
    val root = tempRoot()
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}]}
      """
        .trimIndent()
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        networkFetch = { url, maxBytes ->
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") ->
              BranchFetch.Ok(catalog.encodeToByteArray())
            url.endsWith("/parity/known-differences.json") -> BranchFetch.TooLarge(maxBytes)
            url.endsWith("/images/button.png") -> BranchFetch.Ok(png())
            else -> BranchFetch.NotFound
          }
        },
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    assertTrue(
      registered.getValue("compose-m3").knownDifferences()
        is ServeKnownDifferences.Document.TooLarge,
      "a size refusal must not read as a document the producer never published",
    )
    assertTrue(
      File(store.liveDir("compose-m3")!!, "parity/known-differences.json").length() >
        ServeKnownDifferences.MAX_DOCUMENT_BYTES,
      "the marker's length alone is what the reader refuses from",
    )
  }

  @Test
  fun `an artifact the transport refuses by size still reaches the reader as too-large`() {
    // The artifact half of #4521, and the half that costs something in practice: a mask past the
    // transport's envelope was staged as nothing at all, so the engine reached
    // `artifact-unreadable` — "the producer published no such file" — for a file the producer did
    // publish and this server declined to carry.
    val root = tempRoot()
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}]}
      """
        .trimIndent()
    val document =
      """{"schema":"${ServeKnownDifferences.SCHEMA}","acceptances":[
        {"id":"glyph","issue":"https://github.com/yschimke/m3-catalog/issues/40",
         "mask":"mask.png"}]}"""
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        networkFetch = { url, maxBytes ->
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") ->
              BranchFetch.Ok(catalog.encodeToByteArray())
            url.endsWith("/parity/known-differences.json") ->
              BranchFetch.Ok(document.encodeToByteArray())
            url.endsWith("/parity/known-differences/glyph/mask.png") ->
              BranchFetch.TooLarge(maxBytes)
            url.endsWith("/images/button.png") -> BranchFetch.Ok(png())
            else -> BranchFetch.NotFound
          }
        },
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    val host = registered.getValue("compose-m3")
    assertTrue(
      host.knownDifferences() is ServeKnownDifferences.Document.Text,
      "the document itself was readable",
    )
    assertEquals(
      ServeKnownDifferences.Artifact.TooLarge,
      host.knownDifferenceArtifact("glyph/mask.png"),
    )
  }

  @Test
  fun `an absent artifact stays absent — the marker is only for a size refusal`() {
    // The other side of the same seam, and the reason the marker is opt-in per lane: a 404 must
    // keep answering `unreadable`. A stager that invented a file for every failure would trade one
    // collapsed verdict for the opposite one.
    val root = tempRoot()
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}]}
      """
        .trimIndent()
    val document =
      """{"schema":"${ServeKnownDifferences.SCHEMA}","acceptances":[
        {"id":"glyph","issue":"https://github.com/yschimke/m3-catalog/issues/40",
         "mask":"mask.png"}]}"""
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        networkFetch = { url, _ ->
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") ->
              BranchFetch.Ok(catalog.encodeToByteArray())
            url.endsWith("/parity/known-differences.json") ->
              BranchFetch.Ok(document.encodeToByteArray())
            url.endsWith("/images/button.png") -> BranchFetch.Ok(png())
            else -> BranchFetch.NotFound
          }
        },
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    assertEquals(
      ServeKnownDifferences.Artifact.Unreadable,
      registered.getValue("compose-m3").knownDifferenceArtifact("glyph/mask.png"),
    )
  }

  @Test
  fun `a refreshing catalog never serves one host against another generation's files`() {
    // #4522. The live directory is generation-scoped, so a refresh assembles `g<n+1>` while the
    // registered host keeps reading `g<n>`. What this pins is the property the shared directory
    // could not have: at every moment between the swap and the new registration, the host that is
    // serving reads the files it was built for.
    //
    // Read through the outgoing host itself, since that is the thing the old shape got wrong: it
    // kept serving from a directory whose files a later load had already replaced, so a lazily-read
    // artifact came back as the new generation's bytes under the old generation's metadata.
    val root = tempRoot()
    val catalog = { marker: String ->
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3","title":"$marker",
       "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}]}
      """
        .trimIndent()
    }
    val document = { marker: String ->
      """{"schema":"${ServeKnownDifferences.SCHEMA}","acceptances":[
        {"id":"glyph","issue":"https://github.com/yschimke/m3-catalog/issues/$marker",
         "mask":"mask.png"}]}"""
    }
    var generation = "1"
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = { url ->
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") ->
              catalog(generation).encodeToByteArray()
            url.endsWith("/parity/known-differences.json") ->
              document(generation).encodeToByteArray()
            url.endsWith("/parity/known-differences/glyph/mask.png") ->
              "mask-$generation".encodeToByteArray()
            url.endsWith("/images/button.png") -> png()
            else -> null
          }
        },
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    val firstHost = registered.getValue("compose-m3")
    val firstDir = store.liveDir("compose-m3")!!

    generation = "2"
    // The second load's staged files land in their own generation directory; the first host's
    // remain exactly where they were until the new host takes over.
    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    val secondDir = store.liveDir("compose-m3")!!
    assertFalse(firstDir == secondDir, "a load publishes a new generation directory")
    assertTrue(firstDir.isDirectory, "the outgoing generation survives its successor's load")

    // The first host still reads its own generation's bytes, not the second's.
    assertEquals(
      "mask-1",
      (firstHost.knownDifferenceArtifact("glyph/mask.png") as ServeKnownDifferences.Artifact.Bytes)
        .bytes
        .decodeToString(),
    )
    // And the new host reads the new ones.
    assertEquals(
      "mask-2",
      (registered.getValue("compose-m3").knownDifferenceArtifact("glyph/mask.png")
          as ServeKnownDifferences.Artifact.Bytes)
        .bytes
        .decodeToString(),
    )

    // A third load retires the first generation: the grace period is one refresh, not forever.
    generation = "3"
    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    assertFalse(firstDir.exists(), "the generation two loads back is swept")
    assertTrue(secondDir.isDirectory, "the generation one load back is still readable")
  }

  @Test
  fun `a document the engine refuses whole names nothing to fetch`() {
    // Every one of these is a rejection of the *file*: the engine reaches it before `readArtifact`
    // is called once, and the result carries no `statuses` at all. So a stager that read the fetch
    // list out of one anyway would pull up to 256 × 2 × 8 MiB of individually legal artifacts, on
    // every refresh, for a verdict that names not one record — the exhaustion the caps exist to
    // prevent, reached through the guard itself.
    val record =
      """{"id":"glyph","issue":"https://github.com/yschimke/m3-catalog/issues/40","mask":"mask.png"}"""
    val refused =
      mapOf(
        "another schema" to
          """{"schema":"compose-preview-known-differences/v2","acceptances":[$record]}""",
        "an unknown document-level member" to
          """{"schema":"${ServeKnownDifferences.SCHEMA}","note":"hi","acceptances":[$record]}""",
        "a record that is not an object" to
          """{"schema":"${ServeKnownDifferences.SCHEMA}","acceptances":[$record,7]}""",
        "an unkeyable id" to
          """{"schema":"${ServeKnownDifferences.SCHEMA}","acceptances":[$record,{"id":"  ","mask":"m.png"}]}""",
        "a non-string id" to
          """{"schema":"${ServeKnownDifferences.SCHEMA}","acceptances":[$record,{"id":7,"mask":"m.png"}]}""",
        // Case-folded: `glyph` and `GLYPH` are two map keys and one directory on Windows and on a
        // default macOS filesystem, so a document carrying both cannot be checked out intact.
        "a case-folded duplicate id" to
          """{"schema":"${ServeKnownDifferences.SCHEMA}","acceptances":[$record,{"id":"GLYPH","mask":"m.png"}]}""",
      )

    for ((why, document) in refused) {
      val root = tempRoot()
      val requested = CopyOnWriteArrayList<String>()
      val store =
        ServeCatalogStore(
          root = root,
          register = { n, h -> registered[n] = h },
          trust = { TrustStore.EMPTY },
          fetch = { url ->
            requested += url
            when {
              url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") ->
                """
                {"schema":"design-parity-catalog/v1","system":"compose-m3",
                 "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}]}
                """
                  .trimIndent()
                  .encodeToByteArray()
              url.endsWith("/parity/known-differences.json") -> document.encodeToByteArray()
              url.contains("/parity/known-differences/") -> "mask".encodeToByteArray()
              url.endsWith("/images/button.png") -> png()
              else -> null
            }
          },
        )

      assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok, why)
      assertTrue(
        requested.none { it.contains("/parity/known-differences/") },
        "artifacts were fetched for a document refused for $why: $requested",
      )
      // Staged regardless: the refusal is the consumer's to voice, and it needs the bytes.
      assertTrue(
        registered.getValue("compose-m3").knownDifferences() is ServeKnownDifferences.Document.Text,
        why,
      )
    }
  }

  @Test
  fun `an id blank only to the JVM does not cost the other records their artifacts`() {
    // The mirror's one forbidden direction: claiming a rejection the engine would not make starves
    // legal records of their artifacts and turns them into `artifact-unreadable`.
    //
    // `String.isBlank()` delegates to `Character.isWhitespace`, which counts U+001C..U+001F as
    // whitespace; ECMAScript's `trim()` does not. So this id is keyable to the engine — that record
    // fails on its own as `id-not-safe` while the rest of the document is read normally — and using
    // the JVM's definition here would have rejected the whole document and skipped `glyph`'s
    // artifacts along with it.
    val root = tempRoot()
    val requested = CopyOnWriteArrayList<String>()
    val document =
      """{"schema":"${ServeKnownDifferences.SCHEMA}","acceptances":[
        {"id":"\u001C","mask":"m.png"},
        {"id":"glyph","issue":"https://github.com/yschimke/m3-catalog/issues/40",
         "mask":"mask.png"}]}"""
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = { url ->
          requested += url
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") ->
              """
              {"schema":"design-parity-catalog/v1","system":"compose-m3",
               "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}]}
              """
                .trimIndent()
                .encodeToByteArray()
            url.endsWith("/parity/known-differences.json") -> document.encodeToByteArray()
            url.endsWith("/parity/known-differences/glyph/mask.png") -> "mask".encodeToByteArray()
            url.endsWith("/images/button.png") -> png()
            else -> null
          }
        },
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    assertTrue(
      requested.any { it.endsWith("/parity/known-differences/glyph/mask.png") },
      "the legal record's artifact was skipped: $requested",
    )
    assertTrue(
      registered.getValue("compose-m3").knownDifferenceArtifact("glyph/mask.png")
        is ServeKnownDifferences.Artifact.Bytes
    )
  }

  @Test
  fun `an id blank to JavaScript still rejects the document`() {
    // The other side of the same definition: `trim()` removes the non-breaking U+00A0, which
    // `Character.isWhitespace` does not. The engine calls this id unkeyable and rejects the
    // document, so nothing here is worth fetching for.
    val root = tempRoot()
    val requested = CopyOnWriteArrayList<String>()
    val document =
      """{"schema":"${ServeKnownDifferences.SCHEMA}","acceptances":[
        {"id":"\u00A0","mask":"m.png"},
        {"id":"glyph","issue":"https://github.com/yschimke/m3-catalog/issues/40",
         "mask":"mask.png"}]}"""
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = { url ->
          requested += url
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") ->
              """
              {"schema":"design-parity-catalog/v1","system":"compose-m3",
               "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}]}
              """
                .trimIndent()
                .encodeToByteArray()
            url.endsWith("/parity/known-differences.json") -> document.encodeToByteArray()
            url.contains("/parity/known-differences/") -> "mask".encodeToByteArray()
            url.endsWith("/images/button.png") -> png()
            else -> null
          }
        },
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    assertTrue(
      requested.none { it.contains("/parity/known-differences/") },
      "artifacts were fetched for a document the engine refuses whole: $requested",
    )
  }

  /** A catalog whose only optional extra is its parity issue index, fetched through one seam. */
  private fun loadWithIssueIndexOutcome(
    issues: BranchFetch
  ): Pair<ServeCatalogStore.Result, List<String>> {
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}]}
      """
        .trimIndent()
    val asked = CopyOnWriteArrayList<String>()
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        networkFetch = { url, _ ->
          asked += url
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") ->
              BranchFetch.Ok(catalog.encodeToByteArray())
            url.endsWith("/parity/issues.json") -> issues
            url.endsWith(".png") -> BranchFetch.Ok(png())
            else -> BranchFetch.NotFound
          }
        },
      )
    return store.load("compose-m3") to asked.toList()
  }

  @Test
  fun `a throttled optional asset leaves the load incomplete`() {
    // The load succeeds — every writer beside the required ones is fail-soft, and a catalog missing
    // its issue index is far better than no catalog. What it must NOT do is look settled: the
    // absence is ours, not the producer's, so `incomplete` is what stops the refresher recording
    // this revision as current and never re-reading it.
    val (result, asked) = loadWithIssueIndexOutcome(BranchFetch.Throttled(retryAfterSeconds = 5))
    assertTrue(asked.any { it.endsWith("/parity/issues.json") }, "the index was asked for: $asked")
    val ok = result as ServeCatalogStore.Result.Ok
    assertTrue(ok.incomplete, "a throttled optional asset must not read as a settled revision")
  }

  @Test
  fun `an optional asset the branch does not have leaves the load complete`() {
    // The other half, and the one that must not regress into needless re-reads: `404` is an answer.
    // Most catalogs publish no issue index at all, so treating absence as incomplete would put
    // every one of them into a permanent re-read loop.
    val (result, _) = loadWithIssueIndexOutcome(BranchFetch.NotFound)
    val ok = result as ServeCatalogStore.Result.Ok
    assertTrue(!ok.incomplete, "a genuinely absent optional asset is a settled answer")
  }

  @Test
  fun `a transport failure on an optional asset leaves the load incomplete`() {
    val (result, _) = loadWithIssueIndexOutcome(BranchFetch.Transport("SocketTimeoutException"))
    assertTrue((result as ServeCatalogStore.Result.Ok).incomplete)
  }

  @Test
  fun `a transient failure on a request-time read does not un-settle a concurrent load`() {
    // `incomplete` speaks for the operation that issued the read, not for the store. Lazy
    // request-time reads — a capture somebody opened, a pinned asset — run continuously against
    // the same branch host, so a store-wide signal would let one reader retrying an unavailable
    // capture keep every complete revision unsettled and force a full reload every polling
    // interval. That is traffic amplification precisely while the host is unwell, which is the
    // condition the mechanism exists to survive.
    val requested = CopyOnWriteArrayList<String>()
    var host: ServeBundleHost? = null
    val watched = java.util.concurrent.atomic.AtomicBoolean(false)
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        networkFetch = { url, _ ->
          requested += url
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> {
              // Once the host exists, drive one lazy capture read from a thread that is inside no
              // load at all, and let it finish before this load continues.
              val h = host
              if (h != null && watched.compareAndSet(false, true)) {
                val t = Thread { h.motionBytes("switch-on__ideal__default__dark", ".apng") }
                t.start()
                t.join()
              }
              BranchFetch.Ok(motionCatalogJson.toByteArray())
            }
            url.endsWith(".png") -> BranchFetch.Ok(png())
            // The capture is the unwell asset. It is never staged at registration, so only the
            // request-time read below ever touches it.
            url.endsWith(".apng") -> BranchFetch.Throttled(retryAfterSeconds = 5)
            else -> BranchFetch.NotFound
          }
        },
      )

    assertTrue((store.load("compose-m3") as ServeCatalogStore.Result.Ok).incomplete.not())
    host = registered.getValue("compose-m3")

    val second = store.load("compose-m3") as ServeCatalogStore.Result.Ok
    assertTrue(watched.get(), "the request-time read ran during the second load")
    assertTrue(requested.any { it.endsWith(".apng") }, "and it really was throttled: $requested")
    assertFalse(
      second.incomplete,
      "a throttled read issued outside this load must not un-settle the revision it loaded",
    )
    assertNull(host.motionBytes("switch-on__ideal__default__dark", ".apng"))
  }

  @Test
  fun `a throttled post-publish vector fill un-settles the revision`() {
    // `incomplete` can only speak for what the load itself read. The vector fills run on
    // `figmaExecutor` AFTER the catalog is published, so a throttle there lands once the result has
    // been handed back and the branch head recorded — and without this the missing vectors would
    // wait for the next commit, which is the permanence this whole change exists to end.
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png",
         "figma":{"nodeId":"1:2"}}]}]}
      """
        .trimIndent()
    val unsettled = CopyOnWriteArrayList<String>()
    val deferred = mutableListOf<Runnable>()
    // The publish path itself takes one or two vectors inline (see `scheduleFigmaSvgFetch`) and
    // leaves the rest to the deferred lane. Throttling only after the load has returned is what
    // isolates the case under test: a failure the load could not possibly have counted.
    var published = false
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        // Deferred, so the load returns before the lane runs — which is the whole point.
        figmaExecutor = java.util.concurrent.Executor { deferred += it },
        onPostPublishIncomplete = { unsettled += it },
        networkFetch = { url, _ ->
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") ->
              BranchFetch.Ok(catalog.encodeToByteArray())
            url.endsWith(".png") -> BranchFetch.Ok(png())
            url.endsWith(".svg") ->
              if (published) BranchFetch.Throttled(retryAfterSeconds = 5)
              else BranchFetch.Ok("<svg/>".encodeToByteArray())
            else -> BranchFetch.NotFound
          }
        },
      )

    val result = store.load("compose-m3") as ServeCatalogStore.Result.Ok
    // The load itself read everything it needed: the vectors are not its business.
    assertTrue(!result.incomplete, "the publish path itself was complete")
    assertEquals(emptyList(), unsettled.toList(), "nothing is reported before the lane runs")

    published = true
    deferred.forEach { it.run() }

    assertEquals(
      listOf("compose-m3"),
      unsettled.toList(),
      "a throttled vector fill must un-settle the revision so the next tick re-reads it",
    )
  }

  @Test
  fun `a superseded post-publish lane does not un-settle the revision that replaced it`() {
    // The lane checks the generation on entry, but it does network I/O afterwards — so a refresh
    // can land a whole new revision while it is still reading. The entry check cannot see that;
    // reporting anyway un-settles the *fresh* revision over a throttle belonging to the one it
    // replaced, costing it a needless full reload. The new revision reports for itself.
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png",
         "figma":{"nodeId":"1:2"}}]}]}
      """
        .trimIndent()
    val unsettled = CopyOnWriteArrayList<String>()
    val deferred = mutableListOf<Runnable>()
    lateinit var store: ServeCatalogStore
    // 0: first load. 1: the stale lane is reading. 2: the superseding load is running inside it.
    var phase = 0
    var superseded = false
    store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        figmaExecutor = java.util.concurrent.Executor { deferred += it },
        onPostPublishIncomplete = { unsettled += it },
        networkFetch = { url, _ ->
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") ->
              BranchFetch.Ok(catalog.encodeToByteArray())
            url.endsWith(".png") -> BranchFetch.Ok(png())
            url.endsWith(".svg") ->
              if (phase != 1) BranchFetch.Ok("<svg/>".encodeToByteArray())
              else {
                // Mid-read, a refresh lands a whole new revision under a new generation — the
                // thing the lane's entry check ran too early to see.
                if (!superseded) {
                  superseded = true
                  phase = 2
                  store.load("compose-m3")
                  phase = 1
                }
                BranchFetch.Throttled(retryAfterSeconds = 5)
              }
            else -> BranchFetch.NotFound
          }
        },
      )

    store.load("compose-m3")
    val stale = deferred.toList()
    deferred.clear()

    phase = 1
    stale.forEach { it.run() }
    assertTrue(superseded, "a newer revision really did land while the stale lane was reading")
    assertEquals(
      emptyList(),
      unsettled.toList(),
      "a lane whose revision has been superseded must not un-settle the one that replaced it",
    )
  }

  @Test
  fun `an over-sized artifact is staged, so the reader can refuse it as too large`() {
    // Dropping it would leave a missing file, and a missing file is `artifact-unreadable`/404 — a
    // different verdict from the contract's `artifact-too-large`/413, and one that hides why.
    val root = tempRoot()
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}]}
      """
        .trimIndent()
    val document =
      """
      {"schema":"compose-preview-known-differences/v1","acceptances":[
        {"id":"glyph","issue":"https://github.com/yschimke/m3-catalog/issues/40",
         "mask":"mask.png"}]}
      """
        .trimIndent()
    val oversized = ByteArray(ServeKnownDifferences.MAX_ARTIFACT_BYTES + 1)
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = { url ->
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.encodeToByteArray()
            url.endsWith("/parity/known-differences.json") -> document.encodeToByteArray()
            url.endsWith("/parity/known-differences/glyph/mask.png") -> oversized
            url.endsWith("/images/button.png") -> png()
            else -> null
          }
        },
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    val artifact = registered.getValue("compose-m3").knownDifferenceArtifact("glyph/mask.png")
    assertTrue(
      artifact is ServeKnownDifferences.Artifact.TooLarge,
      "the reader refuses it from the file's length: $artifact",
    )
  }

  @Test
  fun `a catalog publishing no known differences serves without them`() {
    val root = tempRoot()
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}]}
      """
        .trimIndent()
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = { url ->
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.encodeToByteArray()
            url.endsWith("/images/button.png") -> png()
            else -> null
          }
        },
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    // Null, not an empty document: "this catalog accepts nothing" is the ordinary case, and the
    // band and the panel are both absent for it.
    assertNull(registered.getValue("compose-m3").knownDifferences())
  }

  @Test
  fun `a catalog publishing no parity feed serves without one`() {
    val root = tempRoot()
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}]}
      """
        .trimIndent()
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = { url ->
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.encodeToByteArray()
            url.endsWith("/images/button.png") -> png()
            else -> null
          }
        },
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    assertNull(registered.getValue("compose-m3").parityActivity())
  }

  /** A malformed feed must not reach the staging tree, let alone the page. */
  @Test
  fun `a parity feed that fails validation is not staged`() {
    val root = tempRoot()
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}]}
      """
        .trimIndent()
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = { url ->
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.encodeToByteArray()
            // Right filename, wrong schema token — the reader would discard it anyway; this
            // asserts it never lands.
            url.endsWith("/parity/activity.json") ->
              """{"schema":"something-else/v9","gaps":[]}""".encodeToByteArray()
            url.endsWith("/images/button.png") -> png()
            else -> null
          }
        },
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    assertNull(registered.getValue("compose-m3").parityActivity())
  }

  @Test
  fun `valid inline reference survives an invalid manifest duplicate`() {
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "components":[{"componentId":"Button/Filled","images":[{"path":"images/button.png"}]}],
       "references":[{
         "id":"button-design","previewId":"button","label":"Inline fallback",
         "raster":{"path":"references/inline.png","width":2,"height":2},
         "source":{"provider":"inline"}
       }]}
      """
        .trimIndent()
    val manifest =
      """
      {"schema":"compose-preview-references/v1","references":[{
        "id":"button-design","previewId":"button","label":"Broken manifest entry",
        "raster":{"path":"references/manifest.png","width":2,"height":2,
          "sha256":"0000000000000000000000000000000000000000000000000000000000000000"},
        "source":{"provider":"manifest"}
      }]}
      """
        .trimIndent()
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.encodeToByteArray()
        url.endsWith("/references/index.json") -> manifest.encodeToByteArray()
        url.endsWith("/images/button.png") || url.endsWith("/references/manifest.png") -> png()
        url.endsWith("/references/inline.png") -> png()
        else -> null
      }
    }

    assertTrue(
      store(TrustStore.EMPTY, fetch = fetch).load("compose-m3") is ServeCatalogStore.Result.Ok
    )

    val reference = registered.getValue("compose-m3").designReferencesFor("button").single()
    assertEquals("button-design", reference.id)
    assertEquals("Inline fallback", reference.label)
    assertEquals("inline", reference.source.provider)
  }

  @Test
  fun `a failed re-load leaves the previously-served catalog intact`() {
    // The ServeCatalogRefresher re-runs load() on a live server; a transient total image outage
    // must NOT delete the currently-served catalog (which would 404 it until the next success).
    val root = tempRoot()
    var imagesAvailable = true
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalogJson.toByteArray()
        url.endsWith(".png") -> if (imagesAvailable) png() else null
        else -> null
      }
    }
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = fetch,
        registerWasm = { s, d ->
          if (d == null) registeredWasm.remove(s) else registeredWasm[s] = d
        },
      )
    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok, "first load succeeds")
    val png =
      File(store.liveDir("compose-m3")!!, "previews/button-filled__ideal__default__dark.png")
    assertTrue(png.isFile, "the first load writes the preview PNG on disk")

    // Re-load with every image (transiently) unavailable — parseable catalog.json, zero images.
    imagesAvailable = false
    assertTrue(
      store.load("compose-m3") is ServeCatalogStore.Result.Failed,
      "a catalog with no usable images fails the re-load",
    )
    assertTrue(png.isFile, "the previously-served catalog is left intact on a failed re-load")
    assertFalse(
      File(root, "compose-m3/${ServeCatalogStore.STAGING_DIR}").exists(),
      "the staging dir is cleaned up",
    )
  }

  @Test
  fun `a catalog's baked figma svgs are fetched and served self-contained`() {
    val flatSvg = "<svg><text>legacy light fallback</text></svg>"
    val variantSvg = "<svg><image href=\"ideal__default__dark.figma-raster/n0.png\"/></svg>"
    val crop = byteArrayOf(7, 7, 7)
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalogJson.toByteArray()
        url.endsWith("figma/button-filled.svg") -> flatSvg.toByteArray()
        url.endsWith("figma/button-filled/ideal__default__dark.svg") -> variantSvg.toByteArray()
        url.endsWith("figma/button-filled/ideal__default__dark.figma-raster/n0.png") -> crop
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    store(TrustStore.EMPTY, fetch = fetch).load("compose-m3")

    val host = registered.getValue("compose-m3")
    val ok =
      host.renderSvg("button-filled__ideal__default__dark", PreviewOverrides()) as SvgOutcome.Ok
    val out = ok.svg.decodeToString()
    val expected = java.util.Base64.getEncoder().encodeToString(crop)
    assertTrue(
      out.contains("data:image/png;base64,$expected"),
      "the exact dark-variant SVG is served and its sibling crop is inlined: $out",
    )
    assertFalse(out.contains("legacy light fallback"), "the flat light SVG does not replace dark")
  }

  @Test
  fun `a catalog's published design tokens re-theme its web pages`() {
    val tokens =
      """{"color":{"primary":{"${'$'}type":"color","${'$'}value":"#bf0031ff"},
         "surface":{"${'$'}type":"color","${'$'}value":"#fffbffff"},
         "onSurface":{"${'$'}type":"color","${'$'}value":"#201a1aff"}}}"""
    fun load(tokensFile: String): ServeBundleHost {
      registered.clear()
      val withTokens = catalogJson.dropLast(1) + ""","tokensFile":"$tokensFile"}"""
      store(TrustStore.EMPTY) { url ->
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> withTokens.toByteArray()
            url.endsWith("/tokens.dtcg.json") -> tokens.toByteArray()
            url.endsWith(".png") -> png()
            else -> null
          }
        }
        .load("compose-m3")
      return registered.getValue("compose-m3")
    }

    // The declared token file is fetched off the same branch as the images and projected onto the
    // chrome's custom properties, so this system's pages carry its crimson rather than the
    // built-in indigo.
    val themed = load("tokens.dtcg.json").webThemeCss
    assertTrue(
      // Light half of the pair: the projection emits one `light-dark(<light>, <dark>)` declaration
      // per property (see ServeThemeCssTest), and this catalog is light-first.
      themed != null && themed.contains("--cp-accent: light-dark(#bf0031, "),
      "the catalog's own primary reaches the page palette: $themed",
    )
    // A `tokensFile` that tries to leave the catalog is not fetched at all — the branch is trusted,
    // but a garbled/hostile value must not aim the fetch elsewhere. The pages then serve unthemed.
    for (escape in listOf("../../secrets.json", "/etc/passwd", "https://elsewhere/tokens.json")) {
      assertNull(load(escape).webThemeCss, "tokensFile '$escape' must not be fetched")
    }
  }

  @Test
  fun `a catalog with no design tokens serves the built-in chrome`() {
    store(TrustStore.EMPTY).load("compose-m3")
    assertNull(registered.getValue("compose-m3").webThemeCss)
  }

  @Test
  fun `preview ids are flattened to a single route-safe segment`() {
    assertEquals(
      "button-filled__ideal__default__dark",
      ServeCatalogStore.previewIdFor("images/button-filled/ideal__default__dark.png"),
    )
  }

  @Test
  fun `a state-bearing catalog writes a variants manifest that round-trips onto host previews`() {
    // A checkbox with a default + a non-default (unchecked) state, each in light and dark.
    val stateful =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3","components":[
        {"componentId":"Checkbox","images":[
          {"path":"images/checkbox/ideal__default__light.png","state":"default","theme":"light"},
          {"path":"images/checkbox/ideal__default__dark.png","state":"default","theme":"dark"},
          {"path":"images/checkbox/ideal__unchecked__light.png","state":"unchecked","theme":"light"},
          {"path":"images/checkbox/ideal__unchecked__dark.png","state":"unchecked","theme":"dark"}]}]}
      """
        .trimIndent()
    val root = tempRoot()
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> stateful.toByteArray()
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = fetch,
        registerWasm = { s, d ->
          if (d == null) registeredWasm.remove(s) else registeredWasm[s] = d
        },
      )
    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)

    // The manifest is written into the served previews dir, with null keys omitted (all present
    // here).
    val manifest =
      File(store.liveDir("compose-m3")!!, "previews/${ServeCatalogStore.VARIANTS_FILE}")
    assertTrue(manifest.isFile, "variants.json is written")
    val text = manifest.readText()
    assertTrue(
      text.contains(
        "\"checkbox__ideal__unchecked__light\":{" +
          "\"state\":\"unchecked\",\"theme\":\"light\",\"componentId\":\"Checkbox\"}"
      ),
      "manifest carries the unchecked/light entry: $text",
    )

    // …and round-trips onto the registered host's previews.
    val host = registered.getValue("compose-m3")
    val byId = host.previews.associateBy { it.id }
    assertEquals(
      "unchecked" to "dark",
      byId.getValue("checkbox__ideal__unchecked__dark").let { it.state to it.theme },
    )
    assertEquals(
      "default" to "light",
      byId.getValue("checkbox__ideal__default__light").let { it.state to it.theme },
    )
  }

  @Test
  fun `repository-wide catalog retains each preview source module`() {
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"all-modules",
       "source":{"repo":"yschimke/compose-ai-tools","ref":"main","module":""},
       "components":[{"componentId":"TV","sourceFile":"src/main/kotlin/Main.kt",
         "sourceModule":":tv","images":[{"path":"images/tv.png"}]}]}
      """
        .trimIndent()
    val cleared = mutableListOf<String>()
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = { url ->
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.toByteArray()
            url.endsWith(".png") -> png()
            else -> null
          }
        },
        clearTrustedBundles = { cleared += it },
      )

    assertTrue(store.load("all-modules") is ServeCatalogStore.Result.Ok)
    val preview = registered.getValue("all-modules").previews.single()
    assertEquals("src/main/kotlin/Main.kt", preview.sourceFile)
    assertEquals(":tv", preview.sourceModule)
    assertEquals(listOf("all-modules"), cleared)
  }

  @Test
  fun `catalog image declarations reach the baked browse surface`() {
    // A supplement-only preview's daemon is opened lazily, so these catalog fields are the only
    // declaration source available when /api/previews and the initial viewer are built.
    val declared =
      """
      {"schema":"design-parity-catalog/v1","system":"meshcore","components":[
        {"componentId":"Device","images":[{
          "path":"images/device/ideal__default__dark.png",
          "previewId":"Device_Dark",
          "overrides":[{"key":"count","type":"int","label":"Count",
            "default":{"kind":"int","value":2}}],
          "remoteComposeKnobs":[{"name":"label",
            "default":{"kind":"string","value":"Hello"}}],
          "supportsFocus":true,
          "supportsGestures":true
        }]}]}
      """
        .trimIndent()
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> declared.toByteArray()
        url.endsWith(".png") -> png()
        else -> null
      }
    }

    assertTrue(
      store(TrustStore.EMPTY, fetch = fetch).load("meshcore") is ServeCatalogStore.Result.Ok
    )

    val preview = registered.getValue("meshcore").previews.single()
    assertEquals(listOf("count"), preview.overrides.map { it.key })
    assertEquals(listOf("label"), preview.remoteComposeKnobs.map { it.name })
    assertTrue(preview.supportsFocus)
    assertTrue(preview.supportsGestures)
  }

  @Test
  fun `a fixedTheme image reaches the browse surface with nothing else declared`() {
    // A theme specimen declares no knobs and detects no features. `fixedTheme` therefore has to
    // carry a variants-manifest entry on its own — if it didn't, the specimen would arrive with no
    // metadata at all and the landing would happily re-render it under a themeProvider override.
    val declared =
      """
      {"schema":"design-parity-catalog/v1","system":"meshcore","components":[
        {"componentId":"Theme","images":[{
          "path":"images/theme/meshcore-light.png",
          "previewId":"themecatalog__MeshCore_Light",
          "fixedTheme":true
        }]}]}
      """
        .trimIndent()
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> declared.toByteArray()
        url.endsWith(".png") -> png()
        else -> null
      }
    }

    assertTrue(
      store(TrustStore.EMPTY, fetch = fetch).load("meshcore") is ServeCatalogStore.Result.Ok
    )

    assertTrue(registered.getValue("meshcore").previews.single().fixedTheme)
  }

  @Test
  fun `catalog props preserve arbitrary JSON values through the variants manifest`() {
    val flexibleProps =
      """
      {"schema":"design-parity-catalog/v1","system":"reply","components":[
        {"componentId":"Adaptive/Phone","images":[
          {"path":"images/adaptive-phone/ideal__default.png","props":{
            "enabled":true,
            "count":3,
            "nullable":null,
            "nested":{"mode":"compact"},
            "items":[1,"two"]
          }}]}]}
      """
        .trimIndent()
    val root = tempRoot()
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> flexibleProps.toByteArray()
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = fetch,
      )

    assertTrue(store.load("reply") is ServeCatalogStore.Result.Ok)
    val expected =
      Json.parseToJsonElement(
          """{"enabled":true,"count":3,"nullable":null,"nested":{"mode":"compact"},"items":[1,"two"]}"""
        )
        .jsonObject
    val preview = registered.getValue("reply").previews.single()
    assertEquals(expected, preview.props)

    val manifest =
      File(store.liveDir("reply")!!, "previews/${ServeCatalogStore.VARIANTS_FILE}").readText()
    assertEquals(
      expected,
      Json.parseToJsonElement(manifest)
        .jsonObject
        .values
        .single()
        .jsonObject
        .getValue("props")
        .jsonObject,
    )
  }

  @Test
  fun `a malformed catalog reports the deserialization error`() {
    val malformed = """{"components":"not-an-array"}"""
    val result =
      store(
          TrustStore.EMPTY,
          fetch = { url ->
            if (url.endsWith("/${ServeCatalogStore.CATALOG_FILE}")) malformed.toByteArray()
            else null
          },
        )
        .load("broken")

    assertTrue(result is ServeCatalogStore.Result.Failed)
    assertTrue(result.reason.startsWith("could not parse catalog.json: "), result.reason)
    assertTrue(result.reason.length > "could not parse catalog.json: ".length, result.reason)
  }

  @Test
  fun `a sectioned catalog carries section, group and order onto host previews`() {
    // Two components tagged with a section (the tab) + group (the sub-heading) — the tabbed-catalog
    // structure. Order follows the authored component list, not the id-sorted host order.
    val sectioned =
      """
      {"schema":"design-parity-catalog/v1","system":"meshcore-mobile","components":[
        {"componentId":"Theme/Light","section":"Themes","group":"Foundation","images":[
          {"path":"images/theme-light/ideal__default__compact.png"}]},
        {"componentId":"ContactRow","section":"Components","group":"Contacts","images":[
          {"path":"images/contactrow/ideal__default__compact.png"}]}]}
      """
        .trimIndent()
    val root = tempRoot()
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> sectioned.toByteArray()
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = fetch,
        registerWasm = { s, d ->
          if (d == null) registeredWasm.remove(s) else registeredWasm[s] = d
        },
      )
    assertTrue(store.load("meshcore-mobile") is ServeCatalogStore.Result.Ok)

    // variants.json carries the section/group/order the tabbed landing keys off (state/theme
    // absent).
    val manifest =
      File(store.liveDir("meshcore-mobile")!!, "previews/${ServeCatalogStore.VARIANTS_FILE}")
    val text = manifest.readText()
    assertTrue(
      text.contains("\"section\":\"Themes\"") && text.contains("\"group\":\"Foundation\""),
      "manifest carries section + group: $text",
    )
    assertTrue(text.contains("\"order\":"), "manifest carries the authored order: $text")

    // …and round-trips onto the host previews, in authored order (Themes component first).
    val host = registered.getValue("meshcore-mobile")
    val byId = host.previews.associateBy { it.id }
    val theme = byId.getValue("theme-light__ideal__default__compact")
    assertEquals("Themes", theme.section)
    assertEquals("Foundation", theme.group)
    assertEquals(0, theme.catalogOrder)
    val row = byId.getValue("contactrow__ideal__default__compact")
    assertEquals("Components", row.section)
    assertEquals("Contacts", row.group)
    assertEquals(1, row.catalogOrder)
  }

  @Test
  fun `a trusted liveBundle catalog hands the builder the catalog-id to daemon-id alias`() {
    // A catalog that carries a liveBundle and per-image previewId: the store fetches the bundle and
    // invokes the live builder with the catalog-id → daemon-id alias so it can bridge the two id
    // namespaces (see ServeCatalogLiveHost). Only the image that declares a previewId is aliased.
    val json =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "liveBundle":{"path":"bundle/","file":"compose-m3-bundle.png"},
       "components":[{"componentId":"Button/Filled","images":[
         {"path":"images/button-filled/ideal__default__dark.png","theme":"dark","previewId":"FilledButton_Dark"},
         {"path":"images/button-filled/ideal__keyboard-focus__dark.png","theme":"dark"}]}]}
      """
        .trimIndent()
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> json.toByteArray()
        url.endsWith("bundle/compose-m3-bundle.png") -> byteArrayOf(1, 2, 3)
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    val trust =
      TrustStore(
        branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*"))
      )
    var captured: Map<String, String>? = null
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trust },
        fetch = fetch,
        buildTrustedBundle = { _, _, _, alias, _, _ ->
          captured = alias
          true // pretend the live host took over, so no static host is registered
        },
      )
    val result = store.load("compose-m3")

    assertTrue(result is ServeCatalogStore.Result.Ok)
    // Only the previewId-bearing image is aliased; the keyboard-focus (Android-only) image is not.
    assertEquals(mapOf("button-filled__ideal__default__dark" to "FilledButton_Dark"), captured)
    // The live builder claimed the session, so nothing was registered as a plain static host.
    assertTrue(registered["compose-m3"] == null)
  }

  @Test
  fun `liveBundles partition aliases and per-preview paths by module`() {
    val prefix = "module_3a7476__"
    val json =
      """
      {"schema":"design-parity-catalog/v1","system":"all-modules",
       "liveBundle":{"path":"bundle/","file":"0000.png"},
       "liveBundles":[
         {"module":":mobile","path":"bundle/","file":"0000.png","previewIdPrefix":""},
         {"module":":tv","path":"bundle/modules/module_3a7476/","file":"module_3a7476.png","previewIdPrefix":"$prefix"}],
       "components":[
         {"componentId":"Mobile","images":[{"path":"images/mobile.png","previewId":"activity__MainActivity"}]},
         {"componentId":"TV","images":[{"path":"images/tv.png","previewId":"${prefix}activity__MainActivity"}]}]}
      """
        .trimIndent()
    val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
    val fetch: (String) -> ByteArray? = { url ->
      requested += url
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> json.toByteArray()
        url.endsWith("bundle/0000.png") ||
          url.endsWith("bundle/modules/module_3a7476/module_3a7476.png") -> byteArrayOf(1, 2, 3)
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    val trust =
      TrustStore(
        branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*"))
      )
    var captured: List<ServeCatalogStore.TrustedModuleBundle>? = null
    var recorded: List<ServeCatalogStore.VerifiedModuleBundle>? = null
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trust },
        fetch = fetch,
        buildTrustedBundles = { _, bundles, _ ->
          captured = bundles
          true
        },
        recordTrustedBundles = { _, bundles -> recorded = bundles },
      )

    assertTrue(store.load("all-modules") is ServeCatalogStore.Result.Ok)
    val modules = assertNotNull(captured)
    assertEquals(listOf(":mobile", ":tv"), modules.map { it.module })
    assertEquals(listOf(":mobile", ":tv"), assertNotNull(recorded).map { it.module })
    assertEquals(mapOf("mobile" to "activity__MainActivity"), modules[0].alias)
    assertEquals(mapOf("tv" to "${prefix}activity__MainActivity"), modules[1].alias)
    modules[1].perPreviewBundle.fetch("${prefix}activity__MainActivity")
    assertTrue(
      requested.any {
        it.endsWith("bundle/modules/module_3a7476/previews/${prefix}activity__MainActivity.png")
      }
    )
    assertTrue(registered["all-modules"] == null)
  }

  @Test
  fun `a mixed liveBundle routes class-backed and IR previews to the daemon`() {
    val remoteId = "com.example.CatalogKt.RemotePreview"
    val widgetId = "com.example.WidgetKt.WidgetPreview"
    val rcBytes = byteArrayOf(0x52, 0x43, 0x01)
    val bundle =
      polyglotBundle(
        manifest =
          """
          {"schemaVersion":8,"backend":"android",
           "previewIds":["$remoteId","$widgetId"],"coverPreviewId":"$remoteId",
           "classpath":[{"kind":"module","path":"classes/app.jar"}],
           "modulePath":":samples:remote","producedBy":"test",
           "intermediateRepresentations":[
             {"previewId":"$remoteId","format":"remotecompose","path":"ir/$remoteId.rc"}
           ],"externalResources":[]}
          """
            .trimIndent(),
        extra = mapOf("ir/$remoteId.rc" to rcBytes),
      )
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"remote-m3",
       "liveBundle":{"path":"bundle/","file":"bundle.png"},
       "components":[
         {"componentId":"Remote","images":[
           {"path":"images/remote/ideal__default.png","previewId":"$remoteId"}]},
         {"componentId":"Widget","images":[
           {"path":"images/widget/ideal__default.png","previewId":"$widgetId"}]}
       ]}
      """
        .trimIndent()
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.toByteArray()
        url.endsWith("bundle/bundle.png") -> bundle
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    val root = tempRoot()
    var captured: Map<String, String>? = null
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = {
          TrustStore(
            branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*"))
          )
        },
        fetch = fetch,
        buildTrustedBundle = { _, _, _, alias, _, _ ->
          captured = alias
          true
        },
      )

    assertTrue(store.load("remote-m3") is ServeCatalogStore.Result.Ok)
    assertEquals(
      mapOf("remote__ideal__default" to remoteId, "widget__ideal__default" to widgetId),
      captured,
      "the daemon replays IR previews from the carried document instead of reflecting a class",
    )
    assertContentEquals(
      rcBytes,
      File(store.liveDir("remote-m3")!!, "ir/remote__ideal__default.rc").readBytes(),
      "the IR-backed preview remains available for browser-side replay",
    )
  }

  @Test
  fun `live bundles use the larger dedicated download envelope`() {
    // jetchat (36.5 MB) and jetsnack (51.2 MB) are valid published bundles that exceed the 25 MB
    // catalog-asset cap. The ordinary fetcher must remain tight for images, while the executable
    // bundle takes the dedicated 100 MB path shared with uploaded/startup bundles.
    assertTrue(
      ServeCatalogStore.MAX_LIVE_BUNDLE_FETCH_BYTES >= 51_218_125L,
      "the live-bundle cap must accommodate the published jetsnack bundle",
    )
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"jetsnack",
       "liveBundle":{"path":"bundle/","file":"bundle.png"},
       "components":[{"componentId":"Button","images":[
         {"path":"images/button/ideal__default.png","previewId":"ButtonPreview"}]}]}
      """
        .trimIndent()
    // Written from the `serve-catalog-fetch` pool (up to ASSET_FETCH_CONCURRENCY threads call the
    // transport at once), so the recorder has to be concurrent and the assertions have to run off a
    // snapshot — a plain map races put-with-put and iteration-with-put. Insertion order is not
    // relied on: both assertions select by key.
    val requestedLimits = ConcurrentHashMap<String, Long>()
    // Outcome-shaped like the seam it stands in for: one transport, so no lane can reach the
    // network around an injected one.
    val networkFetch: (String, Long) -> BranchFetch = { url, maxBytes ->
      requestedLimits[url] = maxBytes
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> BranchFetch.Ok(catalog.toByteArray())
        url.endsWith("bundle/bundle.png") -> BranchFetch.Ok(byteArrayOf(1, 2, 3))
        url.endsWith(".png") -> BranchFetch.Ok(png())
        else -> BranchFetch.NotFound
      }
    }
    val trust =
      TrustStore(
        branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*"))
      )
    var builderCalled = false
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trust },
        networkFetch = networkFetch,
        buildTrustedBundle = { _, _, _, _, _, _ ->
          builderCalled = true
          true
        },
      )

    assertTrue(store.load("jetsnack") is ServeCatalogStore.Result.Ok)
    val limits = requestedLimits.toMap()
    assertEquals(
      25L * 1024 * 1024,
      limits.entries.single { it.key.endsWith("/catalog.json") }.value,
    )
    assertEquals(
      ServeCatalogStore.MAX_LIVE_BUNDLE_FETCH_BYTES,
      limits.entries.single { it.key.endsWith("bundle/bundle.png") }.value,
    )
    assertTrue(builderCalled)
  }

  @Test
  fun `a trusted liveBundle catalog materialises ir rc docs re-keyed to the catalog id`() {
    // The live bundle carries the captured Remote Compose document as `ir/<daemon-id>.rc`; the
    // store
    // re-keys it to the published catalog id (via the same alias) so the baked host's client-side
    // canvas lane serves it at `/render/<catalog-id>.rc`. A preview whose daemon twin has no `.rc`
    // entry stays docless.
    val root = tempRoot()
    val rcBytes = byteArrayOf(0x52, 0x43, 0x07, 0x08)
    val bundle =
      polyglotBundle(
        manifest =
          """{"schemaVersion":8,"backend":"desktop","previewIds":["a"],"coverPreviewId":"a",""" +
            """"externalResources":[]}""",
        extra = mapOf("ir/FilledButton_Dark.rc" to rcBytes),
      )
    val json =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "liveBundle":{"path":"bundle/","file":"compose-m3-bundle.png"},
       "components":[{"componentId":"Button/Filled","images":[
         {"path":"images/button-filled/ideal__default__dark.png","theme":"dark","previewId":"FilledButton_Dark"},
         {"path":"images/button-filled/ideal__keyboard-focus__dark.png","theme":"dark","previewId":"FilledButton_Focus"}]}]}
      """
        .trimIndent()
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> json.toByteArray()
        url.endsWith("bundle/compose-m3-bundle.png") -> bundle
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    val trust =
      TrustStore(
        branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*"))
      )
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { trust },
        fetch = fetch,
        // Return false so the live builder yields and the baked static host is registered — that's
        // the host whose `remoteComposeDoc` serves the materialised `.rc`.
        buildTrustedBundle = { _, _, _, _, _, _ -> false },
      )
    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)

    // On disk: the daemon-keyed entry landed re-keyed to the catalog id, beside `previews/`.
    assertTrue(
      File(store.liveDir("compose-m3")!!, "ir/button-filled__ideal__default__dark.rc").isFile,
      "ir/<catalog-id>.rc materialised",
    )
    val host = registered.getValue("compose-m3")
    assertTrue(
      rcBytes.contentEquals(host.remoteComposeDoc("button-filled__ideal__default__dark")),
      "the baked host serves the re-keyed document bytes",
    )
    assertTrue(host.hasRemoteComposeDoc("button-filled__ideal__default__dark"))
    // The focus variant's daemon twin (FilledButton_Focus) has no `.rc` entry → docless.
    assertEquals(null, host.remoteComposeDoc("button-filled__ideal__keyboard-focus__dark"))
  }

  @Test
  fun `the per-preview fetcher fetches a daemon-id's own split bundle beside the liveBundle`() {
    // The builder is handed a per-preview fetcher: given a daemon-preview id it fetches that
    // preview's OWN FULL split bundle from <liveBundle.path>/previews/<daemon-id>.png on the same
    // branch (the default render lane). A hit returns a local file; a miss (no per-preview bundle)
    // returns null so the caller falls back to the monolithic daemon.
    val json =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "liveBundle":{"path":"bundle/","file":"compose-m3-bundle.png"},
       "components":[{"componentId":"Button/Filled","images":[
         {"path":"images/button-filled/ideal__default__dark.png","theme":"dark","previewId":"FilledButton_Dark"}]}]}
      """
        .trimIndent()
    val perPreviewBytes =
      polyglotBundle(
        """{"schemaVersion":8,"backend":"desktop","previewIds":["a"],"coverPreviewId":"a","classpath":[{"kind":"module","path":"classes/app.jar"}],"modulePath":":app","producedBy":"test"}"""
      )
    // Thread-safe: a catalog load also kicks off background fetch lanes (vectors, the published
    // rc-compare), so this recorder is written from those threads while the assertions below read
    // it. A plain ArrayList fails the reads with a ConcurrentModificationException.
    val requested = java.util.concurrent.CopyOnWriteArrayList<String>()
    val fetch: (String) -> ByteArray? = { url ->
      requested += url
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> json.toByteArray()
        url.endsWith("bundle/compose-m3-bundle.png") -> byteArrayOf(1, 2, 3)
        url.endsWith("bundle/previews/FilledButton_Dark.png") -> perPreviewBytes
        // Any OTHER per-preview bundle 404s (the branch ships none for it).
        url.contains("bundle/previews/") -> null
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    val trust =
      TrustStore(
        branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*"))
      )
    var perPreviewAccess: PerPreviewBundleAccess? = null
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trust },
        fetch = fetch,
        networkProbe = { url ->
          requested += "HEAD $url"
          // Outcome-shaped like the seam it stands in for: a probe that answered a bare Boolean
          // could not tell "absent" from "the branch refused us", which is what left the
          // executable-bundle lane invisible to /status.json.
          if (url.endsWith("bundle/previews/FilledButton_Dark.png")) BranchFetch.Ok(ByteArray(0))
          else BranchFetch.NotFound
        },
        buildTrustedBundle = { _, _, _, _, _, access ->
          perPreviewAccess = access
          true
        },
      )
    store.load("compose-m3")

    val access = assertNotNull(perPreviewAccess)
    val previewUrl = "bundle/previews/FilledButton_Dark.png"
    val getsBeforeProbe = requested.count { it.endsWith(previewUrl) && !it.startsWith("HEAD ") }
    assertTrue(access.available("FilledButton_Dark"))
    assertEquals(
      getsBeforeProbe,
      requested.count { it.endsWith(previewUrl) && !it.startsWith("HEAD ") },
      "availability probes must not download or hydrate the bundle",
    )
    val fetcher = access.fetch
    // A mapped daemon id resolves its own split bundle from previews/<daemon-id>.png…
    val hit = fetcher("FilledButton_Dark")
    assertTrue(hit != null && hit.isFile)
    assertContentEquals(perPreviewBytes, hit.readBytes())
    assertTrue(requested.any { it.endsWith("bundle/previews/FilledButton_Dark.png") })
    // …a second request for the same id re-uses the cached file rather than re-downloading…
    val fetchCountBefore = requested.count { it.endsWith("bundle/previews/FilledButton_Dark.png") }
    fetcher("FilledButton_Dark")
    assertEquals(
      fetchCountBefore,
      requested.count { it.endsWith("bundle/previews/FilledButton_Dark.png") },
      "the cached per-preview bundle is re-used",
    )
    // …and an id the branch ships no per-preview bundle for yields null (falls back to monolith).
    assertNull(fetcher("MissingButton_Light"))
  }

  @Test
  fun `the per-preview fetcher re-embeds the live bundle's external resources`() {
    val font = "FONT-BYTES".encodeToByteArray()
    val sha = shaHex(font)
    val manifest =
      """{"schemaVersion":8,"backend":"desktop","previewIds":["a"],"coverPreviewId":"a","classpath":[{"kind":"module","path":"classes/app.jar"}],"modulePath":":app","producedBy":"test","externalResources":[{"path":"fonts/Test.ttf","sha256":"$sha","size":${font.size}}]}"""
    val bundleBytes = polyglotBundle(manifest)
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "liveBundle":{"path":"bundle/","file":"compose-m3-bundle.png"},
       "components":[{"componentId":"Button/Filled","images":[
         {"path":"images/button-filled/ideal__default__dark.png","previewId":"FilledButton_Dark"}]}]}
      """
        .trimIndent()
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.encodeToByteArray()
        url.endsWith("bundle/compose-m3-bundle.png") -> bundleBytes
        url.endsWith("bundle/previews/FilledButton_Dark.png") -> bundleBytes
        url.endsWith("bundle/res/$sha") -> font
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    var fetchPerPreview: ((String) -> File?)? = null
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = {
          TrustStore(
            branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*"))
          )
        },
        fetch = fetch,
        buildTrustedBundle = { _, _, _, _, _, access ->
          fetchPerPreview = access.fetch
          true
        },
      )
    store.load("compose-m3")

    val hydrated = assertNotNull(fetchPerPreview?.invoke("FilledButton_Dark"))
    val outer = zipEntries(BundleReader.extractZipBytes(hydrated))
    val hydratedManifest =
      Json.parseToJsonElement(outer.getValue("bundle.json").decodeToString()).jsonObject
    assertFalse("externalResources" in hydratedManifest)
    val appJar = zipEntries(outer.getValue("classes/app.jar"))
    assertContentEquals(font, appJar.getValue("fonts/Test.ttf"))
  }

  @Test
  fun `daemon ids that sanitize to the same stem skip the per-preview lane`() {
    // `bundle split` disambiguates colliding sanitised ids with -2/-3 suffixes the server can't
    // reconstruct, so two daemon ids that sanitise to one stem ("Foo Bar" and "Foo_Bar" → Foo_Bar)
    // must NOT fetch the bare <stem>.png (that's only one of them) — both resolve null and fall
    // back to the monolithic daemon, which renders every preview correctly.
    val json =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "liveBundle":{"path":"bundle/","file":"compose-m3-bundle.png"},
       "components":[{"componentId":"Foo","images":[
         {"path":"images/foo/a.png","theme":"dark","previewId":"Foo Bar"},
         {"path":"images/foo/b.png","theme":"dark","previewId":"Foo_Bar"}]}]}
      """
        .trimIndent()
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> json.toByteArray()
        url.endsWith("bundle/compose-m3-bundle.png") -> byteArrayOf(1, 2, 3)
        url.contains("bundle/previews/") -> byteArrayOf(4, 5, 6) // present, but must NOT be used
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    val trust =
      TrustStore(
        branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*"))
      )
    var fetchPerPreview: ((String) -> File?)? = null
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trust },
        fetch = fetch,
        buildTrustedBundle = { _, _, _, _, _, access ->
          fetchPerPreview = access.fetch
          true
        },
      )
    store.load("compose-m3")

    val fetcher = fetchPerPreview!!
    // Both colliding ids skip the per-preview lane (null) despite the branch serving a Foo_Bar.png.
    assertNull(fetcher("Foo Bar"))
    assertNull(fetcher("Foo_Bar"))
  }

  @Test
  fun `a trusted liveBundle's externalized fonts are fetched into a cache and materialized`() {
    // The bundle's manifest declares an externalized font (lifted out of classes/app.jar by
    // `bundle externalize`); the store must fetch it from bundle/res/<sha>, verify the hash, cache
    // it under <root>/.res-cache/, and hand the builder a materialized classpath dir where the font
    // sits at its recorded path so the daemon's `getResourceAsStream("/fonts/…")` resolves.
    val font = ByteArray(2048) { (it % 131).toByte() }
    val sha =
      java.security.MessageDigest.getInstance("SHA-256").digest(font).joinToString("") {
        "%02x".format(it)
      }
    val bundleBytes =
      polyglotBundle(
        manifest =
          """
          {"schemaVersion":8,"backend":"desktop","previewIds":["a"],"coverPreviewId":"a",
           "classpath":[{"kind":"module","path":"classes/app.jar"}],
           "modulePath":":samples:design-catalog-m3","producedBy":"test",
           "externalResources":[{"path":"fonts/Roboto-Regular.ttf","sha256":"$sha","size":2048}]}
          """
            .trimIndent()
      )
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "liveBundle":{"path":"bundle/","file":"compose-m3-bundle.png"},
       "components":[{"componentId":"Button/Filled","images":[
         {"path":"images/button-filled/ideal__default__dark.png","theme":"dark","previewId":"FilledButton_Dark"}]}]}
      """
        .trimIndent()
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.toByteArray()
        url.endsWith("bundle/compose-m3-bundle.png") -> bundleBytes
        url.endsWith("bundle/res/$sha") -> font
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    val trust =
      TrustStore(
        branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*"))
      )
    val root = tempRoot()
    var capturedDir: File? = null
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { trust },
        fetch = fetch,
        buildTrustedBundle = { _, _, externalResourcesDir, _, _, _ ->
          capturedDir = externalResourcesDir
          true
        },
      )
    val result = store.load("compose-m3")

    assertTrue(result is ServeCatalogStore.Result.Ok)
    // The builder got a materialized dir with the font at its recorded classpath path.
    val dir = capturedDir
    assertTrue(dir != null && dir.isDirectory, "expected a materialized external-resources dir")
    val materializedFont = File(dir, "fonts/Roboto-Regular.ttf")
    assertTrue(materializedFont.isFile, "font materialized at its classpath path")
    assertEquals(font.toList(), materializedFont.readBytes().toList())
    // It was cached content-addressed in the shared blob pool.
    assertTrue(blobFile(root, sha).isFile)
  }

  @Test
  fun `a liveBundle whose externalized font fails to fetch skips the live bundle`() {
    // Fail-closed: a declared external resource that can't be fetched must NOT stand up a live
    // daemon (it would render with the font missing) — the store falls through to the static host.
    val sha = "a".repeat(64)
    val bundleBytes =
      polyglotBundle(
        manifest =
          """
          {"schemaVersion":8,"backend":"desktop","previewIds":["a"],"coverPreviewId":"a",
           "classpath":[{"kind":"module","path":"classes/app.jar"}],
           "modulePath":":m","producedBy":"test",
           "externalResources":[{"path":"fonts/x.ttf","sha256":"$sha","size":10}]}
          """
            .trimIndent()
      )
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "liveBundle":{"path":"bundle/","file":"compose-m3-bundle.png"},
       "components":[{"componentId":"Button/Filled","images":[
         {"path":"images/button-filled/ideal__default__dark.png","theme":"dark"}]}]}
      """
        .trimIndent()
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.toByteArray()
        url.endsWith("bundle/compose-m3-bundle.png") -> bundleBytes
        // bundle/res/<sha> intentionally 404s
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    val trust =
      TrustStore(
        branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*"))
      )
    var builderCalled = false
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trust },
        fetch = fetch,
        buildTrustedBundle = { _, _, _, _, _, _ ->
          builderCalled = true
          true
        },
      )
    val result = store.load("compose-m3")

    assertTrue(result is ServeCatalogStore.Result.Ok)
    // The builder was never reached (fail-closed), and the static baked host serves instead.
    assertFalse(builderCalled, "live builder must not run when a declared font can't be fetched")
    assertTrue(registered["compose-m3"] != null, "static host registered as the fallback")
    // The baked host explains that a declared live bundle was the intent but couldn't be brought up
    // — a distinct reason from "no live bundle published", so the banner/API don't mislead.
    assertEquals(
      listOf(ServeDegradation.LIVEBUNDLE_UNAVAILABLE),
      registered.getValue("compose-m3").degradations.map { it.code },
    )
  }

  @Test
  fun `a liveBundle builder failure reports daemon startup failure when re-render is enabled`() {
    val bundleBytes =
      polyglotBundle(
        manifest =
          """
          {"schemaVersion":8,"backend":"desktop","previewIds":["FilledButton_Dark"],
           "coverPreviewId":"FilledButton_Dark",
           "classpath":[{"kind":"module","path":"classes/app.jar"}],
           "modulePath":":m","producedBy":"test"}
          """
            .trimIndent()
      )
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "liveBundle":{"path":"bundle/","file":"compose-m3-bundle.png"},
       "components":[{"componentId":"Button/Filled","images":[
         {"path":"images/button-filled/ideal__default__dark.png","theme":"dark","previewId":"FilledButton_Dark"}]}]}
      """
        .trimIndent()
    val trust =
      TrustStore(
        branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*"))
      )
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trust },
        serverSideRenderEnabled = true,
        fetch = { url ->
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.toByteArray()
            url.endsWith("bundle/compose-m3-bundle.png") -> bundleBytes
            url.endsWith(".png") -> png()
            else -> null
          }
        },
        buildTrustedBundle = { _, _, _, _, _, _ -> false },
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    val detail = registered.getValue("compose-m3").degradations.single().detail
    assertTrue(detail.contains("live bundle daemon could not be started"), detail)
    assertTrue(!detail.contains("re-render is not enabled"), detail)
  }

  @Test
  fun `a same-size but corrupt cache entry is re-fetched, not trusted`() {
    // The cache key is a sha256, so a pre-existing cache file with the right size but wrong bytes
    // (a partial write / disk fault) must be re-fetched and repaired — not silently materialized.
    val font = ByteArray(2048) { (it % 131).toByte() }
    val sha = shaHex(font)
    val bundleBytes =
      polyglotBundle(
        manifest =
          """
          {"schemaVersion":8,"backend":"desktop","previewIds":["a"],"coverPreviewId":"a",
           "classpath":[{"kind":"module","path":"classes/app.jar"}],
           "modulePath":":m","producedBy":"test",
           "externalResources":[{"path":"fonts/Roboto-Regular.ttf","sha256":"$sha","size":2048}]}
          """
            .trimIndent()
      )
    val catalog =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "liveBundle":{"path":"bundle/","file":"compose-m3-bundle.png"},
       "components":[{"componentId":"Button/Filled","images":[
         {"path":"images/button-filled/ideal__default__dark.png","theme":"dark","previewId":"FilledButton_Dark"}]}]}
      """
        .trimIndent()
    var resFetches = 0
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.toByteArray()
        url.endsWith("bundle/compose-m3-bundle.png") -> bundleBytes
        url.endsWith("bundle/res/$sha") -> {
          resFetches++
          font
        }
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    val trust =
      TrustStore(
        branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*"))
      )
    val root = tempRoot()
    // Pre-seed the shared blob pool with a same-size but WRONG-content entry.
    val cacheFile =
      blobFile(root, sha).apply {
        parentFile.mkdirs()
        writeBytes(ByteArray(2048) { 0 })
      }
    var capturedDir: File? = null
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { trust },
        fetch = fetch,
        buildTrustedBundle = { _, _, externalResourcesDir, _, _, _ ->
          capturedDir = externalResourcesDir
          true
        },
      )
    store.load("compose-m3")

    // The corrupt entry was refetched (not trusted by size alone) and the cache repaired.
    assertEquals(1, resFetches)
    assertEquals(font.toList(), cacheFile.readBytes().toList())
    // The materialized font on the classpath is the correct bytes.
    val materializedFont = File(capturedDir!!, "fonts/Roboto-Regular.ttf")
    assertEquals(font.toList(), materializedFont.readBytes().toList())
  }

  private fun shaHex(bytes: ByteArray) =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
      "%02x".format(it)
    }

  /** Build a minimal desktop-bundle polyglot (PNG cover + zip) with the given bundle.json. */
  // ------------------------------------------------------------------------------------------
  // The blob pool: what a reload and a restart no longer have to re-download.
  // ------------------------------------------------------------------------------------------

  /** A one-entry commit feed, so a load resolves a delivery commit and pins its reads to it. */
  // ------------------------------------------------------------------------------------------
  // The asset cache: small commit-pinned reads answered from the pool.
  // ------------------------------------------------------------------------------------------

  @Test
  fun `a pinned load reads its manifests from the pool on the next load`() {
    // Every asset a load reads is addressed through the delivery commit it resolved first, so the
    // bytes at that URL are immutable and a second load of the same revision need not ask again.
    // A branch that HAS moved names a different commit, so its URLs miss and are fetched — the
    // freshness rule needs no cache logic of its own.
    val reads = java.util.concurrent.atomic.AtomicLong()
    val json =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3","components":[
        {"componentId":"Button/Filled","images":[
          {"path":"images/button-filled/ideal__default__dark.png","theme":"dark"}]}]}
      """
        .trimIndent()
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url ==
          ServeCatalogRevision.commitsFeedUrl(
            "yschimke/compose-ai-tools",
            "design-artifacts/compose-m3",
          ) -> feed(COMMIT).encodeToByteArray()
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> {
          reads.incrementAndGet()
          json.toByteArray()
        }
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trustedBranch },
        fetch = fetch,
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    assertEquals(1, reads.get())
    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)

    assertEquals(1, reads.get(), "the same revision's manifest must not be re-fetched")
    // Not an exact count: a load also samples a baked image to prove the branch can serve one, and
    // that read is pinned and cached too. What matters is that the pool answered rather than the
    // branch, which the manifest count above states precisely.
    assertTrue(assertNotNull(store.branchFetchStats.snapshot()).cached > 0)
  }

  @Test
  fun `an un-pinned load reads its manifests from the branch every time`() {
    // No feed ⇒ no delivery commit ⇒ the base is the branch ref, which is a moving target. Caching
    // under it would answer a regenerated branch with last week's bytes.
    val reads = java.util.concurrent.atomic.AtomicLong()
    val json =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3","components":[
        {"componentId":"Button/Filled","images":[
          {"path":"images/button-filled/ideal__default__dark.png","theme":"dark"}]}]}
      """
        .trimIndent()
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> {
          reads.incrementAndGet()
          json.toByteArray()
        }
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trustedBranch },
        fetch = fetch,
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)

    assertEquals(2, reads.get())
    assertNull(store.branchFetchStats.snapshot()?.cached?.takeIf { it > 0 })
  }

  @Test
  fun `a missing asset is not remembered as missing`() {
    // Only Ok is stored. A NotFound is a statement about one revision that callers cache in their
    // own terms, and a throttle is a statement about now — caching either would turn a bad minute
    // into a permanent answer.
    val pool = CatalogBlobPool(tempRoot())
    val url = "https://raw.githubusercontent.com/o/r/$COMMIT/images/late.png"
    assertFalse(pool.holds(url))
    // Nothing was written for a failed read, so the next attempt is free to succeed.
    pool.write(url, "arrived later".toByteArray())
    assertContentEquals("arrived later".toByteArray(), assertNotNull(pool.read(url)))
  }

  private fun feed(commit: String): String =
    """
    <feed><entry>
      <id>tag:github.com,2008:Grit::Commit/$commit</id>
      <updated>2026-08-19T09:42:57Z</updated>
    </entry></feed>
    """
      .trimIndent()

  private val liveBundleCatalogJson =
    """
    {"schema":"design-parity-catalog/v1","system":"compose-m3",
     "liveBundle":{"path":"bundle/","file":"compose-m3-bundle.png"},
     "components":[{"componentId":"Button/Filled","images":[
       {"path":"images/button-filled/ideal__default__dark.png","theme":"dark","previewId":"FilledButton_Dark"}]}]}
    """
      .trimIndent()

  private val trustedBranch =
    TrustStore(branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*")))

  private val liveBundleBytes: ByteArray by lazy {
    polyglotBundle(
      """{"schemaVersion":8,"backend":"desktop","previewIds":["FilledButton_Dark"],
         "coverPreviewId":"FilledButton_Dark","classpath":[{"kind":"module","path":"classes/app.jar"}],
         "modulePath":":app","producedBy":"test"}"""
    )
  }

  /**
   * A branch that resolves one commit and serves the catalog + its liveBundle, counting every read
   * of the bundle itself. Reads are answered under both the pinned and the branch-name base so the
   * same stub drives a pinned and an un-pinned load.
   */
  private fun liveBundleBranch(
    commit: String,
    bundleReads: java.util.concurrent.atomic.AtomicLong,
    serveFeed: Boolean = true,
  ): (String) -> ByteArray? = { url ->
    when {
      url ==
        ServeCatalogRevision.commitsFeedUrl(
          "yschimke/compose-ai-tools",
          "design-artifacts/compose-m3",
        ) -> if (serveFeed) feed(commit).encodeToByteArray() else null
      url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> liveBundleCatalogJson.toByteArray()
      url.endsWith("bundle/compose-m3-bundle.png") -> {
        bundleReads.incrementAndGet()
        liveBundleBytes
      }
      url.contains("bundle/previews/") -> null
      url.endsWith(".png") -> png()
      else -> null
    }
  }

  @Test
  fun `a pinned load caches the liveBundle so a reload does not download it again`() {
    // The reload half of the problem: `load` deletes the per-system directory before swapping
    // staging over it, so before the pool every regeneration re-pulled a ~100 MB bundle the new
    // revision may not have changed. A commit-pinned URL names one immutable object, so the second
    // load reads the pooled copy.
    val reads = java.util.concurrent.atomic.AtomicLong()
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trustedBranch },
        fetch = liveBundleBranch(COMMIT, reads),
        buildTrustedBundle = { _, _, _, _, _, _ -> true },
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    assertEquals(1, reads.get())
    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)

    assertEquals(1, reads.get(), "a reload must read the pooled bundle, not the branch")
  }

  @Test
  fun `a pool shared with a fresh store carries the liveBundle across a restart`() {
    // The restart half: a rolled container is a new process over the same volume. Two stores with
    // separate roots and one durable pool is exactly that arrangement.
    val pool = CatalogBlobPool(tempRoot())
    val reads = java.util.concurrent.atomic.AtomicLong()
    fun store() =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trustedBranch },
        fetch = liveBundleBranch(COMMIT, reads),
        buildTrustedBundle = { _, _, _, _, _, _ -> true },
        blobs = pool,
      )

    assertTrue(store().load("compose-m3") is ServeCatalogStore.Result.Ok)
    assertEquals(1, reads.get())
    assertTrue(store().load("compose-m3") is ServeCatalogStore.Result.Ok)

    assertEquals(1, reads.get(), "a restarted server must read the pooled bundle")
    assertTrue(pool.snapshot().hits >= 1)
  }

  @Test
  fun `an un-pinned load caches nothing`() {
    // The rule the pool depends on: without a resolved delivery commit the base is the branch ref,
    // which is a moving target. Caching under it would let a regenerated branch be answered with
    // the bytes it published last week, so an un-pinned load stages exactly as it always did.
    val pool = CatalogBlobPool(tempRoot())
    val reads = java.util.concurrent.atomic.AtomicLong()
    fun store() =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trustedBranch },
        fetch = liveBundleBranch(COMMIT, reads, serveFeed = false),
        buildTrustedBundle = { _, _, _, _, _, _ -> true },
        blobs = pool,
      )

    assertTrue(store().load("compose-m3") is ServeCatalogStore.Result.Ok)
    assertTrue(store().load("compose-m3") is ServeCatalogStore.Result.Ok)

    assertEquals(2, reads.get(), "each un-pinned load must re-read the branch")
    assertEquals(0, pool.snapshot().blobs, "nothing addressed by a branch ref may be pooled")
  }

  private fun polyglotBundle(
    manifest: String,
    extra: Map<String, ByteArray> = emptyMap(),
  ): ByteArray {
    val cover = png()
    val appJar =
      ByteArrayOutputStream()
        .also { baos ->
          java.util.zip.ZipOutputStream(baos).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("com/example/CatalogKt.class"))
            z.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0, 0))
            z.closeEntry()
          }
        }
        .toByteArray()
    val zip =
      ByteArrayOutputStream()
        .also { baos ->
          java.util.zip.ZipOutputStream(baos).use { z ->
            for ((name, bytes) in
              linkedMapOf(
                "bundle.json" to manifest.toByteArray(),
                "previews.json" to """{"previews":[{"id":"a","functionName":"A"}]}""".toByteArray(),
                "classes/app.jar" to appJar,
                *extra.entries.map { it.key to it.value }.toTypedArray(),
              )) {
              z.putNextEntry(java.util.zip.ZipEntry(name))
              z.write(bytes)
              z.closeEntry()
            }
          }
        }
        .toByteArray()
    return cover + zip
  }

  private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> = buildMap {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        if (!entry.isDirectory) put(entry.name, zip.readBytes())
        zip.closeEntry()
      }
    }
  }

  @Test
  fun `an untrusted branch still serves the catalog but unverified`() {
    val result = store(TrustStore.EMPTY).load("compose-m3")
    assertEquals(ServeCatalogStore.Result.Ok("compose-m3", 2, "unverified"), result)
    assertTrue(registered.getValue("compose-m3").trust is BundleVerifier.Verdict.Unverified)
  }

  @Test
  fun `a per-system sourceRepo override fetches from that repo and attributes to it`() {
    // Catalog vectors continue fetching on the background executor after load() publishes the
    // host. Keep the recorder safe while that pass appends, then assert against one locked
    // snapshot rather than iterating a list that can still be changing.
    val urls = CopyOnWriteArrayList<String>()
    val trust =
      TrustStore(branches = listOf(TrustedBranch("yschimke/meshcore-mobile", "design-artifacts/*")))
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trust },
        fetch = { url ->
          urls += url
          fetcher()(url)
        },
      )
    val result = store.load("meshcore-mobile", sourceRepo = "yschimke/meshcore-mobile")
    val fetchedUrls = synchronized(urls) { urls.toList() }

    // Every fetch went to the override repo's design-artifacts/<system> branch, not the default —
    // its assets off the raw host, and its publish history off the branch's own commit feed
    // (github.com, the one fetch this load makes that isn't an asset).
    assertTrue(
      fetchedUrls.all {
        it.startsWith(
          "https://raw.githubusercontent.com/yschimke/meshcore-mobile/design-artifacts/meshcore-mobile/"
        ) ||
          it ==
            ServeCatalogRevision.commitsFeedUrl(
              "yschimke/meshcore-mobile",
              "design-artifacts/meshcore-mobile",
            )
      },
      "fetched from the override repo: $fetchedUrls",
    )
    assertTrue(
      result is ServeCatalogStore.Result.Ok &&
        result.trust == "branch:yschimke/meshcore-mobile@design-artifacts/meshcore-mobile",
      "attributed to the override repo's branch: $result",
    )
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
    store(TrustStore.EMPTY, fetch = wasmFetcher(catalog)).load("compose-m3")

    val wasmDir = registeredWasm.getValue("compose-m3")
    assertTrue(File(wasmDir, "index.html").isFile, "index.html landed")
    assertTrue(File(wasmDir, "composeApp.wasm").isFile && File(wasmDir, "skiko.wasm").isFile)
    // The in-browser Wasm tier IS a live lane (the viewer's Live toggle switches to it), so this
    // session is NOT baked-only even though it carries no server-side liveBundle — no banner
    // reason.
    assertTrue(
      registered.getValue("compose-m3").degradations.isEmpty(),
      "a Wasm-backed session must not be flagged snapshot-only",
    )
  }

  @Test
  fun `a refresh that drops the wasm app withdraws its registration`() {
    // Generation directories outlive their host by one refresh, so an app the previous generation
    // registered stays readable after the new host publishes. A registration nobody withdrew would
    // keep the viewer's "Run in browser" toggle serving the OLD catalog's code beside the new
    // catalog's pages — and then, once the sweep took that directory, 404 on the same toggle. So
    // the registration moves with the generation, in both directions.
    val withWasm = wasmCatalog("\"index.html\",\"composeApp.wasm\",\"skiko.wasm\"")
    val withoutWasm =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3","components":[
        {"componentId":"Button/Filled","images":[
          {"path":"images/button-filled/ideal__default__dark.png","theme":"dark"}]}]}
      """
        .trimIndent()
    var catalog = withWasm
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = { url ->
          when {
            url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.toByteArray()
            url.endsWith(".png") -> png()
            url.contains("/web/wasm/") -> "x".toByteArray()
            else -> null
          }
        },
        registerWasm = { s, d ->
          if (d == null) registeredWasm.remove(s) else registeredWasm[s] = d
        },
      )

    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    assertTrue(registeredWasm.containsKey("compose-m3"), "the first generation carries an app")

    catalog = withoutWasm
    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    assertTrue(
      registeredWasm.isEmpty(),
      "a generation with no app must not leave the previous one's registered",
    )
    assertEquals(
      listOf(ServeDegradation.CATALOG_BAKED_ONLY),
      registered.getValue("compose-m3").degradations.map { it.code },
    )
  }

  @Test
  fun `a webRender with a failed required-file fetch registers nothing (fail closed)`() {
    val catalog = wasmCatalog("\"index.html\",\"composeApp.wasm\",\"skiko.wasm\"")
    // composeApp.wasm 404s → the app is incomplete → don't advertise a tier whose iframe would 404.
    store(TrustStore.EMPTY, fetch = wasmFetcher(catalog, missing = setOf("composeApp.wasm")))
      .load("compose-m3")
    assertTrue(registeredWasm.isEmpty(), "incomplete app must not register")
    // With no live lane (Wasm failed to register, no liveBundle), the session IS baked-only and
    // says
    // so — the flag tracks actual registration, not the mere `webRender` declaration.
    assertEquals(
      listOf(ServeDegradation.CATALOG_BAKED_ONLY),
      registered.getValue("compose-m3").degradations.map { it.code },
    )
  }

  @Test
  fun `a webRender with a traversal entry fails closed and writes nothing outside the dir`() {
    val catalog = wasmCatalog("\"index.html\",\"composeApp.wasm\",\"../../escape.html\"")
    val root = tempRoot()
    ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { TrustStore.EMPTY },
        fetch = wasmFetcher(catalog),
        registerWasm = { s, d ->
          if (d == null) registeredWasm.remove(s) else registeredWasm[s] = d
        },
      )
      .load("compose-m3")
    assertTrue(registeredWasm.isEmpty(), "malformed manifest must not register")
    assertTrue(
      root.walkTopDown().none { it.name == "escape.html" },
      "traversal write rejected anywhere under the catalog root",
    )
  }

  @Test
  fun `no webRender means no wasm dir is registered`() {
    store(TrustStore.EMPTY).load("compose-m3")
    assertTrue(registeredWasm.isEmpty())
  }

  // --- Trusted server-side re-render (--allow-render-trusted) gating ---

  private val buildCalls = mutableListOf<Pair<String, ServeCatalogStore.CatalogSource>>()

  private val catalogWithSource =
    """
    {"schema":"design-parity-catalog/v1","system":"compose-m3","components":[
      {"componentId":"Button/Filled","images":[
        {"path":"images/button-filled/ideal__default__dark.png","theme":"dark"}]}],
     "source":{"repo":"yschimke/compose-ai-tools","ref":"main",
               "module":":samples:design-catalog-m3"}}
    """
      .trimIndent()

  private val trustBranches =
    TrustStore(branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*")))

  private fun storeWithBuilder(
    trust: TrustStore,
    catalog: String,
    builderResult: Boolean,
  ): ServeCatalogStore =
    ServeCatalogStore(
      root = tempRoot(),
      register = { n, h -> registered[n] = h },
      trust = { trust },
      fetch = { url ->
        when {
          url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.toByteArray()
          url.endsWith(".png") -> png()
          else -> null
        }
      },
      buildTrustedSource = { system, source, _, _ ->
        buildCalls += system to source
        builderResult
      },
    )

  @Test
  fun `a trusted catalog with a source builds a live session and skips the static host`() {
    val result =
      storeWithBuilder(trustBranches, catalogWithSource, builderResult = true).load("compose-m3")
    assertEquals(1, buildCalls.size, "builder invoked for a trusted catalog with a source")
    assertEquals(":samples:design-catalog-m3", buildCalls.single().second.module)
    assertEquals("main", buildCalls.single().second.ref)
    assertTrue(registered.isEmpty(), "static host skipped once the live session takes over")
    assertTrue(
      result is ServeCatalogStore.Result.Ok && result.trust.endsWith("(live)"),
      "result marked live",
    )
  }

  @Test
  fun `an untrusted catalog with a source never reaches the builder (no RCE on spoof)`() {
    storeWithBuilder(TrustStore.EMPTY, catalogWithSource, builderResult = true).load("compose-m3")
    assertTrue(buildCalls.isEmpty(), "an unverified catalog must never trigger a build")
    assertTrue(registered.getValue("compose-m3").trust is BundleVerifier.Verdict.Unverified)
  }

  @Test
  fun `a trusted catalog with no source serves the static host`() {
    storeWithBuilder(trustBranches, catalogJson, builderResult = true).load("compose-m3")
    assertTrue(buildCalls.isEmpty(), "no source means no build")
    assertTrue(registered.containsKey("compose-m3"))
  }

  @Test
  fun `when the builder declines (ref not allowed) the catalog falls back to baked PNGs`() {
    storeWithBuilder(trustBranches, catalogWithSource, builderResult = false).load("compose-m3")
    assertEquals(1, buildCalls.size, "builder consulted")
    assertTrue(
      registered.containsKey("compose-m3"),
      "fall back to the static host when the build is refused",
    )
  }

  // --- deferred (live-only) coverage — issue #2965 ----------------------------------------------

  /**
   * A catalog that bakes the dark sticker and defers the light one (a `modePriority` thinning),
   * declaring a liveBundle so a trusted server can render the deferred entry on demand.
   */
  private val deferredJson =
    """
    {"schema":"design-parity-catalog/v1","system":"compose-m3",
     "liveBundle":{"path":"bundle/","file":"compose-m3-bundle.png"},
     "components":[{"componentId":"Button/Filled","section":"Components","group":"Buttons",
       "images":[
         {"path":"images/button-filled/ideal__default__dark.png","state":"default","theme":"dark","previewId":"FilledButton_Dark"}]}],
     "deferred":[
       {"componentId":"Button/Filled","section":"Components","group":"Buttons","reason":"mode",
        "path":"images/button-filled/ideal__default__light.png","state":"default","theme":"light",
        "preview":"FilledButton","previewId":"FilledButton_Light",
        "previewIds":["FilledButton_Light","FilledButton_Dark"]}]}
    """
      .trimIndent()

  private val trustedBranches =
    TrustStore(branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*")))

  private fun deferredFetcher(json: String = deferredJson): (String) -> ByteArray? = { url ->
    when {
      url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> json.toByteArray()
      url.endsWith("bundle/compose-m3-bundle.png") -> byteArrayOf(1, 2, 3)
      url.contains("bundle/previews/") -> null
      url.endsWith(".png") -> png()
      else -> null
    }
  }

  @Test
  fun `a deferred record is aliased and registered as a live-only preview under the live lane`() {
    // The whole point of #2965: a deferred entry ships no PNG, so it can only be served where a
    // live daemon can produce it — the baked host the live builder fronts lists it, aliases it to
    // its daemon twin, and marks it live-only so the composite always routes it to the daemon.
    var alias: Map<String, String> = emptyMap()
    var fronted: ServeHost? = null
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trustedBranches },
        fetch = deferredFetcher(),
        buildTrustedBundle = { _, _, _, a, bakedFallback, _ ->
          alias = a
          fronted = bakedFallback()
          true
        },
      )
    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)

    val deferredId = "button-filled__ideal__default__light"
    // The route id is derived from the path the sticker WOULD have had, so it is exactly the id a
    // baked light variant would have been published under — deferring an entry never moves its URL.
    assertEquals(
      mapOf(
        "button-filled__ideal__default__dark" to "FilledButton_Dark",
        deferredId to "FilledButton_Light",
      ),
      alias,
    )
    val host = fronted as ServeBundleHost
    assertEquals(setOf(deferredId), host.liveOnlyPreviewIds)
    assertEquals(
      setOf("button-filled__ideal__default__dark", deferredId),
      host.previews.map { it.id }.toSet(),
    )
    // It carries the same variant metadata a baked preview would, so it lands in the right tab and
    // group and folds onto the component's card instead of floating loose.
    val preview = host.previews.single { it.id == deferredId }
    assertEquals("light", preview.theme)
    assertEquals("default", preview.state)
    assertEquals("Components" to "Buttons", preview.section to preview.group)
    // …and no baked PNG was invented for it.
    assertEquals(RenderOutcome.NotFound, host.render(deferredId, PreviewOverrides()))
  }

  @Test
  fun `a baked-only session hides the deferred previews and records why`() {
    // Fail-soft (issue #2965 point 5): with no live lane there is nothing to render a deferred
    // preview from, so it is omitted rather than listed as a card whose every request 404s — and
    // the session says so, next to the reason it has no live lane at all.
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trustedBranches },
        fetch = deferredFetcher(),
        // The builder declines (e.g. --allow-render-trusted off), so the baked host is terminal.
        buildTrustedBundle = { _, _, _, _, _, _ -> false },
      )
    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)

    val host = registered.getValue("compose-m3")
    assertEquals(listOf("button-filled__ideal__default__dark"), host.previews.map { it.id })
    assertTrue(host.liveOnlyPreviewIds.isEmpty())
    assertTrue(
      ServeDegradation.DEFERRED_NOT_SERVED in host.degradations.map { it.code },
      "the hidden live-only previews are explained: ${host.degradations}",
    )
  }

  @Test
  fun `deferred records with no route or no daemon twin are skipped`() {
    // Three unusable records: no `path` (an older catalog, or one whose export detected naming
    // drift), a traversing path, and one with no daemon preview to render it. None may reach the
    // alias or the host.
    val json =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "liveBundle":{"path":"bundle/","file":"compose-m3-bundle.png"},
       "components":[{"componentId":"Button/Filled","images":[
         {"path":"images/button-filled/ideal__default__dark.png","theme":"dark","previewId":"FilledButton_Dark"}]}],
       "deferred":[
         {"componentId":"A","preview":"A","previewId":"A_Light"},
         {"componentId":"B","preview":"B","path":"images/../../etc/passwd.png","previewId":"B_Light"},
         {"componentId":"C","preview":"C","path":"images/c/ideal__default__light.png",
          "previewIds":["C_Light","C_Dark"]}]}
      """
        .trimIndent()
    var alias: Map<String, String> = emptyMap()
    var fronted: ServeHost? = null
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trustedBranches },
        fetch = deferredFetcher(json),
        buildTrustedBundle = { _, _, _, a, bakedFallback, _ ->
          alias = a
          fronted = bakedFallback()
          true
        },
      )
    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)

    assertEquals(mapOf("button-filled__ideal__default__dark" to "FilledButton_Dark"), alias)
    assertTrue((fronted as ServeBundleHost).liveOnlyPreviewIds.isEmpty())
  }

  @Test
  fun `an ambiguity-free single previewId is enough for an older catalog's deferred record`() {
    // Before the exporter resolved a record's own annotation it recorded only the function's id
    // list. One id is unambiguous, so it still serves; more than one would be a guess (covered
    // above) and is skipped.
    val json =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "liveBundle":{"path":"bundle/","file":"compose-m3-bundle.png"},
       "components":[{"componentId":"Button/Filled","images":[
         {"path":"images/button-filled/ideal__default__dark.png","theme":"dark","previewId":"FilledButton_Dark"}]}],
       "deferred":[
         {"componentId":"Chip","preview":"Chip","path":"images/chip/ideal__default.png",
          "previewIds":["Chip_Only"]}]}
      """
        .trimIndent()
    var alias: Map<String, String> = emptyMap()
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trustedBranches },
        fetch = deferredFetcher(json),
        buildTrustedBundle = { _, _, _, a, _, _ ->
          alias = a
          true
        },
      )
    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)
    assertEquals("Chip_Only", alias["chip__ideal__default"])
  }

  @Test
  fun `a wholly-deferred catalog loads through its live lane`() {
    // Every entry `priority: "deferred"` ⇒ the export publishes a catalog with NO baked images and
    // only `deferred[]`. The empty-images guard must not reject that: it exists to protect a
    // healthy catalog from an image outage, not to refuse the publish that leans hardest on the
    // deferred lane.
    val json =
      """
      {"schema":"design-parity-catalog/v1","system":"compose-m3",
       "liveBundle":{"path":"bundle/","file":"compose-m3-bundle.png"},
       "components":[],
       "deferred":[
         {"componentId":"Button/Filled","reason":"entry","theme":"light",
          "path":"images/button-filled/ideal__default__light.png",
          "preview":"FilledButton","previewId":"FilledButton_Light"}]}
      """
        .trimIndent()
    var fronted: ServeHost? = null
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trustedBranches },
        fetch = deferredFetcher(json),
        buildTrustedBundle = { _, _, _, _, bakedFallback, _ ->
          fronted = bakedFallback()
          true
        },
      )
    val result = store.load("compose-m3")

    assertEquals(
      ServeCatalogStore.Result.Ok(
        "compose-m3",
        1,
        "branch:yschimke/compose-ai-tools@design-artifacts/compose-m3 (live bundle)",
      ),
      result,
    )
    val host = fronted as ServeBundleHost
    assertEquals(listOf("button-filled__ideal__default__light"), host.previews.map { it.id })
    assertEquals(host.previews.map { it.id }.toSet(), host.liveOnlyPreviewIds)
    // The variant metadata still round-trips even though no PNG was written (the staged previews
    // dir has to be created for the manifest alone).
    assertEquals("light", host.previews.single().theme)
  }

  @Test
  fun `an image outage is still a failure even when the catalog defers coverage`() {
    // The mirror of the test above: this catalog DECLARES a baked image, so zero fetched images is
    // an outage — the deferred records must not talk the store into swapping in an empty catalog
    // over the healthy one it is already serving.
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> deferredJson.toByteArray()
        url.endsWith("bundle/compose-m3-bundle.png") -> byteArrayOf(1, 2, 3)
        else -> null // every image 404s
      }
    }
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trustedBranches },
        fetch = fetch,
        buildTrustedBundle = { _, _, _, _, _, _ -> true },
      )
    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Failed)
  }
}
