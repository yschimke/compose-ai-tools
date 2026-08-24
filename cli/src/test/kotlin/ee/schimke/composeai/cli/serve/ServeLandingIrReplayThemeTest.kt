package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A catalog whose previews are **replayed from a captured Remote Compose document** must not offer
 * its declared themes on the landing grid.
 *
 * A declared theme installs a `PreviewWrapperProvider` *around a composition*, so a replay has
 * nothing to wrap: `/render/<id>.png?themeProvider=…` is refused with a terminal 409
 * ([CatalogLiveRouting.irReplayDroppedOverrideNames]), which the grid's theme worker turns into
 * "This preview can't render live" on the card. Offering the chips anyway put an IR-backed catalog
 * — `remote-m3`, every one of whose previews carries a `.rc` document — one click away from a grid
 * of error cards.
 *
 * The trap this guards is that such a host is otherwise *fully live*: it has a daemon twin, so
 * `canRenderOverridesFor` is true and the old gate passed. Both fakes below therefore answer true
 * to it and differ only in whether they carry a document — exactly the pair the landing has to tell
 * apart. Asserted over real HTTP rather than on `ServeWeb.landingPage` directly, because the defect
 * was the wiring: the viewer had already been taught this fact (`data-ir-replay`,
 * [ServeViewerIrReplayControlsTest]) while the landing was still passing renderability alone.
 */
class ServeLandingIrReplayThemeTest {

  private val themeChoice = "data-theme-choice=\"theme:com.example.GoogleSansFlexTheme\""

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", it) }
      .toByteArray()

  /**
   * A live catalog host declaring one theme. [rcDoc] non-null makes every preview IR-replayed — the
   * single axis under test.
   */
  private inner class ThemedHost(private val name: String, private val rcDoc: ByteArray?) :
    ServeHost {
    override val previews =
      listOf(ServePreview("$name-one", "One"), ServePreview("$name-two", "Two"))
    override val label = name
    override val declaredThemes =
      listOf(ServeTheme("Google Sans Flex", "com.example.GoogleSansFlexTheme", "Typeface"))

    // Live: a daemon twin exists for every preview, which is what made the old gate pass.
    override val canRenderOverrides = true

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
      RenderOutcome.Ok(png())

    override fun remoteComposeDoc(previewId: String): ByteArray? = rcDoc

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

  private val registry = ServeSessionRegistry(open = { null })
  private val client = OkHttpClient()

  private val server: ServeHttpServer by lazy {
    registry.register(
      "replayed",
      host = ThemedHost("replayed", rcDoc = byteArrayOf(1, 2, 3)),
      pinned = true,
    )
    registry.register("recomposed", host = ThemedHost("recomposed", rcDoc = null), pinned = true)
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "recomposed",
        isPublic = true,
      )
      .also { it.start() }
  }

  @AfterTest
  fun tearDown() {
    server.stop()
  }

  private fun landing(system: String): String {
    val request = Request.Builder().url("http://127.0.0.1:${server.port}/$system/").build()
    return client.newCall(request).execute().use { response ->
      assertEquals(200, response.code, "landing for $system")
      response.body.string().orEmpty()
    }
  }

  @Test
  fun `an IR-replayed catalog offers no declared-theme chips`() {
    val html = landing("replayed")
    assertFalse(
      html.contains(themeChoice),
      "a themeProvider render of a replayed preview is refused 409, so the chip only produces an " +
        "error card",
    )
    // …and the grid ships none of the machinery behind them: no per-card themed-render base, so a
    // stale `?theme=` on the URL can't drive a render either.
    assertFalse(html.contains("var themeBase = ["), "no themed-render URLs to fetch")
  }

  @Test
  fun `a recomposing catalog still offers them`() {
    val html = landing("recomposed")
    assertTrue(html.contains(themeChoice), "re-running the composable applies the theme")
    assertTrue(html.contains("var themeBase = ["), "and each card carries a themed-render URL")
  }
}
