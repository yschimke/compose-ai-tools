package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A declared theme reaching a preview whose composable is gone.
 *
 * A Remote Compose document emits the roles it draws through as **named state**
 * (`USER:WearM3.primary` and friends) rather than folding them into constants, so the player can
 * re-theme a *replayed* document with no recomposition. `themeProvider` can't ride that lane —
 * there is no composition for a `PreviewWrapperProvider` to wrap, which is why the render lane
 * refuses it — but the colours it stands for can, expressed as `rc.<role>=color:…` seeds.
 *
 * So the server expands one into the other. These pin the expansion's three load-bearing
 * properties: it happens for a replayed preview when the host publishes a mapping, it does **not**
 * happen where recomposition would apply the theme more completely, and the request stops being
 * reported as an un-applied override once satisfied — a render that applied a theme must not also
 * claim it dropped it (#3449).
 */
class ServeThemeReplaySeedTest {

  private val theme = ServeTheme("Coral", "com.example.CoralTheme")
  private val coral = mapOf("WearM3.primary" to "#FF6F61", "WearM3.secondary" to "#FFB4A9")

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", it) }
      .toByteArray()

  /**
   * [rcDoc] non-null makes the preview replayed — the axis the expansion turns on. [replayColors]
   * empty models a catalog that declares themes but publishes no replay mapping, which must keep
   * refusing rather than rendering an unthemed image under a theme's name.
   */
  private inner class ThemedHost(
    private val name: String,
    private val rcDoc: ByteArray?,
    private val replayColors: Map<String, String>,
  ) : ServeHost {
    /** Every override set the render lane was actually asked for, in arrival order. */
    val seen = ConcurrentHashMap.newKeySet<PreviewOverrides>()

    override val previews = listOf(ServePreview("$name-one", "One"))
    override val label = name
    override val declaredThemes = listOf(theme)
    override val canRenderOverrides = true

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      seen.add(overrides)
      return RenderOutcome.Ok(png())
    }

    override fun remoteComposeDoc(previewId: String): ByteArray? = rcDoc

    override fun themeReplayColors(providerFqn: String): Map<String, String> =
      if (providerFqn == theme.providerFqn) replayColors else emptyMap()

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

  private val replayed = ThemedHost("replayed", byteArrayOf(1, 2, 3), coral)
  private val unmapped = ThemedHost("unmapped", byteArrayOf(1, 2, 3), emptyMap())
  private val recomposing = ThemedHost("recomposing", null, coral)

  private val registry = ServeSessionRegistry(open = { null })
  private val client = OkHttpClient()

  private val server: ServeHttpServer by lazy {
    registry.register("replayed", host = replayed, pinned = true)
    registry.register("unmapped", host = unmapped, pinned = true)
    registry.register("recomposing", host = recomposing, pinned = true)
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "replayed",
        isPublic = true,
      )
      .also { it.start() }
  }

  @AfterTest
  fun tearDown() {
    server.stop()
  }

  private fun render(system: String, preview: String): Int {
    val url =
      "http://127.0.0.1:${server.port}/$system/render/$preview.png" +
        "?themeProvider=${theme.providerFqn}"
    return client.newCall(Request.Builder().url(url).build()).execute().use { it.code }
  }

  @Test
  fun `a replayed preview renders the theme as named colour seeds`() {
    assertEquals(200, render("replayed", "replayed-one"), "the seeded render succeeds")

    val overrides = replayed.seen.single()
    assertEquals(
      mapOf(
        "WearM3.primary" to RemoteNamedValue.ColorValue("#FF6F61"),
        "WearM3.secondary" to RemoteNamedValue.ColorValue("#FFB4A9"),
      ),
      overrides.remoteCompose?.namedValues,
      "the theme's roles reach the lane as named values",
    )
    // …and the provider itself does NOT, or `droppedOverridesFor` would report an un-applied
    // override on the very render that applied it, and refuse a 200 it already earned.
    assertNull(overrides.themeProvider, "the provider is satisfied, not forwarded")
  }

  /**
   * Recomposition applies the whole theme — the typeface included, which no named value can carry.
   * Seeding colours there would silently narrow what selecting a theme means.
   */
  @Test
  fun `a recomposing preview keeps the provider untouched`() {
    assertEquals(200, render("recomposing", "recomposing-one"))

    val overrides = recomposing.seen.single()
    assertEquals(theme.providerFqn, overrides.themeProvider, "the lane applies it by recomposing")
    assertTrue(
      overrides.remoteCompose?.namedValues.isNullOrEmpty(),
      "no colours are seeded behind its back",
    )
  }

  /**
   * A catalog can declare themes and publish no replay mapping. Rendering the default pixels under
   * the theme's name would be the #3449 failure — bytes indistinguishable from a theme that changed
   * nothing — so the refusal has to stand.
   */
  @Test
  fun `a replayed preview with no published mapping still refuses`() {
    assertEquals(409, render("unmapped", "unmapped-one"), "terminal, as before")
  }

  /**
   * A blank explicit seed is not the caller overriding a role — `ServeOverrides.parse` skips an
   * empty `rc.` value outright, so honouring the bare key would drop the theme's colour for that
   * role and leave the document on its authored one. The response would still report a themed
   * render: a theme applied in part, claimed in full.
   */
  @Test
  fun `a blank explicit seed does not suppress the theme's colour`() {
    val url =
      "http://127.0.0.1:${server.port}/replayed/render/replayed-one.png" +
        "?themeProvider=${theme.providerFqn}&rc.WearM3.primary="
    assertEquals(200, client.newCall(Request.Builder().url(url).build()).execute().use { it.code })

    assertEquals(
      RemoteNamedValue.ColorValue("#FF6F61"),
      replayed.seen.single().remoteCompose?.namedValues?.get("WearM3.primary"),
      "the theme fills a role the request left blank",
    )
  }

  /**
   * The declared set is narrowed to what the session can apply. A catalog publishing a mapping for
   * one theme and not another must not offer the unmapped one — selecting it would reach the
   * terminal 409 the gate exists to prevent.
   */
  @Test
  fun `only themes with a published mapping are replayable`() {
    // A real host, not a delegate: `replayableThemes()` is an interface default, so `by` would
    // forward it to the delegate and run it against the delegate's themes rather than these.
    val partial =
      object : ServeHost {
        override val previews = emptyList<ServePreview>()
        override val label = "partial"
        override val declaredThemes = listOf(theme, ServeTheme("Teal", "com.example.TealTheme"))

        override fun themeReplayColors(providerFqn: String): Map<String, String> =
          if (providerFqn == theme.providerFqn) coral else emptyMap()

        override fun render(previewId: String, overrides: PreviewOverrides) = RenderOutcome.NotFound

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

    assertEquals(
      listOf(theme.providerFqn),
      partial.replayableThemes().map { it.providerFqn },
      "the unmapped theme is not offered",
    )
  }
}
