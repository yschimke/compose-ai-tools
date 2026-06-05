package ee.schimke.composeai.daemon

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verifies the per-backend extension matrix asserted by issue #1201 — closing the chip-shows- "kind
 * not advertised" gap on CMP-desktop sessions. The test exercises the registry-building helper
 * directly rather than spinning up a full JSON-RPC server: the wire-level `extensions/list`
 * round-trip is exercised by [JsonRpcDesktopIntegrationTest] elsewhere, what's specific to this PR
 * is which `Extension` instances [buildDesktopExtensions] emits given a particular config.
 *
 * **What's still Android-only.** The negative assertions below pin the kinds the desktop daemon
 * deliberately does NOT advertise yet because their producers are Android-API-bound (`uiautomator`,
 * `resources/used`). `a11y` IS advertised (overlay-only): ATF itself is Android-only, but the "what
 * a screen reader sees" overlay + legend is portable — see `a11y_overlay_only_advertised_*`.
 * Issue #1201's per-row triage tracks the migration path; the panel should honour
 * `ServerCapabilities.backend == "desktop"` and grey the remaining chips out instead of relying on
 * the daemon to advertise them.
 */
class BuildDesktopExtensionsTest {
  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private fun build(
    dataRoot: File? = null,
    composeTraceEnabled: Boolean = false,
    displayFilterEnabled: Boolean = false,
    historyManager: ee.schimke.composeai.daemon.history.HistoryManager? = null,
  ): Set<String> {
    val ids =
      buildDesktopExtensions(
          previewIndex = PreviewIndex.empty(),
          recompositionRegistry = RecompositionDataProductRegistry(),
          themeRegistry = ThemeDataProductRegistry(),
          wallpaperRegistry = WallpaperDataProductRegistry(),
          launcherWidgetRegistry = LauncherWidgetDataProductRegistry(),
          historyManager = historyManager,
          dataRoot = dataRoot,
          composeTraceEnabled = composeTraceEnabled,
          displayFilterEnabled = displayFilterEnabled,
        )
        .map { it.id }
    return ids.toSet()
  }

  @Test
  fun core_extensions_register_without_data_root() {
    val ids = build(dataRoot = null)
    assertTrue("device/clip" in ids)
    assertTrue("device/background" in ids)
    assertTrue("render/trace" in ids)
    assertTrue("render/test-failure" in ids)
    assertTrue("animation/lottie" in ids)
    assertTrue("render/overlay-legend" in ids)
    assertTrue("data/theme" in ids)
    assertTrue("data/wallpaper" in ids)
    assertTrue("data/pseudolocale" in ids)
    // Issue #1205 — `data/focus` registers the focus / keyboard-traversal override planner so
    // `renderNow.overrides.focus` is no longer silently ignored on the desktop backend.
    assertTrue("data/focus" in ids)
    assertTrue("data/recomposition" in ids)
    assertTrue("recording/script" in ids)

    // File-based registries gate on dataRoot — none should appear here.
    assertFalse("fonts/used" in ids)
    assertFalse("displayfilter" in ids)
    assertFalse("compose/trace" in ids)
  }

  @Test
  fun fonts_used_advertised_when_data_root_set() {
    val dataRoot = tempFolder.newFolder("data")
    val ids = build(dataRoot = dataRoot)
    assertTrue(
      "'fonts/used' missing — produces NotAvailable on desktop until a Skia font " +
        "producer lands, but advertising it removes the wire-level 'kind not advertised' error",
      "fonts/used" in ids,
    )
  }

  @Test
  fun displayfilter_gated_on_filters_sysprop_via_helper_flag() {
    val dataRoot = tempFolder.newFolder("data")
    assertFalse("displayfilter" in build(dataRoot = dataRoot, displayFilterEnabled = false))
    assertTrue("displayfilter" in build(dataRoot = dataRoot, displayFilterEnabled = true))
  }

  @Test
  fun compose_trace_still_gated_on_perfetto_flag() {
    val dataRoot = tempFolder.newFolder("data")
    assertFalse("compose/trace" in build(dataRoot = dataRoot, composeTraceEnabled = false))
    assertTrue("compose/trace" in build(dataRoot = dataRoot, composeTraceEnabled = true))
  }

  @Test
  fun layoutinspector_and_strings_kinds_advertised_when_data_root_set() {
    val dataRoot = tempFolder.newFolder("data")
    val ids = build(dataRoot = dataRoot)
    // Phase 2 (#1201): the connector modules moved from `android.library` to Compose
    // Multiplatform JVM, so `:daemon:desktop` can register their file-based registries. The
    // producer side is still Android-only (Robolectric semantics tree); these registries return
    // NotAvailable on desktop until a CMP-portable producer ports.
    assertTrue("compose/semantics" in ids)
    assertTrue("layout/inspector" in ids)
    assertTrue("text/strings" in ids)
    assertTrue("i18n/translations" in ids)
  }

  @Test
  fun data_navigation_advertised_when_data_root_set() {
    val dataRoot = tempFolder.newFolder("data")
    val ids = build(dataRoot = dataRoot)
    // Phase 4 (#1201): registry extracted from :daemon:android into :data-navigation-connector so
    // desktop can register it. Producer side stays in :daemon:android (Intent reflection).
    assertTrue("data/navigation" in ids)
  }

  @Test
  fun scroll_advertised_when_data_root_set() {
    // Issue #1604 — scroll is the first #1201 gap closed end-to-end on desktop: the registry lives
    // in the pure-JVM `:data-scroll-connector` and `:renderer-desktop` already carries the capture,
    // so `render/scroll/long` / `render/scroll/gif` are produced on demand rather than tripping
    // `-32020 kind not advertised`. The extension gates on `dataRoot` like the other file-backed
    // registries (the artefacts land under `<dataRoot>/render-scroll-{long,gif}/`).
    assertFalse("scroll" in build(dataRoot = null))
    val ids = build(dataRoot = tempFolder.newFolder("scroll-data"))
    assertTrue("scroll" in ids)
  }

  @Test
  fun scroll_extension_advertises_long_and_gif_descriptors() {
    // The scroll extension carries the LONG / GIF preview-extension descriptors so MCP /
    // `previewExtensions/list` clients discover the scroll surface — mirroring `:daemon:android`'s
    // `DaemonMain`. Without the descriptors the panel would see an advertised data-product kind it
    // can't attribute to an extension.
    val extensions =
      buildDesktopExtensions(
        previewIndex = PreviewIndex.empty(),
        recompositionRegistry = RecompositionDataProductRegistry(),
        themeRegistry = ThemeDataProductRegistry(),
        wallpaperRegistry = WallpaperDataProductRegistry(),
        launcherWidgetRegistry = LauncherWidgetDataProductRegistry(),
        historyManager = null,
        dataRoot = tempFolder.newFolder("scroll-desc-data"),
        composeTraceEnabled = false,
        displayFilterEnabled = false,
      )
    val scroll = extensions.single { it.id == "scroll" }
    val kinds = scroll.dataProductCapabilities.map { it.kind }.toSet()
    assertTrue(
      "scroll registry must advertise render/scroll/long + render/scroll/gif; got $kinds",
      ee.schimke.composeai.scroll.ScrollPreviewExtension.KIND_LONG in kinds &&
        ee.schimke.composeai.scroll.ScrollPreviewExtension.KIND_GIF in kinds,
    )
    val descriptorIds = scroll.previewExtensionDescriptors.map { it.id }.toSet()
    assertTrue(
      "scroll extension must advertise its LONG + GIF descriptors; got $descriptorIds",
      ee.schimke.composeai.scroll.ScrollPreviewExtension.longScrollDescriptor.id in descriptorIds &&
        ee.schimke.composeai.scroll.ScrollPreviewExtension.gifScrollDescriptor.id in descriptorIds,
    )
  }

  @Test
  fun touch_overlay_and_keyboard_band_advertise_data_extension_descriptors() {
    // The override-driven extensions (`data/touch-overlay` + `data/keyboard`) carry a
    // `DataExtensionDescriptor` so GUI clients (panel, MCP) can discover them via
    // `initialize.capabilities.dataExtensions` and gate per-card toggle UI on the daemon
    // actually shipping the matching planner. Without descriptors the capability list is empty
    // and clients fall back to hardcoded knowledge of the field names.
    val extensions =
      buildDesktopExtensions(
        previewIndex = PreviewIndex.empty(),
        recompositionRegistry = RecompositionDataProductRegistry(),
        themeRegistry = ThemeDataProductRegistry(),
        wallpaperRegistry = WallpaperDataProductRegistry(),
        launcherWidgetRegistry = LauncherWidgetDataProductRegistry(),
        historyManager = null,
        dataRoot = null,
        composeTraceEnabled = false,
        displayFilterEnabled = false,
      )
    val touch = extensions.single { it.id == "data/touch-overlay" }
    val touchDescriptorIds = touch.dataExtensionDescriptors.map { it.id.value }
    assertTrue(
      "data/touch-overlay must advertise its DataExtensionDescriptor so the panel can " +
        "discover the toggle; got $touchDescriptorIds",
      TouchOverlayExtension.ID.value in touchDescriptorIds,
    )
    val keyboard = extensions.single { it.id == "data/keyboard" }
    val keyboardDescriptorIds = keyboard.dataExtensionDescriptors.map { it.id.value }
    assertTrue(
      "data/keyboard must advertise its DataExtensionDescriptor so the panel can " +
        "discover the toggle; got $keyboardDescriptorIds",
      KeyboardOverrideExtension.ID.value in keyboardDescriptorIds,
    )
  }

  @Test
  fun android_only_kinds_stay_unadvertised_on_desktop() {
    val dataRoot = tempFolder.newFolder("data")
    val ids = build(dataRoot = dataRoot, composeTraceEnabled = true, displayFilterEnabled = true)
    // Producers for these kinds are Android-API-bound — the panel should grey them out based on
    // `ServerCapabilities.backend == "desktop"` rather than expecting the daemon to advertise.
    // Tracking migration of each in issue #1201.
    assertFalse("resources/used" in ids)
    assertFalse("uiautomator" in ids)
  }

  @Test
  fun a11y_overlay_only_advertised_when_data_root_set() {
    // ATF is Android-only, but the desktop daemon now produces an overlay-only a11y pass (Compose
    // semantics → AWT overlay + legend, empty findings). The registry is file-based, so it gates on
    // dataRoot like the other file-backed registries.
    assertFalse("a11y" in build(dataRoot = null))
    assertTrue("a11y" in build(dataRoot = tempFolder.newFolder("a11y-data")))
  }
}
