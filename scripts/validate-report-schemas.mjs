#!/usr/bin/env node
// Validates the published report schemas under `schema/` against
// representative payloads, so the schema and the payloads it documents
// can't silently drift. Two payload sources are checked:
//
//   1. The canonical example referenced by each schema's
//      `x-composeai.example` (hand-authored from the Kotlin type).
//   2. Every matching data-product payload embedded in the committed
//      preview-harness fixtures (independently maintained) — for any
//      `{ kind, payload }` whose `kind` a report schema claims.
//
// Dependency-free: a focused draft-07 subset validator lives below, so
// this runs under plain `node` with no install step. Run:
//   node scripts/validate-report-schemas.mjs
//
// Exit 0 = all payloads conform; exit 1 = at least one violation.

import { readFileSync, readdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const schemaDir = join(repoRoot, "schema");
const fixturesDir = join(repoRoot, "vscode-extension", "preview-harness", "fixtures");

// ---- draft-07 subset validator --------------------------------------------

function typeOf(v) {
  if (v === null) return "null";
  if (Array.isArray(v)) return "array";
  if (Number.isInteger(v)) return "integer";
  if (typeof v === "number") return "number";
  return typeof v; // string | boolean | object
}

function matchesType(value, type) {
  const actual = typeOf(value);
  const want = Array.isArray(type) ? type : [type];
  return want.some((t) =>
    t === "number" ? actual === "number" || actual === "integer" : actual === t,
  );
}

function validate(value, schema, root, path, errors) {
  if (schema == null || typeof schema !== "object") return;

  if (schema.$ref) {
    const ref = resolve$ref(schema.$ref, root);
    if (!ref) {
      errors.push(`${path}: unresolved $ref ${schema.$ref}`);
      return;
    }
    validate(value, ref, root, path, errors);
    return;
  }

  for (const combinator of ["oneOf", "anyOf"]) {
    if (Array.isArray(schema[combinator])) {
      const ok = schema[combinator].some((sub) => {
        const sErrs = [];
        validate(value, sub, root, path, sErrs);
        return sErrs.length === 0;
      });
      if (!ok) errors.push(`${path}: does not match ${combinator}`);
    }
  }

  if (schema.type && !matchesType(value, schema.type)) {
    errors.push(`${path}: expected type ${JSON.stringify(schema.type)}, got ${typeOf(value)}`);
    return; // type is wrong; downstream checks would be noise
  }

  if (schema.const !== undefined && value !== schema.const) {
    errors.push(`${path}: expected const ${JSON.stringify(schema.const)}, got ${JSON.stringify(value)}`);
  }

  if (Array.isArray(schema.enum) && !schema.enum.includes(value)) {
    errors.push(`${path}: ${JSON.stringify(value)} not in enum ${JSON.stringify(schema.enum)}`);
  }

  if (typeof schema.minimum === "number" && typeof value === "number" && value < schema.minimum) {
    errors.push(`${path}: ${value} < minimum ${schema.minimum}`);
  }

  if (typeOf(value) === "object") {
    for (const key of schema.required ?? []) {
      if (!(key in value)) errors.push(`${path}: missing required property "${key}"`);
    }
    const props = schema.properties ?? {};
    for (const [key, sub] of Object.entries(value)) {
      const childPath = `${path}/${key}`;
      if (key in props) {
        validate(sub, props[key], root, childPath, errors);
      } else if (schema.additionalProperties === false) {
        errors.push(`${childPath}: additional property not allowed`);
      } else if (schema.additionalProperties && typeof schema.additionalProperties === "object") {
        validate(sub, schema.additionalProperties, root, childPath, errors);
      }
    }
  }

  if (typeOf(value) === "array" && schema.items) {
    value.forEach((item, i) => validate(item, schema.items, root, `${path}[${i}]`, errors));
  }
}

function resolve$ref(ref, root) {
  if (!ref.startsWith("#/")) return null;
  let node = root;
  for (const seg of ref.slice(2).split("/")) {
    node = node?.[seg];
    if (node == null) return null;
  }
  return node;
}

function check(value, schema, label, results) {
  const errors = [];
  validate(value, schema, schema, label, errors);
  results.push({ label, errors });
}

// ---- load schemas ----------------------------------------------------------

const schemas = []; // { file, kind, schema, example }
for (const file of readdirSync(schemaDir)) {
  if (!file.endsWith(".schema.json")) continue;
  if (file === "spatial-scene.schema.json") continue; // own codegen test
  const schema = JSON.parse(readFileSync(join(schemaDir, file), "utf8"));
  const meta = schema["x-composeai"] ?? {};
  schemas.push({ file, kind: meta.kind, schema, example: meta.example });
}
const byKind = new Map(schemas.filter((s) => s.kind && s.kind !== "*").map((s) => [s.kind, s]));

const results = [];

// 1. canonical examples
for (const s of schemas) {
  if (!s.example) continue;
  const payload = JSON.parse(readFileSync(join(repoRoot, s.example), "utf8"));
  check(payload, s.schema, `${s.file} ⇐ ${s.example}`, results);
}

// 2. fixture data-product payloads, matched by kind
function collectDataProducts(node, out) {
  if (Array.isArray(node)) {
    for (const n of node) collectDataProducts(n, out);
  } else if (node && typeof node === "object") {
    if (typeof node.kind === "string" && "payload" in node) out.push(node);
    for (const v of Object.values(node)) collectDataProducts(v, out);
  }
}
let fixtureFiles = [];
try {
  fixtureFiles = readdirSync(fixturesDir).filter((f) => f.endsWith(".json"));
} catch {
  // fixtures dir absent (e.g. sparse checkout) — examples still cover the schemas.
}
for (const file of fixtureFiles) {
  const doc = JSON.parse(readFileSync(join(fixturesDir, file), "utf8"));
  const dps = [];
  collectDataProducts(doc.messages ?? doc, dps);
  dps.forEach((dp, i) => {
    const s = byKind.get(dp.kind);
    if (s) check(dp.payload, s.schema, `${file} ⇐ ${dp.kind}[#${i}]`, results);
  });
}

// ---- report ----------------------------------------------------------------

let failures = 0;
for (const { label, errors } of results) {
  if (errors.length === 0) {
    console.log(`  ok   ${label}`);
  } else {
    failures += errors.length;
    console.error(`  FAIL ${label}`);
    for (const e of errors) console.error(`         ${e}`);
  }
}
console.log(`\n${results.length} payload(s) checked across ${schemas.length} schema(s).`);
if (failures > 0) {
  console.error(`${failures} violation(s).`);
  process.exit(1);
}
console.log("All report payloads conform to their published schemas.");
