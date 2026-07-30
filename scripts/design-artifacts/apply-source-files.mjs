/**
 * Re-stamp each component's `sourceFile` (the module-relative path of the `@Preview`
 * function's source file) onto the built catalog manifest.
 *
 * Discovery records `sourceFile` per preview in the bundle's `previews.json`, but the
 * pinned `@design-parity/catalog-export` (`buildCatalog`) never reads it, so it doesn't
 * survive onto the emitted component. Without it the preview server has no per-preview
 * source path to link to — the catalog viewer's "source" link can't be built. This closes
 * that gap in the same post-process pass that stamps `section` / `livePreview` / `display`
 * onto the written `catalog.json` (mirrors {@link file://./apply-spec-sections.mjs}).
 *
 * The path is resolved by joining two lookups: the spec maps each `componentId` to the
 * `preview` function it renders, and [sourceByFn] (built by the generator's
 * `sourceByFunction`) maps a function name to its recorded `sourceFile`. A component whose
 * function carried no path (discovery didn't record one, or an older bundle) is left
 * untouched — the server then simply renders no link for it.
 *
 * Additive and idempotent:
 *  - only components whose spec function resolves to a `sourceFile` are touched;
 *  - a component that already carries a `sourceFile` is left as-is (never clobbered).
 *
 * The `sourceFile` is module-relative (`src/main/kotlin/…/Foo.kt`); the server prefixes it
 * with the catalog's source `module` when building the GitHub blob URL, so this stays the
 * bare path the discovery manifest recorded.
 *
 * @param {{components?: Array<{componentId: string, sourceFile?: string}>}} manifest
 *   The parsed `catalog.json`, mutated in place.
 * @param {{groups?: Array<{components?: Array<{componentId: string, preview?: string}>}>}} spec
 *   The catalog spec the manifest was built from.
 * @param {Map<string, {sourceFile?: string}>} sourceByFn
 *   Function-name → `{ sourceFile }` lookup (the generator's `sourceByFunction(bundle)`).
 * @returns {number} how many components had a `sourceFile` newly stamped.
 */
export function applySourceFiles(manifest, spec, sourceByFn) {
  if (!sourceByFn || sourceByFn.size === 0) return 0;

  const previewByComponentId = new Map();
  for (const group of spec?.groups ?? []) {
    for (const component of group.components ?? []) {
      if (component.preview) {
        previewByComponentId.set(component.componentId, component.preview);
      }
    }
  }

  let stamped = 0;
  for (const component of manifest?.components ?? []) {
    if (component.sourceFile !== undefined) continue; // never clobber an existing path
    const fn = previewByComponentId.get(component.componentId);
    const sourceFile = fn ? sourceByFn.get(fn)?.sourceFile : undefined;
    if (sourceFile) {
      component.sourceFile = sourceFile;
      stamped += 1;
    }
  }
  return stamped;
}
