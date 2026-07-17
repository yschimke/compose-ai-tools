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
 * The `import <package>.<Component>` line for a target, from its owner-class FQN. A top-level
 * composable's owner is the file facade (`com.x.ui.ButtonsKt`), so the package is the class FQN minus
 * its last segment. Returns null when the class FQN has no package.
 */
export function importFor(className, componentName) {
  if (!className || !componentName) return null;
  const pkg = className.split(".").slice(0, -1).join(".");
  return pkg ? `import ${pkg}.${componentName}` : null;
}

/**
 * Render a real Kotlin call site for a component from its parameters — the payload that turns a Code
 * Connect mapping from a bare `Foo()` into `Foo(bar = …, …)`.
 *
 * Only the **required** parameters (no default) form the minimal call; a defaulted parameter is
 * omitted, since a real call site normally would. Values are valid, copyable Kotlin: a function-typed
 * slot renders as `name = { }`; everything else as `name = TODO("Type")` — `TODO()` returns `Nothing`
 * (assignable to any parameter) so the snippet compiles as-is, with the type as the hint, for the
 * developer/agent to replace.
 *
 * @returns `{ codeSnippet, imports }` — `imports` is `[importLine]` or `[]`.
 */
export function renderCallSite(componentName, importLine, parameters = []) {
  const required = parameters.filter((p) => !p.hasDefault);
  const codeSnippet =
    required.length === 0
      ? `${componentName}()`
      : `${componentName}(\n` +
        required
          .map((p) =>
            p.composableSlot
              ? `    ${p.name} = { },`
              : `    ${p.name} = TODO(${JSON.stringify(p.type)}),`,
          )
          .join("\n") +
        `\n)`;
  return { codeSnippet, imports: importLine ? [importLine] : [] };
}

/**
 * Build the `code-connect.json` manifest object.
 *
 * `componentName`/`source` should point at the **production composable** the sticker renders — the
 * thing a designer/agent actually calls — not the zero-arg `@Preview` wrapper. Three sources, in
 * priority order (each mapping records which one won in `confidence`):
 *   1. **explicit** — a `component` (+ optional `import`/`source`) authored on the catalog-spec entry,
 *      surfaced here via `componentByComponentId`. Deterministic; use it where inference is uncertain.
 *   2. **inferred** — discovery's `PreviewTarget` for the preview function (`targetByFn`), carrying
 *      its own `HIGH`/`MEDIUM`/`LOW` confidence and the target's source file.
 *   3. **preview-fallback** — the `@Preview` function itself, when neither of the above is available.
 *      Still a valid mapping, but review it before publishing.
 *
 * @param components   catalog components (each with a `componentId`).
 * @param fnByComponentId Map componentId → `@Preview` function name (from the catalog spec).
 * @param componentByComponentId optional Map componentId → `{ component, import?, source? }` — the
 *                     explicit spec override (source 1 above).
 * @param targetByFn   optional Map function → `{ functionName, sourceFile?, confidence? }` — the
 *                     inferred production composable (source 2 above), from `targetsByFunction`.
 * @param slug         componentId → slug (the same `slug()` the figma-svg emit uses), so a mapping
 *                     can link its editable vector at `figma/<slug>.svg`.
 * @param figmaSvgSlugs Set of slugs that actually carried a `figma/<slug>.svg` (so only real files
 *                     are linked).
 * @param sourceByFn   optional Map function → `{ sourceFile }` lifted from the bundle — the preview
 *                     function's own source file, used only for the preview-fallback source.
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
  componentByComponentId,
  targetByFn,
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
    const explicit = componentByComponentId?.get(componentId);
    const target = targetByFn?.get(fn);
    // Resolve the code symbol + its source file by priority (explicit > inferred > preview).
    let componentName;
    let sourceFile;
    let confidence;
    if (explicit?.component) {
      componentName = explicit.component;
      // Don't inherit the inferred target's file for an explicit component — the explicit override is
      // often there precisely to reject a wrong inference, so its sourceFile would point at the wrong
      // composable. Reuse it only when the inferred function IS the explicit component; otherwise use
      // the authored `source`, then the preview's own file, then (via resolveSource) the module.
      const inferredMatches = target?.functionName === explicit.component;
      sourceFile =
        explicit.source ??
        (inferredMatches ? target?.sourceFile : undefined) ??
        sourceByFn?.get(fn)?.sourceFile;
      confidence = "explicit";
    } else if (target?.functionName) {
      componentName = target.functionName;
      sourceFile = target.sourceFile ?? sourceByFn?.get(fn)?.sourceFile;
      confidence = target.confidence ?? "inferred";
    } else {
      componentName = fn;
      sourceFile = sourceByFn?.get(fn)?.sourceFile;
      confidence = "preview-fallback";
    }
    const mapping = {
      componentId,
      // The Figma layer name a node must carry to receive this mapping. The design-parity importer
      // names each frame by componentId, so this is that name verbatim — resolved to a node id at
      // publish time.
      figmaLayerName: componentId,
      componentName,
      source: resolveSource({ repo: source.repo, ref: source.ref, module: source.module, sourceFile }),
      label,
      // How componentName/source were resolved, so the publish/review step knows what to trust:
      // "explicit" | "HIGH" | "MEDIUM" | "LOW" | "preview-fallback".
      confidence,
      // The @Preview that rendered the sticker, always recorded for traceability even when the
      // mapping points at the underlying component.
      previewName: fn,
    };
    if (explicit?.import) mapping.import = explicit.import;
    // A real call site, but only when the emitted component IS the inferred target — then its captured
    // parameters genuinely belong to `componentName`. (An explicit override that names a different
    // component has no matching signature, so it stays a bare mapping for review.)
    if (target?.functionName && target.functionName === componentName) {
      const importLine = explicit?.import ?? importFor(target.className, componentName);
      const call = renderCallSite(componentName, importLine, target.parameters ?? []);
      mapping.codeSnippet = call.codeSnippet;
      if (call.imports.length > 0) mapping.imports = call.imports;
      if ((target.parameters?.length ?? 0) > 0) mapping.parameters = target.parameters;
    }
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
