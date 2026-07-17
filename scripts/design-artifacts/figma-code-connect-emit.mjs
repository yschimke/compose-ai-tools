/**
 * Emit a `code-connect.json` manifest alongside the layered `figma/<slug>.svg` vectors on a
 * design-catalog delivery branch.
 *
 * The catalog already carries everything Figma Code Connect needs *except* the Figma node id:
 * - `component.componentId` (e.g. `DeviceSummaryCard/Populated`) is the name the design-parity
 *   importer gives the Figma frame — so it is a stable join key from a mapping to a node.
 * - the catalog spec maps each componentId to the `@Preview` function that renders the sticker
 *   (`fnByComponentId`), which is the Compose symbol Code Connect should point at.
 * - the catalog's `--source-repo` / `--source-ref` / `--source-module` (already plumbed through
 *   `generate-design-catalog.mjs` as `manifest.source`) give the repo + ref to build a source URL.
 *
 * Node ids only exist *after* a designer imports the catalog into a Figma file, so this manifest is
 * keyed by `figmaLayerName` (= componentId) and the node id is resolved at publish time — see
 * `publish-code-connect.mjs`, which matches these names against an imported file's layer tree and
 * produces the `send_code_connect_mappings` payload.
 *
 * Pure and deterministic (data in, manifest out — no IO), so the driver can unit-test the join
 * without running the full export. Publishing itself requires a Figma Org/Enterprise plan with a
 * Dev/Full seat; emitting the manifest does not, so every catalog carries it.
 */

/** Code Connect framework/label for a Compose catalog. Matches Figma's `send_code_connect_mappings`
 *  label enum. */
export const COMPOSE_LABEL = "Compose";

/**
 * Resolve a mapping's `source` string — the location Code Connect points at for the component.
 *
 * Prefers a GitHub blob URL (matching the `codeConnectSrc` form Figma's `get_code_connect_map`
 * returns, e.g. `https://github.com/owner/repo/blob/<ref>/<path>`) when the repo + ref + a source
 * file are known. Falls back to a repo-relative path, then to the bare module. `sourceFile` is
 * module-root-relative (as discovery records it), so it is prefixed with the module directory
 * (`:app` → `app`, `:foo:bar` → `foo/bar`) to become repo-relative.
 */
export function resolveSource({ repo, ref, module, sourceFile } = {}) {
  const moduleDir = module ? String(module).replace(/^:/, "").replaceAll(":", "/") : "";
  const path = sourceFile ? (moduleDir ? `${moduleDir}/${sourceFile}` : sourceFile) : moduleDir;
  if (repo && ref && path) return `https://github.com/${repo}/blob/${ref}/${path}`;
  if (path) return path;
  return module ? String(module) : "";
}

/**
 * Build the `code-connect.json` manifest object.
 *
 * @param components   catalog components (each with a `componentId`).
 * @param fnByComponentId Map componentId → `@Preview` function name (from the catalog spec).
 * @param slug         componentId → slug (the same `slug()` the figma-svg emit uses), so a mapping
 *                     can link its editable vector at `figma/<slug>.svg`.
 * @param figmaSvgSlugs Set of slugs that actually carried a `figma/<slug>.svg` (so only real files
 *                     are linked).
 * @param sourceByFn   optional Map function → `{ sourceFile }` lifted from the bundle, so a mapping
 *                     can point at the exact preview source file rather than the bare module.
 * @param system/title system id + title, copied onto the manifest for provenance.
 * @param source       `{ repo, ref, module }` — the catalog's buildable-source pointer.
 * @param label        Code Connect label (default {@link COMPOSE_LABEL}).
 * @param generatedAt  ISO timestamp (injected by the caller so the pure builder stays deterministic).
 * @returns `{ system, title?, label, source, generatedAt?, mappings }`. A component whose preview
 *   function is unknown (not in the spec join) is skipped — there is nothing to point Code Connect at.
 */
export function buildCodeConnectManifest({
  components = [],
  fnByComponentId,
  slug,
  figmaSvgSlugs,
  sourceByFn,
  system,
  title,
  source = {},
  label = COMPOSE_LABEL,
  generatedAt,
} = {}) {
  const mappings = [];
  for (const component of components) {
    const componentId = component.componentId;
    const fn = fnByComponentId?.get(componentId);
    // No preview function ⇒ no code symbol to bind; skip rather than emit a dangling mapping.
    if (!fn) continue;
    const sourceFile = sourceByFn?.get(fn)?.sourceFile;
    const mapping = {
      componentId,
      // The Figma layer name a node must carry to receive this mapping. The design-parity importer
      // names each frame by componentId, so this is that name verbatim — resolved to a node id at
      // publish time.
      figmaLayerName: componentId,
      componentName: fn,
      source: resolveSource({ repo: source.repo, ref: source.ref, module: source.module, sourceFile }),
      label,
    };
    const s = slug?.(componentId);
    if (s && figmaSvgSlugs?.has(s)) mapping.figmaSvg = `figma/${s}.svg`;
    mappings.push(mapping);
  }

  const manifest = {
    system,
    ...(title ? { title } : {}),
    label,
    source: {
      ...(source.repo ? { repo: source.repo } : {}),
      ...(source.ref ? { ref: source.ref } : {}),
      ...(source.module ? { module: source.module } : {}),
    },
    ...(generatedAt ? { generatedAt } : {}),
    // Publishing turns these into Figma Code Connect records via `send_code_connect_mappings`
    // (or `figma connect`); it needs an Org/Enterprise plan + a Dev/Full seat. Review the
    // componentName/source before publishing — where a catalog `preview` is a thin wrapper, retarget
    // it to the underlying component.
    mappings,
  };
  return manifest;
}
