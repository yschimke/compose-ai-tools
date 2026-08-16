/**
 * Multi-module catalog helpers.
 *
 * A preview bundle is deliberately a one-module executable unit.  Publishing a repository-wide
 * catalog therefore keeps the bundles separate and combines their catalog candidates instead of
 * pretending their classpaths can be merged.  These helpers provide the small identity layer the
 * catalog join needs: deterministic duplicate-function names and generated groups for renders the
 * authored spec did not curate.
 */

/** The Gradle module that produced a bundle. */
export function bundleModulePath(bundle, fallback = ":unknown") {
  return bundle?.manifest?.modulePath ?? bundle?.manifest?.module ?? fallback;
}

/** The candidate/spec join key. */
function candidateFunction(candidate) {
  return candidate?.functionName ?? candidate?.componentId;
}

/** The preview record's function name. */
function previewFunction(preview) {
  return preview?.functionName ?? preview?.id;
}

/**
 * Namespace duplicate function names across module bundles.
 *
 * The primary record wins the familiar unqualified name, preserving authored specs. Additional
 * records are sorted by Gradle path and use `<module>::<function>` only when an earlier module has
 * already claimed that function. Unique names stay untouched. Bundle entry ids are not rewritten:
 * they are normally class-qualified already, and keeping them intact preserves daemon addressing.
 */
export function namespaceModuleRecords(primary, additional = []) {
  const ordered = [
    primary,
    ...additional.toSorted((a, b) =>
      bundleModulePath(a.bundle).localeCompare(bundleModulePath(b.bundle)),
    ),
  ];
  const claimed = new Map();
  return ordered.map((record) => {
    const module = bundleModulePath(record.bundle);
    const keyByFunction = new Map();
    for (const preview of record.bundle?.previews ?? []) {
      const fn = previewFunction(preview);
      if (keyByFunction.has(fn)) continue;
      const owner = claimed.get(fn);
      const key = owner && owner !== module ? `${module}::${fn}` : fn;
      keyByFunction.set(fn, key);
      if (!owner) claimed.set(fn, module);
    }
    for (const candidate of record.candidates ?? []) {
      const fn = candidateFunction(candidate);
      if (!keyByFunction.has(fn)) {
        const owner = claimed.get(fn);
        const key = owner && owner !== module ? `${module}::${fn}` : fn;
        keyByFunction.set(fn, key);
        if (!owner) claimed.set(fn, module);
      }
    }
    const previews = (record.bundle?.previews ?? []).map((preview) => ({
      ...preview,
      functionName: keyByFunction.get(previewFunction(preview)) ?? previewFunction(preview),
    }));
    const candidates = (record.candidates ?? []).map((candidate) => ({
      ...candidate,
      functionName:
        keyByFunction.get(candidateFunction(candidate)) ?? candidateFunction(candidate),
      module,
    }));
    return {
      ...record,
      module,
      candidates,
      bundle: { ...record.bundle, previews },
    };
  });
}

/** Preview functions already represented by the effective authored/annotation inventory. */
export function claimedPreviewFunctions(groups) {
  return new Set(
    (groups ?? []).flatMap((group) =>
      (group.components ?? []).flatMap((component) => [
        component.preview,
        component.motionPreview,
        ...(component.variants ?? []).map((variant) => variant.preview),
      ]),
    ).filter(Boolean),
  );
}

/** Component identities already owned by authored or annotation-derived inventory. */
export function claimedComponentIds(groups) {
  return new Set(
    (groups ?? []).flatMap((group) =>
      (group.components ?? []).map((component) => component.componentId).filter(Boolean),
    ),
  );
}

/** Merge per-bundle discovery metadata using the same later-bundle-wins precedence as renders. */
export function combinedBundleMap(bundles, mapBundle) {
  const combined = new Map();
  for (const bundle of bundles ?? []) {
    for (const [key, value] of mapBundle(bundle)) combined.set(key, value);
  }
  return combined;
}

/** Additional module bundles are baked-only until publication carries a real per-module live lane. */
export function additionalBundleLiveConflict(values) {
  if (!(values?.["additional-renders"]?.length > 0)) return null;
  const conflicts = [
    values["publish-live-bundle"] && "--publish-live-bundle",
    values["source-module"] && "--source-module",
  ].filter(Boolean);
  return conflicts.length > 0 ? conflicts : null;
}

function componentId(module, fn) {
  const modulePart = module.replace(/^:/, "").replaceAll(":", "/") || "root";
  return `${modulePart}/${fn}`;
}

/**
 * Generate spec-shaped fallback groups for every unclaimed rendered function.
 *
 * A module becomes the top-level section and `@Preview(group = …)` becomes its group. Functions
 * with no preview group land under `Previews`. Only candidates with a static image participate;
 * metadata-only and GIF-only previews have no sticker for the catalog exporter to anchor, while a
 * normal `@AnimatedPreview` that also carries a still is included and publishes its motion axis.
 */
export function generatedFallbackGroups(
  records,
  claimed = new Set(),
  reservedComponentIds = new Set(),
) {
  const groups = new Map();
  for (const record of records ?? []) {
    const module = record.module ?? bundleModulePath(record.bundle);
    const previewByFunction = new Map();
    for (const preview of record.bundle?.previews ?? []) {
      const fn = previewFunction(preview);
      if (!previewByFunction.has(fn)) previewByFunction.set(fn, preview);
    }
    const seen = new Set();
    for (const candidate of record.candidates ?? []) {
      const fn = candidateFunction(candidate);
      if (!fn || claimed.has(fn) || seen.has(fn) || !(candidate.images?.length > 0)) continue;
      seen.add(fn);
      const preview = previewByFunction.get(fn);
      const name = preview?.params?.group?.trim() || "Previews";
      const key = `${module}\u0000${name}`;
      let group = groups.get(key);
      if (!group) {
        group = { name, section: module, components: [] };
        groups.set(key, group);
      }
      const generatedId = componentId(module, fn);
      if (reservedComponentIds.has(generatedId)) {
        throw new Error(
          `generated fallback componentId '${generatedId}' collides with authored or generated inventory`,
        );
      }
      group.components.push({
        componentId: generatedId,
        preview: fn,
        ...(preview?.params?.name ? { caption: preview.params.name } : {}),
      });
      reservedComponentIds.add(generatedId);
      claimed.add(fn);
    }
  }
  return [...groups.values()];
}

/** First bundle wins on an entry collision, matching the candidate/spec precedence. */
export function combinedBundleEntries(bundles) {
  const entries = {};
  for (const bundle of bundles ?? []) {
    for (const [path, bytes] of Object.entries(bundle?.entries ?? {})) {
      if (!(path in entries)) entries[path] = bytes;
    }
  }
  return entries;
}
