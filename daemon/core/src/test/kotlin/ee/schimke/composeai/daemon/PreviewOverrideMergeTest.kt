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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

  /**
   * The renderer-side twin of the projection above (#4371). A payload-driven render never sees the
   * merge: `localeTag` arrives as a typed wire token and the encoder nulls it out of the bag, so
   * the engines rehydrate it with `withPseudolocaleFrom(spec.localeTag)` before planning. Only
   * pseudolocales fold back — a real locale has no bag-consuming planner.
   */
  @Test
  fun `withPseudolocaleFrom rehydrates only pseudolocale tags`() {
    val none: PreviewOverrides? = null
    assertEquals("en-XA", none.withPseudolocaleFrom("en-XA")?.localeTag)
    assertEquals("ar-XB", none.withPseudolocaleFrom("ar-XB")?.localeTag)
    // Spelling is normalized the same way `toExtensionOverrides` normalizes it, and the tag is
    // carried through verbatim — `Pseudolocale.fromTag` does its own folding downstream.
    assertEquals("ar_xb", none.withPseudolocaleFrom("ar_xb")?.localeTag)

    // A bag that already carries other overrides keeps them.
    val seeded = PreviewOverrides(touchOverlay = true).withPseudolocaleFrom("en-XA")
    assertEquals(true, seeded?.touchOverlay)
    assertEquals("en-XA", seeded?.localeTag)

    // Real locales, and no locale at all, pass through untouched — no bag conjured from nothing.
    assertNull(none.withPseudolocaleFrom("fr-FR"))
    assertNull(none.withPseudolocaleFrom(null))
    assertNull(none.withPseudolocaleFrom("  "))
    val real = PreviewOverrides(touchOverlay = true)
    assertSame(real, real.withPseudolocaleFrom("de"))

    // Idempotent, so the held-session lane (which already carries the tag through
    // `toExtensionOverrides`) is unchanged by the rehydration.
    val held = PreviewOverrides(localeTag = "ar-XB")
    assertEquals(held, held.withPseudolocaleFrom("ar-XB"))
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
   * Exhaustiveness guard for [withCarriedOverrides] — the held/live lane's counterpart of the
   * [layeredOver] canary below.
   *
   * A fully-populated discovery-time bag carried onto a base spec must survive the merge and come
   * back out of [MergedPreviewOverrides.toExtensionOverrides] intact when the per-render overlay is
   * empty, because that is exactly what browsing a `@OverrideVariant` preview in the viewer's Live
   * lane does. Both hosts used to hand-pick a subset here, so the variant's `namedOverrides` seed
   * (and focus / talkBack / permissions / …) never reached the held composition and the split
   * switch composed as its un-split primary (yschimke/wear-m3-catalog#33). Whole-object
   * [assertEquals] against the expected projection, so a field the carry forgets fails here.
   */
  @Test
  fun `withCarriedOverrides keeps every extension field on a held session with no overlay`() {
    val carried = fullyPopulatedOverrides()
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
        .withCarriedOverrides(carried)

    val projected = mergePreviewOverrides(base, null).toExtensionOverrides()

    assertEquals(
      PreviewOverrides(
        material3Theme = carried.material3Theme,
        wallpaper = carried.wallpaper,
        ambient = carried.ambient,
        gestures = carried.gestures,
        focus = carried.focus,
        // The carried locale rides the spec's own `localeTag`, not the bag — only a pseudolocale
        // projects into the extension bag, and the fixture's `fr-FR` is not one.
        localeTag = null,
        touchOverlay = carried.touchOverlay,
        talkBack = carried.talkBack,
        keyboard = carried.keyboard,
        permissions = carried.permissions,
        remoteCompose = carried.remoteCompose,
        launcherWidget = carried.launcherWidget,
        lottie = carried.lottie,
        namedOverrides = carried.namedOverrides,
      ),
      projected,
    )
  }

  @Test
  fun `a live overlay wins per key over the carried variant seed`() {
    // The floor is the baked `@OverrideVariant` seed; a knob the viewer actually edited still wins,
    // and the seeds it did not touch survive.
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
        .withCarriedOverrides(
          PreviewOverrides(
            namedOverrides =
              mapOf(
                "split" to PreviewOverrideValue.BooleanValue(true),
                "label" to PreviewOverrideValue.StringValue("Primary"),
              )
          )
        )

    val merged =
      mergePreviewOverrides(
        base,
        PreviewOverrides(
          namedOverrides = mapOf("label" to PreviewOverrideValue.StringValue("edited"))
        ),
      )

    assertEquals(PreviewOverrideValue.BooleanValue(true), merged.namedOverrides?.get("split"))
    assertEquals(PreviewOverrideValue.StringValue("edited"), merged.namedOverrides?.get("label"))
  }

  @Test
  fun `withCarriedOverrides on a null bag leaves the base spec alone`() {
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

    assertEquals(base, base.withCarriedOverrides(null))
    assertNull(mergePreviewOverrides(base, null).toExtensionOverrides())
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
      clockEpochMillis = 1_700_000_000_000L,
      inspectionMode = true,
      slotMode = true,
      placeholderActive = true,
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

  @Test
  fun `orientation rotates a device-derived frame on the live-session lane`() {
    // #3547 — the viewer's Orientation control has to mean the same thing on `stream/start` /
    // `setOverrides` as it does on `renderNow`. Pixel Tablet is 1280x800dp at density 2.0, i.e.
    // 2560x1600px landscape; portrait trades the axes.
    val merged =
      mergePreviewOverrides(
        baseSpec(),
        PreviewOverrides(device = "id:pixel_tablet", orientation = Orientation.PORTRAIT),
      )

    assertEquals(1600, merged.widthPx)
    assertEquals(2560, merged.heightPx)
    assertEquals(Orientation.PORTRAIT, merged.orientation)
  }

  @Test
  fun `orientation matching the device frame leaves it alone`() {
    val merged =
      mergePreviewOverrides(
        baseSpec(),
        PreviewOverrides(device = "id:pixel_tablet", orientation = Orientation.LANDSCAPE),
      )

    assertEquals(2560, merged.widthPx)
    assertEquals(1600, merged.heightPx)
  }

  @Test
  fun `explicit pixels outrank the orientation request`() {
    val merged =
      mergePreviewOverrides(
        baseSpec(),
        PreviewOverrides(
          device = "id:pixel_tablet",
          orientation = Orientation.PORTRAIT,
          widthPx = 900,
        ),
      )

    // Naming exact pixels wins over every derived value, the rotation included — so 900 stays on
    // the width axis rather than being swapped onto the height.
    assertEquals(900, merged.widthPx)
    assertEquals(1600, merged.heightPx)
  }

  @Test
  fun `an orientation inherited from the base rotates the frame too`() {
    // The base carries the discovery-time orientation; a per-call override that doesn't mention
    // orientation must not quietly un-rotate the frame.
    val merged =
      mergePreviewOverrides(
        baseSpec(orientation = Orientation.PORTRAIT),
        PreviewOverrides(device = "id:pixel_tablet"),
      )

    assertEquals(1600, merged.widthPx)
    assertEquals(2560, merged.heightPx)
  }

  @Test
  fun `merging twice does not oscillate the frame`() {
    // Idempotence: the merge output fed back in as a base must stay put. Without it, a live
    // session that re-merges on every override edit would flip orientation on alternate edits.
    val once =
      mergePreviewOverrides(
        baseSpec(),
        PreviewOverrides(device = "id:pixel_tablet", orientation = Orientation.PORTRAIT),
      )
    val twice =
      mergePreviewOverrides(
        baseSpec(widthPx = once.widthPx, heightPx = once.heightPx, orientation = once.orientation),
        PreviewOverrides(orientation = Orientation.PORTRAIT),
      )

    assertEquals(once.widthPx, twice.widthPx)
    assertEquals(once.heightPx, twice.heightPx)
  }

  @Test
  fun `reports rotation so per-axis state can follow the frame`() {
    // `DesktopHost` swaps wrapWidth/wrapHeight off this flag. It cannot re-derive the answer from
    // the returned dimensions — those are already rotated, so re-asking "does the orientation
    // contradict this frame?" always says no, and a one-wrapped-axis preview would keep wrapping
    // the axis that is no longer free (#3552 review).
    val rotated =
      mergePreviewOverrides(baseSpec(), PreviewOverrides(orientation = Orientation.LANDSCAPE))
    assertTrue("100x200 asked for landscape should report rotated", rotated.rotated)
    assertEquals(200, rotated.widthPx)
    assertEquals(100, rotated.heightPx)

    // Already satisfied: no swap, so nothing downstream should trade its axes either.
    val untouched =
      mergePreviewOverrides(baseSpec(), PreviewOverrides(orientation = Orientation.PORTRAIT))
    assertFalse("100x200 is already portrait", untouched.rotated)

    // Explicit pixels outrank the request, so the frame is the caller's and never rotates.
    val explicit =
      mergePreviewOverrides(
        baseSpec(),
        PreviewOverrides(widthPx = 300, heightPx = 400, orientation = Orientation.LANDSCAPE),
      )
    assertFalse("explicit pixels outrank the rotation", explicit.rotated)
    assertEquals(300, explicit.widthPx)
  }

  private fun baseSpec(
    widthPx: Int = 100,
    heightPx: Int = 200,
    orientation: Orientation? = null,
  ): PreviewOverrideBaseSpec =
    PreviewOverrideBaseSpec(
      widthPx = widthPx,
      heightPx = heightPx,
      density = 1.0f,
      device = null,
      localeTag = null,
      fontScale = null,
      uiMode = null,
      orientation = orientation,
      inspectionMode = null,
    )
}
