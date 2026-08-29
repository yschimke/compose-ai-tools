package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The two places a replayed preview's theme could still be offered but not applied: the **socket
 * lanes**, and a **mixed catalog**'s theme control.
 *
 * `ServeThemeReplaySeedTest` pins the expansion itself over the HTTP render lane — a
 * `themeProvider` for a replayed preview becomes the `rc.<role>=color:…` seeds that re-theme its
 * captured document. This file pins the two ways a request could reach a renderer around it:
 * 1. The WebSocket lanes parse their own overrides from `initial` / `setOverrides` / `switch`
 *    messages. Forwarding a raw provider there streams frames the theme never touched while the
 *    viewer shows it as selected — unchanged pixels presented as a themed render (#3449) with a
 *    socket in front of them, and worse than the snapshot case because a later frame clears the
 *    error overlay while the wrong stream keeps running.
 * 2. A catalog holding **both** replayed and recomposing previews publishes the union of its
 *    themes, because one recomposing preview can apply all of them. A replayed preview mapped for
 *    only some must not be offered the rest — the click would reach the terminal 409 the gate
 *    exists to prevent.
 */
class ServeThemeReplayLaneTest {

  private val coral = ServeTheme("Coral", "com.example.CoralTheme")
  private val teal = ServeTheme("Teal", "com.example.TealTheme")

  /** Only [coral] is published for replay; [teal] is declared and mapped by nothing. */
  private val coralColors = mapOf("WearM3.primary" to "#FF6F61")

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", it) }
      .toByteArray()

  /**
   * A catalog whose previews are of **both** kinds: `<name>-replayed` carries a captured document,
   * `<name>-live` does not. The mix is the point — with only one kind the union and the per-preview
   * set are the same list, and neither gate can be wrong.
   */
  private inner class MixedHost(
    private val name: String,
    override val declaredThemes: List<ServeTheme>,
  ) : ServeHost {
    val seen = CopyOnWriteArrayList<Pair<String, PreviewOverrides>>()

    val replayedId = "$name-replayed"
    val liveId = "$name-live"

    /**
     * One declared bool knob per preview, so a page has a control to read. `true` by default, so a
     * link asking for `false` is visible in the markup exactly when it was seeded.
     */
    private val enabledKnob =
      listOf(
        ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration(
          key = "enabled",
          type = "bool",
          label = "enabled",
          default = ee.schimke.composeai.daemon.protocol.PreviewOverrideValue.BooleanValue(true),
        )
      )

    override val previews =
      listOf(
        ServePreview(replayedId, "Replayed", overrides = enabledKnob),
        ServePreview(liveId, "Live", overrides = enabledKnob),
      )
    override val label = name
    override val canRenderOverrides = true

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      seen.add(previewId to overrides)
      return RenderOutcome.Ok(png())
    }

    override fun remoteComposeDoc(previewId: String): ByteArray? =
      if (previewId == replayedId) byteArrayOf(1, 2, 3) else null

    override fun themeReplayColors(providerFqn: String): Map<String, String> =
      if (providerFqn == coral.providerFqn) coralColors else emptyMap()

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

  private val mixed = MixedHost("mixed", listOf(coral, teal))

  /** The same shape with every declared theme mapped — the case that must stay fully offered. */
  private val fullyMapped = MixedHost("mapped", listOf(coral))

  private val registry = ServeSessionRegistry(open = { null })
  private val client = OkHttpClient()

  private val server: ServeHttpServer by lazy {
    registry.register("mixed", host = mixed, pinned = true)
    registry.register("mapped", host = fullyMapped, pinned = true)
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "mixed",
        isPublic = true,
      )
      .also { it.start() }
  }

  @AfterTest
  fun tearDown() {
    server.stop()
  }

  private fun get(path: String): String =
    client
      .newCall(Request.Builder().url("http://127.0.0.1:${server.port}$path").build())
      .execute()
      .use {
        assertEquals(200, it.code, path)
        it.body.string().orEmpty()
      }

  private fun themeOption(theme: ServeTheme) = "\"theme:${theme.providerFqn}\""

  /** The knob row for [key] on a fetched page, so an assertion reads one control. */
  private fun knobRow(page: String, key: String): String =
    page.lineSequence().first { it.contains("""data-knob-key="$key"""") }

  /**
   * A deep link seeds the viewer's controls only with the axes this page's image could be carrying,
   * and on a replayed preview a `knob.*` is not one of them.
   *
   * `?fallback=baked` makes `respondDroppedOverrides` answer with pixels that ignored what it could
   * not apply. A replayed preview has no composition for a named knob to reach — that is what
   * `CatalogLiveRouting.irReplayDroppedOverrideNames` names — even though this host renders every
   * other axis perfectly well, so a host-wide "can this session apply overrides?" reads `true` and
   * would have seeded a control the pixels never saw.
   *
   * The recomposing twin is the control case: same host, same link, and there the render DOES apply
   * it, so withholding the seed would be the same disagreement pointing the other way.
   */
  @Test
  fun `a baked fallback seeds the axes its render kept, and withholds the ones replay drops`() {
    val replayed = get("/mixed/p/${mixed.replayedId}?knob.enabled=false&fallback=baked")
    assertTrue(
      knobRow(replayed, "enabled").contains(" checked"),
      "a replayed preview seeded a knob its render dropped",
    )

    val live = get("/mixed/p/${mixed.liveId}?knob.enabled=false&fallback=baked")
    assertFalse(
      knobRow(live, "enabled").contains(" checked"),
      "a recomposing preview withheld a knob its render applied",
    )
  }

  // ---------------------------------------------------------------------------------------------
  // The socket lanes.
  // ---------------------------------------------------------------------------------------------

  /** Drive a snapshot socket and return the overrides its frame was rendered with. */
  private fun streamOverrides(
    previewId: String,
    message: String,
    initial: Map<String, String> = emptyMap(),
  ): PreviewOverrides {
    mixed.seen.clear()
    val sent = CopyOnWriteArrayList<String>()
    ServeStreamSession(mixed, previewId, initial, sent::add, system = "mixed")
      .onClientMessage(message)
    assertTrue(sent.isNotEmpty(), "the socket answered nothing: $sent")
    return mixed.seen.last().second
  }

  @Test
  fun `a setOverrides theme reaches a replayed stream as named colour seeds`() {
    val overrides =
      streamOverrides(
        mixed.replayedId,
        """{"type":"setOverrides","overrides":{"themeProvider":"${coral.providerFqn}"}}""",
      )

    assertEquals(
      mapOf("WearM3.primary" to RemoteNamedValue.ColorValue("#FF6F61")),
      overrides.remoteCompose?.namedValues,
      "the theme's roles reach the stream, not just the HTTP lane",
    )
    assertNull(overrides.themeProvider, "the provider is satisfied, not forwarded")
  }

  @Test
  fun `a setOverrides theme on a recomposing stream keeps the provider`() {
    val overrides =
      streamOverrides(
        mixed.liveId,
        """{"type":"setOverrides","overrides":{"themeProvider":"${coral.providerFqn}"}}""",
      )

    assertEquals(coral.providerFqn, overrides.themeProvider, "the lane applies it by recomposing")
    assertTrue(
      overrides.remoteCompose?.namedValues.isNullOrEmpty(),
      "no colours are seeded behind its back",
    )
  }

  /**
   * `switch` with no new overrides carries the *held* ones onto the preview it lands on, so the
   * seeds have to be derived after the landing rather than before. Held un-expanded and expanded
   * per preview, a switch from a recomposing preview to a replayed one seeds it; the reverse hands
   * the provider back. Expanding at store time instead would pin whichever preview the socket
   * opened on.
   */
  @Test
  fun `switching to a replayed preview re-derives the seeds for it`() {
    val held = mapOf("themeProvider" to coral.providerFqn)
    val sent = CopyOnWriteArrayList<String>()
    mixed.seen.clear()
    val session = ServeStreamSession(mixed, mixed.liveId, held, sent::add, system = "mixed")
    session.onClientMessage("""{"type":"requestFrame"}""")
    session.onClientMessage("""{"type":"switch","previewId":"${mixed.replayedId}"}""")

    val (firstId, onLive) = mixed.seen.first()
    val (secondId, onReplayed) = mixed.seen.last()
    assertEquals(mixed.liveId to mixed.replayedId, firstId to secondId, "both frames rendered")
    assertEquals(coral.providerFqn, onLive.themeProvider, "the recomposing preview kept it")
    assertEquals(
      mapOf("WearM3.primary" to RemoteNamedValue.ColorValue("#FF6F61")),
      onReplayed.remoteCompose?.namedValues,
      "and the replayed one it switched to got the seeds instead",
    )
    assertNull(onReplayed.themeProvider)
  }

  // ---------------------------------------------------------------------------------------------
  // Mixed catalogs.
  // ---------------------------------------------------------------------------------------------

  @Test
  fun `a replayed preview is offered only the themes published for replay`() {
    val html = get("/mixed/p/${mixed.replayedId}")
    assertTrue(html.contains(themeOption(coral)), "the mapped theme reaches the document's colours")
    assertFalse(
      html.contains(themeOption(teal)),
      "the unmapped one has nothing to seed, so selecting it would 409",
    )
  }

  @Test
  fun `a recomposing preview in the same catalog is offered all of them`() {
    val html = get("/mixed/p/${mixed.liveId}")
    assertTrue(html.contains(themeOption(coral)))
    assertTrue(html.contains(themeOption(teal)), "re-running the composable applies either")
  }

  /**
   * The grid's chips are the union — one recomposing card publishes every declared theme — so a
   * replayed card that can't take all of them is gated out of the control rather than into an
   * error. Its `themeBase` is the empty string, which the browser's per-card worker skips.
   */
  @Test
  fun `a partially mapped replayed card is not themed from the grid`() {
    val html = get("/mixed/")
    assertTrue(html.contains(themeOption(teal)), "the recomposing card still publishes the chips")
    assertTrue(html.contains("var themeBase = ["), "…and carries a themed-render URL")
    assertTrue(
      Regex("var themeBase = \\[([^]]*)]").find(html)!!.groupValues[1].contains("\"\""),
      "the replayed card opts out rather than 409ing on the unmapped chip",
    )
  }

  /** …and one whose every declared theme *is* published keeps its themed-render URL. */
  @Test
  fun `a fully mapped replayed card stays themed from the grid`() {
    val html = get("/mapped/")
    assertTrue(html.contains(themeOption(coral)))
    assertFalse(
      Regex("var themeBase = \\[([^]]*)]").find(html)!!.groupValues[1].contains("\"\""),
      "every card can apply every theme this catalog offers",
    )
  }
}
