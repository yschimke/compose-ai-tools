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
      if (
        overrides.length > 0 ||
        remoteComposeKnobs.length > 0 ||
        supportsFocus ||
        supportsGestures ||
        fixedTheme
      ) {
        out.set(preview.id, {
          ...(overrides.length > 0 ? { overrides } : {}),
          ...(remoteComposeKnobs.length > 0 ? { remoteComposeKnobs } : {}),
          ...(supportsFocus ? { supportsFocus: true } : {}),
          ...(supportsGestures ? { supportsGestures: true } : {}),
          ...(fixedTheme ? { fixedTheme: true } : {}),
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
  return stamped;
}
