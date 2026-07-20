/**
 * Merge a **borrowed** built catalog into a **primary** built catalog as one new
 * top-level section (tab).
 *
 * The end use: meshcore-mobile reuses the published compose-m3 stickersheet —
 * re-themed under meshcore's palette (`bundle render --knob --res --svg` +
 * `bundle repack`, then `generate-design-catalog.mjs` over that re-themed
 * bundle) — surfaced as its own "Material 3" tab inside meshcore's catalog. Both
 * catalogs are built independently by {@link file://./generate-design-catalog.mjs}
 * (each from its own `catalog.spec.json` + render bundle); this step splices the
 * borrowed one in without either side having to know about the other's spec.
 *
 * What it does, given `--into <primaryOut> --from <borrowedOut> --section <name>`:
 *  - retags every borrowed component with `section: <name>` (the tab it lands
 *    under; its own `group` stays the sub-heading), optionally prefixing the
 *    group via `--group-prefix`,
 *  - strips each borrowed image's `livePreview` deep link (it targets the
 *    borrowed system's own server path, which would mis-link inside the host
 *    catalog — the borrowed section is baked-PNG here),
 *  - folds the borrowed catalog's per-component assets — the files under its
 *    subdirectories (`images/`, `wireframes/`, `figma/`) — into the primary out
 *    dir at the same relative paths, refusing to clobber a differing primary
 *    file,
 *  - appends the borrowed components to the primary `catalog.json` and rewrites
 *    it in place.
 *
 * A catalog's **top-level** files stay the host's: the manifest (`catalog.json`,
 * merged separately above), the token/Figma projections, the `code-connect.json`
 * mappings, and the generated `index.html` / `compare.html` / `README.md` pages
 * — each is derived from that catalog's own component set, and both catalogs
 * (produced by the same generator) carry them, so copying the borrowed ones would
 * collide. The host's generated pages therefore don't reflect the folded section;
 * the preview server renders tabs from the merged `catalog.json`, and a caller
 * that needs the static pages refreshed re-runs the generator's page renderers
 * over the merged catalog. The borrowed token files aren't merged either (the
 * re-themed pixels already carry the host palette — a token-set union is a
 * possible fast-follow). componentIds must not collide across the two catalogs.
 */
import { parseArgs } from "node:util";
import { readFile, writeFile, mkdir, readdir, stat } from "node:fs/promises";
import { dirname, join, relative, resolve, sep } from "node:path";

/**
 * Pure manifest merge: return a new primary manifest with [borrowed]'s components
 * appended under [section]. Throws on a componentId that already exists in
 * [primary] (the asset trees would collide and a tab would carry a duplicate).
 */
export function mergeManifests(primary, borrowed, { section, groupPrefix } = {}) {
  if (!section) throw new Error("merge-catalog-section: a section name is required");
  const primaryIds = new Set(primary.components.map((c) => c.componentId));
  const added = [];
  for (const comp of borrowed.components) {
    if (primaryIds.has(comp.componentId)) {
      throw new Error(
        `merge-catalog-section: componentId '${comp.componentId}' exists in both ` +
          `catalogs — borrowed ids must be distinct from the host's`,
      );
    }
    const next = { ...comp, section };
    if (groupPrefix && comp.group !== undefined) next.group = `${groupPrefix}${comp.group}`;
    // Drop live-preview deep links: they point at the borrowed system's server
    // path (e.g. /compose-m3/…), which is wrong once the component lives in the
    // host catalog. The re-themed section is baked-PNG only.
    if (Array.isArray(next.images)) {
      next.images = next.images.map(({ livePreview, ...img }) => img);
    }
    added.push(next);
  }
  return { ...primary, components: [...primary.components, ...added] };
}

/** Recursively list every file (not directory) under [dir], as absolute paths. */
async function listFiles(dir) {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...(await listFiles(p)));
    else if (entry.isFile()) out.push(p);
  }
  return out;
}

async function sameBytes(a, b) {
  const [ba, bb] = await Promise.all([readFile(a), readFile(b)]);
  return ba.equals(bb);
}

/**
 * Fold the borrowed catalog's per-component assets — the files under its
 * subdirectories (`images/`, `wireframes/`, `figma/`, …) — from [fromDir] into
 * [intoDir] at the same relative paths. Returns the number of files copied.
 * Throws if a NESTED asset already exists in the host with different bytes (a
 * real collision — same asset path, two different images).
 *
 * Only nested files are folded: every **top-level** file a catalog emits is
 * catalog-level, not a per-component asset — the manifest (`catalog.json`), the
 * token projections (`tokens.dtcg.json` / `figma-variables.json`), the
 * `code-connect.json` mappings, and the generated gallery pages (`index.html` /
 * `compare.html` / `README.md`). Each is derived from that catalog's own
 * component set, so the host keeps its own; copying the borrowed ones would both
 * collide with the host's same-named page (different bytes → abort) and be wrong
 * in the host. Skipping *every* top-level file (rather than a fixed denylist)
 * stays correct if the generator grows a new top-level artifact.
 */
async function copyAssets(fromDir, intoDir) {
  let copied = 0;
  for (const src of await listFiles(fromDir)) {
    const rel = relative(fromDir, src);
    // Top-level files are catalog-level (manifest / tokens / code-connect /
    // generated pages), not per-component assets — the host keeps its own. Every
    // foldable asset lives in a subdirectory (images/ / wireframes/ / figma/).
    if (!rel.includes(sep)) continue;
    const dest = join(intoDir, rel);
    const existing = await stat(dest).catch(() => null);
    if (existing) {
      if (!(await sameBytes(src, dest))) {
        throw new Error(
          `merge-catalog-section: asset '${rel}' differs between the two catalogs ` +
            `— refusing to overwrite ${dest}`,
        );
      }
      continue; // identical file already present; nothing to do
    }
    await mkdir(dirname(dest), { recursive: true });
    await writeFile(dest, await readFile(src));
    copied += 1;
  }
  return copied;
}

/**
 * Merge the catalog under [from] into the catalog under [into] as section
 * [section]. Reads both `catalog.json`s, copies the borrowed assets in, and
 * rewrites `<into>/catalog.json`. Returns `{ componentsAdded, filesCopied }`.
 */
export async function mergeCatalogSection({ into, from, section, groupPrefix }) {
  const intoDir = resolve(into);
  const fromDir = resolve(from);
  const primary = JSON.parse(await readFile(join(intoDir, "catalog.json"), "utf8"));
  const borrowed = JSON.parse(await readFile(join(fromDir, "catalog.json"), "utf8"));

  const merged = mergeManifests(primary, borrowed, { section, groupPrefix });
  const filesCopied = await copyAssets(fromDir, intoDir);
  await writeFile(
    join(intoDir, "catalog.json"),
    `${JSON.stringify(merged, null, 2)}\n`,
    "utf8",
  );
  return { componentsAdded: borrowed.components.length, filesCopied };
}

// CLI: only run when invoked directly (the exports above are unit-tested).
if (import.meta.url === `file://${process.argv[1]}`) {
  const { values } = parseArgs({
    options: {
      into: { type: "string" },
      from: { type: "string" },
      section: { type: "string" },
      "group-prefix": { type: "string" },
    },
  });
  if (!values.into || !values.from || !values.section) {
    console.error(
      "usage: merge-catalog-section --into <primaryOutDir> --from <borrowedOutDir> " +
        "--section <name> [--group-prefix <p>]",
    );
    process.exit(2);
  }
  const { componentsAdded, filesCopied } = await mergeCatalogSection({
    into: values.into,
    from: values.from,
    section: values.section,
    groupPrefix: values["group-prefix"],
  });
  console.log(
    `[merge-catalog-section] folded ${componentsAdded} component(s) into ` +
      `section "${values.section}" (${filesCopied} asset file(s) copied)`,
  );
}
