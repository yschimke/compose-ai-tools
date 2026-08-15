/**
 * The `motion` axis of a published catalog component — the animated captures a component publishes
 * alongside its static sticker.
 *
 * ### Why this is its own axis rather than more `images[]`
 *
 * Everything in `images[]` is a still, and every consumer of that array assumes it: the sticker
 * sheet lays them out as a grid, the design-parity run diffs them against a Figma node, the Figma
 * import writes them into a frame. An animated capture is none of those things — there is no
 * meaningful "diff this APNG against a kit node", and a sheet that pasted a 114-frame recording in
 * as a sticker would publish its first frame and silently drop the point.
 *
 * So a motion capture rides beside the stills instead. The component keeps exactly the card it had;
 * `motion[]` is additive, and a consumer that has never heard of it is unaffected.
 *
 * ### What lands here
 *
 * Both halves of motion capture, which are the same artifact to a reader and differ only in what
 * moved the pixels:
 *   - `@InteractionPreview` — a scripted pointer gesture (`kind: "interaction"`)
 *   - `@AnimatedPreview` — a self-running animation (`kind: "animation"`)
 *
 * Each entry carries the caption its annotation declared, because a motion capture without one is
 * close to useless: the reader can see *that* something moved, and the caption is what tells them
 * which property they are being shown.
 *
 * Pure and dependency-free so it unit-tests without an `npm ci`, like its sibling axis modules.
 */

/** Artifact extensions a motion capture publishes, longest-first so `.apng` wins over `.png`. */
const MOTION_EXTENSIONS = [".apng", ".gif"];

/** The two ways a capture came to move. */
export const MOTION_KINDS = ["interaction", "animation"];

/** The preview function whose motion artifacts belong to a catalog component. */
export function motionPreviewFor(component) {
  return component?.motionPreview ?? component?.preview;
}

/**
 * The motion artifacts a bundle carries for one `@Preview` function.
 *
 * Matched off the bundle's own entry paths rather than off the manifest's capture list: the entry
 * is the artifact that actually exists, and a manifest that promised one the render never wrote
 * would otherwise publish a component pointing at a 404. The manifest is still consulted — for the
 * caption and the kind, which the filename cannot carry.
 *
 * @param {{entries?: Record<string, unknown>, previews?: Array<object>}} bundle
 * @param {string} functionName the `@Preview` function to collect for.
 * @returns {Array<{path: string, kind: string, caption?: string, theme?: string}>}
 */
export function motionArtifactsFor(bundle, functionName) {
  const out = [];
  for (const preview of bundle?.previews ?? []) {
    if ((preview.functionName ?? preview.id) !== functionName) continue;
    for (const capture of preview.captures ?? []) {
      const declaration = motionDeclarationOf(capture);
      if (!declaration) continue;
      const path = bundleEntryFor(bundle, preview.id, capture.renderOutput);
      if (!path) continue;
      out.push({
        path,
        kind: declaration.kind,
        ...(declaration.caption ? { caption: declaration.caption } : {}),
      });
    }
  }
  return out;
}

/**
 * The motion declaration on one capture, or `null` when it isn't a motion capture.
 *
 * An `@AnimatedPreview` that names no caption still counts — it is a real artifact and the viewer
 * can show it unlabelled — whereas a capture carrying neither annotation is not motion at all.
 */
export function motionDeclarationOf(capture) {
  if (capture?.interaction)
    return { kind: "interaction", caption: capture.interaction.caption || undefined };
  if (capture?.animation)
    return { kind: "animation", caption: capture.animation.caption || undefined };
  return null;
}

/**
 * The `previews/…` entry a motion capture landed at, or `undefined` when it never rendered.
 *
 * Tries the capture's own `renderOutput` leaf first, because a function carrying BOTH annotations
 * disambiguates them with an `_interaction` suffix and only the manifest knows which file is which.
 * Falls back to the plain `previews/<id>.<ext>`, which is where a capture that owns its function
 * outright lands.
 *
 * @param {object} bundle
 * @param {string} previewId
 * @param {string} [renderOutput] the manifest's `renders/<stem>.<ext>` path for this capture.
 */
function bundleEntryFor(bundle, previewId, renderOutput) {
  const candidates = [];
  const leaf = renderOutput?.slice(renderOutput.lastIndexOf("/") + 1);
  if (leaf) candidates.push(`previews/${leaf}`);
  for (const extension of MOTION_EXTENSIONS) candidates.push(`previews/${previewId}${extension}`);
  return candidates.find((path) => bundle?.entries?.[path]);
}

/**
 * Folds a component's motion artifacts onto it, keyed by the theme its render was baked under so the
 * viewer can show the capture matching the card the reader is looking at.
 *
 * The theme is read off the sibling **still** rather than off the motion artifact, deliberately: a
 * capture's own id carries the same `_Dark` / `_Light` suffix, but re-deriving it here would be a
 * second implementation of the mode-naming rule that `catalog-themes.mjs` already owns, and the two
 * would eventually disagree about a catalog with unusual mode names. Matching positionally against
 * the images the join already resolved keeps one rule.
 *
 * @param {Array<{path: string, theme?: string}>} images the component's baked stills.
 * @param {Array<{path: string, kind: string, caption?: string}>} artifacts from [motionArtifactsFor].
 * @returns {Array<object>} one motion entry per artifact, theme-tagged where it could be resolved.
 */
export function foldMotion(images, artifacts) {
  if (!artifacts?.length) return [];
  return artifacts.map((artifact) => {
    const theme = themeForArtifact(images, artifact.path);
    return {
      ...artifact,
      ...(theme !== undefined ? { theme } : {}),
    };
  });
}

/**
 * The theme of the still sharing this artifact's preview id.
 *
 * Both are named from the same preview, so `previews/Foo_Dark.apng` and `previews/Foo_Dark.png`
 * share every character but the extension — which is exactly the join, and needs no knowledge of
 * how modes are spelled.
 */
function themeForArtifact(images, artifactPath) {
  const stem = artifactPath.replace(/\.[^.]+$/, "");
  for (const image of images ?? []) {
    if (image?.path?.replace(/\.[^.]+$/, "") === stem) return image.theme;
  }
  return undefined;
}
