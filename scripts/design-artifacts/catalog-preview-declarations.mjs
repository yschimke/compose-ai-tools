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
 * The subset of a preview's `@Preview` params that decides how it is PRESENTED — the ground behind
 * it and the device frame around it — or null when it states none of them.
 *
 * Deliberately not the whole params record: locale, font scale and density describe how the render
 * was produced and are already baked into the pixels, so republishing them would grow every
 * catalog for no reader. These six are the ones a browse surface has to know *before* it opens
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
        previewParams
      ) {
        out.set(preview.id, {
          ...(overrides.length > 0 ? { overrides } : {}),
          ...(remoteComposeKnobs.length > 0 ? { remoteComposeKnobs } : {}),
          ...(supportsFocus ? { supportsFocus: true } : {}),
          ...(supportsGestures ? { supportsGestures: true } : {}),
          ...(fixedTheme ? { fixedTheme: true } : {}),
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
  // Only `fixedTheme` is stamped here. The knob declarations describe controls the viewer offers
  // once a daemon is open, and a deferred record already resolves those through its live twin;
  // `fixedTheme` is the one that has to be known BEFORE anything is opened, because it decides
  // whether the card is asked to re-render at all.
  for (const record of manifest.deferred ?? []) {
    const previewId = record.previewId ?? (record.previewIds?.length === 1 ? record.previewIds[0] : null);
    if (!previewId) continue;
    if (byId.get(previewId)?.fixedTheme !== true) continue;
    record.fixedTheme = true;
    stamped += 1;
  }
  return stamped;
}
