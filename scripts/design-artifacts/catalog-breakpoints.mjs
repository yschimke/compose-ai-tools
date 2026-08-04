/**
 * Apply a catalog spec's named breakpoints to candidate images.
 *
 * The candidate reader classifies numeric `widthDp` values with Material window
 * size classes (`compact` / `medium` / `expanded`). Catalog specs may declare a
 * more useful vocabulary for their domain, such as Wear's `smallRound` and
 * `largeRound`. Re-tag matching images after the bundle has been read so those
 * declared names become the catalog's size axis.
 *
 * A breakpoint may be declared by `device` (the `@Preview(device = …)` id the render actually ran
 * under, e.g. `id:wearos_small_round`) as well as by `widthDp`. Prefer the device: it is the
 * annotation's own identity, carried verbatim through discovery from the multipreview expansion
 * table, whereas a width is a fingerprint that two devices can share and that a device the spec
 * never listed silently fails to match — leaving the image on the generic `compact` class and
 * collapsing two distinct renders onto one output axis. `widthDp` stays supported (and is still
 * worth declaring: the live-preview bridge scores a candidate annotation's width against it), but
 * it is now the fallback rather than the only key.
 *
 * @param {Array<{previewId?: string, componentId: string, images?: Array<object>}>} candidates
 * @param {Array<{id: string, params?: object, captures?: Array<{params?: object}>}>} previews
 * @param {Array<{size: string, widthDp?: number, device?: string}> | undefined} breakpoints
 * @returns {number} number of images assigned a declared breakpoint name
 */
export function applySpecBreakpoints(candidates, previews, breakpoints) {
  const sizeOf = breakpointMatcher(breakpoints);
  if (!sizeOf) return 0;

  let applied = 0;
  for (const { image, params } of eachCandidateImage(candidates, previews)) {
    const size = sizeOf(params);
    if (size === undefined) continue;
    image.size = size;
    applied += 1;
  }
  return applied;
}

/**
 * The `@Preview(device = …)` ids a render used that NO declared breakpoint names, sorted.
 *
 * A device id in the render is a deliberate statement that this capture is a different screen from
 * its siblings; if the spec's breakpoints don't name it, the image keeps the generic width class
 * and — for a multipreview whose expansions differ only by device — becomes indistinguishable from
 * the sibling that did match. That collapse used to surface much later, as an overwritten sticker
 * or a duplicate-axis failure, so report it up front. Only meaningful once a catalog declares
 * breakpoints at all: a catalog with none has opted out of the axis entirely.
 *
 * @param {Array<{id: string, params?: object, captures?: Array<{params?: object}>}>} previews
 * @param {Array<{size: string, widthDp?: number, device?: string}> | undefined} breakpoints
 * @returns {string[]}
 */
export function undeclaredBreakpointDevices(previews, breakpoints) {
  const sizeOf = breakpointMatcher(breakpoints);
  if (!sizeOf) return [];
  const undeclared = new Set();
  for (const preview of previews ?? []) {
    for (const params of eachCaptureParams(preview)) {
      if (typeof params.device !== "string" || params.device.length === 0) continue;
      if (sizeOf(params) === undefined) undeclared.add(params.device);
    }
  }
  return [...undeclared].sort();
}

/**
 * `params` → the declared breakpoint name, or undefined. Null when the spec declares no usable
 * breakpoint, so callers can skip the whole pass rather than walk every image for nothing.
 *
 * Exported because the live-preview bridge has to answer the same question in reverse (which
 * breakpoint did this candidate annotation render?), and a second implementation there would drift
 * from this one — which is precisely how a device-keyed breakpoint would end up tagging a baked
 * sticker while the live lane still resolved it by width.
 */
export function breakpointMatcher(breakpoints) {
  const named = (breakpoints ?? []).filter(
    ({ size }) => typeof size === "string" && size.length > 0,
  );
  const sizeByDevice = new Map(
    named
      .filter(({ device }) => typeof device === "string" && device.length > 0)
      .map(({ device, size }) => [device, size]),
  );
  const sizeByWidth = new Map(
    named
      .filter(({ widthDp }) => typeof widthDp === "number" && Number.isFinite(widthDp))
      .map(({ widthDp, size }) => [widthDp, size]),
  );
  if (sizeByDevice.size === 0 && sizeByWidth.size === 0) return null;
  return (params) => {
    // Device first: an image whose device IS declared must never fall through to a width lookup
    // that could name a different breakpoint (two round devices can render at the same width).
    if (typeof params?.device === "string") {
      const byDevice = sizeByDevice.get(params.device);
      if (byDevice !== undefined) return byDevice;
      // A declared-device catalog that also lists this device's siblings by width still resolves
      // through the width table below — the two keys are alternatives, not a mode switch.
    }
    return sizeByWidth.get(params?.widthDp);
  };
}

/** The captures of a preview, or a single implicit one for a preview that declares none. */
function capturesOf(preview) {
  return Array.isArray(preview?.captures) && preview.captures.length > 0
    ? preview.captures
    : [{}];
}

/**
 * The effective params of one capture: the preview's params, overlaid by the capture's own. An
 * index past the capture list falls back to the preview's params alone — a candidate can carry
 * more images than the manifest lists captures (a scroll/animation sequence), and those extra
 * frames come from the same annotation.
 */
function captureParams(preview, index) {
  return {
    ...(preview?.params ?? {}),
    ...(capturesOf(preview)[index]?.params ?? {}),
  };
}

/** Each capture's effective params. */
function* eachCaptureParams(preview) {
  for (let index = 0; index < capturesOf(preview).length; index += 1) {
    yield captureParams(preview, index);
  }
}

/** Each candidate image paired with the effective params of the capture that produced it. */
function* eachCandidateImage(candidates, previews) {
  const previewById = new Map((previews ?? []).map((preview) => [preview.id, preview]));
  for (const candidate of candidates ?? []) {
    const preview = previewById.get(candidate.previewId ?? candidate.componentId);
    if (!preview) continue;
    for (let index = 0; index < (candidate.images ?? []).length; index += 1) {
      yield { image: candidate.images[index], params: captureParams(preview, index) };
    }
  }
}

/**
 * Wear's standard round devices, keyed by the `@Preview(device = …)` ids the built-in multipreview
 * expansions carry (`PreviewDiscovery.BUILT_IN_MULTIPREVIEW_EXPANSIONS`) as well as by width.
 * `xlRound` and `smallSquare` are here even though `@WearPreviewDevices` fans out to only the two
 * round sizes: `@WearPreviewSquare` and a hand-written `@CatalogWearBreakpoints` reach them, and
 * an undeclared device is exactly the silent collapse this table exists to prevent.
 */
export const DEFAULT_WEAR_BREAKPOINTS = Object.freeze([
  Object.freeze({ size: "smallRound", widthDp: 192, device: "id:wearos_small_round" }),
  Object.freeze({ size: "largeRound", widthDp: 227, device: "id:wearos_large_round" }),
  Object.freeze({ size: "xlRound", widthDp: 240, device: "id:wearos_xl_round" }),
  Object.freeze({ size: "smallSquare", widthDp: 180, device: "id:wearos_square" }),
]);

/**
 * Resolve the breakpoint vocabulary for one catalog.
 *
 * Wear's standard device previews use 192 dp and 227 dp round displays. Without domain names the
 * generic candidate reader classifies both widths as Material `compact`, collapsing distinct
 * renders onto one output axis. Apply the standard Wear names when a catalog declares a Wear
 * Compose library and omits `breakpoints`; any explicit array (including `[]`) remains
 * authoritative.
 *
 * @param {{library?: string[], breakpoints?: Array<{size: string, widthDp?: number, device?: string}>}} spec
 * @returns {Array<{size: string, widthDp?: number, device?: string}> | undefined}
 */
export function catalogBreakpoints(spec) {
  if (Array.isArray(spec?.breakpoints)) return spec.breakpoints;
  const isWear = (spec?.library ?? []).some((dependency) =>
    dependency.startsWith("androidx.wear.compose:"),
  );
  return isWear ? DEFAULT_WEAR_BREAKPOINTS : undefined;
}
