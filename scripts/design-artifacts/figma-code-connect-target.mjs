/**
 * Resolve the **production composable** a catalog sticker renders, so Code Connect points at the real
 * component (`DeviceSummaryCard`) instead of the zero-arg `@Preview` wrapper
 * (`DeviceSummaryCardPopulatedPreview`) that nobody calls in real code.
 *
 * Discovery already infers this: each preview in the bundle's `previews.json` carries a
 * `targets: [{ className, functionName, sourceFile, confidence, signals }]` list (see
 * `PreviewInfo.targets` / `PreviewTargetInference`), most-confident first, inferred by walking the
 * preview's bytecode for the project-local `@Composable` call it renders. This module lifts that per
 * function so the emit join can prefer it over the preview name.
 *
 * Read robustly, independent of whether the private bundle reader preserves the `targets` field:
 * first from the parsed `bundle.previews[].targets`, then — if none survived parsing — from the raw
 * `previews.json` entry in the bundle. Pure (no IO); unit-tested against both shapes.
 */

/**
 * The best target for a preview: the first (most-confident) entry, since discovery orders them
 * most-confident first and v1 emits at most one. Returns `{ functionName, sourceFile, confidence }`
 * or null when the preview carried no inferred target (nothing cleared the confidence threshold).
 */
export function bestTarget(targets) {
  const t = Array.isArray(targets) ? targets[0] : undefined;
  if (!t || !isValidKotlinIdentifier(t.functionName)) return null;
  return {
    functionName: t.functionName,
    className: t.className ?? undefined,
    sourceFile: t.sourceFile ?? undefined,
    confidence: t.confidence ?? undefined,
    // The target composable's real value parameters (name / type / hasDefault / composableSlot),
    // recovered from its Kotlin metadata — the raw material for a real call site. Empty when the
    // signature couldn't be read.
    parameters: Array.isArray(t.parameters) ? t.parameters : [],
  };
}

/** Conservative source-level Kotlin identifier check for generated call sites/imports. */
export function isValidKotlinIdentifier(value) {
  return typeof value === "string" && /^[A-Za-z_][A-Za-z0-9_]*$/.test(value);
}

/** Parse the raw `previews.json` entry (a `{ previews: [...] }` manifest or a bare array). */
function parsePreviewsEntry(bundle) {
  const bytes = bundle?.entries?.["previews.json"];
  if (!bytes) return [];
  try {
    const parsed = JSON.parse(new TextDecoder().decode(bytes));
    return Array.isArray(parsed) ? parsed : (parsed.previews ?? []);
  } catch {
    return [];
  }
}

/**
 * Map `functionName → { functionName, sourceFile, confidence }` for the composable each preview
 * renders. Prefers the light variant (matching the catalog's light-themed sticker) for a
 * deterministic pick when a function has several theme/size variants. A function whose previews
 * carried no target is simply absent — the emit join then falls back to the preview name.
 */
export function targetsByFunction(bundle) {
  const out = new Map();
  const prefer = (id) => /(_|\b)light$/i.test(String(id ?? ""));
  const ingest = (previews) => {
    for (const p of previews ?? []) {
      const fn = p.functionName ?? p.id;
      if (!fn) continue;
      const target = bestTarget(p.targets);
      if (!target) continue;
      if (out.has(fn) && !prefer(p.id)) continue;
      out.set(fn, target);
    }
  };
  const parsedPreviews = bundle?.previews ?? [];
  // Parsed previews first (the common path when the reader preserves `targets`)…
  ingest(parsedPreviews);
  // …then the raw manifest, so a reader that dropped the field still yields targets. Reuse the
  // parsed preview's function identity by id: repository-wide publication namespaces duplicate
  // functions on the parsed bundle, while the embedded previews.json intentionally stays raw.
  const parsedFunctionById = new Map(
    parsedPreviews.map((preview) => [preview.id, preview.functionName ?? preview.id]),
  );
  ingest(
    parsePreviewsEntry(bundle).map((preview) => ({
      ...preview,
      functionName: parsedFunctionById.get(preview.id) ?? preview.functionName,
    })),
  );
  return out;
}
