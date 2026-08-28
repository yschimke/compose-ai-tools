/**
 * Lift authored, per-preview controls out of preview bundles and onto catalog images.
 *
 * A catalog's monolithic live daemon only knows the primary bundle. Supplement-only previews are
 * rendered by lazily-opened per-preview bundles, so their declarations must be discoverable from
 * catalog.json before that daemon is opened. The publisher already has both bundles in memory;
 * recording the small declaration payloads here keeps catalog loading lazy on the serve side.
 */

const decoder = new TextDecoder();

function sidecarDeclarations(bundle, previewId, suffix) {
  const bytes = bundle?.entries?.[`previews/${previewId}.${suffix}.json`];
  if (!bytes) return [];
  try {
    const payload = JSON.parse(decoder.decode(bytes));
    return Array.isArray(payload?.declarations) ? payload.declarations : [];
  } catch {
    return [];
  }
}

/**
 * Whether a `@Preview(locale = …)` render was composed right-to-left — the direction the renderer
 * resolved a gutter's leading / trailing edges against.
 *
 * `Intl.Locale#textInfo` rather than a hand-kept language list: it agrees with the renderer's
 * `LocaleDirection` on every tag that reaches here (`ar`, `iw`, `fa`, `ur`, `ckb`, `yi`, …) and on
 * the bidi pseudolocale `ar-XB`, whose Arabic base language it reads through, while `en-XA` stays
 * left-to-right. A second copy of that language table is a thing to drift, and this needs none.
 */
function rendersRightToLeft(locale) {
  if (typeof locale !== "string" || locale.trim() === "") return false;
  try {
    // `_` to `-` first. A catalog spells a locale the way the annotation did, and `ar_XB` / `ar_EG`
    // are supported spellings — `Intl.Locale` throws on them, the catch below reads that as LTR,
    // and an asymmetric gutter is then published with its left and right edges swapped. The
    // renderer does not have this problem because `Pseudolocale.fromTag` and
    // `LocaleDirection.isRtl` both normalise the separator before they look; this is the publisher
    // agreeing with the pixels rather than with the string it was handed.
    return new Intl.Locale(locale.trim().replace(/_/g, "-")).textInfo?.direction === "rtl";
  } catch {
    return false;
  }
}

/**
 * A preview's declared `@CaptureGutter`, resolved to **physical edges in render pixels**, or null
 * when it declares none (or every edge is zero).
 *
 * Two resolutions happen here, and both exist because the reader can do neither:
 *
 * *Pixels, not dp.* `presentationParams` deliberately drops density (it is baked into the image),
 * and the gutter is only useful next to the image's own pixel dimensions. Each edge rounds on its
 * own, which is the rule the renderer used when it grew the canvas — so `4dp` at 2.625 comes back
 * as the same 11px the render actually carries.
 *
 * *Left/right, not start/end.* The annotation declares leading / trailing, and the renderer placed
 * them against the layout direction of the locale it composed in — so on an RTL capture `start` is
 * the right-hand margin. A consumer sees pixels, not a direction, so it would have to guess;
 * resolving it here means the published record is about the image rather than about the
 * annotation.
 */
export function captureGutterPx(gutter, { density, locale } = {}) {
  if (!gutter) return null;
  // **No density, no record.** This converts dp to render pixels, and a render whose manifest
  // leaves `density` null did not use 1x: the Android gutter path falls back to `2.0f`
  // (`RobolectricRenderTest`) and the ordinary render spec to `DeviceDimensions.DEFAULT_DENSITY`,
  // 2.625f. Publishing as though it were 1x subtracts a third to a half of the real margin, and a
  // consumer cannot tell a wrong crop from a right one — it just draws the component at the wrong
  // size, which is the complaint the gutter exists to answer.
  //
  // Guessing is not available either: the two backends fall back differently and this publisher
  // does not reliably know which produced the PNG. So it declines. A preview with no density keeps
  // the behaviour it had before gutters existed — the whole canvas, un-cropped — rather than a
  // cropped one that is confidently off by 2x.
  if (!(Number.isFinite(density) && density > 0)) return null;
  const scale = density;
  const px = (dp) => Math.round(Math.max(0, Number(dp) || 0) * scale);
  const rtl = rendersRightToLeft(locale);
  const out = {
    left: px(rtl ? gutter.end : gutter.start),
    top: px(gutter.top),
    right: px(rtl ? gutter.start : gutter.end),
    bottom: px(gutter.bottom),
  };
  return out.left || out.top || out.right || out.bottom ? out : null;
}

/**
 * The subset of a preview's `@Preview` params that decides how it is PRESENTED — the ground behind
 * it and the device frame around it — or null when it states none of them.
 *
 * Deliberately not the whole params record: locale, font scale and density describe how the render
 * was produced and are already baked into the pixels, so republishing them would grow every
 * catalog for no reader. These are the ones a browse surface has to know *before* it opens
 * anything, because they decide what it paints behind the image and what shape it clips it to.
 *
 * Omitting an all-defaults record keeps the catalog quiet for the ordinary preview: a component
 * sticker states no device and no background, and a `previewParams: {}` on every image would be
 * pure noise. Kotlin reads a missing record back as null and keeps its existing behaviour.
 */
function presentationParams(params) {
  if (!params) return null;
  const out = {};
  if (params.uiMode) out.uiMode = params.uiMode;
  if (params.showBackground === true) out.showBackground = true;
  if (params.backgroundColor) out.backgroundColor = params.backgroundColor;
  if (typeof params.device === "string" && params.device.trim() !== "") out.device = params.device;
  // The dp ride along ONLY with a device, because that is the only thing they qualify: the frame
  // resolver applies them both-axes-or-neither against a named device, and on their own they say
  // nothing a consumer of this record can use.
  if (out.device) {
    if (Number.isFinite(params.widthDp)) out.widthDp = params.widthDp;
    if (Number.isFinite(params.heightDp)) out.heightDp = params.heightDp;
  }
  // The seventh, and the one that changes what a sheet DRAWS rather than what it paints behind:
  // a `@CaptureGutter` render's canvas is the component plus the gutter, so a consumer fitting the
  // whole canvas to a column draws the component smaller than its gutter-less siblings by exactly
  // that margin (m3-catalog#179). It can only subtract what it is told.
  const captureGutter = captureGutterPx(params.captureGutter, params);
  if (captureGutter) out.captureGutter = captureGutter;
  return Object.keys(out).length > 0 ? out : null;
}

/** Metadata that must be visible on the browse surface before a preview daemon is opened. */
export function declarationsByPreviewId(bundles) {
  const out = new Map();
  for (const bundle of Array.isArray(bundles) ? bundles : [bundles]) {
    if (!bundle) continue;
    for (const preview of bundle.previews ?? []) {
      if (!preview?.id || out.has(preview.id)) continue;
      const overrides = sidecarDeclarations(bundle, preview.id, "overrides");
      const remoteComposeKnobs = sidecarDeclarations(bundle, preview.id, "remotecompose");
      const captures = preview.captures ?? [];
      const supportsFocus = captures.some((capture) => capture?.focus || capture?.focusGif);
      const supportsGestures = captures.some((capture) => capture?.gestureHint);
      // `@FixedTheme` / a `@ThemeCatalog`-synthesised sheet: the browse surface must not re-render
      // this card under a theme override, and it has to know that from catalog.json alone —
      // deciding it lazily would mean the specimen re-themes until its daemon happens to be opened.
      const fixedTheme = preview.fixedTheme === true;
      // `@OverrideVariant(secondary = true)`: a cell that is rendered and compared but kept out of
      // the browse surface's variant tree. It rides here rather than on the image's `state`,
      // because it says nothing about WHICH cell this is — only about how prominently to list it —
      // and because the browse surface has to know it from catalog.json alone, before any daemon is
      // opened, exactly as `fixedTheme` does.
      const secondary = preview.overrides?.secondary === true;
      // What ground this render sits on and what device frame it was captured in. These live ONLY
      // in the bundle's root `previews.json`, which a published catalog does not stage — it carries
      // per-preview metadata on `previews/variants.json` instead. Without lifting them here, every
      // preview on the read-only serving path arrives with the annotation defaults, so the
      // per-preview backdrop falls back to the catalog's declared stage for all of them and the
      // device clip never resolves: a round Wear comparison is drawn on a square stage there and
      // nowhere else.
      const previewParams = presentationParams(preview.params);
      if (
        overrides.length > 0 ||
        remoteComposeKnobs.length > 0 ||
        supportsFocus ||
        supportsGestures ||
        fixedTheme ||
        secondary ||
        previewParams
      ) {
        out.set(preview.id, {
          ...(overrides.length > 0 ? { overrides } : {}),
          ...(remoteComposeKnobs.length > 0 ? { remoteComposeKnobs } : {}),
          ...(supportsFocus ? { supportsFocus: true } : {}),
          ...(supportsGestures ? { supportsGestures: true } : {}),
          ...(fixedTheme ? { fixedTheme: true } : {}),
          ...(secondary ? { secondary: true } : {}),
          ...(previewParams ? { previewParams } : {}),
        });
      }
    }
  }
  return out;
}

/** Stamp declarations onto every catalog image whose live-preview bridge names a daemon id. */
export function applyCatalogPreviewDeclarations(manifest, bundles) {
  const byId = declarationsByPreviewId(bundles);
  let stamped = 0;
  for (const component of manifest.components ?? []) {
    for (const image of component.images ?? []) {
      const declarations = byId.get(image.previewId);
      if (!declarations) continue;
      Object.assign(image, declarations);
      stamped += 1;
    }
  }
  // Deferred records carry no image — CI declared them live-only rather than rasterising them — so
  // the loop above never sees them. A live-only theme specimen needs the flag MORE than a baked
  // one: its only render is the live one, so without it the browse surface re-renders it under
  // every declared theme with no baked pixels to fall back to.
  //
  // Only `fixedTheme` and `secondary` are stamped here. The knob declarations describe controls the
  // viewer offers once a daemon is open, and a deferred record already resolves those through its
  // live twin; these two have to be known BEFORE anything is opened, because they decide whether
  // the card is asked to re-render at all and whether it is listed in the variant tree.
  //
  // `secondary` needs this as much as `fixedTheme` does. A second-tier cell that CI declared
  // live-only — because its catalog variant or theme took deferred priority — reaches the browse
  // surface through this loop and nowhere else, so leaving it out kept exactly those cells in the
  // variant tree: the flag went missing for the coverage it was added to thin out.
  for (const record of manifest.deferred ?? []) {
    const previewId = record.previewId ?? (record.previewIds?.length === 1 ? record.previewIds[0] : null);
    if (!previewId) continue;
    const declarations = byId.get(previewId);
    if (!declarations) continue;
    let carried = false;
    if (declarations.fixedTheme === true && record.fixedTheme !== true) {
      record.fixedTheme = true;
      carried = true;
    }
    if (declarations.secondary === true && record.secondary !== true) {
      record.secondary = true;
      carried = true;
    }
    if (carried) stamped += 1;
  }
  return stamped;
}
