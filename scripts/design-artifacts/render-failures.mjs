import { modeOfPreviewId } from "./catalog-priority.mjs";
import { slug } from "./render-wireframe-svg.mjs";

/** Canonical discovery id for an in-bundle (filename-safe) preview id. */
function rawPreviewId(bundle, id) {
  const at = bundle.manifest?.previewIds?.indexOf(id) ?? -1;
  return at >= 0 && bundle.manifest?.rawPreviewIds?.[at]
    ? bundle.manifest.rawPreviewIds[at]
    : id;
}

/** Lift schema-versioned renderer error sidecars into design-parity catalog records. */
export function renderFailuresFromBundles(bundles, spec) {
  const locations = new Map();
  for (const group of spec.groups ?? []) {
    for (const component of group.components ?? []) {
      const add = (preview, variant) => {
        if (!preview) return;
        const list = locations.get(preview) ?? [];
        list.push({ group, component, variant });
        locations.set(preview, list);
      };
      add(component.preview, null);
      for (const variant of component.variants ?? []) add(variant.preview, variant);
    }
  }

  const failures = [];
  const seen = new Set();
  for (const bundle of bundles.filter(Boolean)) {
    for (const preview of bundle.previews ?? []) {
      const bytes = bundle.entries?.[`previews/${preview.id}.error.json`];
      if (!bytes) continue;
      let error;
      try {
        error = JSON.parse(new TextDecoder().decode(bytes));
      } catch {
        continue;
      }
      if (error.schema !== "compose-preview-error/v1") continue;
      const fn = preview.functionName ?? preview.id;
      const matches = locations.get(fn) ?? [];
      const raw = rawPreviewId(bundle, preview.id);
      for (const match of matches.length
        ? matches
        : [{ group: {}, component: { componentId: fn } }]) {
        const componentId = match.component.componentId ?? fn;
        const key = `${componentId}\u0000${raw}\u0000${error.exception}\u0000${error.message}`;
        if (seen.has(key)) continue;
        seen.add(key);
        const mode = modeOfPreviewId(raw, spec.modes);
        failures.push({
          id: `render-failed--${slug(componentId)}--${slug(raw)}`,
          componentId,
          preview: raw,
          phase: error.phase ?? "render",
          errorClass: error.exception ?? "RenderError",
          message: error.message ?? "",
          ...(error.stackTrace ? { stackTrace: error.stackTrace } : {}),
          ...(error.topAppFrame ? { topAppFrame: error.topAppFrame } : {}),
          ...(match.group.name ? { group: match.group.name } : {}),
          ...(match.group.section ? { section: match.group.section } : {}),
          ...(match.variant?.state ? { state: match.variant.state } : {}),
          ...(match.variant?.props ? { props: match.variant.props } : {}),
          ...(match.variant?.theme ?? mode ? { mode: match.variant?.theme ?? mode } : {}),
          ...(preview.sourceFile ? { sourceFile: preview.sourceFile } : {}),
        });
      }
    }
  }
  return failures;
}
