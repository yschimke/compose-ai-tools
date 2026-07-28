/**
 * Apply a catalog spec's named width breakpoints to candidate images.
 *
 * The candidate reader classifies numeric `widthDp` values with Material window
 * size classes (`compact` / `medium` / `expanded`). Catalog specs may declare a
 * more useful vocabulary for their domain, such as Wear's `smallRound` and
 * `largeRound`. Re-tag matching images after the bundle has been read so those
 * declared names become the catalog's size axis.
 *
 * @param {Array<{previewId?: string, componentId: string, images?: Array<object>}>} candidates
 * @param {Array<{id: string, params?: object, captures?: Array<{params?: object}>}>} previews
 * @param {Array<{size: string, widthDp: number}> | undefined} breakpoints
 * @returns {number} number of images assigned a declared breakpoint name
 */
export function applySpecBreakpoints(candidates, previews, breakpoints) {
  const sizeByWidth = new Map(
    (breakpoints ?? [])
      .filter(
        ({ size, widthDp }) =>
          typeof size === "string" &&
          size.length > 0 &&
          typeof widthDp === "number" &&
          Number.isFinite(widthDp),
      )
      .map(({ size, widthDp }) => [widthDp, size]),
  );
  if (sizeByWidth.size === 0) return 0;

  const previewById = new Map(previews.map((preview) => [preview.id, preview]));
  let applied = 0;
  for (const candidate of candidates) {
    const preview = previewById.get(candidate.previewId ?? candidate.componentId);
    if (!preview) continue;
    const captures =
      Array.isArray(preview.captures) && preview.captures.length > 0
        ? preview.captures
        : [{}];
    for (let index = 0; index < (candidate.images ?? []).length; index += 1) {
      const params = {
        ...(preview.params ?? {}),
        ...(captures[index]?.params ?? {}),
      };
      const size = sizeByWidth.get(params.widthDp);
      if (size === undefined) continue;
      candidate.images[index].size = size;
      applied += 1;
    }
  }
  return applied;
}
