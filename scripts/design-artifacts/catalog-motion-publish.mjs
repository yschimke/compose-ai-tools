/**
 * Publishing the `motion` axis onto the delivery branch.
 *
 * ### The gap this closes
 *
 * `catalog-motion.mjs` resolves a component's animated captures out of the render bundle and folds
 * them onto `components[].motion[]` — but the path it records there is the artifact's home *inside
 * the bundle* (`previews/<id>.apng`), because that is the only name it has at join time. The bundle
 * is not published; `design-artifacts/<system>` is. So a catalog shipped as-is declares motion whose
 * bytes exist nowhere a reader can reach, and every consumer downstream of the branch — the serve
 * viewer above all — resolves the path to a 404.
 *
 * This module names the branch-relative home for each artifact so the generator can copy the bytes
 * there and rewrite the declaration to match. After it runs, `motion[].path` means the same thing
 * `images[].path` already does: a file that is actually on the branch, relative to `catalog.json`.
 *
 * ### Why the name is inherited from the sibling still, not invented
 *
 * A motion capture and the sticker beside it depict the same component in the same theme, state and
 * size — the capture just shows it moving. `buildCatalog` has already resolved a collision-safe name
 * for that sticker (`images/<slug>/<variant>__<state>[__theme][__size][…].png`), against a naming
 * scheme that lives in another package. Deriving a second scheme here would be a second thing to
 * keep in step with it, and the two would eventually disagree about the same component.
 *
 * So the published motion path is the sticker's path with `images/` → `motion/` and the extension
 * swapped. Identical reasoning to the per-variant figma-svg emit in `generate-design-catalog.mjs`,
 * which mirrors `images/` → `figma/` for exactly this reason: naming that is *derived* from the
 * still cannot drift away from the still.
 *
 * Depends on nothing outside the standard library, so it unit-tests without an `npm ci` like its
 * sibling axis modules. [planMotionPublish] is pure on top of that — the naming rule, the part that
 * can silently drift away from `images/`, is testable without a bundle or an output directory.
 */

import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join } from "node:path";

import { catalogSlug } from "./catalog-image-path.mjs";

/** Artifact extensions a motion capture publishes — the same pair `catalog-motion.mjs` matches. */
const MOTION_EXTENSIONS = [".apng", ".gif"];

/**
 * The disambiguating suffixes `PreviewDiscovery` appends when one `@Preview` function carries a
 * motion annotation *and* something else that would claim the same filename — `_interaction` when an
 * interaction shares with a GIF-producing peer, `_anim` when an animation shares with a scroll /
 * timing fan-out or a `@FocusedPreview(gif = true)`. Both are appended to the preview id, so
 * stripping them recovers the id the sibling sticker is named from.
 *
 * `_focus_gif` is deliberately absent: a focused GIF carries neither an `interaction` nor an
 * `animation` declaration, so `motionDeclarationOf` never classifies it as motion and no artifact
 * reaches here under that name.
 */
const MOTION_SUFFIXES = ["_interaction", "_anim"];

/** The disambiguator [leaf] ends with, or `null` when it carries none. */
function motionSuffixOf(leaf) {
  return MOTION_SUFFIXES.find((suffix) => leaf.endsWith(suffix)) ?? null;
}

/** The branch directory motion artifacts publish under, beside `images/`. */
export const MOTION_DIR = "motion";

/**
 * The preview id of the **still** a bundle-internal motion artifact sits beside.
 *
 * `previews/Switch_Dark.apng` was rendered from preview `Switch_Dark`, whose sticker is
 * `previews/Switch_Dark.png` — so the leaf minus its extension is already the join key. The
 * exception is a function whose motion capture had to share its filename space, which
 * `PreviewDiscovery` disambiguates with a `_interaction` / `_anim` suffix; stripping that recovers
 * the same key.
 *
 * @param {string} artifactPath a bundle-relative `previews/…` path.
 * @returns {string|null} the sibling still's preview id, or `null` if this isn't a motion artifact.
 */
export function motionSiblingPreviewId(artifactPath) {
  const extension = motionExtensionOf(artifactPath);
  if (!extension) return null;
  const leaf = artifactPath.slice(artifactPath.lastIndexOf("/") + 1, -extension.length);
  const suffix = motionSuffixOf(leaf);
  return suffix ? leaf.slice(0, -suffix.length) : leaf;
}

/** The motion extension [artifactPath] ends with, or `null`. */
function motionExtensionOf(artifactPath) {
  return MOTION_EXTENSIONS.find((extension) => artifactPath?.endsWith(extension)) ?? null;
}

/**
 * The branch-relative path a motion artifact publishes at.
 *
 * With a sibling sticker resolved, the name is that sticker's:
 * `images/switch-on/ideal__default__dark.png` becomes
 * `motion/switch-on/ideal__default__dark.apng`, carrying the `__interaction` / `__anim`
 * disambiguator through as a variant segment when the artifact had one — so a function publishing
 * both an interaction and an animation can't have one silently overwrite the other.
 *
 * Without one (an image the live-preview bridge deliberately left unstamped, so no `previewId` to
 * join on) the artifact still publishes, under its own leaf in the component's directory. The bytes
 * reaching the branch matters more than the name being variant-shaped: an unresolved sibling is a
 * naming problem, whereas dropping the file is lost coverage.
 *
 * @param {string} componentId the owning component, for the fallback's directory.
 * @param {string} artifactPath the bundle-relative `previews/…` path.
 * @param {string} [imagePath] the sibling still's published `images/…` path, when one was resolved.
 * @returns {string|null} the `motion/…` path, or `null` if this isn't a motion artifact.
 */
export function motionPublishPath(componentId, artifactPath, imagePath) {
  const extension = motionExtensionOf(artifactPath);
  if (!extension) return null;
  if (imagePath?.startsWith("images/") && imagePath.endsWith(".png")) {
    const stem = imagePath.slice("images/".length, -".png".length);
    const leaf = artifactPath.slice(artifactPath.lastIndexOf("/") + 1, -extension.length);
    const suffix = motionSuffixOf(leaf);
    const segment = suffix ? `__${suffix.slice(1)}` : "";
    return `${MOTION_DIR}/${stem}${segment}${extension}`;
  }
  const leaf = artifactPath.slice(artifactPath.lastIndexOf("/") + 1);
  return `${MOTION_DIR}/${catalogSlug(componentId)}/${leaf}`;
}

/**
 * Plans the branch home of every motion artifact a written manifest declares.
 *
 * Pure: it reads the manifest and returns the moves, leaving both the copying and the rewrite to the
 * caller. That keeps the naming rule — the part that can silently drift away from `images/` —
 * testable without a bundle, an output directory or an `npm ci`.
 *
 * An entry whose `path` is already a `motion/…` path is passed through as a no-op rather than
 * re-prefixed, so a manifest that has been through this pass twice is unchanged by the second one.
 *
 * @param {{components?: Array<object>}} manifest the written `catalog.json`.
 * @returns {{moves: Array<{componentId: string, entry: object, source: string, target: string,
 *   viaSibling: boolean}>, unresolved: number}}
 */
export function planMotionPublish(manifest) {
  const moves = [];
  let unresolved = 0;
  for (const component of manifest?.components ?? []) {
    if (!component?.motion?.length) continue;
    const stillByPreviewId = new Map();
    for (const image of component.images ?? []) {
      if (image?.previewId && image.path && !stillByPreviewId.has(image.previewId)) {
        stillByPreviewId.set(image.previewId, image.path);
      }
    }
    for (const entry of component.motion) {
      const source = entry?.path;
      if (!source || source.startsWith(`${MOTION_DIR}/`)) continue;
      const imagePath = stillByPreviewId.get(motionSiblingPreviewId(source) ?? "");
      const target = motionPublishPath(component.componentId, source, imagePath);
      if (!target) continue;
      if (!imagePath) unresolved += 1;
      moves.push({
        componentId: component.componentId,
        entry,
        source,
        target,
        viaSibling: Boolean(imagePath),
      });
    }
  }
  return { moves, unresolved };
}

/**
 * Copies every declared motion artifact out of the bundle into `<outPath>/motion/…` and repoints the
 * manifest's declarations at where they landed.
 *
 * Mutates [manifest] in place; the caller writes it. A declaration whose bytes the bundle does not
 * carry is **dropped** rather than published: a path that 404s is worse than no motion at all, since
 * the viewer would offer a capture and then fail to load it. `motionArtifactsFor` only records
 * artifacts it matched against `entries`, so that is unreachable short of a bug — which is exactly
 * why it is reported rather than skipped silently.
 *
 * @param {{components?: Array<object>}} manifest the written `catalog.json`, mutated in place.
 * @param {Record<string, ArrayBufferView|ArrayBuffer>} entries the render bundle's entries.
 * @param {string} outPath the bundle output directory `motion/` is written under.
 * @returns {Promise<{published: number, unresolved: number, missing: Array<string>}>}
 */
export async function publishMotionArtifacts(manifest, entries, outPath) {
  const { moves, unresolved } = planMotionPublish(manifest);
  const missing = [];
  let published = 0;
  for (const move of moves) {
    const bytes = entries?.[move.source];
    if (!bytes) {
      missing.push(move.source);
      continue;
    }
    const target = join(outPath, move.target);
    await mkdir(dirname(target), { recursive: true });
    await writeFile(target, Buffer.from(bytes));
    move.entry.path = move.target;
    published += 1;
  }
  if (missing.length > 0) {
    for (const component of manifest.components ?? []) {
      if (!component.motion) continue;
      component.motion = component.motion.filter((entry) => !missing.includes(entry.path));
      if (component.motion.length === 0) delete component.motion;
    }
  }
  return { published, unresolved, missing };
}
