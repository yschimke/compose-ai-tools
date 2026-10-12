// Tests for assemble-typed-catalog.mjs, the assembler behind typed-catalog-artifacts-reusable.yml.
// It publishes through the installed export driver, so DRIVER_DIR must name one (CI: the Design
// Artifacts Driver Tests job). Locally:
//
//   DRIVER_DIR=$(.github/scripts/install-export-driver.sh "$(mktemp -d)") \
//     node --test scripts/design-artifacts/assemble-typed-catalog.test.mjs

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { mkdtemp, mkdir, readFile, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { crc32, deflateSync } from "node:zlib";
import { fileURLToPath } from "node:url";

import {
  TypedCatalogError,
  assembleTypedCatalog,
  checkTypedPair,
  planImages,
  pngSize,
  repinTemplate,
} from "./assemble-typed-catalog.mjs";

const DRIVER_DIR = process.env.DRIVER_DIR;
if (!DRIVER_DIR || !existsSync(join(DRIVER_DIR, "package.json"))) {
  throw new Error(
    "DRIVER_DIR must name an installed @design-parity/export-driver — run " +
      "`.github/scripts/install-export-driver.sh <dest>` and pass the directory it prints",
  );
}
// The driver's own zip dependency, so the archive under test is read by the same library.
const { zipSync } = createRequire(join(DRIVER_DIR, "package.json"))("fflate");
const SCRIPT = join(dirname(fileURLToPath(import.meta.url)), "assemble-typed-catalog.mjs");
const SYSTEM = "demo-typed-pilot";

/** The runtime assembler's framing: path, NUL, length, NUL, bytes; sorted by UTF-8 path. */
function treeIntegrity(files) {
  const digest = createHash("sha256");
  for (const [path, bytes] of [...files].sort(([a], [b]) =>
    Buffer.compare(Buffer.from(a), Buffer.from(b)),
  )) {
    digest.update(path, "utf8");
    digest.update(Uint8Array.of(0));
    digest.update(String(bytes.length), "utf8");
    digest.update(Uint8Array.of(0));
    digest.update(bytes);
  }
  return digest.digest("hex");
}

/** A real, decodable PNG: 8-bit greyscale, one filter byte and `width` zero bytes per row. */
function png(width, height) {
  const chunk = (type, data) => {
    const head = Buffer.alloc(4);
    head.writeUInt32BE(data.length);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(Buffer.concat([Buffer.from(type, "latin1"), data])));
    return Buffer.concat([head, Buffer.from(type, "latin1"), data, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth; colour type 0 (greyscale), no interlace
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", deflateSync(Buffer.alloc((width + 1) * height))),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

function builderCatalog({
  id = SYSTEM,
  adapter = "compose-component-records",
  record = "lib/Text",
  projection = true,
  templates = ["ui-builder/designs/hello.json"],
} = {}) {
  return {
    schema: "compose-ui-builder/catalog/v2",
    catalog: { id, title: "Demo — typed pilot" },
    statusSemantics: {
      composeSourceExport: { adapter, version: 1 },
      templates,
      components: {
        "demo/text": { record, ...(projection ? { exportProjection: { targets: [] } } : {}) },
        "demo/export-text": { record, excluded: "an export target of demo/text" },
      },
    },
  };
}

function runtimeArchive({ runtimeId = `${SYSTEM}-0123456789ab`, tamper = false } = {}) {
  const files = new Map([
    ["index.html", Buffer.from("<html></html>")],
    ["fonts/a.ttf", Buffer.from([0, 1, 2])],
  ]);
  const manifest = {
    schema: "compose-ui-builder-runtime/v1",
    runtimeId,
    protocolVersion: 2,
    entrypoint: "index.html",
    integritySha256: treeIntegrity(files),
  };
  if (tamper) files.set("index.html", Buffer.from("<html>changed</html>"));
  return {
    manifest,
    zip: Buffer.from(
      zipSync({
        ...Object.fromEntries([...files].map(([path, bytes]) => [path, new Uint8Array(bytes)])),
        "runtime-manifest.json": new Uint8Array(Buffer.from(JSON.stringify(manifest))),
      }),
    ),
  };
}

/** A caller tree: the pair, a runtime zip, guidelines, readiness and parity evidence. */
async function fixture({ builder = builderCatalog(), runtime = runtimeArchive(), readiness } = {}) {
  const root = await mkdtemp(join(tmpdir(), "typed-catalog-"));
  const pair = join(root, "pair");
  await mkdir(join(pair, "ui-builder/designs"), { recursive: true });
  await mkdir(join(pair, "generated-source"), { recursive: true });
  await writeFile(join(pair, "ui-builder.json"), JSON.stringify(builder));
  await writeFile(
    join(pair, "components.json"),
    JSON.stringify({ schemaVersion: 1, components: [{ canonicalId: "lib/Text" }] }),
  );
  await writeFile(
    join(pair, "ui-builder/designs/hello.json"),
    JSON.stringify({ id: "hello", catalogPin: { systemId: "demo", catalogRevision: "x" } }),
  );
  await writeFile(join(pair, "generated-source/Fixture.kt"), "// review evidence");
  await writeFile(join(root, "runtime.zip"), runtime.zip);
  await writeFile(
    join(root, "guidelines.json"),
    JSON.stringify({
      schema: "compose-ui-builder/catalog-guidelines/v1",
      catalog: "demo",
      rules: [{ id: "r1" }],
      frames: [],
    }),
  );
  await writeFile(
    join(root, "readiness.json"),
    JSON.stringify(
      readiness ?? {
        browserVerification: {
          runtimeId: runtime.manifest.runtimeId,
          integritySha256: runtime.manifest.integritySha256,
        },
      },
    ),
  );
  await mkdir(join(root, "evidence"), { recursive: true });
  await writeFile(join(root, "evidence/Button Case-typed.png"), png(192, 192));
  await writeFile(join(root, "evidence/Button Case-production.png"), png(192, 192));
  await writeFile(join(root, "evidence/button-case-typed.png"), png(100, 50));
  return { root, pair, out: join(root, "out"), runtime };
}

const base = (f, extra = {}) => ({
  driverDir: DRIVER_DIR,
  system: SYSTEM,
  pair: f.pair,
  runtime: join(f.root, "runtime.zip"),
  out: f.out,
  ...extra,
});

const json = async (path) => JSON.parse(await readFile(path, "utf8"));

test("a typed pair is laid out as a delivery branch", async () => {
  const f = await fixture();
  const { manifest, warnings } = await assembleTypedCatalog(
    base(f, {
      guidelines: join(f.root, "guidelines.json"),
      readiness: join(f.root, "readiness.json"),
      requireBrowserVerification: true,
      images: join(f.root, "evidence"),
      imageVariant: "typed",
      imageSection: "Typed pilot",
      imageGroup: "JVM parity",
      imageCaption: "Typed renderer, parity case {name}",
      imageComponentPrefix: "typed-pilot/",
      requireImages: true,
      library: ["org.example:lib"],
    }),
  );
  assert.deepEqual(warnings, []);
  assert.equal(manifest.system, SYSTEM);
  assert.equal(manifest.title, "Demo — typed pilot");
  assert.deepEqual(manifest.library, ["org.example:lib"]);
  assert.equal(manifest.componentsFile, "components.json");
  assert.equal(manifest.uiBuilderFile, "ui-builder.json");
  assert.equal(manifest.uiBuilderGuidelinesFile, "ui-builder.guidelines.json");
  assert.deepEqual(manifest.uiBuilderRuntime, {
    path: "ui-builder/runtime.zip",
    runtimeId: f.runtime.manifest.runtimeId,
    protocolVersion: 2,
    integritySha256: f.runtime.manifest.integritySha256,
  });
  assert.equal(manifest.generatedAt, undefined, "content alone decides whether a commit appends");
  assert.equal(manifest.source, undefined);
  assert.deepEqual(await json(join(f.out, "catalog.json")), manifest);

  // Only the `-typed` evidence, one component per case, colliding slugs disambiguated.
  assert.deepEqual(
    manifest.components.map((c) => [c.componentId, c.images[0].path, c.images[0].width]),
    [
      ["typed-pilot/button-case", "images/button-case/typed.png", 192],
      ["typed-pilot/button-case-2", "images/button-case-2/typed.png", 100],
    ],
  );
  assert.equal(manifest.components[0].caption, "Typed renderer, parity case Button Case");
  assert.equal(manifest.components[0].section, "Typed pilot");
  assert.ok(existsSync(join(f.out, "images/button-case-2/typed.png")));

  assert.deepEqual((await json(join(f.out, "ui-builder/designs/hello.json"))).catalogPin, {
    systemId: SYSTEM,
    catalogRevision: "x",
  });
  // The pair's own template is untouched; only the published copy is re-pinned.
  assert.equal(
    (await json(join(f.pair, "ui-builder/designs/hello.json"))).catalogPin.systemId,
    "demo",
  );
  assert.equal((await json(join(f.out, "ui-builder.guidelines.json"))).catalog, SYSTEM);
  assert.ok(existsSync(join(f.out, "ui-builder/runtime.zip")));
  assert.ok(existsSync(join(f.out, "readiness.json")));
  assert.ok(!existsSync(join(f.out, "generated-source")));
});

test("templates can come from a root other than the pair", async () => {
  const f = await fixture();
  const repo = join(f.root, "repo");
  await mkdir(join(repo, "ui-builder/designs"), { recursive: true });
  await writeFile(
    join(repo, "ui-builder/designs/hello.json"),
    JSON.stringify({ id: "hello-from-repo", catalogPin: { systemId: "production" } }),
  );
  await assembleTypedCatalog(base(f, { templatesRoot: repo, images: join(f.root, "evidence") }));
  const published = await json(join(f.out, "ui-builder/designs/hello.json"));
  assert.equal(published.id, "hello-from-repo");
  assert.equal(published.catalogPin.systemId, SYSTEM);
  assert.equal(
    (await json(join(repo, "ui-builder/designs/hello.json"))).catalogPin.systemId,
    "production",
  );
});

test("a pair that is not this typed catalog is refused", () => {
  const record = { components: [{ canonicalId: "lib/Text" }] };
  assert.deepEqual(checkTypedPair(builderCatalog(), record, SYSTEM), ["demo/text"]);
  assert.throws(
    () => checkTypedPair(builderCatalog({ id: "demo" }), record, SYSTEM),
    /names catalog 'demo'/,
  );
  assert.throws(
    () => checkTypedPair(builderCatalog({ adapter: "compose-material3" }), record, SYSTEM),
    /compose-component-records v1/,
  );
  assert.throws(
    () => checkTypedPair(builderCatalog({ projection: false }), record, SYSTEM),
    /no exportProjection/,
  );
  assert.throws(
    () => checkTypedPair(builderCatalog({ record: "lib/Missing" }), record, SYSTEM),
    /components.json lacks: demo\/text -> lib\/Missing/,
  );
});

test("the runtime must verify and carry the expected id prefix", async () => {
  const tampered = await fixture({ runtime: runtimeArchive({ tamper: true }) });
  await assert.rejects(assembleTypedCatalog(base(tampered)), /integrity mismatch/);

  const production = await fixture({ runtime: runtimeArchive({ runtimeId: "demo-p2-0123" }) });
  await assert.rejects(
    assembleTypedCatalog(base(production)),
    /'demo-p2-0123' does not start with 'demo-typed-pilot-'/,
  );
  // A catalog whose runtime is named differently from its id says so.
  const named = await fixture({ runtime: runtimeArchive({ runtimeId: "demo-pilot-0123" }) });
  const { manifest } = await assembleTypedCatalog(base(named, { runtimeIdPrefix: "demo-pilot-" }));
  assert.equal(manifest.uiBuilderRuntime.runtimeId, "demo-pilot-0123");
});

test("a readiness report must have verified the published runtime", async () => {
  const other = await fixture({
    readiness: { browserVerification: { runtimeId: "x", integritySha256: "y" } },
  });
  await assert.rejects(
    assembleTypedCatalog(base(other, { readiness: join(other.root, "readiness.json") })),
    /verified runtime x\/y/,
  );
  // A report that records no browser verification passes unless one is required.
  const plain = await fixture({ readiness: { readyForCutover: false } });
  await assembleTypedCatalog(base(plain, { readiness: join(plain.root, "readiness.json") }));
  await assert.rejects(
    assembleTypedCatalog(
      base(plain, {
        readiness: join(plain.root, "readiness.json"),
        requireBrowserVerification: true,
      }),
    ),
    /verified runtime undefined/,
  );
  await assert.rejects(
    assembleTypedCatalog(base(plain, { requireBrowserVerification: true })),
    /none was given/,
  );
});

test("no images is a warning, or a refusal when images are required", async () => {
  const f = await fixture();
  const { manifest, warnings } = await assembleTypedCatalog(base(f));
  assert.deepEqual(manifest.components, []);
  assert.match(warnings[0], /catalog had no usable images/);
  await assert.rejects(
    assembleTypedCatalog(base(f, { requireImages: true })),
    (error) => error instanceof TypedCatalogError && /no usable images/.test(error.message),
  );
  // A directory with no PNG of the requested variant is the same as none.
  await assert.rejects(
    assembleTypedCatalog(
      base(f, { images: join(f.root, "evidence"), imageVariant: "dark", requireImages: true }),
    ),
    /no usable images/,
  );
});

test("images: every PNG by default, non-PNG bytes refused", async () => {
  const f = await fixture();
  const all = await planImages(join(f.root, "evidence"), { componentPrefix: "x/" });
  assert.deepEqual(
    all.map((i) => i.path),
    [
      "images/button-case-production/default.png",
      "images/button-case-typed/default.png",
      "images/button-case-typed-2/default.png",
    ],
  );
  assert.deepEqual(pngSize(png(3, 4)), { width: 3, height: 4 });
  assert.equal(pngSize(Buffer.from("not a png at all, really not")), null);
  await writeFile(join(f.root, "evidence/broken-typed.png"), "nope");
  await assert.rejects(
    planImages(join(f.root, "evidence"), { variant: "typed" }),
    /not a whole PNG/,
  );
});

test("a truncated or corrupt PNG is refused", async () => {
  const good = png(4, 4);
  assert.deepEqual(pngSize(good), { width: 4, height: 4 });
  // Truncated: cut inside IDAT, and just before IEND.
  assert.equal(pngSize(good.subarray(0, 45)), null);
  assert.equal(pngSize(good.subarray(0, good.length - 12)), null);
  // A flipped byte inside IDAT fails its CRC; one inside IHDR fails IHDR's.
  const idat = Buffer.from(good);
  idat[45] ^= 0xff;
  assert.equal(pngSize(idat), null);
  const ihdr = Buffer.from(good);
  ihdr[17] ^= 0x01;
  assert.equal(pngSize(ihdr), null);
  // Zero width, with a CRC that matches it.
  assert.equal(pngSize(png(0, 4)), null);
  // Trailing bytes after IEND.
  assert.equal(pngSize(Buffer.concat([good, Buffer.from([0])])), null);
  const f = await fixture();
  await writeFile(join(f.root, "evidence/cut-typed.png"), good.subarray(0, good.length - 12));
  await assert.rejects(
    assembleTypedCatalog(base(f, { images: join(f.root, "evidence"), imageVariant: "typed" })),
    /cut-typed\.png is not a whole PNG/,
  );
});

test("a template the catalog advertises must ship", async () => {
  const f = await fixture({
    builder: builderCatalog({
      templates: ["ui-builder/designs/hello.json", "ui-builder/designs/absent.json"],
    }),
  });
  await assert.rejects(assembleTypedCatalog(base(f)), /missing template\(s\): .*absent\.json/);
  const unsafe = await fixture({ builder: builderCatalog({ templates: ["catalog.json"] }) });
  await assert.rejects(assembleTypedCatalog(base(unsafe)), /unsafe template\(s\): catalog\.json/);
});

test("a template keeps every field but its pin", () => {
  const out = JSON.parse(repinTemplate(Buffer.from('{"id":"a","nodes":[1]}'), SYSTEM));
  assert.deepEqual(out, { id: "a", nodes: [1], catalogPin: { systemId: SYSTEM } });
});

test("the CLI reports a refusal as a workflow error and exits 1", async () => {
  const f = await fixture({ builder: builderCatalog({ id: "demo" }) });
  const run = (...args) =>
    spawnSync(process.execPath, [SCRIPT, ...args], {
      encoding: "utf8",
      env: { ...process.env, DRIVER_DIR },
    });
  const common = ["--pair", f.pair, "--runtime", join(f.root, "runtime.zip"), "--out", f.out];
  const refused = run("--system", SYSTEM, ...common);
  assert.equal(refused.status, 1, refused.stderr);
  assert.match(refused.stderr, /::error title=Typed catalog demo-typed-pilot::.*names catalog/);

  const ok = run(
    "--system",
    "demo",
    ...common,
    "--runtime-id-prefix",
    "demo-",
    "--images",
    "",
    "--source-repo",
    "o/r",
    "--source-ref",
    "abc",
  );
  assert.equal(ok.status, 0, ok.stderr);
  assert.match(ok.stdout, /::warning title=Typed catalog demo::.*no usable images/);
  assert.deepEqual((await json(join(f.out, "catalog.json"))).source, { repo: "o/r", ref: "abc" });
  assert.equal(run("--system", "demo").status, 2);
});
