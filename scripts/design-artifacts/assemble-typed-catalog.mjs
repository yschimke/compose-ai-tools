#!/usr/bin/env node
// Assemble a `design-artifacts/<system>` delivery tree from a PREBUILT typed catalog pair, for
// `.github/workflows/typed-catalog-artifacts-reusable.yml`. The layout is the one a preview server
// reads from a rendered catalog's branch:
//
//   catalog.json                 manifest: componentsFile, uiBuilderFile, uiBuilderRuntime, images
//   components.json              the pair's component record
//   ui-builder.json              the pair's builder catalog (catalog id == <system>)
//   ui-builder.guidelines.json   optional; re-addressed to <system>
//   ui-builder/designs/*.json    the templates ui-builder.json advertises, re-pinned to <system>
//   ui-builder/runtime.zip       the catalog renderer archive, verified against its own manifest
//   images/<slug>/<variant>.png  optional baked stickers, one component each
//   readiness.json               optional readiness report, checked against the published runtime
//
// A typed catalog has no `@Preview` bundle to render, so this is not a lane of
// design-artifacts-reusable.yml. Every file the server reads is still written by the export
// driver's own publishers (`@design-parity/export-driver`, the same lock that workflow installs):
// the record copy, template containment, guidelines check and runtime integrity check are the
// production ones rather than a second implementation of them. This file adds only what a typed
// pair must additionally prove before it is published:
//
//   * ui-builder.json names catalog `<system>` (a pilot published under a production id would be
//     a second production catalog, not a trial);
//   * it selects the `compose-component-records` v1 Compose source export, and at least one
//     component carries an `exportProjection` for it to export through;
//   * every `record` a builder component names exists in components.json;
//   * the runtime id starts with the expected prefix (default `<system>-`);
//   * a readiness report, when given, verified THIS runtime (id and digest) if it records a
//     browser verification, and must record one when that is required;
//   * at least one usable image, when images are required (the default in the workflow).
//
// IMAGES. compose-preview-server's `ServeCatalogStore.load` refuses a catalog that declares no
// baked image and no deferred (live-rendered) preview — "catalog had no usable images" — and it
// stages a catalog's executable runtime only when it loads that catalog's generation. A branch
// published with no images therefore carries a runtime no server ever serves. A deferred record
// needs a daemon preview id, which a typed catalog has none of, so the only way in is at least one
// PNG under `images/`, declared in `components[].images` and fetchable from the branch.
//
//   node assemble-typed-catalog.mjs --driver <dir> --system <id> --pair <dir> --runtime <zip>
//     --out <dir> [--templates-root <dir>] [--runtime-id-prefix <p>] [--guidelines <file>]
//     [--readiness <file>] [--require-browser-verification] [--images <dir>]
//     [--image-variant <v>] [--image-section <s>] [--image-group <g>] [--image-caption <c>]
//     [--image-component-prefix <p>] [--require-images] [--title <t>] [--library <coord>]...
//     [--renderer <label>] [--source-repo <r> --source-ref <sha> --source-module <:m>]
//
// `--driver` defaults to $DRIVER_DIR, which `.github/scripts/install-export-driver.sh` exports.

import { mkdir, readdir, readFile, rm, writeFile, copyFile, stat } from "node:fs/promises";
import { join, relative, resolve, sep } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { parseArgs } from "node:util";

export const COMPONENT_RECORDS = { adapter: "compose-component-records", version: 1 };
const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
// A catalog id is a delivery branch segment and a URL path segment on the server.
const SYSTEM_ID = /^[a-z0-9][a-z0-9._-]*$/;
const IMAGE_VARIANT = /^[a-z0-9][a-z0-9_-]*$/;

/** A refusal: the pair, runtime or images must not be published as they are. */
export class TypedCatalogError extends Error {}
function refuse(message) {
  throw new TypedCatalogError(message);
}

/**
 * The checks a typed builder catalog must pass on top of the driver's structural parse. Returns
 * the component ids carrying an export projection.
 */
export function checkTypedPair(builder, record, system) {
  if (builder?.catalog?.id !== system)
    refuse(`ui-builder.json names catalog '${builder?.catalog?.id}', not '${system}'`);
  const semantics = builder.statusSemantics ?? {};
  const exportAdapter = semantics.composeSourceExport;
  if (
    exportAdapter?.adapter !== COMPONENT_RECORDS.adapter ||
    exportAdapter?.version !== COMPONENT_RECORDS.version
  )
    refuse(
      `ui-builder.json must select ${COMPONENT_RECORDS.adapter} v${COMPONENT_RECORDS.version} ` +
        `for composeSourceExport, found ${JSON.stringify(exportAdapter ?? null)}`,
    );
  const components = semantics.components ?? {};
  const projected = Object.entries(components)
    .filter(([, component]) => component?.exportProjection)
    .map(([id]) => id);
  if (projected.length === 0)
    refuse(
      "ui-builder.json carries no exportProjection, so the typed export has nothing to export",
    );
  const records = new Set((record?.components ?? []).map((component) => component?.canonicalId));
  const dangling = Object.entries(components)
    .filter(([, component]) => component?.record && !records.has(component.record))
    .map(([id, component]) => `${id} -> ${component.record}`);
  if (dangling.length > 0)
    refuse(`builder component(s) name records components.json lacks: ${dangling.join(", ")}`);
  return projected;
}

/** A template re-pinned to `system`, everything else kept, so a design made from it selects it. */
export function repinTemplate(bytes, system) {
  const template = JSON.parse(Buffer.from(bytes).toString("utf8"));
  if (!template || typeof template !== "object" || Array.isArray(template)) return bytes;
  template.catalogPin = { ...(template.catalogPin ?? {}), systemId: system };
  return Buffer.from(`${JSON.stringify(template, null, 2)}\n`);
}

export const slugOf = (value) =>
  value
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");

/** Width and height from a PNG's IHDR, or null for bytes that are not a PNG. */
export function pngSize(bytes) {
  if (bytes.length < 24 || !bytes.subarray(0, 8).equals(PNG_SIGNATURE)) return null;
  if (bytes.toString("latin1", 12, 16) !== "IHDR") return null;
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
}

async function filesUnder(dir, { skip = () => false } = {}) {
  const files = new Map();
  async function walk(current) {
    for (const entry of await readdir(current, { withFileTypes: true })) {
      const path = join(current, entry.name);
      const key = relative(dir, path).split(sep).join("/");
      if (skip(key)) continue;
      if (entry.isDirectory()) await walk(path);
      else if (entry.isFile()) files.set(key, path);
    }
  }
  await walk(dir);
  return files;
}

/**
 * One sticker component per PNG under `dir`, sorted by path. With a `variant`, only files named
 * `<case>-<variant>.png` are taken (the parity-evidence shape: `<case>-typed.png` beside
 * `<case>-production.png`) and the suffix is dropped from the case; without one, every PNG is a
 * case of variant `default`. The case is the path below `dir`, extension dropped.
 */
export async function planImages(dir, options = {}) {
  const variant = options.variant || "default";
  const suffix = options.variant ? `-${options.variant}.png` : ".png";
  const files = [...(await filesUnder(dir)).entries()]
    .filter(([key]) => key.toLowerCase().endsWith(suffix))
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
  const slugs = new Set();
  const planned = [];
  for (const [key, source] of files) {
    const name = key.slice(0, -suffix.length);
    const base = slugOf(name) || "case";
    let slug = base;
    for (let n = 2; slugs.has(slug); n++) slug = `${base}-${n}`;
    slugs.add(slug);
    const bytes = await readFile(source);
    const size = pngSize(bytes);
    if (!size) refuse(`${key} is not a PNG`);
    const path = `images/${slug}/${variant}.png`;
    planned.push({
      source,
      path,
      component: {
        componentId: `${options.componentPrefix ?? ""}${slug}`,
        caption: (options.caption ?? "{name}").replaceAll("{name}", name),
        ...(options.section ? { section: options.section } : {}),
        ...(options.group ? { group: options.group } : {}),
        images: [{ variant, path, state: "default", ...size }],
      },
    });
  }
  return planned;
}

/** Assemble and validate the delivery tree at `out`. Throws {@link TypedCatalogError} on refusal. */
export async function assembleTypedCatalog(options) {
  const { driverDir, system, out } = options;
  if (!driverDir) refuse("no export driver: pass --driver or set DRIVER_DIR");
  if (!SYSTEM_ID.test(system ?? "")) refuse(`'${system}' is not a catalog id`);
  if (options.imageVariant && !IMAGE_VARIANT.test(options.imageVariant))
    refuse(`'${options.imageVariant}' is not an image variant`);
  const driver = (file) => import(pathToFileURL(join(resolve(driverDir), file)).href);
  const { publishComponentRecord } = await driver("catalog-component-record.mjs");
  const {
    UI_BUILDER_FILE,
    UI_BUILDER_GUIDELINES_FILE,
    parseUiBuilderCatalog,
    publishUiBuilderCatalog,
    uiBuilderGuidelinesManifestFields,
    uiBuilderGuidelinesWarning,
  } = await driver("catalog-ui-builder.mjs");
  const { publishUiBuilderRuntime } = await driver("catalog-ui-builder-runtime.mjs");

  // The pair as bundle entries (pair-relative path -> bytes), the shape the driver's publishers
  // read. Generated Kotlin fixtures are review evidence, not delivery.
  const pair = resolve(options.pair);
  const entries = {};
  for (const [key, path] of await filesUnder(pair, { skip: (k) => k === "generated-source" }))
    entries[key] = await readFile(path);

  const builder = parseUiBuilderCatalog(entries[UI_BUILDER_FILE]);
  if (!builder) refuse(`${pair}/${UI_BUILDER_FILE} is missing or is not a builder catalog`);
  let record = null;
  try {
    record = JSON.parse(Buffer.from(entries["components.json"] ?? "null").toString("utf8"));
  } catch {
    // reported by the publisher below
  }
  const projected = checkTypedPair(builder, record, system);

  // Templates: looked up under `templatesRoot` (the pair by default, or e.g. the repository root
  // when the pair names the committed templates), and re-pinned to this catalog. The source file is
  // never rewritten; only the published copy is.
  const templatesRoot = resolve(options.templatesRoot ?? pair);
  const templates = (builder.statusSemantics.templates ?? [])
    .map((entry) => (entry !== null && typeof entry === "object" ? entry.path : entry))
    .filter((path) => typeof path === "string" && path.length > 0);
  for (const path of templates) {
    // Containment is the publisher's check (it reports `unsafe`); this only avoids reading outside
    // the root on the way there.
    if (path.startsWith("/") || path.split("/").includes("..")) continue;
    let bytes = entries[path];
    if (templatesRoot !== pair) {
      try {
        bytes = await readFile(join(templatesRoot, path));
      } catch {
        // fall back to the pair's own copy, if it carries one
      }
    }
    if (!bytes) {
      delete entries[path];
      continue;
    }
    try {
      entries[path] = repinTemplate(bytes, system);
    } catch {
      entries[path] = bytes; // the publisher reports it unreadable
    }
  }

  // Guidelines, re-addressed: a host accepts guidelines only for the catalog id they name, and a
  // typed catalog's designs are the same screens its production guidelines were written for.
  if (options.guidelines) {
    let guidelines;
    try {
      guidelines = JSON.parse(await readFile(resolve(options.guidelines), "utf8"));
    } catch (error) {
      refuse(`guidelines ${options.guidelines} are unreadable (${error.message})`);
    }
    entries[UI_BUILDER_GUIDELINES_FILE] = Buffer.from(
      `${JSON.stringify({ ...guidelines, catalog: system }, null, 2)}\n`,
    );
  }

  await rm(out, { recursive: true, force: true });
  await mkdir(out, { recursive: true });

  const publishedRecord = await publishComponentRecord(entries, out);
  if (!publishedRecord) refuse("components.json is missing or is not a component record");
  const published = await publishUiBuilderCatalog(entries, out);
  if (!published) refuse("ui-builder.json is not a builder catalog");
  for (const [kind, paths] of [
    ["missing", published.missingTemplates],
    ["unsafe", published.unsafeTemplates],
    ["unreadable", published.unreadableTemplates],
  ]) {
    if (paths.length > 0) refuse(`${kind} template(s): ${paths.join(", ")}`);
  }
  const guidelinesWarning = uiBuilderGuidelinesWarning(published, system);
  if (guidelinesWarning) refuse(guidelinesWarning);

  let runtime;
  try {
    runtime = await publishUiBuilderRuntime(resolve(options.runtime), out);
  } catch (error) {
    refuse(`runtime ${options.runtime}: ${error.message}`);
  }
  if (!runtime) refuse("no runtime archive given");
  const prefix = options.runtimeIdPrefix || `${system}-`;
  if (!runtime.runtimeId.startsWith(prefix))
    refuse(`runtime id '${runtime.runtimeId}' does not start with '${prefix}'`);

  // A readiness report that records a browser verification must have verified THESE bytes, so the
  // branch never claims a verification of another runtime. Required when asked for.
  if (options.readiness) {
    let readiness;
    try {
      readiness = JSON.parse(await readFile(resolve(options.readiness), "utf8"));
    } catch (error) {
      refuse(`readiness report ${options.readiness} is unreadable (${error.message})`);
    }
    const verified = readiness?.browserVerification;
    if (verified || options.requireBrowserVerification) {
      if (
        verified?.runtimeId !== runtime.runtimeId ||
        verified?.integritySha256 !== runtime.integritySha256
      )
        refuse(
          `readiness.json verified runtime ${verified?.runtimeId}/${verified?.integritySha256}, ` +
            `not the published ${runtime.runtimeId}/${runtime.integritySha256}`,
        );
    }
    await writeFile(join(out, "readiness.json"), `${JSON.stringify(readiness, null, 2)}\n`);
  } else if (options.requireBrowserVerification) {
    refuse("a browser-verified readiness report is required and none was given");
  }

  const components = [];
  if (options.images) {
    let isDir = false;
    try {
      isDir = (await stat(resolve(options.images))).isDirectory();
    } catch {
      isDir = false;
    }
    if (!isDir) refuse(`images directory ${options.images} does not exist`);
    for (const image of await planImages(resolve(options.images), {
      variant: options.imageVariant,
      section: options.imageSection,
      group: options.imageGroup,
      caption: options.imageCaption,
      componentPrefix: options.imageComponentPrefix ?? `${system}/`,
    })) {
      await mkdir(join(out, image.path, ".."), { recursive: true });
      await copyFile(image.source, join(out, image.path));
      components.push(image.component);
    }
  }
  const warnings = [];
  if (components.length === 0) {
    const why =
      "the catalog declares no images, and compose-preview-server refuses such a catalog " +
      "('catalog had no usable images'), so its runtime is never staged";
    if (options.requireImages) refuse(why);
    warnings.push(why);
  }

  // No `generatedAt`: the content alone decides whether a publish appends a commit (the runtime id
  // names the source commit, so each new commit is still a new generation). No `source` unless
  // asked for: a source block offers trusted server-side re-render of that module, which a typed
  // catalog's stickers are not previews of.
  const library = (options.library ?? []).filter(Boolean);
  const manifest = {
    schema: "design-parity-catalog/v1",
    system,
    title: options.title || builder.catalog.title || system,
    ...(library.length > 0 ? { library } : {}),
    renderer: options.renderer || `typed-catalog ${runtime.runtimeId}`,
    components,
    componentsFile: publishedRecord.path,
    uiBuilderFile: published.path,
    ...uiBuilderGuidelinesManifestFields(published),
    uiBuilderRuntime: runtime,
    ...(options.sourceRepo
      ? {
          source: {
            repo: options.sourceRepo,
            ref: options.sourceRef,
            ...(options.sourceModule ? { module: options.sourceModule } : {}),
          },
        }
      : {}),
  };
  await writeFile(join(out, "catalog.json"), `${JSON.stringify(manifest, null, 2)}\n`);
  return {
    manifest,
    warnings,
    summary:
      `${system}: ${publishedRecord.components} record component(s), ${published.components} ` +
      `builder component(s) (${projected.length} projected), ${published.templates.length} ` +
      `template(s), runtime ${runtime.runtimeId}, ${components.length} image(s)`,
  };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { values } = parseArgs({
    options: {
      driver: { type: "string", default: process.env.DRIVER_DIR },
      system: { type: "string" },
      pair: { type: "string" },
      "templates-root": { type: "string" },
      runtime: { type: "string" },
      "runtime-id-prefix": { type: "string" },
      guidelines: { type: "string" },
      readiness: { type: "string" },
      "require-browser-verification": { type: "boolean", default: false },
      images: { type: "string" },
      "image-variant": { type: "string" },
      "image-section": { type: "string" },
      "image-group": { type: "string" },
      "image-caption": { type: "string" },
      "image-component-prefix": { type: "string" },
      "require-images": { type: "boolean", default: false },
      title: { type: "string" },
      library: { type: "string", multiple: true },
      renderer: { type: "string" },
      "source-repo": { type: "string" },
      "source-ref": { type: "string" },
      "source-module": { type: "string" },
      out: { type: "string" },
    },
  });
  if (!values.system || !values.pair || !values.runtime || !values.out) {
    console.error(
      "usage: assemble-typed-catalog.mjs --system <id> --pair <dir> --runtime <zip> --out <dir> " +
        "[--driver <dir>] [options]",
    );
    process.exit(2);
  }
  // Empty strings arrive from unset workflow inputs; read them as "not given".
  const given = (value) => (value === "" ? undefined : value);
  try {
    const result = await assembleTypedCatalog({
      driverDir: given(values.driver),
      system: values.system,
      pair: values.pair,
      templatesRoot: given(values["templates-root"]),
      runtime: values.runtime,
      runtimeIdPrefix: given(values["runtime-id-prefix"]),
      guidelines: given(values.guidelines),
      readiness: given(values.readiness),
      requireBrowserVerification: values["require-browser-verification"],
      images: given(values.images),
      imageVariant: given(values["image-variant"]),
      imageSection: given(values["image-section"]),
      imageGroup: given(values["image-group"]),
      imageCaption: given(values["image-caption"]),
      imageComponentPrefix: given(values["image-component-prefix"]),
      requireImages: values["require-images"],
      title: given(values.title),
      library: values.library,
      renderer: given(values.renderer),
      sourceRepo: given(values["source-repo"]),
      sourceRef: given(values["source-ref"]),
      sourceModule: given(values["source-module"]),
      out: resolve(values.out),
    });
    for (const warning of result.warnings)
      console.log(`::warning title=Typed catalog ${values.system}::${warning}`);
    console.log(`${result.summary} -> ${values.out}`);
  } catch (error) {
    if (!(error instanceof TypedCatalogError)) throw error;
    console.error(`::error title=Typed catalog ${values.system}::${error.message}`);
    process.exit(1);
  }
}
