import { findPreview } from "./preview-id-alias.mjs";

/**
 * Preserve preview parameters that the candidate reader does not currently expose as catalog
 * image axes, then remove duplicate renders produced by overlapping multi-preview annotations.
 *
 * Wear commonly stacks `@WearPreviewDevices` and `@WearPreviewFontScales` on one function. Both
 * annotations include the small-round/default-font render, while the remaining font-scale renders
 * differ only by `PreviewParams.fontScale`. The candidate reader otherwise represents all of them
 * as the same default/compact image, so the catalog either overwrites stickers or (correctly) trips
 * its duplicate-axis guard.
 *
 * Non-default font scales become a `props.fontScale` catalog axis. Exact render duplicates are
 * removed only when every effective preview/capture parameter matches after discarding `name` and
 * `group`, which label an annotation but do not change its pixels. The image's already-derived
 * catalog axes are part of the identity too, so synthetic states and other meaningful variants are
 * never collapsed merely because they share display parameters.
 *
 * @param {Array<{previewId?: string, componentId: string, functionName?: string, images?: Array<object>}>} candidates
 * @param {Array<{id: string, params?: object, captures?: Array<{params?: object}>}>} previews
 * @param {Map<string, string>} [aliases] raw preview id → bundle-entry id (see preview-id-alias.mjs)
 * @returns {{fontScales: number, duplicates: number}} applied axis and duplicate counts
 */
export function applyCatalogPreviewAxes(candidates, previews, aliases) {
  const previewById = new Map(previews.map((preview) => [preview.id, preview]));
  const seenByFunction = new Map();
  let fontScales = 0;
  let duplicates = 0;

  for (const candidate of candidates) {
    // Raw discovery id vs sanitised bundle-entry id — see preview-id-alias.mjs. Without this the
    // fontScale axis and the duplicate collapse silently skip every space-named preview.
    const preview = findPreview(
      previewById,
      candidate.previewId ?? candidate.componentId,
      aliases,
    );
    if (!preview) continue;
    const captures =
      Array.isArray(preview.captures) && preview.captures.length > 0
        ? preview.captures
        : [{}];
    const fn = candidate.functionName ?? candidate.componentId;
    const seen = seenByFunction.get(fn) ?? new Set();
    seenByFunction.set(fn, seen);

    candidate.images = (candidate.images ?? []).filter((image, index) => {
      const params = {
        ...(preview.params ?? {}),
        ...(captures[index]?.params ?? {}),
      };
      const fontScale = params.fontScale;
      if (typeof fontScale === "number" && Number.isFinite(fontScale) && fontScale !== 1) {
        image.props = { ...(image.props ?? {}), fontScale: formatFontScale(fontScale) };
        fontScales += 1;
      }

      const key = stableJson({
        params: renderParams(params),
        axes: {
          variant: image.variant ?? "ideal",
          state: image.state ?? "default",
          theme: image.theme ?? null,
          size: image.size ?? null,
          props: image.props ?? {},
        },
      });
      if (seen.has(key)) {
        duplicates += 1;
        return false;
      }
      seen.add(key);
      return true;
    });
  }

  return { fontScales, duplicates };
}

function formatFontScale(value) {
  return Number.isInteger(value) ? value.toFixed(1) : String(value);
}

function renderParams(params) {
  const { name: _name, group: _group, ...rendering } = params;
  return rendering;
}

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.entries(value)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([key, child]) => `${JSON.stringify(key)}:${stableJson(child)}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}
