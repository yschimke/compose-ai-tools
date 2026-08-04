package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
      registerWasm = { s, d -> registeredWasm[s] = d },
      maxImages = maxImages,
      figmaExecutor = figmaExecutor,
    )

  @Test
  fun `the image cap bounds the previews a catalog declares`() {
    // Fetching lazily makes the ceiling count DECLARED previews rather than successfully fetched
    // ones: whether an image can be had isn't known at load time any more, and finding out would
    // mean fetching everything — the thing lazy loading exists to avoid. So a cap of two publishes
    // the first two declarations, and a card whose image turns out to be missing reports NotFound
    // on request instead of being silently replaced by a later one. The cap defaults to 1000 while
    // the largest published catalog declares ~200, so it does not bind in practice.
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
    val requested = Collections.synchronizedList(mutableListOf<String>())
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

  @Test
  fun `a catalog publishes before its baked vectors are fetched`() {
    // The vectors are the last bulk fetch on the publish path — one per image plus one per slug.
    // Publishing must not wait for them: the catalog serves (uncropped, briefly) and the pass fills
    // them behind it. Captured rather than run so the assertion is about ordering, not timing.
    val deferred = mutableListOf<Runnable>()
    val requested = Collections.synchronizedList(mutableListOf<String>())
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
    val requested = Collections.synchronizedList(mutableListOf<String>())
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
    val host = registered.getValue("compose-m3") as ServeBundleHost
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
    val requested = mutableListOf<String>()
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
        registerWasm = { s, d -> registeredWasm[s] = d },
      )
    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok, "first load succeeds")
    val png = File(root, "compose-m3/previews/button-filled__ideal__default__dark.png")
    assertTrue(png.isFile, "the first load writes the preview PNG on disk")

    // Re-load with every image (transiently) unavailable — parseable catalog.json, zero images.
    imagesAvailable = false
    assertTrue(
      store.load("compose-m3") is ServeCatalogStore.Result.Failed,
      "a catalog with no usable images fails the re-load",
    )
    assertTrue(png.isFile, "the previously-served catalog is left intact on a failed re-load")
    assertFalse(File(root, "compose-m3.staging").exists(), "the staging dir is cleaned up")
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
      themed != null && themed.contains("--cp-accent: #bf0031;"),
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
        registerWasm = { s, d -> registeredWasm[s] = d },
      )
    assertTrue(store.load("compose-m3") is ServeCatalogStore.Result.Ok)

    // The manifest is written into the served previews dir, with null keys omitted (all present
    // here).
    val manifest = File(root, "compose-m3/previews/${ServeCatalogStore.VARIANTS_FILE}")
    assertTrue(manifest.isFile, "variants.json is written")
    val text = manifest.readText()
    assertTrue(
      text.contains(
        "\"checkbox__ideal__unchecked__light\":{\"state\":\"unchecked\",\"theme\":\"light\"}"
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

    val manifest = File(root, "reply/previews/${ServeCatalogStore.VARIANTS_FILE}").readText()
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
        registerWasm = { s, d -> registeredWasm[s] = d },
      )
    assertTrue(store.load("meshcore-mobile") is ServeCatalogStore.Result.Ok)

    // variants.json carries the section/group/order the tabbed landing keys off (state/theme
    // absent).
    val manifest = File(root, "meshcore-mobile/previews/${ServeCatalogStore.VARIANTS_FILE}")
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
      File(root, "remote-m3/ir/remote__ideal__default.rc").readBytes(),
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
    val requestedLimits = linkedMapOf<String, Long>()
    val networkFetch: (String, Long) -> ByteArray? = { url, maxBytes ->
      requestedLimits[url] = maxBytes
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalog.toByteArray()
        url.endsWith("bundle/bundle.png") -> byteArrayOf(1, 2, 3)
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
        networkFetch = networkFetch,
        buildTrustedBundle = { _, _, _, _, _, _ ->
          builderCalled = true
          true
        },
      )

    assertTrue(store.load("jetsnack") is ServeCatalogStore.Result.Ok)
    assertEquals(
      25L * 1024 * 1024,
      requestedLimits.entries.single { it.key.endsWith("/catalog.json") }.value,
    )
    assertEquals(
      ServeCatalogStore.MAX_LIVE_BUNDLE_FETCH_BYTES,
      requestedLimits.entries.single { it.key.endsWith("bundle/bundle.png") }.value,
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
      File(root, "compose-m3/ir/button-filled__ideal__default__dark.rc").isFile,
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
    val perPreviewBytes = byteArrayOf(9, 8, 7)
    val requested = mutableListOf<String>()
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
    var fetchPerPreview: ((String) -> File?)? = null
    val store =
      ServeCatalogStore(
        root = tempRoot(),
        register = { n, h -> registered[n] = h },
        trust = { trust },
        fetch = fetch,
        buildTrustedBundle = { _, _, _, _, _, fetcher ->
          fetchPerPreview = fetcher
          true
        },
      )
    store.load("compose-m3")

    val fetcher = fetchPerPreview
    assertTrue(fetcher != null, "the builder was handed a per-preview fetcher")
    // A mapped daemon id resolves its own split bundle from previews/<daemon-id>.png…
    val hit = fetcher!!("FilledButton_Dark")
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
        buildTrustedBundle = { _, _, _, _, _, fetcher ->
          fetchPerPreview = fetcher
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
    // It was cached content-addressed under the shared cache dir.
    assertTrue(File(root, "${ServeCatalogStore.RES_CACHE_DIR}/$sha").isFile)
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
    // Pre-seed the shared cache with a same-size but WRONG-content entry.
    val cacheFile =
      File(root, "${ServeCatalogStore.RES_CACHE_DIR}/$sha").apply {
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

  @Test
  fun `an untrusted branch still serves the catalog but unverified`() {
    val result = store(TrustStore.EMPTY).load("compose-m3")
    assertEquals(ServeCatalogStore.Result.Ok("compose-m3", 2, "unverified"), result)
    assertTrue(registered.getValue("compose-m3").trust is BundleVerifier.Verdict.Unverified)
  }

  @Test
  fun `a per-system sourceRepo override fetches from that repo and attributes to it`() {
    val urls = mutableListOf<String>()
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

    // Every fetch went to the override repo's design-artifacts/<system> branch, not the default.
    assertTrue(
      urls.all {
        it.startsWith(
          "https://raw.githubusercontent.com/yschimke/meshcore-mobile/design-artifacts/meshcore-mobile/"
        )
      },
      "fetched from the override repo: $urls",
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
