package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import ee.schimke.composeai.daemon.protocol.FocusDirection
import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.GestureKindOverride
import ee.schimke.composeai.daemon.protocol.GestureOverride
import ee.schimke.composeai.daemon.protocol.KeyboardOverride
import ee.schimke.composeai.daemon.protocol.LauncherWidgetOverride
import ee.schimke.composeai.daemon.protocol.LauncherWidgetSize
import ee.schimke.composeai.daemon.protocol.LottieOverride
import ee.schimke.composeai.daemon.protocol.Material3ThemeOverrides
import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PermissionGrantStateOverride
import ee.schimke.composeai.daemon.protocol.PermissionsOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteComposeProfile
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.daemon.protocol.WallpaperOverride
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewOverrideMergeTest {
  @Test
  fun `null overrides preserve base spec`() {
    val base =
      PreviewOverrideBaseSpec(
        widthPx = 100,
        heightPx = 200,
        density = 2.0f,
        device = "id:pixel_5",
        localeTag = "en-US",
        fontScale = 1.2f,
        uiMode = UiMode.DARK,
        orientation = Orientation.PORTRAIT,
        inspectionMode = false,
      )

    val merged = mergePreviewOverrides(base, null)

    assertEquals(100, merged.widthPx)
    assertEquals(200, merged.heightPx)
    assertEquals(2.0f, merged.density)
    assertEquals("id:pixel_5", merged.device)
    assertEquals("en-US", merged.localeTag)
    assertEquals(1.2f, merged.fontScale)
    assertEquals(UiMode.DARK, merged.uiMode)
    assertEquals(Orientation.PORTRAIT, merged.orientation)
    assertEquals(false, merged.inspectionMode)
  }

  @Test
  fun `device override resolves dimensions and explicit fields win`() {
    val base =
      PreviewOverrideBaseSpec(
        widthPx = 100,
        heightPx = 200,
        density = 1.0f,
        device = null,
        localeTag = null,
        fontScale = null,
        uiMode = null,
        orientation = null,
        inspectionMode = null,
      )

    val merged =
      mergePreviewOverrides(
        base,
        PreviewOverrides(
          device = "spec:width=50dp,height=80dp,dpi=320",
          widthPx = 123,
          localeTag = "de",
          fontScale = 1.5f,
          uiMode = UiMode.LIGHT,
          orientation = Orientation.LANDSCAPE,
          inspectionMode = true,
        ),
      )

    assertEquals(123, merged.widthPx)
    assertEquals(160, merged.heightPx)
    assertEquals(2.0f, merged.density)
    assertEquals("spec:width=50dp,height=80dp,dpi=320", merged.device)
    assertEquals("de", merged.localeTag)
    assertEquals(1.5f, merged.fontScale)
    assertEquals(UiMode.LIGHT, merged.uiMode)
    assertEquals(Orientation.LANDSCAPE, merged.orientation)
    assertEquals(true, merged.inspectionMode)
  }

  @Test
  fun `focus override merges into the bag and clears via toExtensionOverrides`() {
    val base =
      PreviewOverrideBaseSpec(
        widthPx = 320,
        heightPx = 480,
        density = 2.0f,
        device = null,
        localeTag = null,
        fontScale = null,
        uiMode = null,
        orientation = null,
        inspectionMode = null,
      )

    val merged =
      mergePreviewOverrides(
        base,
        PreviewOverrides(focus = FocusOverride(direction = FocusDirection.Next, step = 1)),
      )

    assertEquals(FocusOverride(direction = FocusDirection.Next, step = 1), merged.focus)
    val extensionOverrides = merged.toExtensionOverrides()
    assertEquals(
      FocusOverride(direction = FocusDirection.Next, step = 1),
      extensionOverrides?.focus,
    )

    val empty = mergePreviewOverrides(base, PreviewOverrides()).toExtensionOverrides()
    assertNull("merged extension bag should be null when no extension fields are set", empty)
  }

  @Test
  fun `permissions override merges into the bag and flows through toExtensionOverrides`() {
    val base =
      PreviewOverrideBaseSpec(
        widthPx = 320,
        heightPx = 480,
        density = 2.0f,
        device = null,
        localeTag = null,
        fontScale = null,
        uiMode = null,
        orientation = null,
        inspectionMode = null,
      )

    val override =
      PermissionsOverride(
        grants = mapOf("android.permission.CAMERA" to PermissionGrantStateOverride.GRANTED)
      )
    val merged = mergePreviewOverrides(base, PreviewOverrides(permissions = override))

    assertEquals(override, merged.permissions)
    assertEquals(override, merged.toExtensionOverrides()?.permissions)
  }

  @Test
  fun `gestures override merges into the bag and flows through toExtensionOverrides`() {
    val base =
      PreviewOverrideBaseSpec(
        widthPx = 320,
        heightPx = 480,
        density = 2.0f,
        device = null,
        localeTag = null,
        fontScale = null,
        uiMode = null,
        orientation = null,
        inspectionMode = null,
      )

    val override = GestureOverride(showHints = true, invoke = GestureKindOverride.PRIMARY)
    val merged = mergePreviewOverrides(base, PreviewOverrides(gestures = override))

    assertEquals(override, merged.gestures)
    // The projection must carry `gestures` so `GesturePreviewOverrideExtension.plan` sees it —
    // without this the Wear override path is inert (hints never force, handlers never invoke).
    assertEquals(override, merged.toExtensionOverrides()?.gestures)
    assertNull(mergePreviewOverrides(base, PreviewOverrides()).toExtensionOverrides())
  }

  @Test
  fun `talkBack override merges into the bag and flows through toExtensionOverrides`() {
    // Issue #1956 — the TalkBack focus overlay is opt-in via overrides.talkBack; it must survive
    // the
    // merge → toExtensionOverrides projection so DesktopRecordingSession can read it off the spec.
    val base =
      PreviewOverrideBaseSpec(
        widthPx = 320,
        heightPx = 480,
        density = 2.0f,
        device = null,
        localeTag = null,
        fontScale = null,
        uiMode = null,
        orientation = null,
        inspectionMode = null,
      )

    val merged = mergePreviewOverrides(base, PreviewOverrides(talkBack = true))
    assertEquals(true, merged.talkBack)
    assertEquals(true, merged.toExtensionOverrides()?.talkBack)

    // Default (unset) stays off and projects to an empty bag.
    assertNull(mergePreviewOverrides(base, PreviewOverrides()).toExtensionOverrides())
  }

  @Test
  fun `remoteCompose override merges into the bag and flows through toExtensionOverrides`() {
    val base =
      PreviewOverrideBaseSpec(
        widthPx = 320,
        heightPx = 480,
        density = 2.0f,
        device = null,
        localeTag = null,
        fontScale = null,
        uiMode = null,
        orientation = null,
        inspectionMode = null,
      )

    val override =
      RemoteComposeOverride(
        profile = RemoteComposeProfile.ANDROIDX,
        namedValues = mapOf("score" to RemoteNamedValue.FloatValue(0.5f)),
      )
    val merged = mergePreviewOverrides(base, PreviewOverrides(remoteCompose = override))

    assertEquals(override, merged.remoteCompose)
    assertEquals(override, merged.toExtensionOverrides()?.remoteCompose)
  }

  @Test
  fun `pseudolocale localeTag flows through toExtensionOverrides so the planner runs`() {
    val base =
      PreviewOverrideBaseSpec(
        widthPx = 400,
        heightPx = 800,
        density = 3.0f,
        device = null,
        localeTag = null,
        fontScale = null,
        uiMode = null,
        orientation = null,
        inspectionMode = null,
      )

    val accent =
      mergePreviewOverrides(base, PreviewOverrides(localeTag = "en-XA")).toExtensionOverrides()
    assertNotNull(
      "locale-only en-XA must produce a non-null extension bag so PseudolocalePreviewOverrideExtension plans the around-composable",
      accent,
    )
    assertEquals("en-XA", accent?.localeTag)

    val bidi =
      mergePreviewOverrides(base, PreviewOverrides(localeTag = "ar-XB")).toExtensionOverrides()
    assertNotNull(bidi)
    assertEquals("ar-XB", bidi?.localeTag)

    val realLocale =
      mergePreviewOverrides(base, PreviewOverrides(localeTag = "fr-FR")).toExtensionOverrides()
    assertNull(
      "non-pseudo locales must not project into the extension bag — the renderer applies them via qualifiers / LocaleList directly",
      realLocale,
    )
  }

  @Test
  fun `namedOverrides per-key merge over base and flow through toExtensionOverrides`() {
    val base =
      PreviewOverrideBaseSpec(
        widthPx = 320,
        heightPx = 480,
        density = 2.0f,
        device = null,
        localeTag = null,
        fontScale = null,
        uiMode = null,
        orientation = null,
        inspectionMode = null,
        namedOverrides =
          mapOf(
            "rowCount" to PreviewOverrideValue.IntValue(3),
            "label" to PreviewOverrideValue.StringValue("base"),
          ),
      )

    // A follow-up render that only edits `label` must keep `rowCount` (per-key merge, not replace).
    val merged =
      mergePreviewOverrides(
        base,
        PreviewOverrides(
          namedOverrides = mapOf("label" to PreviewOverrideValue.StringValue("edited"))
        ),
      )

    assertEquals(PreviewOverrideValue.StringValue("edited"), merged.namedOverrides?.get("label"))
    assertEquals(PreviewOverrideValue.IntValue(3), merged.namedOverrides?.get("rowCount"))
    assertEquals(merged.namedOverrides, merged.toExtensionOverrides()?.namedOverrides)
  }

  @Test
  fun `withThemeProvider folds the FQN onto a null or existing held-session bag`() {
    // toExtensionOverrides() drops themeProvider, so the held/live path folds it back on. A null
    // bag
    // (no other extension override) still gains one carrying only the theme.
    val fromNull = (null as PreviewOverrides?).withThemeProvider("com.example.BrandDark")
    assertNotNull(fromNull)
    assertEquals("com.example.BrandDark", fromNull!!.themeProvider)

    // An existing bag keeps its other fields and gains the theme.
    val existing = PreviewOverrides(talkBack = true)
    val folded = existing.withThemeProvider("com.example.BrandLight")
    assertEquals("com.example.BrandLight", folded?.themeProvider)
    assertEquals(true, folded?.talkBack)

    // Blank / null FQN is a no-op — a themeless held bag stays exactly as-is (including null).
    assertNull((null as PreviewOverrides?).withThemeProvider(null))
    assertNull((null as PreviewOverrides?).withThemeProvider(""))
    assertEquals(existing, existing.withThemeProvider(null))
  }

  @Test
  fun `withSizeBounds carries the wrapped-axis bounds onto a null or existing held-session bag`() {
    // toExtensionOverrides() drops the renderer-read size bounds, so the held/live path folds them
    // back on. A null bag gains one carrying only the bounds.
    val source = PreviewOverrides(minWidthPx = 120, maxWidthPx = 400, maxHeightPx = 800)
    val fromNull = (null as PreviewOverrides?).withSizeBounds(source)
    assertNotNull(fromNull)
    assertEquals(120, fromNull!!.minWidthPx)
    assertEquals(400, fromNull.maxWidthPx)
    assertEquals(800, fromNull.maxHeightPx)
    assertNull(fromNull.minHeightPx)

    // An existing bag keeps its other fields and gains the bounds.
    val existing = PreviewOverrides(themeProvider = "com.example.Brand")
    val folded = existing.withSizeBounds(source)
    assertEquals("com.example.Brand", folded?.themeProvider)
    assertEquals(400, folded?.maxWidthPx)

    // A source with no bounds set is a no-op — the held bag stays exactly as-is (including null).
    assertNull((null as PreviewOverrides?).withSizeBounds(PreviewOverrides()))
    assertNull((null as PreviewOverrides?).withSizeBounds(null))
    assertEquals(existing, existing.withSizeBounds(PreviewOverrides(fontScale = 2f)))
  }

  /**
   * Exhaustiveness guard for [layeredOver]: an empty overlay over a fully-populated base must
   * round-trip to the base unchanged. Every [PreviewOverrides] field is non-null here, so a field
   * the merge forgot to carry would come back null and fail the whole-object [assertEquals] — the
   * canary that keeps the hand-written field list in sync as the protocol grows.
   */
  @Test
  fun `layeredOver with an empty overlay preserves every base field`() {
    val base = fullyPopulatedOverrides()
    assertEquals(base, PreviewOverrides().layeredOver(base))
  }

  @Test
  fun `layeredOver lets the overlay win per-field and merges namedOverrides per-key`() {
    val base =
      PreviewOverrides(
        fontScale = 1.0f,
        themeProvider = "com.example.Base",
        namedOverrides =
          mapOf(
            "rowCount" to PreviewOverrideValue.IntValue(3),
            "label" to PreviewOverrideValue.StringValue("base"),
          ),
      )
    // A `?knob.label=…` edit arrives as a sparse overlay: only `label` (and, say, fontScale) set.
    // The base's themeProvider and the untouched `rowCount` seed must survive.
    val overlay =
      PreviewOverrides(
        fontScale = 2.0f,
        namedOverrides = mapOf("label" to PreviewOverrideValue.StringValue("edited")),
      )
    val merged = overlay.layeredOver(base)!!
    assertEquals(2.0f, merged.fontScale!!, 0.0f) // overlay wins where set
    assertEquals("com.example.Base", merged.themeProvider) // base fills the gap
    assertEquals(PreviewOverrideValue.StringValue("edited"), merged.namedOverrides?.get("label"))
    assertEquals(PreviewOverrideValue.IntValue(3), merged.namedOverrides?.get("rowCount"))
  }

  @Test
  fun `layeredOver degenerates to the other side when one bag is null`() {
    val bag = PreviewOverrides(fontScale = 1.5f)
    assertEquals(bag, bag.layeredOver(null))
    assertEquals(bag, (null as PreviewOverrides?).layeredOver(bag))
    assertNull((null as PreviewOverrides?).layeredOver(null))
  }

  /**
   * Every [PreviewOverrides] field set to a distinct non-null value — the exhaustiveness fixture.
   */
  private fun fullyPopulatedOverrides() =
    PreviewOverrides(
      widthPx = 111,
      heightPx = 222,
      minWidthPx = 33,
      minHeightPx = 44,
      maxWidthPx = 555,
      maxHeightPx = 666,
      density = 3.0f,
      localeTag = "fr-FR",
      fontScale = 1.4f,
      uiMode = UiMode.DARK,
      orientation = Orientation.LANDSCAPE,
      device = "id:pixel_7",
      captureAdvanceMs = 64L,
      inspectionMode = true,
      slotMode = true,
      clearBackground = true,
      material3Theme = Material3ThemeOverrides(colorScheme = mapOf("primary" to "#FFFF0000")),
      themeProvider = "com.example.BrandDark",
      wallpaper = WallpaperOverride(seedColor = "#3366FF"),
      ambient = AmbientOverride(state = AmbientStateOverride.AMBIENT),
      gestures = GestureOverride(enabled = true),
      focus = FocusOverride(tabIndex = 2),
      touchOverlay = true,
      talkBack = true,
      keyboard = KeyboardOverride(visible = true),
      permissions =
        PermissionsOverride(
          grants = mapOf("android.permission.CAMERA" to PermissionGrantStateOverride.GRANTED)
        ),
      remoteCompose = RemoteComposeOverride(profile = RemoteComposeProfile.ANDROIDX),
      launcherWidget = LauncherWidgetOverride(cells = LauncherWidgetSize(width = 4, height = 2)),
      lottie = LottieOverride(progress = 0.5f),
      namedOverrides = mapOf("label" to PreviewOverrideValue.StringValue("base")),
    )
}
