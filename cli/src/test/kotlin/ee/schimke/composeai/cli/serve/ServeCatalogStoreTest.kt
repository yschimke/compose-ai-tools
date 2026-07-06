package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
  fun `a catalog's baked figma svgs are fetched and served self-contained`() {
    val svg = "<svg><image href=\"button-filled.figma-raster/n0.png\"/></svg>"
    val crop = byteArrayOf(7, 7, 7)
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalogJson.toByteArray()
        url.endsWith("figma/button-filled.svg") -> svg.toByteArray()
        url.endsWith("figma/button-filled.figma-raster/n0.png") -> crop
        url.endsWith(".png") -> png()
        else -> null
      }
    }
    store(TrustStore.EMPTY, fetch).load("compose-m3")

    val host = registered.getValue("compose-m3")
    val ok =
      host.renderSvg("button-filled__ideal__default__dark", PreviewOverrides()) as SvgOutcome.Ok
    val out = ok.svg.decodeToString()
    val expected = java.util.Base64.getEncoder().encodeToString(crop)
    assertTrue(
      out.contains("data:image/png;base64,$expected"),
      "crop inlined into served svg: $out",
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
        trust = trust,
        fetch = fetch,
        buildTrustedBundle = { _, _, _, alias, _ ->
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
        trust = trust,
        fetch = fetch,
        buildTrustedBundle = { _, _, externalResourcesDir, _, _ ->
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
        trust = trust,
        fetch = fetch,
        buildTrustedBundle = { _, _, _, _, _ ->
          builderCalled = true
          true
        },
      )
    val result = store.load("compose-m3")

    assertTrue(result is ServeCatalogStore.Result.Ok)
    // The builder was never reached (fail-closed), and the static baked host serves instead.
    assertFalse(builderCalled, "live builder must not run when a declared font can't be fetched")
    assertTrue(registered["compose-m3"] != null, "static host registered as the fallback")
  }

  /** Build a minimal desktop-bundle polyglot (PNG cover + zip) with the given bundle.json. */
  private fun polyglotBundle(manifest: String): ByteArray {
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
        trust = trust,
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
      trust = trust,
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
}
