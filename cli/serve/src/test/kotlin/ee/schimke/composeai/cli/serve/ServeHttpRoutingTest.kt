package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideType
import ee.schimke.composeai.data.overrides.PreviewOverridesPayload
import ee.schimke.composeai.data.remotecompose.RemoteComposeDeclarationsPayload
import ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * End-to-end routing check for [ServeHttpServer]: a real embedded server fronting two static
 * [ServeBundleHost] sessions, exercised over HTTP. Guards the two access forms — the legacy
 * `?session=` query lane and the canonical path lane (`/<system>/…`) — and, crucially, that the
 * constant top-level routes (`/healthz`, `/readyz`, `/version`) still win over the `/{system}`
 * catch-all in Ktor's route scoring (a regression here would 404 liveness/readiness checks or
 * shadow `/version`).
 *
 * Runs public (no token) so the assertions stay about routing, not the auth gate ([ServeAuthTest]).
 */
class ServeHttpRoutingTest {

  private val previewId = "com.example.Red"
  private val refreshes = mutableListOf<String>()
  @Volatile private var blockRefresh = false
  private val refreshStarted = CountDownLatch(1)
  private val releaseRefresh = CountDownLatch(1)
  private val ordinaryBurstHostRenders = AtomicInteger()
  private val leasedBurstHostRenders = AtomicInteger()

  private val burstHost =
    object : ServeHost {
      override val previews = listOf(ServePreview(previewId, previewId))
      override val label = "burst"
      override val declaredThemes = listOf(ServeTheme("Brand", "com.example.Brand"))
      override val themeRenderBurstCapacity = 5

      override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
        ordinaryBurstHostRenders.incrementAndGet()
        return RenderOutcome.Ok(png())
      }

      override fun renderLeased(previewId: String, overrides: PreviewOverrides): RenderOutcome {
        leasedBurstHostRenders.incrementAndGet()
        return RenderOutcome.Ok(png())
      }

      override fun subscribeStream(
        previewId: String,
        overrides: PreviewOverrides,
        codec: StreamCodec?,
        maxFps: Int?,
        onUnavailable: ((String) -> Unit)?,
        onFrame: (StreamFrameParams) -> Unit,
      ): StreamHandle? = null

      override fun activeStreamCount(): Int = 0

      override fun close() {}
    }

  /**
   * A session whose live lane exists but cannot serve right now — the daemon is down / cold / out
   * of seats — so an override-bearing render falls back to the catalog's baked PNG. Exactly the
   * state #3449 was reported in: the pixels are published bytes that ignore the override.
   */
  private val liveDownHost =
    object : ServeHost {
      override val previews = listOf(ServePreview(previewId, previewId))
      override val label = "live-down"
      override val canRenderOverrides = true

      override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
        RenderOutcome.Ok(png(), RenderOutcome.Generation.BAKED)

      override fun subscribeStream(
        previewId: String,
        overrides: PreviewOverrides,
        codec: StreamCodec?,
        maxFps: Int?,
        onUnavailable: ((String) -> Unit)?,
        onFrame: (StreamFrameParams) -> Unit,
      ): StreamHandle? = null

      override fun activeStreamCount(): Int = 0

      override fun close() {}
    }

  /**
   * [liveDownHost]'s Remote Compose twin: the same "live lane exists but can't serve right now"
   * state, on a preview that carries a captured `ir/<id>.rc` document and is therefore **replayed**
   * rather than recomposed.
   *
   * This is the shape the refusal used to get wrong. Being replayed was read as "no retry can ever
   * help", so every transient baked fallback here answered `409` + "the override can never apply" —
   * about a daemon that returns `200` the moment it is warm. The viewer treats `409` as final, so a
   * cold start permanently disabled the lane. Terminality belongs to the *axis*, and only the
   * handful in [CatalogLiveRouting.irReplayDroppedOverrideNames] have it.
   */
  private val liveDownRcHost =
    object : ServeHost {
      override val previews = listOf(ServePreview(previewId, previewId))
      override val label = "live-down-rc"
      override val canRenderOverrides = true

      override fun remoteComposeDoc(previewId: String): ByteArray? = rcDocBytes

      override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
        RenderOutcome.Ok(png(), RenderOutcome.Generation.BAKED)

      override fun subscribeStream(
        previewId: String,
        overrides: PreviewOverrides,
        codec: StreamCodec?,
        maxFps: Int?,
        onUnavailable: ((String) -> Unit)?,
        onFrame: (StreamFrameParams) -> Unit,
      ): StreamHandle? = null

      override fun activeStreamCount(): Int = 0

      override fun close() {}
    }

  /** Distinct bytes so a test can tell a published player raster from the baked snapshot. */
  private fun publishedPng(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  /**
   * [liveDownRcHost] plus the catalog's published `rc-compare` staging — the offline parity run
   * having already drawn this document with every player.
   *
   * The daemon here is permanently unavailable (every render falls back to baked), which is what
   * makes the assertions unambiguous: anything this host answers with published bytes it answered
   * WITHOUT a renderer.
   */
  private val rcPublishedHost =
    object : ServeHost {
      override val previews = listOf(ServePreview(previewId, previewId))
      override val label = "rc-published"
      override val canRenderOverrides = true

      override fun remoteComposeDoc(previewId: String): ByteArray? = rcDocBytes

      override fun rcCompare(): RcCompareManifest =
        RcCompareManifest(
          lanes =
            listOf(
              RcCompareLane("embedded", "AndroidX Embedded", "emb"),
              RcCompareLane("cmp-jvm", "RC · cmp-jvm player", "jvm"),
            ),
          rows =
            listOf(
              RcCompareRow(
                previewId = previewId,
                width = 3,
                height = 3,
                lanes =
                  mapOf(
                    "embedded" to RcCompareCell(rendered = true, render = "embedded/0.png"),
                    "cmp-jvm" to RcCompareCell(rendered = true, render = "cmp-jvm/0.png"),
                  ),
              )
            ),
        )

      override fun rcCompareImage(name: String): ByteArray? =
        if (name == "embedded/0.png" || name == "cmp-jvm/0.png") publishedPng() else null

      override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
        RenderOutcome.Ok(png(), RenderOutcome.Generation.BAKED)

      /**
       * Local baked pixels, exactly as a real bundle host has — and, like the real one, answered
       * WITHOUT consulting the overrides.
       *
       * This is what makes the ordering load-bearing rather than incidental. The published lane was
       * first written after this call, so on any host that actually implements it the lane was dead
       * code: the bare `?rcPlayer=` request got the baked PNG (the *Java* capture) and was then
       * refused for dropping `rcPlayer`, with the staged raster it asked for sitting unread.
       */
      override fun bakedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? =
        RenderOutcome.Ok(png(), RenderOutcome.Generation.BAKED)

      override fun subscribeStream(
        previewId: String,
        overrides: PreviewOverrides,
        codec: StreamCodec?,
        maxFps: Int?,
        onUnavailable: ((String) -> Unit)?,
        onFrame: (StreamFrameParams) -> Unit,
      ): StreamHandle? = null

      override fun activeStreamCount(): Int = 0

      override fun close() {}
    }

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  /** An author-declared string knob, carried into the bundle as an `overrides.json` sidecar. */
  private val labelKnob =
    PreviewOverrideDeclaration(
      key = "label",
      type = PreviewOverrideType.STRING,
      label = "Label",
      default = PreviewOverrideValue.StringValue("Tap me"),
    )

  /** A declared Remote Compose knob, carried into the bundle as a `remotecompose.json` sidecar. */
  private val rcColorKnob =
    RemoteComposeKnobDeclaration("shaderColor", RemoteNamedValue.ColorValue("#FF7DE2FF"))

  /** Arbitrary Remote Compose document bytes, carried into the bundle as an `ir/<id>.rc`. */
  private val rcDocBytes = byteArrayOf(0x52, 0x43, 0x01, 0x02, 0x03)

  private fun bundle(
    label: String,
    title: String? = null,
    overrides: List<PreviewOverrideDeclaration> = emptyList(),
    remoteComposeKnobs: List<RemoteComposeKnobDeclaration> = emptyList(),
    degradations: List<ServeDegradation> = emptyList(),
    rcDoc: ByteArray? = null,
    designReference: Boolean = false,
    parityFeed: Boolean = false,
    stagesRcCompare: Boolean = false,
    tagIndex: Boolean = false,
    /**
     * False builds the shape `--bundles` and an upload produce: the same host type with no
     * `catalog.json` behind it. Defaults true because every other fixture here stands in for a
     * published catalog.
     */
    isCatalog: Boolean = true,
  ): ServeBundleHost {
    val dir = Files.createTempDirectory("routing-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    File(dir, "previews/$previewId.png").writeBytes(png())
    if (rcDoc != null) {
      File(dir, "ir").apply { mkdirs() }
      File(dir, "ir/$previewId.rc").writeBytes(rcDoc)
    }
    if (designReference) {
      val references = File(dir, "references").apply { mkdirs() }
      File(references, "red-design.png").writeBytes(png())
      File(references, "index.json")
        .writeText(
          Json.encodeToString(
            DesignReferenceManifest.serializer(),
            DesignReferenceManifest(
              references =
                listOf(
                  DesignReference(
                    id = "red-design",
                    previewId = previewId,
                    label = "Red design",
                    raster =
                      DesignReferenceRaster("references/red-design.png", width = 2, height = 2),
                    source = DesignReferenceSource(provider = "figma", revision = "7"),
                  ),
                  DesignReference(
                    id = "red-design-review",
                    previewId = previewId,
                    label = "Red design review",
                    raster =
                      DesignReferenceRaster("references/red-design.png", width = 2, height = 2),
                    source = DesignReferenceSource(provider = "penpot", revision = "8"),
                  ),
                )
            ),
          )
        )
    }
    if (tagIndex) {
      // The published element index, exactly as `scripts/design-artifacts/tag-index.mjs` writes it:
      // one unique tag with a box, one unique tag whose every carrying node had a zero-area box,
      // and one carried by two nodes. The last two are the interesting ones — a tag with no
      // geometry is still an identity, and a tag with `count: 2` is not one at all.
      File(dir, ServeTagIndexStore.DIRECTORY).apply { mkdirs() }
      File(dir, "${ServeTagIndexStore.DIRECTORY}/${ServeTagIndexStore.INDEX_FILE}")
        .writeText(
          Json.encodeToString(
            TagIndexManifest.serializer(),
            TagIndexManifest(
              previews =
                mapOf(
                  previewId to
                    mapOf(
                      "glyph" to
                        WireTagEntry(
                          count = 1,
                          bounds = AnnotationBounds(x = 18, y = 18, width = 24, height = 24),
                          space = ServeSemanticsTags.RENDER_PIXELS,
                        ),
                      "plain-marker" to
                        WireTagEntry(count = 1, space = ServeSemanticsTags.RENDER_PIXELS),
                      "row" to
                        WireTagEntry(
                          count = 2,
                          bounds = AnnotationBounds(x = 0, y = 0, width = 90, height = 20),
                          space = ServeSemanticsTags.RENDER_PIXELS,
                        ),
                    )
                )
            ),
          )
        )
    }
    if (parityFeed) {
      File(dir, ParityActivity.DIRECTORY).apply { mkdirs() }
      File(dir, "${ParityActivity.DIRECTORY}/${ParityActivity.FILE}")
        .writeText(
          Json.encodeToString(
            ParityActivity.serializer(),
            ParityActivity(
              generatedAt = "2026-08-06T09:12:00Z",
              windowDays = 30,
              code =
                CodeLane(
                  repo = "yschimke/compose-ai-tools",
                  ref = "main",
                  events =
                    listOf(
                      CodeEvent(
                        sha = "4e73ec2b9f0a1c3d5e7f9a1b3c5d7e9f0a1b3c5d",
                        subject = "fix: red is too red",
                        at = "2026-08-05T10:00:00Z",
                        previewIds = listOf(previewId),
                        components = listOf("Red"),
                      )
                    ),
                ),
              figma =
                FigmaLane(
                  fileKey = "abc123",
                  fileName = "Compose M3",
                  comments =
                    listOf(
                      FigmaCommentEvent(
                        id = "c1",
                        at = "2026-08-04T10:00:00Z",
                        message = "swatch is off",
                        nodeId = "51592:4768",
                        components = listOf("Blue"),
                      )
                    ),
                ),
              gaps =
                listOf(
                  MappingGap(
                    kind = MappingGap.Kind.UNMAPPED_DESIGN_NODE,
                    detail = "Figma component with no code entry",
                    ref = "figma:abc123/1:2",
                  )
                ),
            ),
          )
        )
    }
    if (overrides.isNotEmpty()) {
      val sidecar =
        Json.encodeToString(
          PreviewOverridesPayload.serializer(),
          PreviewOverridesPayload(overrides),
        )
      File(dir, "previews/$previewId.overrides.json").writeText(sidecar)
    }
    if (remoteComposeKnobs.isNotEmpty()) {
      val sidecar =
        Json.encodeToString(
          RemoteComposeDeclarationsPayload.serializer(),
          RemoteComposeDeclarationsPayload(remoteComposeKnobs),
        )
      File(dir, "previews/$previewId.remotecompose.json").writeText(sidecar)
    }
    return ServeBundleHost(
      dir,
      label = label,
      isCatalog = isCatalog,
      title = title,
      degradations = degradations,
      stagesRcCompare = stagesRcCompare,
    )
  }

  /**
   * A baked catalog that also carries the published `figma/<slug>.svg` vector, so the `.svg` lane
   * serves something instead of 404ing. The vector is as static as the PNG — it was exported at the
   * preview's discovery-time axes — which is what makes an override on this lane a silent drop
   * (#3449).
   */
  private fun svgBundle(label: String): ServeBundleHost {
    val dir = Files.createTempDirectory("routing-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    File(dir, "previews/$previewId.png").writeBytes(png())
    val figma = File(dir, "figma").apply { mkdirs() }
    // Layered the way a real `compose/figma-svg` export is — a `<g id="…">` per composable, nested
    // as the composables nest — because that structure is what the `?exploded=1` lane splits on.
    File(figma, "$previewId.svg")
      .writeText(
        """
        <svg xmlns="http://www.w3.org/2000/svg" width="40" height="60" viewBox="0 0 40 60">
        <g transform="translate(0, 0)">
          <g id="Card">
            <rect x="0" y="0" width="40" height="60" fill="#FFFFFF"/>
            <g id="Text"><text x="4" y="20">hi</text></g>
          </g>
        </g>
        </svg>
        """
          .trimIndent()
      )
    return ServeBundleHost(dir, label = label, figmaDir = figma, isCatalog = true)
  }

  private val registry = ServeSessionRegistry(open = { null })
  private val rcWasmDir =
    Files.createTempDirectory("routing-rc-wasm").toFile().also { dir ->
      dir.deleteOnExit()
      File(dir, "index.html").writeText("<html>cmp wasm</html>")
      File(dir, "rcPlayer.wasm").writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6d))
    }
  private val server: ServeHttpServer by lazy {
    registry.register("default-mod", host = bundle("default-mod"), pinned = true)
    registry.register(
      "compose-m3",
      host =
        bundle(
          "compose-m3",
          "Compose Material 3",
          listOf(labelKnob),
          listOf(rcColorKnob),
          rcDoc = rcDocBytes,
          designReference = true,
          parityFeed = true,
          tagIndex = true,
        ),
      pinned = true,
    )
    // A baked-only session carrying a degradation reason — reachable at /baked-only/… but kept off
    // catalogSessions so the home-index test is unaffected. Exercises the /api/previews surfacing.
    registry.register(
      "baked-only",
      host = bundle("baked-only", degradations = listOf(ServeDegradation.catalogBakedOnly())),
      pinned = true,
    )
    // A catalog whose background lane WILL stage a published player comparison, but has not yet —
    // the only shape `rcComparePending()` is true for. Kept off `catalogSessions` like `baked-only`
    // so the home-index test is unaffected.
    registry.register(
      "staging-rc",
      host = bundle("staging-rc", rcDoc = rcDocBytes, stagesRcCompare = true),
      pinned = true,
    )
    // A PLAIN BUNDLE — the shape `--bundles` and an upload produce: the same `ServeBundleHost`
    // type with no `catalog.json` behind it. Kept off `catalogSessions` like `baked-only` so the
    // home-index test is unaffected.
    registry.register(
      "plain-bundle",
      host = bundle("plain-bundle", isCatalog = false),
      pinned = true,
    )
    registry.register("burst", host = burstHost, pinned = true)
    registry.register("live-down", host = liveDownHost, pinned = true)
    registry.register("live-down-rc", host = liveDownRcHost, pinned = true)
    registry.register("rc-published", host = rcPublishedHost, pinned = true)
    registry.register("svg-catalog", host = svgBundle("svg-catalog"), pinned = true)
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "default-mod",
        isPublic = true,
        rcPlayerWasmDir = rcWasmDir,
        catalogSessions = listOf("compose-m3"),
        catalogRefresh = { system, force ->
          refreshes += if (force) "$system!force" else system
          if (blockRefresh) {
            refreshStarted.countDown()
            releaseRefresh.await(5, TimeUnit.SECONDS)
          }
          CatalogRefreshResult.CURRENT
        },
      )
      .also { it.start() }
  }

  private val client = OkHttpClient()

  private fun get(path: String): Pair<Int, String> {
    val req = Request.Builder().url("http://127.0.0.1:${server.port}$path").build()
    client.newCall(req).execute().use { r ->
      return r.code to r.body.string()
    }
  }

  /** A `HEAD` for [path]: status plus the headers, which are the entire point of the method. */
  private fun head(path: String): Pair<Int, okhttp3.Headers> {
    val req = Request.Builder().url("http://127.0.0.1:${server.port}$path").head().build()
    client.newCall(req).execute().use { r ->
      return r.code to r.headers
    }
  }

  /** [get], keeping the response headers — the baked-fallback signals ride there. */
  private fun getFull(path: String): Triple<Int, String, okhttp3.Headers> {
    val req = Request.Builder().url("http://127.0.0.1:${server.port}$path").build()
    client.newCall(req).execute().use { r ->
      return Triple(r.code, r.body.string(), r.headers)
    }
  }

  /** [getFull] for a binary body — the published-raster lane is compared byte for byte. */
  private fun getFullBytes(path: String): Triple<Int, ByteArray, okhttp3.Headers> {
    val req = Request.Builder().url("http://127.0.0.1:${server.port}$path").build()
    client.newCall(req).execute().use { r ->
      return Triple(r.code, r.body.bytes(), r.headers)
    }
  }

  private fun post(path: String): Pair<Int, String> {
    val req =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}$path")
        .post(ByteArray(0).toRequestBody())
        .build()
    client.newCall(req).execute().use { r ->
      return r.code to r.body.string()
    }
  }

  /** Poll `/readyz` until it latches ready (the prober renders off the request path), up to ~5s. */
  private fun awaitReady(): Boolean {
    repeat(50) {
      if (get("/readyz") == 200 to "ready") return true
      Thread.sleep(100)
    }
    return false
  }

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
  }

  /**
   * `GET /tags/{name}` — the published element tag index, which had no HTTP surface until the
   * focused comparison's element selector became its first consumer.
   *
   * The three shapes in the fixture are the three a consumer has to tell apart, and every one of
   * them has to survive the wire: a unique tag with a box, a unique tag with **no** box (still an
   * identity — `count` is what makes one), and a tag two nodes carry (not an identity at all).
   */
  @Test
  fun `the tag index lane serves one preview's published index, on both session forms`() {
    for (path in
      listOf(
        "/compose-m3/tags/$previewId",
        "/compose-m3/tags/$previewId.json",
        "/tags/$previewId?session=compose-m3",
      )) {
      val (code, body) = get(path)
      assertEquals(200, code, "$path: $body")
      val payload = Json.parseToJsonElement(body).jsonObject
      assertEquals(previewId, payload["previewId"]?.jsonPrimitive?.content, path)
      val tags = payload["tags"]!!.jsonObject
      assertEquals(setOf("glyph", "plain-marker", "row"), tags.keys, path)
      val glyph = tags["glyph"]!!.jsonObject
      assertEquals(1, glyph["count"]?.jsonPrimitive?.content?.toInt(), path)
      // The plane, named on every entry. A consumer that read an index declaring nothing as though
      // it declared render pixels would compare bounds in a plane nobody stated — see D1 — so the
      // discriminator has to actually reach the browser rather than merely exist in Kotlin.
      assertEquals(
        ServeSemanticsTags.RENDER_PIXELS,
        glyph["space"]?.jsonPrimitive?.content,
        path,
      )
      assertEquals(18, glyph["bounds"]!!.jsonObject["x"]?.jsonPrimitive?.content?.toInt(), path)
      // Absent bounds are absent, not zeroed: a zero-area rectangle is a box a gate would measure
      // against, and this tag has none.
      assertTrue(
        tags["plain-marker"]!!.jsonObject["bounds"].let { it == null || it.toString() == "null" },
        path,
      )
      assertEquals(2, tags["row"]!!.jsonObject["count"]?.jsonPrimitive?.content?.toInt(), path)
    }
  }

  @Test
  fun `a preview whose id ends in json keeps its own index rather than another preview's`() {
    // A preview id is unrestricted path-segment data. Stripping `.json` unconditionally would
    // answer such a preview with a 404 — or, where the stripped form is also a real preview, with
    // somebody else's element index, which is a selector silently targeting the wrong elements.
    val host =
      object : ServeHost {
        override val previews =
          listOf(ServePreview("com.example.Red", "Red"), ServePreview("com.example.Red.json", "J"))
        override val label = "dotted"

        // This lane never renders — it reads a published file — so the required member is a stub.
        override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
          RenderOutcome.NotFound

        override fun subscribeStream(
          previewId: String,
          overrides: PreviewOverrides,
          codec: StreamCodec?,
          maxFps: Int?,
          onUnavailable: ((String) -> Unit)?,
          onFrame: (StreamFrameParams) -> Unit,
        ): StreamHandle? = null

        override fun activeStreamCount(): Int = 0

        override fun close() {}

        override fun tagIndexForPreview(previewId: String) =
          when (previewId) {
            "com.example.Red" -> mapOf("plain" to ServeSemanticsTags.TagEntry(count = 1))
            "com.example.Red.json" -> mapOf("dotted" to ServeSemanticsTags.TagEntry(count = 1))
            else -> emptyMap()
          }
      }
    registry.register("dotted", host = host, pinned = true)
    fun tagsOf(path: String): Set<String> {
      val (code, body) = get(path)
      assertEquals(200, code, "$path: $body")
      return Json.parseToJsonElement(body).jsonObject["tags"]!!.jsonObject.keys
    }
    // Verbatim wins: the id really ending in `.json` gets its own entry, not `com.example.Red`'s.
    assertEquals(setOf("dotted"), tagsOf("/dotted/tags/com.example.Red.json"))
    // And the alias still resolves where no preview claims the literal name.
    assertEquals(setOf("plain"), tagsOf("/dotted/tags/com.example.Red"))
  }

  @Test
  fun `a session that publishes no index answers an empty one, and an unknown preview 404s`() {
    // "This preview carries no tags" and "this server cannot tell you" are different answers, and a
    // consumer that cannot tell them apart has no way to choose between offering no tag targets and
    // offering none YET.
    val (code, body) = get("/default-mod/tags/$previewId")
    assertEquals(200, code, body)
    assertEquals(0, Json.parseToJsonElement(body).jsonObject["tags"]!!.jsonObject.size)
    assertEquals(404, get("/compose-m3/tags/com.example.NotHere").first)
  }

  @Test
  fun `constant top-level routes still win over the system catch-all`() {
    assertEquals(200 to "ok", get("/healthz"))
    val (code, body) = get("/version")
    assertEquals(200, code)
    assertTrue(body.contains("compose-preview-serve/version/v1"), "version json: $body")
  }

  /**
   * The site icons, at the three well-known paths. `/favicon.ico` in particular is what a link
   * unfurler probes when a page declares no icon it understands — it answered 404 with an HTML body
   * before these routes existed, which is why an unfurled link showed a generic globe.
   *
   * Constant first segments, so like `/healthz` they have to outscore the `/{system}` catch-all:
   * without that, `/favicon.ico` resolves as a request for a design system of that name.
   */
  @Test
  fun `the site icon routes are served and outscore the system catch-all`() {
    listOf(
        ServeSiteIcon.SVG_PATH to "image/svg+xml",
        ServeSiteIcon.ICO_PATH to "image/vnd.microsoft.icon",
        ServeSiteIcon.APPLE_TOUCH_PATH to "image/png",
      )
      .forEach { (path, contentType) ->
        val (code, _, headers) = getFull(path)
        assertEquals(200, code, "$path should be served")
        assertTrue(
          headers["Content-Type"].orEmpty().startsWith(contentType),
          "$path content type: ${headers["Content-Type"]}",
        )
        assertTrue(headers["ETag"].orEmpty().isNotEmpty(), "$path carries an ETag")
      }
  }

  /**
   * The unfurl card lane. A card is resolved purely by the content hash in its name, so a name this
   * server never drew is a 404 rather than a lookup against anything a caller controls.
   */
  @Test
  fun `the social card lane serves a drawn card and refuses an unknown one`() {
    val home = get("/")
    assertEquals(200, home.first)
    val cardPath =
      assertNotNull(
        Regex("<meta property=\"og:image\" content=\"[^\"]*(/social/[0-9a-f]+\\.png)\">")
          .find(home.second)
          ?.groupValues
          ?.get(1),
        "the front door advertises a drawn card: ${home.second.take(2000)}",
      )

    val (code, _, headers) = getFull(cardPath)
    assertEquals(200, code)
    assertTrue(
      headers["Content-Type"].orEmpty().startsWith("image/png"),
      "${headers["Content-Type"]}",
    )

    assertEquals(404, get("/social/0000000000000000.png").first)
  }

  /**
   * …and the front door declares that card's real size, at the aspect a large-image unfurl is laid
   * out at. Before this it advertised the featured catalog's own 1078×2399 phone render, which
   * every consumer cropped to a horizontal band.
   */
  @Test
  fun `the front door declares a card shaped like a large-image unfurl`() {
    val (code, body) = get("/")

    assertEquals(200, code)
    assertTrue(
      body.contains("<meta property=\"og:image:width\" content=\"1200\">"),
      body.take(2000),
    )
    assertTrue(
      body.contains("<meta property=\"og:image:height\" content=\"630\">"),
      body.take(2000),
    )
    assertTrue(
      body.contains("<meta name=\"twitter:card\" content=\"summary_large_image\">"),
      body.take(2000),
    )
  }

  @Test
  fun `catalog refresh supports path and query session routes`() {
    assertEquals(200 to "{\"status\":\"current\"}", post("/compose-m3/refresh"))
    assertEquals(200 to "{\"status\":\"current\"}", post("/refresh?session=compose-m3"))
    assertEquals(listOf("compose-m3", "compose-m3"), refreshes)
  }

  @Test
  fun `forcing a refresh is refused on a public server with no admin token`() {
    // This server is `--public` with no admin token, so the browse gate authorizes everyone. An
    // ordinary refresh is safe to hand out — it short-circuits on an unchanged head — but forcing
    // removes that short-circuit, so an anonymous caller could drive a full re-stage in a loop.
    // A box that configured no admin credential cannot be forced at all, rather than being
    // forceable
    // by everyone.
    assertEquals(404, post("/compose-m3/refresh?force=1").first)
    assertEquals(404, post("/refresh?session=compose-m3&force=1").first)
    assertTrue(refreshes.isEmpty(), "a refused force must do no remote work: $refreshes")

    // Anything other than an explicit 1 is an ordinary refresh, which stays open.
    assertEquals(200 to "{\"status\":\"current\"}", post("/compose-m3/refresh?force=0"))
    assertEquals(listOf("compose-m3"), refreshes)
  }

  @Test
  fun `theme burst lease routes deny hosts that only support serial rendering`() {
    assertEquals(409, post("/compose-m3/api/theme-render-lease").first)
    assertEquals(409, post("/api/theme-render-lease?session=compose-m3").first)
    assertEquals(400, post("/compose-m3/api/theme-render-lease/release").first)
  }

  @Test
  fun `a valid theme lease routes the render through the leased host lane`() {
    val (grantCode, grantBody) = post("/burst/api/theme-render-lease")
    assertEquals(200, grantCode)
    val lease =
      Json.parseToJsonElement(grantBody).jsonObject.getValue("lease").jsonPrimitive.content

    val (renderCode, _) =
      get("/burst/render/$previewId.png" + "?themeProvider=com.example.Brand&_themeLease=$lease")

    assertEquals(200, renderCode)
    assertEquals(1, leasedBurstHostRenders.get())
    assertEquals(0, ordinaryBurstHostRenders.get())
  }

  @Test
  fun `a theme render whose lease has gone falls back to the serial lane, it is not refused`() {
    // A page holds several short-lived claims over its life — one for the on-screen batch, one per
    // deferred batch as the visitor scrolls — and every one of them is handed back or expires. A
    // render still carrying a reaped token used to be answered `429`, which no retry could ever
    // satisfy, so the grid sat on the previous theme's pixels for good. It is the same request as
    // one carrying no token at all: render it, serially.
    val (renderCode, _) =
      get("/burst/render/$previewId.png" + "?themeProvider=com.example.Brand&_themeLease=reaped")

    assertEquals(200, renderCode)
    assertEquals(1, ordinaryBurstHostRenders.get(), "served by the unleased lane")
    assertEquals(0, leasedBurstHostRenders.get())
  }

  /**
   * #3449: a validated override that could not be applied must not come back as `200 image/png`
   * carrying the un-overridden snapshot — those pixels are byte-identical to the override-free
   * render, so the caller reads "this override changes nothing" for a render that never happened.
   */
  @Test
  fun `an override a static session cannot apply is refused, not answered with baked pixels`() {
    val (plainCode, plainBody, plainHeaders) = getFull("/default-mod/render/$previewId.png")
    assertEquals(200, plainCode)
    assertTrue(plainBody.isNotEmpty(), "the un-overridden request still serves baked pixels")
    assertEquals("baked", plainHeaders[ServeHttpServer.GENERATION_HEADER])
    assertEquals(null, plainHeaders[ServeHttpServer.DROPPED_OVERRIDES_HEADER])

    // 409, not 503: a static bundle has no live lane at all, so retrying can never help.
    val (code, body, headers) = getFull("/default-mod/render/$previewId.png?fontScale=2.0")
    assertEquals(409, code)
    assertEquals("fontScale", headers[ServeHttpServer.DROPPED_OVERRIDES_HEADER])
    assertTrue(body.contains("override not applied: fontScale"), "body names the param: $body")
  }

  @Test
  fun `every override kind agrees — uiMode and knobs are refused like fontScale`() {
    assertEquals(409, get("/default-mod/render/$previewId.png?uiMode=dark").first)
    val (_, _, knobHeaders) =
      getFull("/compose-m3/render/$previewId.png?knob.label=Hi&fontScale=1.5")
    assertEquals("fontScale,knob.label", knobHeaders[ServeHttpServer.DROPPED_OVERRIDES_HEADER])
    // An unrecognised param is not an override, so it still serves the snapshot (unchanged).
    assertEquals(200, get("/default-mod/render/$previewId.png?zzzBogusParam=1").first)
  }

  /**
   * The vector lane drops overrides exactly like the raster one — a `figma/<slug>.svg` read off the
   * delivery branch was exported at the preview's discovery-time axes — so it must refuse too, and
   * with the same shape.
   */
  @Test
  fun `the svg lane refuses an override it cannot apply, and marks an accepted fallback`() {
    val (plainCode, plainBody, plainHeaders) = getFull("/svg-catalog/render/$previewId.svg")
    assertEquals(200, plainCode)
    assertTrue(plainBody.contains("<svg"), "the un-overridden request serves the baked vector")
    assertEquals("baked", plainHeaders[ServeHttpServer.GENERATION_HEADER])
    assertEquals(null, plainHeaders[ServeHttpServer.DROPPED_OVERRIDES_HEADER])

    val (code, body, headers) = getFull("/svg-catalog/render/$previewId.svg?fontScale=2.0")
    assertEquals(409, code)
    assertEquals("fontScale", headers[ServeHttpServer.DROPPED_OVERRIDES_HEADER])
    assertTrue(body.contains("override not applied: fontScale"), "body names the param: $body")

    val (okCode, okBody, okHeaders) =
      getFull("/svg-catalog/render/$previewId.svg?fontScale=2.0&fallback=baked")
    assertEquals(200, okCode)
    assertEquals(plainBody, okBody, "the opted-in fallback is the baked vector")
    assertEquals(
      ServeHttpServer.RENDER_BAKED_FALLBACK,
      okHeaders[ServeHttpServer.RENDER_HEADER],
      "the fallback vector says it is one",
    )
    assertEquals("fontScale", okHeaders[ServeHttpServer.DROPPED_OVERRIDES_HEADER])
  }

  /**
   * `?exploded=1` is a *presentation* of the vector export, not a render override — so it must work
   * on a fully static catalog serving a baked `figma/<slug>.svg` (no daemon anywhere), and it must
   * not be reported as a dropped override the way `fontScale` above is.
   */
  @Test
  fun `the svg lane serves an exploded view of the baked vector`() {
    val (flatCode, flat, _) = getFull("/svg-catalog/render/$previewId.svg")
    assertEquals(200, flatCode)
    assertFalse(flat.contains("cp-exploded-plane"), "the default lane is unchanged")

    val (code, body, headers) = getFull("/svg-catalog/render/$previewId.svg?exploded=1")
    assertEquals(200, code)
    assertEquals(
      null,
      headers[ServeHttpServer.DROPPED_OVERRIDES_HEADER],
      "a view axis is not an override the baked lane has to refuse",
    )
    assertTrue(body.contains("data-exploded=\"true\""), "served exploded: $body")
    // Card and Text paint; the structural-only frame depth is retained in context, not emitted as
    // a full-size empty sheet.
    assertEquals(2, Regex("class=\"cp-exploded-plane\"").findAll(body).count())
    assertTrue(body.contains("data-layers=\"Card\""))

    // The knobs reshape the same bytes without a re-render. `explodeDepth=1` folds everything below
    // the first level together, so the stack loses a plane.
    val (_, shallow, _) = getFull("/svg-catalog/render/$previewId.svg?exploded=1&explodeDepth=1")
    assertEquals(1, Regex("class=\"cp-exploded-plane\"").findAll(shallow).count())
    // An out-of-range or unparseable knob falls back to the default rather than 400ing a link.
    val (bogusCode, bogus, _) =
      getFull("/svg-catalog/render/$previewId.svg?exploded=1&explodeTilt=nope&explodeDepth=999")
    assertEquals(200, bogusCode)
    assertEquals(2, Regex("class=\"cp-exploded-plane\"").findAll(bogus).count())

    // A hand-typed separation far past anything the slider offers is bounded, not obeyed: past
    // ~2.1e6 the canvas numbers stop surviving formatting and the picture collapses instead of
    // merely spreading out.
    val (hugeCode, huge, _) =
      getFull("/svg-catalog/render/$previewId.svg?exploded=1&explodeGap=3000000")
    assertEquals(200, hugeCode)
    assertEquals(2, Regex("class=\"cp-exploded-plane\"").findAll(huge).count())
    val height = Regex("<svg[^>]*\\bheight=\"([\\d.]+)\"").find(huge)!!.groupValues[1].toDouble()
    assertTrue(height < 10_000, "the stack is bounded, not 3 million units tall: $height")
  }

  /** `?exploded=0` is the explicit off state a bookmarked URL can carry; it changes nothing. */
  @Test
  fun `the svg lane leaves an explicitly disabled exploded view flat`() {
    val (_, flat, _) = getFull("/svg-catalog/render/$previewId.svg")
    val (code, body, _) = getFull("/svg-catalog/render/$previewId.svg?exploded=0")
    assertEquals(200, code)
    assertEquals(flat, body)
  }

  /**
   * The Storybook isolation pages are consumed by PNG-diffing visual tools — precisely the
   * caller #3449 describes — and a story's args ride the same override params, so they refuse too.
   * The page shape has no room for a signal of its own, hence the status.
   */
  @Test
  fun `the storybook iframe lanes refuse a dropped override`() {
    assertEquals(200, get("/compose-m3/iframe.html?id=$previewId").first)
    val (code, _, headers) = getFull("/compose-m3/iframe.html?id=$previewId&fontScale=2.0")
    assertEquals(409, code)
    assertEquals("fontScale", headers[ServeHttpServer.DROPPED_OVERRIDES_HEADER])

    assertEquals(200, get("/svg-catalog/iframe.html?id=$previewId&format=svg").first)
    val (svgCode, _, svgHeaders) =
      getFull("/svg-catalog/iframe.html?id=$previewId&format=svg&fontScale=2.0")
    assertEquals(409, svgCode)
    assertEquals("fontScale", svgHeaders[ServeHttpServer.DROPPED_OVERRIDES_HEADER])

    // The opt-in still works here: the page is served, marked as carrying baked bytes.
    val (okCode, _, okHeaders) =
      getFull("/compose-m3/iframe.html?id=$previewId&fontScale=2.0&fallback=baked")
    assertEquals(200, okCode)
    assertEquals(ServeHttpServer.RENDER_BAKED_FALLBACK, okHeaders[ServeHttpServer.RENDER_HEADER])
  }

  @Test
  fun `a live lane that is merely unavailable answers 503 with a retry hint`() {
    val (code, body, headers) = getFull("/live-down/render/$previewId.png?fontScale=2.0")
    assertEquals(503, code)
    assertEquals("2", headers["Retry-After"])
    assertEquals("fontScale", headers[ServeHttpServer.DROPPED_OVERRIDES_HEADER])
    assertTrue(body.contains("retry shortly"), "body offers a retry: $body")
  }

  /**
   * A replayed preview whose daemon is merely cold gets the same `503` a recomposing one does.
   *
   * `rcPlayer` is the sharpest case: it selects which player replays the captured document, so it
   * is precisely what the replay path reads — never a recomposition-only axis. It used to answer
   * `409` + "this preview is replayed … so the override can never apply", which is false about a
   * lane that serves the identical URL as `200` once warm, and final to a viewer that reads `409`
   * as "stop asking".
   */
  @Test
  fun `a cold replayed preview is 503, not a terminal refusal — the axis decides`() {
    for (query in listOf("rcPlayer=java", "rcPlayer=cmp-android", "fontScale=2.0", "uiMode=dark")) {
      val (code, body, headers) = getFull("/live-down-rc/render/$previewId.png?$query")
      assertEquals(503, code, "$query is retryable on a replayed preview")
      assertEquals("2", headers["Retry-After"], "$query offers a retry")
      assertTrue(body.contains("retry shortly"), "$query body offers a retry: $body")
      assertEquals(
        query.substringBefore('='),
        headers[ServeHttpServer.DROPPED_OVERRIDES_HEADER],
        "$query is still named as un-applied",
      )
    }
  }

  /**
   * The other half: an axis a replay genuinely cannot honour stays a terminal `409`, whatever the
   * daemon's state. `localeTag` resolved to a literal at capture and `RemoteContext` exposes no
   * locale, so no amount of warming will apply it.
   */
  @Test
  fun `an axis no replay can honour stays a terminal 409 on the same host`() {
    val (code, body, headers) = getFull("/live-down-rc/render/$previewId.png?localeTag=ar")
    assertEquals(409, code)
    assertEquals(null, headers["Retry-After"], "a terminal refusal offers no retry")
    assertEquals("localeTag", headers[ServeHttpServer.DROPPED_OVERRIDES_HEADER])
    assertTrue(body.contains("can never apply"), "body explains the finality: $body")
  }

  /**
   * Mixed: one terminal axis decides the status, because the request as written can never be
   * satisfied in full and `Retry-After` would invite a loop that never converges. The *message*
   * names only the hopeless axis, so the reason given is about the one that is actually hopeless,
   * while the header keeps naming everything that went un-applied.
   */
  @Test
  fun `one terminal axis makes the whole refusal terminal, and names itself`() {
    val (code, body, headers) =
      getFull("/live-down-rc/render/$previewId.png?localeTag=ar&rcPlayer=java")
    assertEquals(409, code)
    assertEquals("localeTag,rcPlayer", headers[ServeHttpServer.DROPPED_OVERRIDES_HEADER])
    assertTrue(body.contains("override not applied: localeTag —"), "names the hopeless axis: $body")
    assertFalse(body.contains("rcPlayer"), "does not blame the retryable axis: $body")
  }

  /** The opt-in still works on the replay lane, and still marks the pixels as a fallback. */
  @Test
  fun `fallback=baked works on a replayed preview too`() {
    val (code, _, headers) =
      getFull("/live-down-rc/render/$previewId.png?rcPlayer=java&fallback=baked")
    assertEquals(200, code)
    assertEquals(ServeHttpServer.RENDER_BAKED_FALLBACK, headers[ServeHttpServer.RENDER_HEADER])
    assertEquals("rcPlayer", headers[ServeHttpServer.DROPPED_OVERRIDES_HEADER])
  }

  /**
   * The commonest Remote Compose page view — a viewer opening on its default player with nothing
   * else selected — is answered from the catalog's published parity staging, with no renderer.
   *
   * This host's daemon never serves (every render falls back to baked), so a `200` carrying the
   * published bytes can only have come from the staging. Before this lane existed the same request
   * went to the daemon: ~0.75s warm on the public box, and on a cold one a baked fallback that then
   * refused. The staged lane is still what answers it: the baked PNG is the catalog's own capture
   * and this request names a player, so the two are only interchangeable when they happen to be the
   * same player — a coincidence the routing must not depend on.
   */
  @Test
  fun `a bare player selection is served from the published parity staging`() {
    val published = publishedPng()
    for (query in listOf("rcPlayer=cmp-android", "rcPlayer=embedded")) {
      val (code, body, headers) = getFullBytes("/rc-published/render/$previewId.png?$query")
      assertEquals(200, code, "$query is answered")
      assertContentEquals(published, body, "$query serves the published raster")
      assertEquals("rc-published", headers[ServeHttpServer.GENERATION_HEADER])
      assertEquals(
        null,
        headers[ServeHttpServer.DROPPED_OVERRIDES_HEADER],
        "the player WAS applied, so nothing is reported dropped",
      )
    }
  }

  /**
   * The safety condition. A request carrying anything beyond the player selection asks for pixels
   * the parity run never drew, so it must route to the renderer exactly as before — and on this
   * host that means the un-served daemon, hence the refusal rather than a wrong `200`.
   */
  @Test
  fun `a player selection with any other override does not take the published lane`() {
    for (query in
      listOf(
        "rcPlayer=cmp-android&fontScale=2.0",
        "rcPlayer=cmp-android&knob.label=Hi",
        "rcPlayer=cmp-android&localeTag=ar",
      )) {
      val (code, _, headers) = getFull("/rc-published/render/$previewId.png?$query")
      assertNotEquals(200, code, "$query must not be answered from published bytes")
      assertNotEquals(
        "rc-published",
        headers[ServeHttpServer.GENERATION_HEADER],
        "$query did not take the published lane",
      )
    }
  }

  /**
   * The published lane must be consulted **before** the baked snapshot, because `bakedRender` does
   * not look at the overrides — it answers with the preview's published PNG whenever the file is
   * local. Ordering it second made the whole lane dead code on any host that has baked pixels,
   * which is every real bundle and catalog host.
   */
  @Test
  fun `the published lane wins over baked pixels, which ignore the overrides`() {
    val baked = getFullBytes("/rc-published/render/$previewId.png")
    assertEquals(200, baked.first)
    assertEquals("baked", baked.third[ServeHttpServer.GENERATION_HEADER])

    val (code, body, headers) =
      getFullBytes("/rc-published/render/$previewId.png?rcPlayer=cmp-android")
    assertEquals(200, code)
    assertEquals("rc-published", headers[ServeHttpServer.GENERATION_HEADER])
    assertContentEquals(publishedPng(), body, "the requested player's raster, not the baked PNG")
    assertFalse(
      body.contentEquals(baked.second),
      "the two lanes are distinguishable, so this asserts something",
    )
  }

  /**
   * cmp-jvm reaches the published lane too. Its short-circuit returns before the override parse the
   * `cached` chain reads, so it needs catching there or a bare request spawns a one-shot desktop
   * JVM (~4.3s) to redraw a document the parity run already drew.
   */
  @Test
  fun `a bare cmp-jvm selection is served from the staging, not the subprocess`() {
    val (code, body, headers) = getFullBytes("/rc-published/render/$previewId.png?rcPlayer=cmp-jvm")
    assertEquals(200, code)
    assertEquals("rc-published", headers[ServeHttpServer.GENERATION_HEADER])
    assertContentEquals(publishedPng(), body)

    // …and only when bare: anything more asks for pixels the parity run never drew.
    val (mixedCode, _, mixedHeaders) =
      getFull("/rc-published/render/$previewId.png?rcPlayer=cmp-jvm&fontScale=2.0")
    assertNotEquals("rc-published", mixedHeaders[ServeHttpServer.GENERATION_HEADER])
    assertNotEquals(200, mixedCode)
  }

  /**
   * A host that can answer a player from published bytes must also **offer** it. The capability
   * list and the render lane disagreed: this host answers a bare `cmp-android` request perfectly
   * well, but advertised only `js`, so the viewer greyed the option out and Catalog mode fell back
   * to the JS canvas instead of its preferred embedded default — leaving a working lane reachable
   * only by hand-typing a URL.
   */
  @Test
  fun `a staged player is advertised as enabled, not just answerable`() {
    val (code, body) = get("/rc-published/p/$previewId")
    assertEquals(200, code)
    for (wire in listOf("cmp-android", "cmp-jvm")) {
      assertTrue(
        body.contains("<option value=\"rc:$wire\">"),
        "$wire is offered without a disabled attribute",
      )
      assertFalse(
        body.contains("<option value=\"rc:$wire\" disabled>"),
        "$wire is not greyed out",
      )
    }
    // …and the page opens on the embedded player rather than demoting to the JS canvas.
    assertTrue(body.contains("data-rc-default=\"cmp-android\""), "embedded is the default lane")
    // A player nothing staged, and which no renderer here can produce, stays honestly unavailable.
    assertTrue(body.contains("<option value=\"rc:java\" disabled>"), "unstaged java stays greyed")
  }

  /** A lane the parity run staged nothing for falls through to the renderer, not to a 404. */
  @Test
  fun `an unstaged player falls through to the ordinary render path`() {
    val (code, _, headers) = getFull("/rc-published/render/$previewId.png?rcPlayer=java")
    assertNotEquals("rc-published", headers[ServeHttpServer.GENERATION_HEADER])
    assertTrue(code == 503 || code == 409, "routed to the renderer, which cannot serve: $code")
  }

  @Test
  fun `fallback=baked opts back into the snapshot, marked unmissably`() {
    val (code, body, headers) =
      getFull("/live-down/render/$previewId.png?fontScale=2.0&fallback=baked")
    assertEquals(200, code)
    assertTrue(body.isNotEmpty(), "the snapshot is served")
    assertEquals(
      ServeHttpServer.RENDER_BAKED_FALLBACK,
      headers[ServeHttpServer.RENDER_HEADER],
      "the response says the pixels are a fallback",
    )
    assertEquals("fontScale", headers[ServeHttpServer.DROPPED_OVERRIDES_HEADER])
    assertEquals("no-store", headers["Cache-Control"])
  }

  @Test
  fun `concurrent catalog refresh requests coalesce before remote work`() {
    blockRefresh = true
    val executor = Executors.newSingleThreadExecutor()
    try {
      val first = executor.submit<Pair<Int, String>> { post("/compose-m3/refresh") }
      assertTrue(refreshStarted.await(5, TimeUnit.SECONDS), "the first refresh started")

      assertEquals(202 to "{\"status\":\"checking\"}", post("/compose-m3/refresh"))
      assertEquals(listOf("compose-m3"), refreshes, "the second request did no remote work")

      releaseRefresh.countDown()
      assertEquals(200 to "{\"status\":\"current\"}", first.get(5, TimeUnit.SECONDS))
    } finally {
      releaseRefresh.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun `readyz goes green once the default session renders a preview`() {
    // The first poll kicks off the server-owned readiness prober; the default session (default-mod)
    // carries a baked preview, so that render succeeds OFF the request path and latches ready — the
    // signal docker-rollout gates the swap on. Unlike /healthz (a static "ok"), this only flips
    // because a real render succeeded. Poll until green, exactly like a real healthcheck does.
    assertTrue(awaitReady(), "readyz should latch ready after the default session renders")
    // Latched: it stays green.
    assertEquals(200 to "ready", get("/readyz"))
  }

  @Test
  fun `a suspended catalog still serves its published captures`() {
    // The motion lane read its host with `peekHost`, which answers "resident right now" and goes
    // null for any session the idle timer has suspended — so on a long-running server every
    // capture 404'd for every catalog nobody had touched recently, which is most of them most of
    // the time. Nothing caught it because a fixture registers `pinned = true` and a pinned session
    // is never suspended; the bug only exists once an idle clock does. Modelled here the way the
    // registry models it: an entry that is known and resumable but has no live host.
    val motionId = "switch-on__ideal__default__light"
    val dir =
      Files.createTempDirectory("routing-suspended-motion").toFile().also { it.deleteOnExit() }
    File(dir, "previews").apply { mkdirs() }
    File(dir, "previews/switch-on.png").writeBytes(png())
    val bakedHost =
      ServeBundleHost(
        dir,
        label = "suspended-cat",
        title = "Suspended Catalog",
        declaredBaked = listOf("switch-on"),
        declaredMotion = listOf(motionId),
        fetchMotion = { id ->
          if (id == motionId) BranchFetch.Ok("capture".toByteArray()) else BranchFetch.NotFound
        },
        motionBranchPaths = mapOf(motionId to "motion/switch-on/ideal__default__light.apng"),
      )
    // The production shape, and the reason a bare bundle host would not have exercised the bug: a
    // trusted catalog resumes as a ServeCatalogLiveHost fronting its baked host, and the captures
    // live on the baked half. A catalog served by a bundle host directly is pinned, so it is
    // exactly the sessions that CAN suspend that reach the lane through this composite.
    val resumed = ServeCatalogLiveHost(emptyMap(), bundle("suspended-live"), bakedHost)
    val suspendedRegistry = ServeSessionRegistry(open = { resumed })
    suspendedRegistry.register("default-mod", host = bundle("default-mod"), pinned = true)
    suspendedRegistry.register(
      "suspended-cat",
      state =
        ServeSessionState(
          descriptor = File("daemon-launch.json"),
          workspaceRoot = File("."),
          workspaceName = "w",
          previews = emptyList(),
          label = "suspended-cat",
        ),
    )
    // The precondition the bug lived on: nothing is resident, so a peek finds nothing to serve.
    assertEquals(null, suspendedRegistry.peekHost("suspended-cat"))

    val suspendedServer =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = suspendedRegistry,
          defaultSessionId = "default-mod",
          isPublic = true,
          catalogSessions = listOf("suspended-cat"),
        )
        .also { it.start() }
    try {
      val req =
        Request.Builder()
          .url("http://127.0.0.1:${suspendedServer.port}/suspended-cat/motion/$motionId.apng")
          .build()
      client.newCall(req).execute().use { r ->
        assertEquals(200, r.code, "a suspended catalog must resume to serve its capture")
        assertEquals("capture", r.body.string())
      }
    } finally {
      suspendedServer.stop()
      suspendedRegistry.close()
    }
  }

  @Test
  fun `a capture the branch is refusing is a 503, not a 404`() {
    // The whole point of BranchFetch reaching this route. 404 says "the catalog never published
    // this recording", which is what the reader was told for a throttle too — so a rate-limited
    // capture looked exactly like one that does not exist, in the viewer and in any log.
    val motionId = "switch-on__ideal__default__light"
    val dir = Files.createTempDirectory("routing-motion-503").toFile().also { it.deleteOnExit() }
    File(dir, "previews").apply { mkdirs() }
    File(dir, "previews/switch-on.png").writeBytes(png())

    var answer: BranchFetch = BranchFetch.Throttled(retryAfterSeconds = 7)
    val motionRegistry = ServeSessionRegistry(open = { null })
    motionRegistry.register("default-mod", host = bundle("default-mod"), pinned = true)
    motionRegistry.register(
      "throttled-cat",
      host =
        ServeBundleHost(
          dir,
          label = "throttled-cat",
          title = "Throttled Catalog",
          declaredBaked = listOf("switch-on"),
          declaredMotion = listOf(motionId),
          fetchMotion = { answer },
          motionBranchPaths = mapOf(motionId to "motion/switch-on/ideal__default__light.apng"),
        ),
      pinned = true,
    )
    val motionServer =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = motionRegistry,
          defaultSessionId = "default-mod",
          isPublic = true,
          catalogSessions = listOf("throttled-cat"),
        )
        .also { it.start() }
    fun fetchMotion(): Pair<Int, String?> {
      val req =
        Request.Builder()
          .url("http://127.0.0.1:${motionServer.port}/throttled-cat/motion/$motionId.apng")
          .build()
      client.newCall(req).execute().use { r ->
        return r.code to r.header("Retry-After")
      }
    }
    try {
      // Throttled: 503, and the branch host's own interval is passed straight through.
      assertEquals(503 to "7", fetchMotion())

      // An outage that names no interval still gets a usable one rather than none.
      answer = BranchFetch.Unavailable(503)
      val (code, retryAfter) = fetchMotion()
      assertEquals(503, code)
      assertNotEquals(null, retryAfter, "a 503 without Retry-After tells the client nothing")

      // A capture the catalog genuinely never published stays a 404 — this is not a blanket 503.
      answer = BranchFetch.NotFound
      assertEquals(404 to null, fetchMotion())

      // A capture past the transport's envelope is a third answer again: it exists, it is not
      // coming, and asking again will not shrink it. It carries no bytes and is not transient, so
      // without a case of its own it lands in the 404 branch — a file the branch is holding,
      // reported as one that was never published. That is the absence-versus-refusal confusion this
      // whole route exists to end, arriving through the outcome added to end it elsewhere.
      //
      // Not 503 either: `Retry-After` on something that will be exactly as oversized next time is a
      // promise the server cannot keep.
      answer = BranchFetch.TooLarge(25L * 1024 * 1024)
      assertEquals(413 to null, fetchMotion())

      // …and once the branch serves it, it serves.
      answer = BranchFetch.Ok("capture".toByteArray())
      assertEquals(200 to null, fetchMotion())
    } finally {
      motionServer.stop()
      motionRegistry.close()
    }
  }

  @Test
  fun `the motion lane resumes a known session but never forks an unknown one`() {
    // Leasing falls through to the session factory for an unknown id, which in project mode with
    // `--revisions` checks out a ref and runs a Gradle build. A revision host publishes no
    // captures, so such a request can only 404 — after paying for the build. The lane must resume
    // what is already registered and refuse everything else.
    val forked = mutableListOf<String>()
    val factoryRegistry =
      ServeSessionRegistry(
        open = { null },
        factory =
          ServeSessionFactory { id ->
            forked += id
            null
          },
      )
    factoryRegistry.register("default-mod", host = bundle("default-mod"), pinned = true)
    val factoryServer =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = factoryRegistry,
          defaultSessionId = "default-mod",
          isPublic = true,
        )
        .also { it.start() }
    try {
      val req =
        Request.Builder()
          .url("http://127.0.0.1:${factoryServer.port}/some-unknown-ref/motion/anything.apng")
          .build()
      client.newCall(req).execute().use { r -> assertEquals(404, r.code) }
      assertTrue(forked.isEmpty(), "the lane must not fork a session to answer 404: $forked")
    } finally {
      factoryServer.stop()
      factoryRegistry.close()
    }
  }

  @Test
  fun `a failed optional catalog does not block readiness`() {
    val partialRegistry = ServeSessionRegistry(open = { null })
    partialRegistry.register("default-mod", host = bundle("partial-default"), pinned = true)
    val loads =
      CatalogLoadTracker(
        listOf(
          CatalogLoadTracker.Config(
            system = "reply",
            listed = true,
            repo = "yschimke/compose-samples",
            branch = "design-artifacts/reply",
          )
        )
      )
    loads.recordFailure("reply", "could not parse catalog.json")
    val partialServer =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = partialRegistry,
          defaultSessionId = "default-mod",
          isPublic = true,
          catalogSessions = listOf("reply"),
          catalogLoads = loads,
        )
        .also { it.start() }
    try {
      var response = 503 to "warming"
      for (attempt in 0 until 50) {
        val req = Request.Builder().url("http://127.0.0.1:${partialServer.port}/readyz").build()
        client.newCall(req).execute().use { r -> response = r.code to r.body.string() }
        if (response.first == 200) break
        Thread.sleep(100)
      }
      assertEquals(200 to "ready", response)
    } finally {
      partialServer.stop()
      partialRegistry.close()
    }
  }

  @Test
  fun `readyz falls through to a later loaded catalog when the configured default failed`() {
    val catalogRegistry = ServeSessionRegistry(open = { null })
    catalogRegistry.register("reply", host = bundle("reply"), pinned = true)
    val loads =
      CatalogLoadTracker(
        listOf(
          CatalogLoadTracker.Config(
            system = "compose-m3",
            listed = true,
            repo = "yschimke/compose-ai-tools",
            branch = "design-artifacts/compose-m3",
          ),
          CatalogLoadTracker.Config(
            system = "reply",
            listed = true,
            repo = "yschimke/compose-samples",
            branch = "design-artifacts/reply",
          ),
        )
      )
    loads.recordFailure("compose-m3", "could not parse catalog.json")
    loads.recordSuccess("reply")
    val catalogServer =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = catalogRegistry,
          defaultSessionId = "compose-m3",
          isPublic = true,
          catalogSessions = listOf("compose-m3", "reply"),
          catalogLoads = loads,
        )
        .also { it.start() }
    try {
      var response = 503 to "warming"
      for (attempt in 0 until 50) {
        val req = Request.Builder().url("http://127.0.0.1:${catalogServer.port}/readyz").build()
        client.newCall(req).execute().use { r -> response = r.code to r.body.string() }
        if (response.first == 200) break
        Thread.sleep(100)
      }
      assertEquals(200 to "ready", response)
    } finally {
      catalogServer.stop()
      catalogRegistry.close()
    }
  }

  @Test
  fun `readyz waits for an all-unlisted catalog server to load a catalog`() {
    val catalogRegistry = ServeSessionRegistry(open = { null })
    val loads =
      CatalogLoadTracker(
        listOf(
          CatalogLoadTracker.Config(
            system = "meshcore-mobile",
            listed = false,
            repo = "yschimke/meshcore-mobile",
            branch = "design-artifacts/meshcore-mobile",
          )
        )
      )
    val catalogServer =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = catalogRegistry,
          defaultSessionId = "",
          isPublic = true,
          appCatalogSessions = listOf("meshcore-mobile"),
          catalogLoads = loads,
        )
        .also { it.start() }
    try {
      Request.Builder().url("http://127.0.0.1:${catalogServer.port}/readyz").build().let { req ->
        client.newCall(req).execute().use { r ->
          assertEquals(503, r.code)
          assertEquals("warming", r.body.string())
        }
      }

      catalogRegistry.register("meshcore-mobile", host = bundle("meshcore-mobile"), pinned = true)
      loads.recordSuccess("meshcore-mobile")

      var response = 503 to "warming"
      for (attempt in 0 until 50) {
        val req = Request.Builder().url("http://127.0.0.1:${catalogServer.port}/readyz").build()
        client.newCall(req).execute().use { r -> response = r.code to r.body.string() }
        if (response.first == 200) break
        Thread.sleep(100)
      }
      assertEquals(200 to "ready", response)
    } finally {
      catalogServer.stop()
      catalogRegistry.close()
    }
  }

  @Test
  fun `readyz withholds ready when the default session cannot render`() {
    // A server whose default session resolves to nothing (an empty registry) can't render a
    // representative preview, so /readyz must report 503 "warming" — NOT a false green. This is the
    // failure docker-rollout must catch: a replica up on the port but unable to serve. Its own
    // server + registry so the class-level `server` fields are untouched.
    val emptyRegistry = ServeSessionRegistry(open = { null })
    val brokenServer =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = emptyRegistry,
          defaultSessionId = "no-such-session",
          isPublic = true,
        )
        .also { it.start() }
    try {
      val req = Request.Builder().url("http://127.0.0.1:${brokenServer.port}/readyz").build()
      client.newCall(req).execute().use { r ->
        assertEquals(503, r.code)
        assertEquals("warming", r.body.string())
      }
    } finally {
      brokenServer.stop()
      emptyRegistry.close()
    }
  }

  @Test
  fun `the wasm route serves an app registered after the listener bound`() {
    // #3127 made the listener bind BEFORE the catalogs load, so `wasmCatalogs` is empty at route-
    // installation time and only fills in later. Gating the route's registration on the map being
    // non-empty therefore dropped `/wasm/…` for the whole process lifetime, while the viewer —
    // which reads the same live map on each request — still offered "Run in browser (Wasm)". The
    // route must be installed unconditionally and consult the map per request.
    val appDir = Files.createTempDirectory("serve-wasm-late").toFile().also { it.deleteOnExit() }
    val wasmCatalogs = mutableMapOf<String, File>()
    val lateRegistry = ServeSessionRegistry(open = { null })
    lateRegistry.register("default-mod", host = bundle("late-default"), pinned = true)
    val lateServer =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = lateRegistry,
          defaultSessionId = "default-mod",
          isPublic = true,
          wasmCatalogs = wasmCatalogs,
        )
        .also { it.start() }
    fun fetch(path: String): Pair<Int, String> {
      val req = Request.Builder().url("http://127.0.0.1:${lateServer.port}$path").build()
      client.newCall(req).execute().use { r ->
        return r.code to r.body.string()
      }
    }
    try {
      // Nothing registered yet: 404 from inside the handler, not a missing route.
      assertEquals(404, fetch("/wasm/compose-m3/").first)
      // The catalog load finishes and publishes its app into the live map.
      File(appDir, "index.html").writeText("<!doctype html><title>wasm app</title>")
      wasmCatalogs["compose-m3"] = appDir
      val (code, body) = fetch("/wasm/compose-m3/")
      assertEquals(200, code, "the late-registered wasm app must be reachable")
      assertTrue(body.contains("wasm app"), "served index.html: $body")
      // An unknown system still 404s.
      assertEquals(404, fetch("/wasm/nope/").first)
    } finally {
      lateServer.stop()
      lateRegistry.close()
    }
  }

  @Test
  fun `private wasm route keeps auto-discovered local assets behind the path token`() {
    val appDir = Files.createTempDirectory("serve-wasm-private").toFile().also { it.deleteOnExit() }
    File(appDir, "index.html").writeText("<!doctype html><script src=app.js></script>")
    File(appDir, "app.js").writeText("window.started = true")
    val registry = ServeSessionRegistry(open = { null })
    val privateServer =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "secret",
          sessions = registry,
          defaultSessionId = "local",
          wasmCatalogs = mapOf("local" to appDir),
          privateWasmCatalogs = setOf("local"),
        )
        .also { it.start() }
    fun fetch(path: String): Pair<Int, String> {
      val req = Request.Builder().url("http://127.0.0.1:${privateServer.port}$path").build()
      client.newCall(req).execute().use { response ->
        return response.code to response.body.string()
      }
    }
    try {
      assertEquals(404, fetch("/wasm/local/").first, "ordinary route must not bypass the token")
      assertEquals(404, fetch("/wasm-private/wrong/local/").first)
      assertEquals(200, fetch("/wasm-private/secret/local/").first)
      assertEquals("window.started = true", fetch("/wasm-private/secret/local/app.js").second)
    } finally {
      privateServer.stop()
      registry.close()
    }
  }

  @Test
  fun `a catalog is reachable under its canonical path`() {
    val (landingCode, landing) = get("/compose-m3/")
    assertEquals(200, landingCode)
    // The landing's own links stay on the path (no &session=). Public mode is open, so the link
    // also carries no ?token — the route needs none.
    assertTrue(landing.contains("href=\"/compose-m3/p/$previewId\""), "path card link: $landing")
    assertTrue(!landing.contains("token="), "public path landing links are token-free: $landing")
    assertTrue(landing.contains("1 preview · 1 view"), "catalog visit counted: $landing")

    val (viewerCode, viewer) = get("/compose-m3/p/$previewId")
    assertEquals(200, viewerCode)
    assertTrue(viewer.contains("data-preview-id=\"$previewId\""), "viewer for the preview")

    // The path render lane returns the baked PNG bytes.
    val renderReq =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}/compose-m3/render/$previewId.png")
        .build()
    client.newCall(renderReq).execute().use { r ->
      assertEquals(200, r.code)
      assertEquals("image/png", r.body.contentType()?.let { "${it.type}/${it.subtype}" })
    }

    val (apiCode, api) = get("/compose-m3/api/previews")
    assertEquals(200, apiCode)
    assertTrue(api.contains("\"module\":\"compose-m3\""), "api for the path session: $api")
    assertTrue(api.contains("\"views\":1"), "catalog and preview engagement in api: $api")

    val (componentsCode, components) = get("/api/components")
    assertEquals(200, componentsCode)
    assertTrue(
      components.contains("\"catalog\":\"compose-m3\"") &&
        components.contains("\"href\":\"/compose-m3/p/$previewId\""),
      "global component index names the catalog and canonical viewer: $components",
    )
  }

  @Test
  fun `the design-parity view serves under both session forms and links from the landing`() {
    val (pathCode, page) = get("/compose-m3/parity")
    assertEquals(200, pathCode)
    assertTrue(page.contains("Design parity"), "parity heading: $page")
    // The code lane's commit link is rebuilt from the validated repo + sha, not taken verbatim.
    assertTrue(
      page.contains(
        "https://github.com/yschimke/compose-ai-tools/commit/4e73ec2b9f0a1c3d5e7f9a1b3c5d7e9f0a1b3c5d"
      ),
      "commit link: $page",
    )
    // …and the Figma comment deep-links to its node, in Figma's `-` URL form.
    assertTrue(
      page.contains("https://www.figma.com/design/abc123?node-id=51592-4768"),
      "figma node link: $page",
    )
    // The comment names a component with no code movement, so it lands in the drift band.
    assertTrue(page.contains("design only"), "one-sided design movement: $page")
    // A producer-declared gap the server could not have derived itself.
    assertTrue(page.contains("design node with no code"), "declared gap: $page")

    val (queryCode, _) = get("/parity?session=compose-m3")
    assertEquals(200, queryCode, "the legacy ?session= form serves the same view")

    val (jsonCode, json) = get("/compose-m3/parity.json")
    assertEquals(200, jsonCode)
    assertTrue(
      json.contains("\"schema\":\"compose-preview-serve/parity/v1\""),
      "json schema: $json",
    )
    assertTrue(json.contains("\"percent\":100"), "the one component is mapped: $json")
    assertTrue(json.contains("\"lane\":\"figma-comment\""), "the comment lane: $json")
    assertEquals(jsonCode to json, get("/compose-m3/parity?format=json"))

    val (_, landing) = get("/compose-m3")
    assertTrue(landing.contains("href=\"/compose-m3/parity\""), "landing links the view: $landing")
  }

  @Test
  fun `a session with no references and no feed offers no parity view at all`() {
    // `default-mod` maps nothing and publishes no feed — a page of zeroes helps nobody, so the
    // route 404s and the landing must not advertise it.
    val (code, _) = get("/parity")
    assertEquals(404, code)
    assertEquals(404, get("/parity.json").first)

    val (_, landing) = get("/")
    assertTrue(!landing.contains("/parity"), "no dead parity link on the landing: $landing")
  }

  @Test
  fun `a plain module's landing offers no catalog tracker`() {
    // `burst` is a plain `ServeHost`, not a `ServeBundleHost`, so `catalogBundleHost` is null and
    // there is no catalog to file anything against. The page-scoped report was built
    // unconditionally, and `repoFor` falls back to compose-ai-tools when a session names neither
    // source nor provenance — so this module's visitor was offered a form naming the TOOL's own
    // tracker as the repo that declares "this catalog", for a catalog that does not exist. Every
    // caller's parameter documents the opposite: "Null (a plain module, or any caller that has
    // nothing to file against) omits it entirely."
    //
    // `/burst` rather than `/`: this server registers several sessions, so `/` is the FRONT DOOR
    // and carries no catalog report either way — an assertion there passes whatever the handler
    // does, which is how the first version of this test managed to hold against the bug.
    //
    // Only the catalog half is asserted absent. The floating launcher's SERVER half legitimately
    // points at this repository — a preview server bug is ours — and is not what this removes.
    val (code, landing) = get("/burst")
    assertEquals(200, code, "the plain module's landing is served: $landing")
    assertTrue(
      !landing.contains("id=\"cp-report\""),
      "a plain module's landing carries no catalog report row: $landing",
    )
    assertTrue(
      !landing.contains("data-cp-subject=\"this catalog\""),
      "and nothing claims a catalog it does not have: $landing",
    )
  }

  @Test
  fun `a plain bundle is not a catalog and offers no catalog tracker`() {
    // `ServeBundleHost` backs three different things: a catalog published by `ServeCatalogStore`, a
    // `--bundles` directory, and an uploaded portable bundle. Only the first has a `catalog.json`.
    // Testing the host TYPE read all three as catalogs, so an upload was offered a report about
    // "this catalog" against the fallback compose-ai-tools repo — the same defect as the plain
    // module one door along, arriving through a host that IS a `ServeBundleHost`.
    val (code, landing) = get("/plain-bundle")
    assertEquals(200, code, "the plain bundle's landing is served: $landing")
    assertTrue(
      !landing.contains("id=\"cp-report\""),
      "a plain bundle carries no catalog report row: $landing",
    )
    assertTrue(
      !landing.contains("data-cp-subject=\"this catalog\""),
      "and claims no catalog it does not have: $landing",
    )
  }

  @Test
  fun `a page-scoped report pins the presentation mode it was filed from`() {
    // The report link is read by a triager who does not have the reporter's cookie. Mode is
    // deliberately a property of the visitor rather than of each URL, but `?chrome=` was kept
    // precisely as a permalink — "a link may pin the presentation it was written for". Without it a
    // Catalog-mode report opens the Dev surface for a Dev-mode triager, which is not the page that
    // was reported.
    val (_, dev) = get("/compose-m3")
    assertTrue(dev.contains("chrome%3Ddev") || dev.contains("chrome=dev"), "Dev pins dev: $dev")

    val (_, catalog) = get("/compose-m3?chrome=catalog")
    assertTrue(
      catalog.contains("chrome%3Dcatalog") || catalog.contains("chrome=catalog"),
      "Catalog mode pins catalog: $catalog",
    )
    // A URL that already pinned itself is left alone rather than pinned twice.
    assertTrue(
      !catalog.contains("chrome%3Dcatalog%26chrome") && !catalog.contains("chrome=catalog&chrome"),
      "the pin is not appended to a URL that already carries one: $catalog",
    )

    // An UNRECOGNISED pin is replaced, not kept: `interfaceMode` accepts only `catalog`/`dev`, so
    // the request fell back to the cookie or the server default and the raw value names a mode the
    // page was never served in. Keeping it pins the wrong surface through the one value nobody
    // validated; appending would leave two `chrome=` for the reader's parser to break.
    val (_, bogus) = get("/compose-m3?chrome=invalid")
    // Scoped to the report form: the raw query legitimately survives elsewhere on the page (the
    // canonical `og:url` is the URL as requested). What must not carry it is the report.
    val report = bogus.substringAfter("id=\"cp-report\"", "").substringBefore("</form>")
    assertTrue(report.isNotEmpty(), "the catalog report row is present: $bogus")
    assertTrue(
      report.contains("chrome%3Ddev") || report.contains("chrome=dev"),
      "an unrecognised pin is replaced with the resolved mode: $report",
    )
    assertTrue(
      !report.contains("chrome%3Dinvalid") && !report.contains("chrome=invalid"),
      "and the unrecognised value does not survive into the report: $report",
    )
  }

  @Test
  fun `a percent-encoded chrome pin is replaced, not doubled`() {
    // Ktor decodes a parameter NAME before it reaches `queryParameters`, so `?%63hrome=invalid` is
    // `chrome` to every read in the server — including the check that decides this URL carries an
    // unrecognised pin. Comparing the raw text when dropping it kept the pair and appended a second
    // `chrome=`, and a reader taking the first value read the invalid one, ignored it and fell back
    // to their own mode: the wrong-surface failure the pin exists to prevent, restored by the
    // replacement meant to close it.
    val (_, page) = get("/compose-m3?%63hrome=invalid")
    val report = page.substringAfter("id=\"cp-report\"", "").substringBefore("</form>")
    assertTrue(report.isNotEmpty(), "the catalog report row is present: $page")
    assertTrue(
      report.contains("chrome%3Ddev") || report.contains("chrome=dev"),
      "the resolved mode is pinned: $report",
    )
    // Neither spelling of the stale pin survives — the encoded pair is what used to.
    assertTrue(
      !report.contains("%2563hrome") && !report.contains("%63hrome"),
      "the encoded pin is dropped rather than carried alongside the new one: $report",
    )
    assertTrue(
      !report.contains("chrome%3Dinvalid") && !report.contains("chrome=invalid"),
      "and its value does not reach the report by any spelling: $report",
    )
  }

  @Test
  fun `a query name that is not this pin survives the replacement`() {
    // The drop is scoped to what the server itself reads as the chrome pin. An unrelated parameter
    // — encoded or not, and including one whose name is not valid percent-encoding — is carried
    // through untouched, so replacing the pin never costs a report its other context.
    val (_, page) = get("/compose-m3?locale=en-US&%7Anote=keep&chrome=invalid")
    val report = page.substringAfter("id=\"cp-report\"", "").substringBefore("</form>")
    assertTrue(report.isNotEmpty(), "the catalog report row is present: $page")
    assertTrue(
      report.contains("locale%3Den-US") || report.contains("locale=en-US"),
      "an ordinary parameter is kept: $report",
    )
    assertTrue(
      report.contains("note%3Dkeep") || report.contains("note=keep"),
      "so is an encoded one that is not the pin: $report",
    )
  }

  @Test
  fun `viewer unfurl metadata uses the external origin and preserves render overrides`() {
    val request =
      Request.Builder()
        .url(
          "http://127.0.0.1:${server.port}/compose-m3/p/$previewId" + "?fontScale=1.5&locale=en-US"
        )
        .header("Host", "preview.coo.ee")
        .header("X-Forwarded-Proto", "https")
        .build()

    client.newCall(request).execute().use { response ->
      assertEquals(200, response.code)
      val html = response.body.string().orEmpty()
      assertTrue(
        html.contains(
          "<meta property=\"og:url\" content=\"https://preview.coo.ee/compose-m3/p/" +
            "$previewId?fontScale=1.5&amp;locale=en-US\">"
        ),
        "canonical external viewer URL: $html",
      )
      val imageUrl =
        "https://preview.coo.ee/compose-m3/render/$previewId.png?" +
          "fontScale=1.5&amp;locale=en-US"
      assertTrue(
        html.contains("<meta property=\"og:image\" content=\"$imageUrl\">"),
        "Open Graph points at the matching render: $html",
      )
      assertTrue(
        html.contains("<meta name=\"twitter:image\" content=\"$imageUrl\">"),
        "Twitter points at the matching render: $html",
      )
    }
  }

  @Test
  fun `each browse page unfurls the content expected for that page`() {
    fun proxied(path: String): Pair<Int, String> {
      val request =
        Request.Builder()
          .url("http://127.0.0.1:${server.port}$path")
          .header("Host", "preview.coo.ee")
          .header("X-Forwarded-Proto", "https")
          .build()
      client.newCall(request).execute().use { response ->
        return response.code to response.body.string()
      }
    }

    // Both the index and a catalog landing advertise a DRAWN card ([ServeSocialCard]) on the
    // `/social/` lane, at the origin the proxy presents. Neither points at a render any more: those
    // are portrait phone screenshots, and a large-image card crops one to a horizontal band.
    val cardImage =
      Regex(
        """<meta property="og:image" content="(https://preview\.coo\.ee/social/[a-f0-9]+\.png)">"""
      )

    val (homeCode, home) = proxied("/")
    assertEquals(200, homeCode)
    assertTrue(cardImage.containsMatchIn(home), "the server index advertises a drawn card: $home")

    val (catalogCode, catalog) = proxied("/compose-m3/")
    assertEquals(200, catalogCode)
    assertTrue(
      cardImage.containsMatchIn(catalog),
      "a catalog landing advertises its own drawn card: $catalog",
    )
    assertNotEquals(
      cardImage.find(home)!!.groupValues[1],
      cardImage.find(catalog)!!.groupValues[1],
      "…and it is a different card from the index's — different heading, different picture",
    )

    val (statusCode, status) = proxied("/status")
    assertEquals(200, statusCode)
    assertTrue(
      status.contains("<meta name=\"twitter:card\" content=\"summary\">"),
      "the utility page gets an accurate text card",
    )
    assertTrue(!status.contains("<meta property=\"og:image\""), "status does not claim a component")

    val (missingCode, missing) = proxied("/compose-m3/p/does-not-exist")
    assertEquals(404, missingCode)
    assertTrue(
      missing.contains("<meta property=\"og:title\" content=\"Not found — compose-preview\">"),
      "the error page describes itself",
    )
    assertTrue(!missing.contains("<meta property=\"og:image\""), "404 does not claim a component")
  }

  @Test
  fun `public browse pages and baked previews advertise static generation`() {
    val homeReq = Request.Builder().url("http://127.0.0.1:${server.port}/").build()
    client.newCall(homeReq).execute().use { response ->
      assertEquals("static-page", response.header(ServeHttpServer.GENERATION_HEADER))
      assertTrue(response.header("Cache-Control")?.startsWith("public, max-age=60") == true)
    }

    val landingReq = Request.Builder().url("http://127.0.0.1:${server.port}/compose-m3/").build()
    client.newCall(landingReq).execute().use { response ->
      assertEquals("static-page", response.header(ServeHttpServer.GENERATION_HEADER))
      assertTrue(response.header("Cache-Control")?.startsWith("public, max-age=60") == true)
    }

    val viewerReq =
      Request.Builder().url("http://127.0.0.1:${server.port}/rc-published/p/$previewId").build()
    client.newCall(viewerReq).execute().use { response ->
      assertEquals("static-page", response.header(ServeHttpServer.GENERATION_HEADER))
      assertTrue(
        response.header("Cache-Control")?.startsWith("public, max-age=60") == true,
        "viewer ${response.code} cache-control was ${response.header("Cache-Control")}",
      )
    }

    // compose-m3 carries an RC document and no published comparison manifest — but no background
    // lane is going to bring it one either, and a host with nothing to wait for is fully baked.
    // Dropping it to `no-store` is what `stagesRcCompare` was introduced to stop: gating on the
    // absent file alone made `pending()` permanently true for every laneless session and kept its
    // viewer pages out of the edge cache for the life of the host.
    val settledViewerReq =
      Request.Builder().url("http://127.0.0.1:${server.port}/compose-m3/p/$previewId").build()
    client.newCall(settledViewerReq).execute().use { response ->
      assertEquals("static-page", response.header(ServeHttpServer.GENERATION_HEADER))
      assertTrue(
        response.header("Cache-Control")?.startsWith("public, max-age=60") == true,
        "laneless viewer ${response.code} cache-control was ${response.header("Cache-Control")}",
      )
    }

    // …and the case that IS uncacheable: a catalog whose lane will stage a comparison and has not
    // yet. The page's shape depends on a manifest that lands asynchronously, so a short edge cache
    // would serve the pre-manifest shape for minutes after the lanes were ready.
    val pendingViewerReq =
      Request.Builder().url("http://127.0.0.1:${server.port}/staging-rc/p/$previewId").build()
    client.newCall(pendingViewerReq).execute().use { response ->
      assertEquals("static-page", response.header(ServeHttpServer.GENERATION_HEADER))
      assertEquals("no-store", response.header("Cache-Control"))
    }

    val missingViewerReq =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}/compose-m3/p/not-published-yet")
        .build()
    client.newCall(missingViewerReq).execute().use { response ->
      assertEquals(404, response.code)
      assertEquals("no-store", response.header("Cache-Control"))
    }

    val renderReq =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}/compose-m3/render/$previewId.png")
        .build()
    client.newCall(renderReq).execute().use { response ->
      assertEquals("baked", response.header(ServeHttpServer.GENERATION_HEADER))
      assertTrue(response.header("Cache-Control")?.startsWith("public, max-age=300") == true)
    }
  }

  @Test
  fun `variant render remains non cacheable`() {
    // This fixture is a static bundle, so it can only return baked bytes. Since #3449 that is a
    // refusal unless the caller opts into the snapshot — and the opted-in response, being a variant
    // request, must still never poison the cache for another query.
    val req =
      Request.Builder()
        .url(
          "http://127.0.0.1:${server.port}/compose-m3/render/$previewId.png" +
            "?fontScale=1.5&fallback=baked"
        )
        .build()
    client.newCall(req).execute().use { response ->
      assertEquals("baked", response.header(ServeHttpServer.GENERATION_HEADER))
      assertEquals("no-store", response.header("Cache-Control"))
    }
  }

  @Test
  fun `a static bundle 404s the svg render lane`() {
    // The .svg lane is routed and dispatched, but a bundle host has no daemon to run the figma-svg
    // export, so it resolves to NotFound (only a daemon-backed ServeRenderHost produces SVG).
    val (code, _) = get("/compose-m3/render/$previewId.svg")
    assertEquals(404, code)
  }

  @Test
  fun `a static bundle 404s the full-page svg render lane`() {
    // `?scroll=long` routes to the full-page (compose/figma-svg-long) lane; a bundle host has no
    // daemon to run the expanded re-render, so it resolves to NotFound like the viewport SVG lane.
    val (code, _) = get("/compose-m3/render/$previewId.svg?scroll=long")
    assertEquals(404, code)
  }

  @Test
  fun `a static bundle 404s the slots render lane`() {
    // The .slots lane is routed and dispatched, but a bundle host has no daemon to capture a
    // semantics tree, so it resolves to NotFound (only a daemon-backed ServeRenderHost extracts
    // slots).
    val (code, _) = get("/compose-m3/render/$previewId.slots")
    assertEquals(404, code)
  }

  @Test
  fun `the rc render lane serves the captured remote compose document bytes`() {
    // compose-m3 carries an `ir/<id>.rc` sidecar, so `GET /render/<id>.rc` returns those
    // bytes
    // verbatim (octet-stream) for the in-browser player to replay client-side.
    val req =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}/compose-m3/render/$previewId.rc")
        .build()
    client.newCall(req).execute().use { r ->
      assertEquals(200, r.code)
      assertEquals(
        "application/octet-stream",
        r.body.contentType()?.let { "${it.type}/${it.subtype}" },
      )
      assertTrue(rcDocBytes.contentEquals(r.body.bytes()), "rc bytes served verbatim")
    }
  }

  @Test
  fun `a bundle without an rc sidecar 404s the rc render lane`() {
    // default-mod carries no `ir/` tree, so the client-side player lane resolves to NotFound rather
    // than serving an empty document.
    val (code, _) = get("/render/$previewId.rc?session=default-mod")
    assertEquals(404, code)
  }

  @Test
  fun `the viewer offers the in-browser canvas lane when a preview carries an rc document`() {
    // compose-m3's preview has an `ir/<id>.rc` sidecar, so its viewer page advertises the RC canvas
    // lane: the flag the transport JS keys on, the canvas, the mode radio, and the toggle button.
    val (code, html) = get("/compose-m3/p/$previewId")
    assertEquals(200, code)
    assertTrue(html.contains("data-has-rc-doc=\"1\""), "viewer flags the rc document: $html")
    assertTrue(html.contains("id=\"cp-rc-canvas\""), "rc canvas element present")
    // The renderer combo replaces the old row of chips: `rc:js` drives the same canvas lane.
    assertTrue(html.contains("id=\"cp-lane-select\""), "renderer combo present")
    assertTrue(html.contains("value=\"rc:js\""), "js player option present")
    assertTrue(html.contains("value=\"rc:cmp-wasm\""), "cmp-wasm player option present")
    assertTrue(html.contains("id=\"cp-rc-wasm\""), "cmp-wasm iframe present")
    assertTrue(html.contains("value=\"rc\""), "rc mode radio present")
    // The client-side lane JS loads the player and applies knob edits without a daemon round-trip.
    val viewerJs = viewerSource()
    assertTrue(viewerJs.contains("/rc-player/bundle.js"), "the lane loads the player bundle")
    assertTrue(viewerJs.contains("RcdPlayer"), "the lane creates the Rc player")
    assertTrue(viewerJs.contains("setNamedFloatOverride"), "rc knob edits apply client-side")
  }

  @Test
  fun `the cmp wasm player distribution is served with wasm content type`() {
    assertEquals(200 to "<html>cmp wasm</html>", get("/rc-player-wasm/"))
    val req =
      Request.Builder().url("http://127.0.0.1:${server.port}/rc-player-wasm/rcPlayer.wasm").build()
    client.newCall(req).execute().use { response ->
      assertEquals(200, response.code)
      assertEquals("application/wasm", response.body.contentType().toString())
      assertEquals("no-cache", response.header("Cache-Control"))
      assertTrue(response.header("ETag")?.isNotBlank() == true, "wasm response carries an ETag")
      assertTrue(
        byteArrayOf(0x00, 0x61, 0x73, 0x6d).contentEquals(response.body.bytes()),
        "wasm bytes served verbatim",
      )
    }
  }

  @Test
  fun `the viewer omits the canvas lane when the preview has no rc document`() {
    // default-mod carries no `.rc` document, so the RC canvas lane's HTML (flag, canvas, toggle) is
    // absent and its Remote Compose knobs stay on the daemon path.
    val (code, html) = get("/p/$previewId?session=default-mod")
    assertEquals(200, code)
    // The transport JS always *reads* `data-has-rc-doc`, so assert the attribute form (`="1"`) is
    // absent rather than the bare name, plus the lane's HTML elements.
    assertTrue(
      !html.contains("data-has-rc-doc=\"1\""),
      "no rc-doc flag on a docless preview: $html",
    )
    assertTrue(!html.contains("id=\"cp-rc-canvas\""), "no rc canvas on a docless preview")
    assertTrue(
      !html.contains("id=\"cp-lane-select\""),
      "no renderer combo on a docless single-lane preview",
    )
  }

  @Test
  fun `the rc player bundle is served as javascript with a conditional-request etag`() {
    // The vendored Remote Compose player rides in the CLI jar and is served over
    // `/rc-player/bundle.js` (a constant segment, session-independent) so the viewer's client-side
    // canvas lane can load the `RC` global. Served as JS (so the browser executes it) with a
    // content-hash ETag for cheap conditional requests.
    val etag: String
    val req = Request.Builder().url("http://127.0.0.1:${server.port}/rc-player/bundle.js").build()
    client.newCall(req).execute().use { r ->
      assertEquals(200, r.code)
      assertEquals("text/javascript", r.body.contentType()?.let { "${it.type}/${it.subtype}" })
      val body = r.body.string()
      assertTrue(body.contains("RcdPlayer"), "the bundle exposes the RcdPlayer entry point")
      etag = r.header("ETag") ?: ""
      assertTrue(etag.isNotEmpty(), "carries a content-hash ETag")
    }
    // A conditional GET carrying the matching ETag gets a cheap 304 instead of re-downloading the
    // ~640 KB bundle.
    val conditional =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}/rc-player/bundle.js")
        .header("If-None-Match", etag)
        .build()
    client.newCall(conditional).execute().use { r -> assertEquals(304, r.code) }
  }

  @Test
  fun `the rc typefaces are served as a stylesheet plus the faces it declares`() {
    // Without these the client-side lane paints a document's generic families in whatever the
    // *visitor's* machine calls `sans-serif` — different outlines, ~4% different line metrics, and
    // no
    // Medium weight — while the PNG beside it used the vendored files (issue #3480).
    val cssReq = Request.Builder().url("http://127.0.0.1:${server.port}/rc-fonts/fonts.css").build()
    val css =
      client.newCall(cssReq).execute().use { r ->
        assertEquals(200, r.code)
        assertEquals("text/css", r.body.contentType()?.let { "${it.type}/${it.subtype}" })
        r.body.string()
      }
    for (face in ServeRcFonts.FACES) {
      assertTrue(css.contains("font-family:\"${face.family}\""), "declares ${face.family}: $css")
      assertTrue(css.contains("/rc-fonts/${face.file}"), "points at ${face.file}: $css")
    }

    val face = ServeRcFonts.FACES.first()
    val etag: String
    val faceReq =
      Request.Builder().url("http://127.0.0.1:${server.port}/rc-fonts/${face.file}").build()
    client.newCall(faceReq).execute().use { r ->
      assertEquals(200, r.code)
      assertEquals("font/ttf", r.body.contentType()?.let { "${it.type}/${it.subtype}" })
      assertTrue(r.body.contentLength() > 1024, "the face carries its bytes")
      etag = r.header("ETag") ?: ""
      assertTrue(etag.isNotEmpty(), "carries a content-hash ETag")
    }
    // A repeat visitor revalidates instead of re-downloading a few hundred KB per face.
    val conditional =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}/rc-fonts/${face.file}")
        .header("If-None-Match", etag)
        .build()
    client.newCall(conditional).execute().use { r -> assertEquals(304, r.code) }

    // The route serves a declared set, not an arbitrary classpath path.
    val (undeclared, _) = get("/rc-fonts/Roboto-Black.ttf")
    assertEquals(404, undeclared)
  }

  @Test
  fun `serve web assets are versioned and conditionally cacheable`() {
    val scripts =
      listOf(
        "vue-runtime.js",
        "catalog-components.js",
        "compare-components.js",
        "design-components.js",
        "parity-components.js",
        "viewer-components.js",
        "remote-compose.js",
        "known-differences.js",
        "viewer.js",
      )
    scripts.forEach { name ->
      val asset = ServeWebAssets.load(name) ?: error("$name missing")
      val versionedPath = "/assets/serve/${asset.version}/$name"
      val req = Request.Builder().url("http://127.0.0.1:${server.port}$versionedPath").build()
      client.newCall(req).execute().use { r ->
        assertEquals(200, r.code, name)
        assertEquals(
          "text/javascript",
          r.body.contentType()?.let { "${it.type}/${it.subtype}" },
          name,
        )
        assertEquals("public, max-age=31536000, immutable", r.header("Cache-Control"), name)
        assertEquals(asset.etag, r.header("ETag"), name)
      }
    }

    val asset = ServeWebAssets.load("vue-runtime.js") ?: error("Vue runtime missing")
    val versionedPath = "/assets/serve/${asset.version}/vue-runtime.js"
    val conditional =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}$versionedPath")
        .header("If-None-Match", asset.etag)
        .build()
    client.newCall(conditional).execute().use { r -> assertEquals(304, r.code) }

    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.port}/assets/serve/stale/vue-runtime.js")
          .build()
      )
      .execute()
      .use { r -> assertEquals(404, r.code) }

    client
      .newCall(
        Request.Builder().url("http://127.0.0.1:${server.port}/assets/serve/vue-runtime.js").build()
      )
      .execute()
      .use { r ->
        assertEquals(200, r.code)
        assertEquals("no-cache", r.header("Cache-Control"))
      }
  }

  @Test
  fun `api previews advertises v2 and carries author override declarations`() {
    val (code, api) = get("/compose-m3/api/previews")
    assertEquals(200, code)
    // v2 = the payload now carries per-preview override declarations.
    assertTrue(api.contains("\"schema\":\"compose-preview-serve/v2\""), "schema v2: $api")
    // The declared `label` knob (from the sidecar) surfaces to a programmatic client.
    assertTrue(api.contains("\"overrides\":["), "overrides array present: $api")
    assertTrue(api.contains("\"key\":\"label\""), "declared knob key: $api")
    assertTrue(api.contains("\"value\":\"Tap me\""), "declared knob default value: $api")
    // The declared Remote Compose knob (from the `.remotecompose.json` sidecar) surfaces too, with
    // its typed default (the `kind` discriminator + argb the connector round-trips).
    assertTrue(api.contains("\"remoteComposeKnobs\":["), "rc knobs array present: $api")
    assertTrue(api.contains("\"name\":\"shaderColor\""), "declared rc knob name: $api")
    assertTrue(api.contains("\"kind\":\"color\""), "rc knob typed default kind: $api")
    assertTrue(api.contains("\"argb\":\"#FF7DE2FF\""), "rc knob default argb: $api")
    // A fully-served (non-degraded) session carries an empty degradations array.
    assertTrue(
      api.contains("\"degradations\":[]"),
      "empty degradations for a live-capable session: $api",
    )
  }

  @Test
  fun `api previews surfaces the degradation reason for a snapshot-only session`() {
    val (code, api) = get("/baked-only/api/previews")
    assertEquals(200, code)
    // The session-level reason rides alongside `trust`, so a programmatic client (the Figma plugin)
    // sees WHY the session is snapshot-only without scraping the viewer HTML.
    assertTrue(api.contains("\"code\":\"catalog-baked-only\""), "degradation code: $api")
    assertTrue(api.contains("publishes no live bundle"), "human detail: $api")
  }

  @Test
  fun `the bare root serves the design-systems home index, not the default module`() {
    val (code, body) = get("/")
    assertEquals(200, code)
    assertTrue(body.contains("Design systems"), "root is the systems index: $body")
    // A card links to the catalog's canonical path and shows a hero preview — from the PREBAKED
    // `/hero/` lane, not the live `/render` one, so the front door costs the server nothing to
    // paint.
    assertTrue(body.contains("href=\"/compose-m3/\""), "index card links to the system: $body")
    assertTrue(
      heroSrc(body)?.startsWith("/hero/compose-m3/") == true,
      "index card shows a prebaked hero: $body",
    )
    // Nothing the BROWSER fetches may point at the render lane. Scoped to the attributes that
    // actually issue a request (`src`, `href`) rather than the whole body, because the `og:image`
    // below deliberately does name the render — a meta tag is inert until a link unfurler reads it,
    // and one baked PNG per shared link is not the per-visitor render load this guards against.
    assertTrue(
      !Regex("""(src|href)="[^"]*/compose-m3/render/""").containsMatchIn(body),
      "the front door does not put a render request on the server: $body",
    )
    // The unfurl image is a DRAWN card off the `/social/` lane — neither the downscaled `/hero/`
    // thumbnail the page lays out (too small for a link-preview card) nor the full render behind it
    // (a portrait phone screenshot, which every consumer crops to a horizontal band). See
    // [ServeSocialCard].
    assertTrue(
      body.contains("""<meta property="og:image" content="http://"""),
      "the front door advertises an unfurl image: $body",
    )
    assertTrue(
      Regex("""<meta property="og:image" content="[^"]*/social/[0-9a-f]+\.png"""")
        .containsMatchIn(body),
      "the unfurl image is a drawn card: $body",
    )
    assertTrue(
      !Regex("""<meta property="og:image" content="[^"]*/compose-m3/render/""")
        .containsMatchIn(body),
      "…and no longer a catalog render: $body",
    )
    // …and it carries the card's size, so a fetcher lays the card out without downloading the
    // image first.
    assertTrue(body.contains("<meta property=\"og:image:width\""), "declares its width: $body")
    assertTrue(body.contains("<meta property=\"og:image:height\""), "declares its height: $body")
    // It is NOT the default module's own preview grid.
    assertTrue(!body.contains("default-mod"), "root is the index, not the default module: $body")
  }

  @Test
  fun `the component-browser home index includes plain local module sessions`() {
    val localRegistry = ServeSessionRegistry(open = { null })
    localRegistry.register("shared:ui", host = burstHost, pinned = true)
    val localServer =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = localRegistry,
          defaultSessionId = "shared:ui",
          isPublic = true,
          componentBrowser = true,
          catalogSessions = listOf("shared:ui"),
        )
        .also { it.start() }
    try {
      val request = Request.Builder().url("http://127.0.0.1:${localServer.port}/").build()
      val body = client.newCall(request).execute().use { it.body.string().orEmpty() }
      assertTrue(body.contains("href=\"/shared%3Aui/\""), body)
      assertTrue(body.contains("burst"), body)
      assertTrue(body.contains("class=\"cp-component-browser\""), body)

      val devRequest =
        Request.Builder().url("http://127.0.0.1:${localServer.port}/?chrome=dev").build()
      val devBody = client.newCall(devRequest).execute().use { it.body.string().orEmpty() }
      assertFalse(devBody.contains("class=\"cp-component-browser\""), devBody)
      assertTrue(
        devBody.contains("data-cp-interface-mode=\"dev\" aria-pressed=\"true\""),
        devBody,
      )
    } finally {
      localServer.stop()
      localRegistry.close()
    }
  }

  /**
   * The Catalog / Dev switch is a mode the visitor is in, carried by a cookie the browser sends
   * with every request — so no URL has to mention it. `?chrome=` stays as a permalink that pins one
   * request's presentation without changing what the visitor is in afterwards.
   */
  @Test
  fun `the interface mode comes from a cookie, and the chrome query outranks it`() {
    val localRegistry = ServeSessionRegistry(open = { null })
    localRegistry.register("shared:ui", host = burstHost, pinned = true)
    val localServer =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = localRegistry,
          defaultSessionId = "shared:ui",
          isPublic = true,
          componentBrowser = true,
          catalogSessions = listOf("shared:ui"),
        )
        .also { it.start() }

    fun get(path: String, cookie: String? = null): Pair<String, List<String>> {
      val request =
        Request.Builder()
          .url("http://127.0.0.1:${localServer.port}$path")
          .apply { cookie?.let { header("Cookie", it) } }
          .build()
      return client.newCall(request).execute().use {
        it.body.string().orEmpty() to it.headers("Vary")
      }
    }

    try {
      // No cookie: the command's own default (`browse` → Catalog) still decides.
      val (plain, plainVary) = get("/")
      assertTrue(plain.contains("class=\"cp-component-browser\""), plain)
      // The body was chosen by a header the request may or may not carry, so a shared cache must
      // not key these bytes on the URL alone.
      assertTrue(plainVary.any { it.contains("Cookie", ignoreCase = true) }, "$plainVary")

      val (dev, _) = get("/", cookie = "${ServeWeb.INTERFACE_MODE_COOKIE}=dev")
      assertFalse(dev.contains("class=\"cp-component-browser\""), dev)
      assertTrue(dev.contains("data-cp-interface-mode=\"dev\" aria-pressed=\"true\""), dev)

      // Other cookies on the same host are none of this switch's business.
      val (unrelated, _) =
        get("/", cookie = "cp_gh_auth=x; ${ServeWeb.INTERFACE_MODE_COOKIE}=nonsense")
      assertTrue(unrelated.contains("class=\"cp-component-browser\""), unrelated)

      // A permalink pins the presentation for that one request…
      val (pinned, pinnedVary) =
        get("/?chrome=catalog", cookie = "${ServeWeb.INTERFACE_MODE_COOKIE}=dev")
      assertTrue(pinned.contains("class=\"cp-component-browser\""), pinned)
      // …and answers without reading the cookie at all, so it keeps the wider cache key.
      assertFalse(pinnedVary.any { it.contains("Cookie", ignoreCase = true) }, "$pinnedVary")

      // Following it does not re-mode the visitor: the next plain URL is Dev again.
      val (after, _) = get("/", cookie = "${ServeWeb.INTERFACE_MODE_COOKIE}=dev")
      assertFalse(after.contains("class=\"cp-component-browser\""), after)
    } finally {
      localServer.stop()
      localRegistry.close()
    }
  }

  /**
   * `uses:` is a Dev-mode affordance, and the route that answers it is gated by the same switch the
   * page is — so a Catalog-mode request gets a 404 rather than a result, and the operator cannot be
   * reached by typing its URL in a presentation that does not offer it.
   *
   * What matching actually returns is [PreviewUsageIndexTest]'s subject; this host has no source
   * fetcher, which is the other case worth pinning here: it answers `available: false` rather than
   * an empty list, because the filter must be able to tell "nothing calls that" from "nobody
   * looked".
   */
  @Test
  fun `the uses index is Dev-mode only, and says so when it cannot answer`() {
    val localRegistry = ServeSessionRegistry(open = { null })
    localRegistry.register("shared:ui", host = burstHost, pinned = true)
    val localServer =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = localRegistry,
          defaultSessionId = "shared:ui",
          isPublic = true,
          componentBrowser = true,
          catalogSessions = listOf("shared:ui"),
        )
        .also { it.start() }

    fun get(path: String): Pair<Int, String> {
      val request = Request.Builder().url("http://127.0.0.1:${localServer.port}$path").build()
      return client.newCall(request).execute().use { it.code to it.body.string().orEmpty() }
    }

    try {
      val (catalogCode, _) = get("/api/uses?q=Button&chrome=catalog")
      assertEquals(404, catalogCode)

      val (devCode, devBody) = get("/api/uses?q=Button&chrome=dev")
      assertEquals(200, devCode)
      val dto = Json.parseToJsonElement(devBody).jsonObject
      assertFalse(dto.getValue("available").jsonPrimitive.content.toBoolean(), devBody)

      // The path form of the same route is gated identically — a canonical `/<system>/` URL is not
      // a way around the mode.
      assertEquals(404, get("/shared%3Aui/api/uses?q=Button&chrome=catalog").first)
      assertEquals(200, get("/shared%3Aui/api/uses?q=Button&chrome=dev").first)
    } finally {
      localServer.stop()
      localRegistry.close()
    }
  }

  /**
   * Every route answers HEAD, because that is the probe a link unfurler sends before it commits to
   * downloading a page or its `og:image`. Before [io.ktor.server.plugins.autohead.AutoHeadResponse]
   * was installed these were 405 where a constant segment matched (`/`, `/status`) and 404 where
   * routing needed a `{system}` (every catalog page and every render), so the entire site read as
   * dead to anything that probes before fetching.
   */
  @Test
  fun `HEAD answers wherever GET does, with the same headers`() {
    val paths =
      listOf(
        "/",
        "/status",
        "/version",
        "/compose-m3/",
        "/compose-m3/p/$previewId",
        "/compose-m3/render/$previewId.png",
        "/robots.txt",
        "/sitemap.xml",
      )
    for (path in paths) {
      val (headCode, headHeaders) = head(path)
      val (getCode, _, getHeaders) = getFull(path)
      assertEquals(getCode, headCode, "HEAD and GET disagree on status for $path")
      assertEquals(200, headCode, "HEAD $path")
      // The headers are what the probe is asking about: a HEAD that reported a different content
      // type than the GET would be worse than no HEAD at all.
      assertEquals(
        getHeaders["Content-Type"],
        headHeaders["Content-Type"],
        "HEAD and GET disagree on content type for $path",
      )
      assertEquals(
        getHeaders["Cache-Control"],
        headHeaders["Cache-Control"],
        "HEAD and GET disagree on cache-control for $path",
      )
    }
  }

  /**
   * `AutoHeadResponse` answers a HEAD by running the whole GET handler and discarding the body,
   * which is right for a page or a baked PNG and badly wrong for the work lanes: `HEAD /bundle.zip`
   * would render every preview and pack a zip only to throw it away, so an anonymous `curl -I` on a
   * public host could burn a catalog's render capacity while downloading nothing. Those refuse the
   * probe with `405` + `Allow: GET` instead.
   */
  @Test
  fun `HEAD is refused on the lanes whose GET does real work`() {
    for (path in listOf("/compose-m3/bundle.zip", "/compose-m3/bundle/$previewId")) {
      val (code, headers) = head(path)
      assertEquals(405, code, "HEAD $path must not run the GET handler")
      assertEquals("GET", headers["Allow"], "refusal names the method that works: $path")
    }
    // An override-bearing render is a daemon render; the bare path is the baked file an unfurler
    // probes for og:image and must keep answering.
    assertEquals(
      405,
      head("/compose-m3/render/$previewId.png?fontScale=1.5").first,
      "an override render must not be triggered by a bodyless probe",
    )
    // The non-PNG products are made on demand whether or not a query is present, so the suffix
    // alone has to refuse — an override-free `HEAD …/render/<id>.svg` would otherwise take the
    // render semaphore just to have its body discarded.
    for (suffix in listOf(".svg", ".slots", ".a11y", ".annotations", ".rc")) {
      assertEquals(
        405,
        head("/compose-m3/render/$previewId$suffix").first,
        "a bodyless probe must not produce $suffix",
      )
    }
    // A chrome-less story frame has no baked lane at all.
    assertEquals(405, head("/compose-m3/iframe.html?id=$previewId").first)
    assertEquals(200, head("/compose-m3/render/$previewId.png").first, "the bake still answers")
  }

  /**
   * The token gate has to run before the HEAD refusal. `rejectBadToken` answers 404 to conceal that
   * a route exists at all, so refusing first with `405` + `Allow: GET` would tell an
   * unauthenticated scanner the opposite — and `/history/render` is only registered when the
   * repository-backed surface is enabled, making the difference a probe for optional configuration.
   */
  @Test
  fun `an unauthenticated HEAD on a gated lane is concealed, not refused`() {
    val gated =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "the-secret",
          sessions = registry,
          defaultSessionId = "default-mod",
          isPublic = false,
        )
        .also { it.start() }
    try {
      fun headOn(path: String): Pair<Int, okhttp3.Headers> {
        val req = Request.Builder().url("http://127.0.0.1:${gated.port}$path").head().build()
        client.newCall(req).execute().use { r ->
          return r.code to r.headers
        }
      }
      for (path in listOf("/bundle.zip", "/bundle/$previewId", "/render/$previewId.svg")) {
        val (code, headers) = headOn(path)
        assertEquals(404, code, "HEAD $path must conceal the route, not advertise it")
        assertEquals(null, headers["Allow"], "a concealed route names no methods: $path")
      }
      // With the token, the refusal is the one the work-lane guard exists to give.
      assertEquals(405, headOn("/bundle.zip?token=the-secret").first)
    } finally {
      gated.stop()
    }
  }

  /**
   * A HEAD is a probe, not a visit. `AutoHeadResponse` answers it by running the GET pipeline and
   * dropping the body, so the view tallies would otherwise count the probe an unfurler sends *and*
   * the fetch that follows it — double-counting every link shared into a chat.
   */
  @Test
  fun `a HEAD probe does not count as a view`() {
    val path = "/compose-m3/p/$previewId"
    // Establish a baseline, then probe repeatedly: the tally must not move.
    get(path)
    val (_, afterGet) = get(path)
    val baseline = viewCount(afterGet)
    repeat(3) { head(path) }
    val (_, afterHeads) = get(path)
    // One more than the baseline: the GET that read the tally back, and none of the three HEADs.
    assertEquals(
      baseline + 1,
      viewCount(afterHeads),
      "HEAD probes were counted as views: $afterHeads",
    )
  }

  /** The viewer's rendered view tally, or 0 when the page shows none. */
  private fun viewCount(html: String): Int =
    Regex("""cp-viewer-engage[^>]*>([\d,]+)""")
      .find(html)
      ?.groupValues
      ?.get(1)
      ?.replace(",", "")
      ?.toIntOrNull() ?: 0

  /**
   * A viewer's `og:image` inherits the page's query suffix, so a link shared from an overridden
   * view points at a re-render whose pixel size is not the baked one. Declaring the baked
   * dimensions there would have the card lay out against a size the image doesn't have — omit them
   * and let the fetcher measure.
   */
  @Test
  fun `an overridden viewer link declares no image dimensions`() {
    val (_, plain) = get("/compose-m3/p/$previewId")
    assertTrue(
      plain.contains("<meta property=\"og:image:width\""),
      "baked view is measured: $plain",
    )

    val (_, overridden) = get("/compose-m3/p/$previewId?fontScale=1.5")
    assertTrue(
      overridden.contains("fontScale=1.5\">"),
      "the card still points at the overridden render: $overridden",
    )
    assertFalse(
      overridden.contains("og:image:width"),
      "baked dimensions must not describe an overridden render: $overridden",
    )
  }

  /**
   * `robots.txt` opens the browsing surface and closes the lanes that cost the box something. The
   * assertions name the failure modes rather than the file's exact text: the browsing pages must
   * stay crawlable (that is the whole point of publishing a sitemap), and the render-with-overrides
   * lane must not be, since a crawler walking the grid's theme links would re-render every preview.
   */
  @Test
  fun `robots txt opens the browse surface and closes the render and code lanes`() {
    val (code, body) = get("/robots.txt")
    assertEquals(200, code)
    assertTrue(body.startsWith("# compose-preview"), "robots.txt is the real file: $body")
    // The expensive lanes.
    for (closed in listOf("/playground", "/bundle.zip", "/wasm/", "/*/render/*?", "/*/compare")) {
      assertTrue(body.contains("Disallow: $closed"), "closes $closed: $body")
    }
    // The browsing surface is never disallowed — no rule may match a catalog landing, a viewer, or
    // the baked PNG an unfurl card points at. Compared line-exactly, because the legitimate
    // `/*/render/*?` rule has the open form as a prefix and a substring test would read it as a
    // violation of itself.
    val rules = body.lines().map { it.trim() }
    for (open in
      listOf(
        "Disallow: /",
        "Disallow: /$",
        "Disallow: /*/p/",
        "Disallow: /*/render/",
        "Disallow: /*?",
      )) {
      assertFalse(rules.contains(open), "must not close the browse surface ($open): $body")
    }
    // Link unfurlers get their own group, without the crawl delay and without wildcard rules their
    // simple prefix parsers would misread.
    assertTrue(body.contains("User-agent: Slackbot-LinkExpanding"), "names Slackbot: $body")
    assertTrue(body.contains("User-agent: Slack-ImgProxy"), "names Slack's image fetcher: $body")
    assertTrue(body.contains("Sitemap: http://"), "advertises the sitemap: $body")
  }

  /**
   * The sitemap lists the pages worth landing on — catalog landings and viewers — built from the
   * remembered catalog metadata rather than from live hosts, so a suspended catalog still appears.
   */
  @Test
  fun `sitemap lists catalog landings and their preview viewers`() {
    val (code, body) = get("/sitemap.xml")
    assertEquals(200, code)
    assertTrue(body.startsWith("<?xml"), "sitemap is XML: $body")
    assertTrue(body.contains("<loc>http://127.0.0.1:${server.port}/</loc>"), "front door: $body")
    assertTrue(body.contains("/compose-m3/</loc>"), "catalog landing: $body")
    assertTrue(body.contains("/compose-m3/p/$previewId</loc>"), "preview viewer: $body")
    // Not the images, and not the lanes robots.txt just closed — a sitemap that advertises what
    // robots.txt forbids is a contradiction a crawler reports as an error.
    assertFalse(body.contains("/render/"), "sitemap lists pages, not images: $body")
    assertFalse(body.contains("/compare"), "sitemap does not list the closed lanes: $body")
    // Unlisted catalogs are served but not published; they stay off the sitemap like they stay off
    // the front door.
    assertFalse(body.contains("/baked-only/"), "unlisted catalogs are not in the sitemap: $body")
  }

  /** The `src` of the first hero `<img>` on the home index, or null when there is none. */
  private fun heroSrc(html: String): String? =
    Regex("""<img[^>]*src="(/hero/[^"]+)"""").find(html)?.groupValues?.get(1)

  @Test
  fun `a prebaked hero is served immutable, and revalidates to 304`() {
    val src = heroSrc(get("/").second) ?: error("no prebaked hero on the front door")
    val req = Request.Builder().url("http://127.0.0.1:${server.port}$src").build()
    val etag =
      client.newCall(req).execute().use { r ->
        assertEquals(200, r.code)
        assertEquals("image/png", r.header("Content-Type")?.substringBefore(';'))
        assertEquals(
          "public, max-age=31536000, immutable",
          r.header("Cache-Control"),
          "the content-hashed hero URL can be cached forever",
        )
        assertTrue(r.body.bytes().isNotEmpty(), "hero bytes are served")
        r.header("ETag") ?: error("hero carries no ETag")
      }
    // A conditional request (a cache that chose to revalidate anyway) costs bytes, not a render.
    val conditional =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}$src")
        .header("If-None-Match", etag)
        .build()
    client.newCall(conditional).execute().use { assertEquals(304, it.code) }
  }

  @Test
  fun `a presence heartbeat keeps a session alive and answers with nothing`() {
    // 204, no body: a heartbeat isn't something a page can act on. Leasing the session is the
    // point — that is what stops the reaper closing a daemon under a visitor who is still reading.
    val (code, body) = post("/compose-m3/api/presence")
    assertEquals(204, code)
    assertEquals("", body)
    // A catalog this server doesn't have is not an error either: an open tab whose catalog was
    // since removed should go quiet, not start reporting failures at the visitor.
    assertEquals(204, post("/no-such-system/api/presence").first)
  }

  @Test
  fun `an unknown hero name 404s`() {
    assertEquals(404, get("/hero/compose-m3/0000000000000000.png").first)
  }

  @Test
  fun `the legacy query session lane still works`() {
    val (code, body) = get("/?session=compose-m3")
    assertEquals(200, code)
    assertTrue(body.contains("com.example"), "query-lane landing lists the preview")

    val (viewerCode, _) = get("/p/$previewId?session=compose-m3")
    assertEquals(200, viewerCode)
  }

  @Test
  fun `format comparison is available on canonical and query session lanes`() {
    val (pathCode, pathBody) = get("/compose-m3/compare")
    assertEquals(200, pathCode)
    assertTrue(pathBody.contains("id=\"cp-compare\""), "canonical comparison page: $pathBody")
    assertTrue(
      pathBody.contains(
        ">Compose Material 3</a><span class=\"cp-crumb-sep\" aria-hidden=\"true\">/</span>" +
          "<span class=\"cp-crumb-current\">Compare formats</span>"
      ),
      "the comparison breadcrumb — now in the site header — uses the catalog's human title: " +
        pathBody,
    )
    assertTrue(
      pathBody.contains("data-compare-format=\"rc\"") &&
        !pathBody.contains("data-compare-format=\"svg\""),
      "the page exposes the carried RC format without a dead SVG tab: $pathBody",
    )
    assertTrue(
      pathBody.contains("data-compare-format=\"reference\"") &&
        pathBody.contains("data-reference-neutral=\"/compose-m3/reference/red-design.png\"") &&
        pathBody.contains(
          "data-reference-detail-neutral=\"/compose-m3/compare/$previewId?reference=red-design\""
        ),
      "the native comparison gallery also exposes the exact design reference: $pathBody",
    )
    assertTrue(
      pathBody.contains("data-rc-neutral=\"/compose-m3/render/$previewId.rc\""),
      "the path-mounted page keeps its RC document URL under the catalog path: $pathBody",
    )
    // …and the page can be reported against the CATALOG. Without `#cp-report` here the floating
    // launcher keeps its catalog half hidden, leaving the preview server's own tracker as the only
    // route out of a page whose whole subject is a catalog's fidelity (issue #4289).
    assertTrue(
      pathBody.contains("id=\"cp-report\"") &&
        pathBody.contains("data-cp-subject=\"these comparisons\""),
      "the comparison wall offers the catalog tracker: $pathBody",
    )
    assertTrue(
      pathBody.contains("### Which page") && !pathBody.contains("| Preview |"),
      "the wall's report is page-scoped — it names no preview it cannot honestly single out: " +
        pathBody,
    )

    val (queryCode, queryBody) = get("/compare?session=compose-m3")
    assertEquals(200, queryCode)
    assertTrue(
      queryBody.contains("data-rc-neutral=\"/render/$previewId.rc?session=compose-m3\""),
      "the legacy page keeps its session query on the RC document URL: $queryBody",
    )
    // The landing links each comparison it can actually offer, by name: this catalog carries RC
    // documents and design references but no SVG export, so it gets the RC action and the
    // design-tool one, and no "compare SVG" leading to a dead tab.
    val landing = get("/compose-m3/").second
    assertTrue(
      landing.contains("href=\"/compose-m3/compare?format=rc\">compare RC players</a>") &&
        !landing.contains("compare SVG"),
      "the catalog landing links the comparison formats it carries: $landing",
    )
    assertTrue(
      landing.contains("href=\"/compose-m3/compare?format=reference\">compare to Figma</a>") &&
        landing.contains("href=\"/compose-m3/parity\">design parity</a>"),
      "the catalog landing compares against the design tool its references come from: $landing",
    )
  }

  @Test
  fun `design reference detail and inert raster are served on canonical routes`() {
    val (pageCode, page) = get("/compose-m3/compare/$previewId?reference=red-design")
    assertEquals(200, pageCode)
    assertTrue(page.contains("id=\"cp-reference-compare\""), "reference detail: $page")
    assertTrue(page.contains(">Reference</h2>"), "reference lane: $page")
    assertTrue(page.contains(">Diff</h2>"), "diff lane: $page")
    assertTrue(page.contains(">Actual</h2>"), "actual lane: $page")
    assertTrue(page.contains("Source:</strong> figma · revision 7"), "provenance: $page")
    assertTrue(page.contains(">Red design review</a>"), "reference selector: $page")
    assertTrue(
      page.contains("reference=red-design-review"),
      "selector links every reference for the preview: $page",
    )

    val (reviewCode, reviewPage) = get("/compose-m3/compare/$previewId?reference=red-design-review")
    assertEquals(200, reviewCode)
    assertTrue(reviewPage.contains("Source:</strong> penpot · revision 8"), reviewPage)

    val request =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}/compose-m3/reference/red-design.png")
        .build()
    client.newCall(request).execute().use { response ->
      assertEquals(200, response.code)
      assertEquals("image/png", response.header("Content-Type")?.substringBefore(';'))
      assertTrue(response.body.bytes().isNotEmpty())
    }
    assertEquals(404, get("/compose-m3/reference/missing.png").first)
    assertEquals(404, get("/compose-m3/compare/$previewId?reference=missing").first)
  }

  @Test
  fun `format comparison 404s when a session carries no alternate format`() {
    assertEquals(404, get("/baked-only/compare").first)
  }

  @Test
  fun `an unknown system path 404s like a bad session`() {
    assertEquals(404, get("/no-such-system/").first)
    assertEquals(404, get("/no-such-system/p/$previewId").first)
  }

  @Test
  fun `local browse sessions expose source from their contained module root`() {
    val root = Files.createTempDirectory("local-browse-source").toFile().also { it.deleteOnExit() }
    val relative = "src/commonMain/kotlin/example/LocalButton.kt"
    File(root, relative).apply {
      parentFile.mkdirs()
      writeText(
        """
        package example

        import androidx.compose.material3.Button
        import androidx.compose.runtime.Composable

        @Composable
        fun LocalButton() {
          Button(onClick = {}) {}
        }
        """
          .trimIndent()
      )
    }
    val localId = "example.LocalButton"
    val localHost =
      object : ServeHost {
        override val previews =
          listOf(
            ServePreview(
              id = localId,
              label = "Local button",
              sourceFile = relative,
              bodyLine = 7,
            )
          )
        override val label = "local"

        override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
          RenderOutcome.Ok(png())

        override fun subscribeStream(
          previewId: String,
          overrides: PreviewOverrides,
          codec: StreamCodec?,
          maxFps: Int?,
          onUnavailable: ((String) -> Unit)?,
          onFrame: (StreamFrameParams) -> Unit,
        ): StreamHandle? = null

        override fun activeStreamCount(): Int = 0

        override fun close() {}
      }
    val localRegistry = ServeSessionRegistry(open = { null })
    localRegistry.register("local", host = localHost, pinned = true)
    val localServer =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = localRegistry,
          defaultSessionId = "local",
          isPublic = true,
          componentBrowser = true,
          catalogSessions = listOf("local"),
          localSourceRoots = mapOf("local" to root),
        )
        .also { it.start() }
    try {
      fun localGet(path: String): Pair<Int, String> {
        val request = Request.Builder().url("http://127.0.0.1:${localServer.port}$path").build()
        return client.newCall(request).execute().use { it.code to it.body.string() }
      }

      val (pageCode, page) = localGet("/local/p/$localId")
      assertEquals(200, pageCode)
      assertTrue(page.contains("data-usage-src=\"/local/usage/$localId\""), page)

      val (sourceCode, source) = localGet("/local/usage/$localId")
      assertEquals(200, sourceCode)
      assertTrue(source.contains("LocalButton"), source)
      assertTrue(source.contains("Button(onClick"), source)
    } finally {
      localServer.stop()
    }
  }

  @Test
  fun `index_json serves a storybook stories index`() {
    val (code, body) = get("/compose-m3/index.json")
    assertEquals(200, code)
    assertTrue(body.contains("\"v\":${StorybookCompat.INDEX_VERSION}"), "index version: $body")
    assertTrue(body.contains("\"entries\":"), "entries map: $body")
    assertTrue(body.contains("\"type\":\"story\""), "story-typed entry: $body")
    // The synthetic importPath encodes the native preview id, so a reader can recover it.
    assertTrue(
      body.contains("virtual:compose-preview/$previewId"),
      "entry importPath carries the native id: $body",
    )
    // The legacy query-session lane serves the same index.
    val (queryCode, queryBody) = get("/index.json?session=compose-m3")
    assertEquals(200, queryCode)
    assertTrue(queryBody.contains("\"entries\":"), "query-lane index: $queryBody")
  }

  @Test
  fun `iframe_html renders one story in isolation as an html page embedding the png`() {
    // The native preview id is accepted verbatim by iframe.html (the deep-link escape hatch), so
    // this doesn't depend on how the story id is minted from the label.
    val req =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}/compose-m3/iframe.html?id=$previewId")
        .build()
    client.newCall(req).execute().use { r ->
      assertEquals(200, r.code)
      assertEquals("text/html", r.body.contentType()?.let { "${it.type}/${it.subtype}" })
      val body = r.body.string()
      assertTrue(body.startsWith("<!doctype html>"), "is an html document: $body")
      assertTrue(body.contains("src=\"data:image/png;base64,"), "embeds the render png: $body")
    }
  }

  @Test
  fun `iframe_html 400s a missing id and 404s an unknown one`() {
    assertEquals(400, get("/compose-m3/iframe.html").first)
    assertEquals(404, get("/compose-m3/iframe.html?id=no.such.Story").first)
  }

  @Test
  fun `iframe_html format svg 404s on a static bundle`() {
    // The SVG lane inlines the figma-svg export, which only a daemon-backed host produces; a static
    // ServeBundleHost 404s it just like the /render/<id>.svg lane does.
    assertEquals(404, get("/compose-m3/iframe.html?id=$previewId&format=svg").first)
  }
}
