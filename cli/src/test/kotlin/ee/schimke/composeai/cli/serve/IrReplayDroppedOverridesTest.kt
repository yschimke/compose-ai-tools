package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [CatalogLiveRouting.irReplayDroppedOverrideNames] — the daemon-lane counterpart of
 * [CatalogLiveRouting.droppedOverrideNames].
 *
 * A schema-v5 IR-backed preview is redrawn by replaying its captured document, never by re-running
 * the composable. Overrides that only reach the pixels through a fresh composition are therefore
 * inert — and, before this predicate existed, invisibly so: the daemon rendered, answered `200`
 * with `generation=daemon`, and handed back bytes byte-identical to the baked snapshot. A caller
 * diffing renders across override values reads that as "the override had no visual effect".
 *
 * The bar for entering the list is **no representation in the document at all**, not "observed
 * inert against one catalog" — `fontScale` and `uiMode` look inert on `remote-m3` yet a document
 * can defer both to the host and resolve them at paint time, so naming them would refuse an
 * override the replay can honour. The negative assertions here are the load-bearing ones.
 */
class IrReplayDroppedOverridesTest {

  private val lightId = "button-namedlabel__ideal__default__light"

  @Test
  fun `an override-free request drops nothing`() {
    assertEquals(
      emptyList(),
      CatalogLiveRouting.irReplayDroppedOverrideNames(lightId, PreviewOverrides()),
    )
  }

  /**
   * `stringResource()` resolved to a literal at capture and the text op holds it, and — unlike the
   * font/theme pair below — `RemoteContext` exposes no locale among its system variables, so a
   * document has no way to defer the choice to the host.
   */
  @Test
  fun `localeTag is named — a document cannot defer it to the host`() {
    assertEquals(
      listOf("localeTag"),
      CatalogLiveRouting.irReplayDroppedOverrideNames(lightId, PreviewOverrides(localeTag = "ar")),
    )
  }

  /**
   * The subtle case, and the one worth guarding hardest.
   *
   * Both look inert against `remote-m3` — every render comes back byte-identical to the baked
   * snapshot — but that is a property of *those documents*, which baked absolute text sizes and
   * concrete colours at capture. A document can defer either to the host and resolve it at paint
   * time with no recomposition: `RemoteComposeView.getDefaultTextSize()` is `14f * density *
   * Configuration.fontScale`, and `onDraw` derives the paint theme from
   * `Configuration.isNightModeActive()` when the player's theme is `THEME_UNSPECIFIED`. Both read
   * the live `Configuration` that `RenderEngine` already sets, so the wiring is end-to-end today.
   *
   * Naming either would 409 an override the replay can honour.
   */
  @Test
  fun `fontScale and uiMode are never named — a document may read the host value`() {
    assertEquals(
      emptyList(),
      CatalogLiveRouting.irReplayDroppedOverrideNames(lightId, PreviewOverrides(fontScale = 2.0f)),
    )
    assertEquals(
      emptyList(),
      CatalogLiveRouting.irReplayDroppedOverrideNames(
        lightId,
        PreviewOverrides(uiMode = UiMode.DARK),
      ),
    )
    assertEquals(
      emptyList(),
      CatalogLiveRouting.irReplayDroppedOverrideNames(
        lightId,
        PreviewOverrides(uiMode = UiMode.LIGHT),
      ),
    )
  }

  /** Both seed a composition that never runs on the replay path. */
  @Test
  fun `theme providers and knobs are named`() {
    assertEquals(
      listOf("themeProvider"),
      CatalogLiveRouting.irReplayDroppedOverrideNames(
        lightId,
        PreviewOverrides(themeProvider = "com.example.BrandTheme"),
      ),
    )
    assertEquals(
      listOf("knob.label"),
      CatalogLiveRouting.irReplayDroppedOverrideNames(
        lightId,
        PreviewOverrides(
          namedOverrides = mapOf("label" to PreviewOverrideValue.StringValue("Tap me"))
        ),
      ),
    )
  }

  /**
   * The heart of it: the Remote Compose facet is **not** wholesale inert. Colour and float seeds
   * reach the replayed document through the player's `StateUpdater` and genuinely move pixels
   * (`rc.shaderColor` / `rc.progress` on `remote-m3` both do). A *string* seed does not land in the
   * alpha player, so only that kind is reported.
   *
   * When the player starts honouring string seeds, this is the assertion that fails — which is the
   * point: the entry comes out of the predicate at the same time, rather than the server quietly
   * refusing an override that had begun working.
   */
  @Test
  fun `only string rc seeds are named — colour and float seeds reach the replay`() {
    val mixed =
      PreviewOverrides(
        remoteCompose =
          RemoteComposeOverride(
            namedValues =
              mapOf(
                "label" to RemoteNamedValue.StringValue("HELLO"),
                "shaderColor" to RemoteNamedValue.ColorValue("#FF00FF00"),
                "progress" to RemoteNamedValue.FloatValue(0.95f),
                "iconSize" to RemoteNamedValue.DpValue(64f),
              )
          )
      )
    assertEquals(
      listOf("rc.label"),
      CatalogLiveRouting.irReplayDroppedOverrideNames(lightId, mixed),
    )
  }

  /** Picking the player that draws the replay is exactly what the replay path reads. */
  @Test
  fun `the player selection is honoured by the replay`() {
    assertEquals(
      emptyList(),
      CatalogLiveRouting.irReplayDroppedOverrideNames(
        lightId,
        PreviewOverrides(
          remoteCompose = RemoteComposeOverride(player = RemoteComposePlayerKind.EMBEDDED)
        ),
      ),
    )
  }

  /**
   * The size / density family reaches the player through the capture's `displayMetrics`, so a
   * replay can answer it. Listing it would turn working renders into refusals — the failure mode
   * this predicate's narrowness exists to avoid.
   */
  @Test
  fun `size overrides are not named`() {
    assertEquals(
      emptyList(),
      CatalogLiveRouting.irReplayDroppedOverrideNames(
        lightId,
        PreviewOverrides(widthPx = 640, heightPx = 480, density = 2.0f),
      ),
    )
  }

  @Test
  fun `every named axis reports together, sorted within a family`() {
    assertEquals(
      listOf("localeTag", "themeProvider", "knob.a", "knob.b", "rc.text"),
      CatalogLiveRouting.irReplayDroppedOverrideNames(
        lightId,
        PreviewOverrides(
          fontScale = 1.5f,
          localeTag = "fr",
          uiMode = UiMode.DARK,
          themeProvider = "com.example.BrandTheme",
          namedOverrides =
            mapOf(
              "b" to PreviewOverrideValue.StringValue("second"),
              "a" to PreviewOverrideValue.StringValue("first"),
            ),
          remoteCompose =
            RemoteComposeOverride(
              namedValues = mapOf("text" to RemoteNamedValue.StringValue("body"))
            ),
        ),
      ),
    )
  }
}
