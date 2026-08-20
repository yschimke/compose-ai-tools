package ee.schimke.composeai.daemon

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.devices.FrameOrientation
import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.FocusOverride
import ee.schimke.composeai.daemon.protocol.GestureOverride
import ee.schimke.composeai.daemon.protocol.KeyboardOverride
import ee.schimke.composeai.daemon.protocol.LauncherWidgetOverride
import ee.schimke.composeai.daemon.protocol.LottieOverride
import ee.schimke.composeai.daemon.protocol.Material3ThemeOverrides
import ee.schimke.composeai.daemon.protocol.Orientation
import ee.schimke.composeai.daemon.protocol.PermissionsOverride
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.daemon.protocol.WallpaperOverride
import ee.schimke.composeai.data.overrides.PreviewOverrideValue

/**
 * Backend-neutral subset of a render spec that [PreviewOverrides] can mutate.
 *
 * Concrete hosts keep backend-local `RenderSpec` types, but recording and render override semantics
 * need to stay identical across hosts. This small DTO lets hosts adapt their local spec into a
 * shared merge function, then copy the merged fields back into their local type.
 */
data class PreviewOverrideBaseSpec(
  val widthPx: Int,
  val heightPx: Int,
  val density: Float,
  val device: String?,
  val localeTag: String?,
  val fontScale: Float?,
  val uiMode: UiMode?,
  val orientation: Orientation?,
  val inspectionMode: Boolean?,
  val material3Theme: Material3ThemeOverrides? = null,
  val wallpaper: WallpaperOverride? = null,
  val ambient: AmbientOverride? = null,
  val gestures: GestureOverride? = null,
  val focus: FocusOverride? = null,
  val touchOverlay: Boolean? = null,
  val talkBack: Boolean? = null,
  val keyboard: KeyboardOverride? = null,
  val permissions: PermissionsOverride? = null,
  val remoteCompose: RemoteComposeOverride? = null,
  val launcherWidget: LauncherWidgetOverride? = null,
  val lottie: LottieOverride? = null,
  val namedOverrides: Map<String, PreviewOverrideValue>? = null,
)

data class MergedPreviewOverrides(
  val widthPx: Int,
  val heightPx: Int,
  val density: Float,
  val device: String?,
  val localeTag: String?,
  val fontScale: Float?,
  val uiMode: UiMode?,
  val orientation: Orientation?,
  val inspectionMode: Boolean?,
  val material3Theme: Material3ThemeOverrides?,
  val wallpaper: WallpaperOverride?,
  val ambient: AmbientOverride?,
  val gestures: GestureOverride?,
  val focus: FocusOverride?,
  val touchOverlay: Boolean?,
  val talkBack: Boolean?,
  val keyboard: KeyboardOverride?,
  val permissions: PermissionsOverride?,
  val remoteCompose: RemoteComposeOverride?,
  val launcherWidget: LauncherWidgetOverride?,
  val lottie: LottieOverride?,
  val namedOverrides: Map<String, PreviewOverrideValue>?,
  /**
   * Whether [widthPx] / [heightPx] came back with their axes traded because [orientation]
   * contradicted the resolved frame.
   *
   * Callers that carry **per-axis** state alongside the frame have to rotate it in step, and they
   * can't re-derive the answer from the returned dimensions — those are already rotated, so asking
   * "does the orientation contradict this?" a second time always says no. `DesktopHost` is the case
   * in point: its `wrapWidth` / `wrapHeight` describe which axis wrap-contents, and a one-wrapped-
   * axis preview that rotates without swapping them measures the wrong axis and crops (#3552
   * review). Anything holding only whole-frame state can ignore this.
   */
  val rotated: Boolean = false,
) {
  /**
   * Project the merged overrides down to a [PreviewOverrides] bag that only carries fields
   * extensions consume. Returns `null` when no extension-driven override is set so the renderer can
   * skip the data-extension pipeline entirely.
   *
   * **`localeTag` exception.** Pseudolocale handling (`en-XA` / `ar-XB`) is in two halves: the
   * renderer rewrites the qualifier directly, but the around-composable wrap that pseudolocalises
   * `Resources.getText` (Android) / flips `LocalLayoutDirection` (Desktop) is driven by
   * `PseudolocalePreviewOverrideExtension`, which inspects `localeTag`. So when the caller set
   * *only* `localeTag = "en-XA"` we still need to flow the bag through the planner pipeline;
   * without this, locale-only overrides ran the qualifier rewrite but never installed the
   * around-composable, leaving strings un-pseudolocalised.
   */
  fun toExtensionOverrides(): PreviewOverrides? {
    val isPseudolocale = isPseudolocaleTag(localeTag)
    if (
      material3Theme == null &&
        wallpaper == null &&
        ambient == null &&
        gestures == null &&
        focus == null &&
        touchOverlay != true &&
        talkBack != true &&
        keyboard == null &&
        permissions == null &&
        remoteCompose == null &&
        launcherWidget == null &&
        lottie == null &&
        namedOverrides.isNullOrEmpty() &&
        !isPseudolocale
    ) {
      return null
    }
    return PreviewOverrides(
      material3Theme = material3Theme,
      wallpaper = wallpaper,
      ambient = ambient,
      gestures = gestures,
      focus = focus,
      localeTag = if (isPseudolocale) localeTag else null,
      touchOverlay = touchOverlay,
      talkBack = talkBack,
      keyboard = keyboard,
      permissions = permissions,
      remoteCompose = remoteCompose,
      launcherWidget = launcherWidget,
      lottie = lottie,
      namedOverrides = namedOverrides,
    )
  }
}

/**
 * Fill every **extension-consumed** field of this base spec from the discovery-time overrides bag
 * [carried] the host resolved alongside it (`RenderSpec.overrides`).
 *
 * The held-session lane (`interactive/start` → `stream/start` → `setOverrides`, and the recording
 * twin) reaches [mergePreviewOverrides] through each host's `applyOverrides`, which adapts its
 * backend-local `RenderSpec` into a [PreviewOverrideBaseSpec]. Every field the adapter forgets is a
 * field the live render silently loses — the merge fills it from `null`, [toExtensionOverrides]
 * projects `null` back out, and the renderer composes as if the base had never carried it. Both
 * hosts hand-picked a *subset* here, which is how a `@OverrideVariant` preview browsed in the
 * viewer's **Live** lane rendered its base state: `namedOverrides` — the baked seed
 * `renderSpecFromInfo` resolves from `previews.json` — was never copied across, so
 * `switchbutton__ideal__split` composed the un-split switch its primary draws
 * (yschimke/wear-m3-catalog#33). `focus`, `talkBack`, `touchOverlay`, `permissions`,
 * `remoteCompose`, `launcherWidget`, `keyboard` and `lottie` were dropped the same way.
 *
 * So the copy lives here, once, beside the field list it has to stay exhaustive over — a new
 * extension-consumed field is added in this file and both hosts inherit it, rather than being
 * hand-added to two adapters that already disagreed with each other. The per-render overlay still
 * wins per key: this is only the floor the overlay lands on. `PreviewOverrideMergeTest` guards the
 * round trip (populated bag → base spec → merge with no overlay → [toExtensionOverrides]).
 *
 * Display-geometry fields (`widthPx`, `density`, `uiMode`, …) are deliberately NOT touched: the
 * host resolves those from its own spec, where the discovery-time values already live.
 */
fun PreviewOverrideBaseSpec.withCarriedOverrides(
  carried: PreviewOverrides?
): PreviewOverrideBaseSpec =
  if (carried == null) this
  else
    copy(
      material3Theme = carried.material3Theme,
      wallpaper = carried.wallpaper,
      ambient = carried.ambient,
      gestures = carried.gestures,
      focus = carried.focus,
      touchOverlay = carried.touchOverlay,
      talkBack = carried.talkBack,
      keyboard = carried.keyboard,
      permissions = carried.permissions,
      remoteCompose = carried.remoteCompose,
      launcherWidget = carried.launcherWidget,
      lottie = carried.lottie,
      namedOverrides = carried.namedOverrides,
    )

/**
 * Fold a `themeProvider` FQN back onto an (optionally null) held-session overrides bag. The held /
 * recording spec's `overrides` is [MergedPreviewOverrides.toExtensionOverrides] — the
 * extension-only projection, which intentionally omits `themeProvider` (a renderer-read field, not
 * an extension-consumed one). But the renderer reads `spec.overrides.themeProvider` directly, so a
 * live `stream/start` / `setOverrides` carrying a theme selection would otherwise drop it and keep
 * the default wrapper. Both hosts' `applyOverrides` call this to carry the selection through,
 * mirroring how `clearBackground` is carried onto the held spec. A blank / null FQN is a no-op.
 */
fun PreviewOverrides?.withThemeProvider(themeProvider: String?): PreviewOverrides? =
  if (themeProvider.isNullOrBlank()) this
  else (this ?: PreviewOverrides()).copy(themeProvider = themeProvider)

/**
 * Carry the wrapped-axis size bounds (the Max / Min / Within controls) onto a held-session
 * overrides bag. Like `themeProvider`, these are **renderer-read** fields — the desktop
 * `RenderEngine` reads `spec.overrides.{min,max}{Width,Height}Px` directly — so
 * `toExtensionOverrides()` (the extension-only projection) drops them. Without this a live
 * `stream/start` / `setOverrides` carrying a size-mode change would fall back to the unbounded
 * wrap. Mirrors [withThemeProvider] and the `clearBackground` carry in each host's
 * `applyOverrides`. A [source] with no bounds set is a no-op.
 */
fun PreviewOverrides?.withSizeBounds(source: PreviewOverrides?): PreviewOverrides? {
  if (
    source?.minWidthPx == null &&
      source?.minHeightPx == null &&
      source?.maxWidthPx == null &&
      source?.maxHeightPx == null
  ) {
    return this
  }
  return (this ?: PreviewOverrides()).copy(
    minWidthPx = source.minWidthPx,
    minHeightPx = source.minHeightPx,
    maxWidthPx = source.maxWidthPx,
    maxHeightPx = source.maxHeightPx,
  )
}

/**
 * Put a pseudolocale [localeTag] (`en-XA` / `ar-XB`) back onto an (optionally null) extension bag,
 * so the renderer's `PreviewOverrideExtensions.plan(spec.overrides)` sees the tag the spec carries.
 *
 * **Why the renderer has to do this.** `localeTag` reaches a backend as a **typed wire token**
 * (`…;localeTag=ar-XB;…`), which `RenderSpec.parseFromPayload` reads into `spec.localeTag`.
 * `JsonRpcServer.encodeRenderPayload` deliberately nulls every tokenised field out of the base64
 * `overrides=<bag>` it emits alongside, so nothing travels twice — and
 * `PreviewOverridesEncodingCompletenessTest.tokenisedFieldsAreNotRestatedInTheBag` pins that. But
 * `PseudolocalePreviewOverrideExtension` (Android) / `…Desktop` plan off the **bag**, so on every
 * daemon lane the planner was handed `localeTag = null` and abstained: the qualifier / `LocaleList`
 * half of the override applied, the around-composable that pseudolocalises `stringResource(...)`
 * and flips `LocalLayoutDirection` for `ar-XB` never did. `?localeTag=ar-XB` on the preview server
 * rendered plain LTR English and looked like the feature was off (#4371). The Gradle path never had
 * the bug — `RobolectricRenderTest` plans from `params.locale` directly, which is why the baked
 * catalog PNGs are right and only the live daemon lane was wrong.
 *
 * So the rehydration lives at the **renderer**, where `spec.localeTag` and `spec.overrides` meet:
 * one seam covering every payload producer (the JSON-RPC encoder, both `PreviewManifestRouter`s
 * forwarding a manifest-declared `@Preview(locale = "en-XA")`, the CLI), rather than an emission
 * rule each of them has to remember. Idempotent, so the held-session lane — which already carries
 * the tag through [MergedPreviewOverrides.toExtensionOverrides]'s pseudolocale exception — passes
 * through unchanged.
 *
 * Only pseudolocales are folded back: a real locale (`de`, `ar`, `zh-Hant-TW`) has no bag-consuming
 * planner, and restating it would put a tokenised field back in the bag for no reader.
 */
fun PreviewOverrides?.withPseudolocaleFrom(localeTag: String?): PreviewOverrides? =
  if (!isPseudolocaleTag(localeTag)) this
  else (this ?: PreviewOverrides()).copy(localeTag = localeTag)

/**
 * Hard-coded duplicate of `Pseudolocale.fromTag(...) != null`. Inlined here so `:daemon:core` (the
 * protocol module) doesn't take a dependency on `:data-pseudolocale-core` just to gate the bag
 * projection in [MergedPreviewOverrides.toExtensionOverrides]. If new pseudolocale tags ever land,
 * keep this list in sync with the `Pseudolocale` enum's `tag` values.
 */
private fun isPseudolocaleTag(tag: String?): Boolean {
  if (tag.isNullOrBlank()) return false
  val normalized = tag.replace('_', '-').lowercase()
  return normalized == "en-xa" || normalized == "ar-xb"
}

/**
 * Layer a sparse per-call [PreviewOverrides] bag ([this], the winner) over a discovery-time base
 * bag ([base]), field by field: the per-call value wins where set, [base] fills every unset field,
 * and `namedOverrides` merges **per-key** (not whole-map replace) — the same rule
 * [mergePreviewOverrides] applies, so editing one knob never drops the other seeds the base already
 * declared. A null receiver or [base] degenerates to the other side.
 *
 * Used by the previewId render path
 * ([ee.schimke.composeai.daemon.DesktopHost.specFromPreviewIdPayload]): the resolved base spec can
 * carry a theme / wallpaper / named seeds in `RenderSpec.overrides` (interactive / recording
 * resolvers construct such specs, and a future manifest could seed them), while a `?knob.<key>=…`
 * edit arrives as a sparse bag carrying only the changed knob. Replacing wholesale would drop the
 * base's other overrides; this layers them. Today's production serve resolver
 * (`renderSpecFromInfo`) leaves `.overrides` null, so this is a no-op there — but the merge keeps
 * the seam correct if that changes.
 *
 * The field list must stay exhaustive over [PreviewOverrides]; a forgotten field silently drops its
 * base value. `PreviewOverrideMergeTest.layeredOver…` guards this by asserting an empty overlay
 * over a fully-populated base round-trips to the base unchanged.
 */
fun PreviewOverrides?.layeredOver(base: PreviewOverrides?): PreviewOverrides? {
  val over = this ?: return base
  if (base == null) return over
  return PreviewOverrides(
    widthPx = over.widthPx ?: base.widthPx,
    heightPx = over.heightPx ?: base.heightPx,
    minWidthPx = over.minWidthPx ?: base.minWidthPx,
    minHeightPx = over.minHeightPx ?: base.minHeightPx,
    maxWidthPx = over.maxWidthPx ?: base.maxWidthPx,
    maxHeightPx = over.maxHeightPx ?: base.maxHeightPx,
    density = over.density ?: base.density,
    localeTag = over.localeTag ?: base.localeTag,
    fontScale = over.fontScale ?: base.fontScale,
    uiMode = over.uiMode ?: base.uiMode,
    orientation = over.orientation ?: base.orientation,
    device = over.device ?: base.device,
    captureAdvanceMs = over.captureAdvanceMs ?: base.captureAdvanceMs,
    clockEpochMillis = over.clockEpochMillis ?: base.clockEpochMillis,
    inspectionMode = over.inspectionMode ?: base.inspectionMode,
    slotMode = over.slotMode ?: base.slotMode,
    placeholderActive = over.placeholderActive ?: base.placeholderActive,
    clearBackground = over.clearBackground ?: base.clearBackground,
    material3Theme = over.material3Theme ?: base.material3Theme,
    themeProvider = over.themeProvider ?: base.themeProvider,
    wallpaper = over.wallpaper ?: base.wallpaper,
    ambient = over.ambient ?: base.ambient,
    gestures = over.gestures ?: base.gestures,
    focus = over.focus ?: base.focus,
    touchOverlay = over.touchOverlay ?: base.touchOverlay,
    talkBack = over.talkBack ?: base.talkBack,
    keyboard = over.keyboard ?: base.keyboard,
    permissions = over.permissions ?: base.permissions,
    remoteCompose = over.remoteCompose ?: base.remoteCompose,
    launcherWidget = over.launcherWidget ?: base.launcherWidget,
    lottie = over.lottie ?: base.lottie,
    // Per-key merge (not whole-map replace): a follow-up edit of one knob keeps the base's other
    // seeds. Overlay entries win over base entries with the same key. Mirrors
    // mergePreviewOverrides.
    namedOverrides =
      if (base.namedOverrides == null && over.namedOverrides == null) null
      else (base.namedOverrides ?: emptyMap()) + (over.namedOverrides ?: emptyMap()),
  )
}

/**
 * Merge per-call [PreviewOverrides] over a discovery-time spec.
 *
 * Explicit `widthPx` / `heightPx` / `density` overrides win over `device`-resolved values. Device
 * resolution matches `renderNow.overrides.device`: resolve the supplied device id/spec, derive
 * pixels from its dp geometry at the effective density, then let explicit pixel dimensions replace
 * either axis.
 */
fun mergePreviewOverrides(
  base: PreviewOverrideBaseSpec,
  overrides: PreviewOverrides?,
): MergedPreviewOverrides {
  if (overrides == null) {
    return MergedPreviewOverrides(
      widthPx = base.widthPx,
      heightPx = base.heightPx,
      density = base.density,
      device = base.device,
      localeTag = base.localeTag,
      fontScale = base.fontScale,
      uiMode = base.uiMode,
      orientation = base.orientation,
      inspectionMode = base.inspectionMode,
      material3Theme = base.material3Theme,
      wallpaper = base.wallpaper,
      ambient = base.ambient,
      gestures = base.gestures,
      focus = base.focus,
      touchOverlay = base.touchOverlay,
      talkBack = base.talkBack,
      keyboard = base.keyboard,
      permissions = base.permissions,
      remoteCompose = base.remoteCompose,
      launcherWidget = base.launcherWidget,
      lottie = base.lottie,
      namedOverrides = base.namedOverrides,
    )
  }
  val deviceOverride = overrides.device?.takeIf { it.isNotBlank() }
  val deviceSpec = deviceOverride?.let { DeviceDimensions.resolve(it) }
  val effectiveDensity = overrides.density ?: deviceSpec?.density ?: base.density
  val naturalWidthPx =
    overrides.widthPx
      ?: deviceSpec?.let { (it.widthDp * effectiveDensity).toInt().coerceAtLeast(1) }
      ?: base.widthPx
  val naturalHeightPx =
    overrides.heightPx
      ?: deviceSpec?.let { (it.heightDp * effectiveDensity).toInt().coerceAtLeast(1) }
      ?: base.heightPx
  val effectiveOrientation = overrides.orientation ?: base.orientation
  // Rotate the frame when the effective orientation contradicts it (#3547) — the live-session
  // (`stream/start`, `setOverrides`) twin of the same swap in `JsonRpcServer.encodeRenderPayload`
  // and both routers, so the viewer's Orientation control means the same thing on every lane.
  // Explicit `widthPx` / `heightPx` outrank it; `device` does not, being the frame under rotation.
  // Safe to apply over an already-rotated base because `orientedPx` only swaps a frame that
  // contradicts the request.
  val orientationCanSwap = overrides.widthPx == null && overrides.heightPx == null
  val (widthPx, heightPx) =
    if (orientationCanSwap)
      FrameOrientation.orientedPx(naturalWidthPx, naturalHeightPx, effectiveOrientation)
    else naturalWidthPx to naturalHeightPx
  return MergedPreviewOverrides(
    widthPx = widthPx,
    heightPx = heightPx,
    rotated = widthPx != naturalWidthPx || heightPx != naturalHeightPx,
    density = effectiveDensity,
    device = deviceOverride ?: base.device,
    localeTag = overrides.localeTag?.takeIf { it.isNotBlank() } ?: base.localeTag,
    fontScale = overrides.fontScale ?: base.fontScale,
    uiMode = overrides.uiMode ?: base.uiMode,
    orientation = effectiveOrientation,
    inspectionMode = overrides.inspectionMode ?: base.inspectionMode,
    material3Theme = overrides.material3Theme ?: base.material3Theme,
    wallpaper = overrides.wallpaper ?: base.wallpaper,
    ambient = overrides.ambient ?: base.ambient,
    gestures = overrides.gestures ?: base.gestures,
    focus = overrides.focus ?: base.focus,
    touchOverlay = overrides.touchOverlay ?: base.touchOverlay,
    talkBack = overrides.talkBack ?: base.talkBack,
    keyboard = overrides.keyboard ?: base.keyboard,
    permissions = overrides.permissions ?: base.permissions,
    remoteCompose = overrides.remoteCompose ?: base.remoteCompose,
    launcherWidget = overrides.launcherWidget ?: base.launcherWidget,
    lottie = overrides.lottie ?: base.lottie,
    // Per-key merge (not whole-map replace): editing one knob in a follow-up render must not drop
    // the
    // others the caller already set. Override entries win over base entries with the same key.
    namedOverrides =
      if (base.namedOverrides == null && overrides.namedOverrides == null) null
      else (base.namedOverrides ?: emptyMap()) + (overrides.namedOverrides ?: emptyMap()),
  )
}
