#!/usr/bin/env node
// Single-source codegen for the SpatialScene wire contract.
//
// Source of truth: schema/spatial-scene.schema.json. This script templates that schema into the
// three language mirrors (Kotlin / TypeScript / C++) so they cannot drift. Run it after editing the
// schema; CI runs it with --check to fail if a committed mirror is stale.
//
//   node scripts/codegen/gen-spatial-scene.mjs          # (re)write the mirrors
//   node scripts/codegen/gen-spatial-scene.mjs --check  # fail if any mirror is out of date
//
// Deliberately dependency-free (Node stdlib only) and bespoke to this small schema: it produces the
// existing idiomatic shapes (kotlinx defaults, TS string-literal unions, nlohmann std::optional)
// rather than a generic tool's lowest-common-denominator output. See docs/design/WIRE_IDL_CODEGEN.md.

import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..", "..");
const schemaPath = resolve(repoRoot, "schema/spatial-scene.schema.json");
const schema = JSON.parse(readFileSync(schemaPath, "utf8"));
const defs = schema.definitions;
const cfg = schema["x-codegen"];
const VERSION_CONST = cfg["version-const"];
const VERSION_VALUE = defs[cfg.root].properties.version.default;

const OUTPUTS = {
  kotlin: "api/preview-data-api/src/main/kotlin/ee/schimke/composeai/xr/SpatialScene.kt",
  typescript: "vscode-extension/src/webview/shared/spatialScene.ts",
  cpp: "renderers/xr-composite/src/spatial_scene.hpp",
};

const BANNER = (relPath) => [
  `// GENERATED FILE — DO NOT EDIT.`,
  `// Source of truth: schema/spatial-scene.schema.json`,
  `// Regenerate: node scripts/codegen/gen-spatial-scene.mjs (CI checks with --check).`,
];

// ---- schema helpers ----------------------------------------------------------------------------

const refName = (s) => s.$ref ? s.$ref.replace("#/definitions/", "") : null;

// Resolve a property schema into a normalized descriptor the emitters share.
function describe(name, prop, required) {
  const isReq = required.includes(name);
  let kind, item, ref, nullable = false;
  if (prop.$ref) {
    kind = "ref"; ref = refName(prop);
  } else if (prop.oneOf) {
    nullable = prop.oneOf.some((s) => s.type === "null");
    const r = prop.oneOf.find((s) => s.$ref);
    kind = "ref"; ref = refName(r);
  } else if (prop.type === "array") {
    kind = "array"; item = refName(prop.items) ?? prop.items.type;
  } else if (Array.isArray(prop.type)) {
    nullable = prop.type.includes("null");
    kind = prop.type.find((t) => t !== "null");
  } else {
    kind = prop.type;
  }
  const def = "default" in prop ? prop.default : undefined;
  const hasScalarDefault = def !== undefined && def !== null && kind !== "array";
  return {
    name, kind, item, ref, nullable, required: isReq,
    enum: prop.enum, default: def, hasScalarDefault,
    description: prop.description,
    isVersion: name === "version" && kind === "integer",
  };
}

function properties(defName) {
  const d = defs[defName];
  const req = d.required ?? [];
  return Object.entries(d.properties).map(([n, p]) => describe(n, p, req));
}

const typeOrder = Object.keys(defs); // authored in dependency order

// ---- doc-comment formatting --------------------------------------------------------------------

function block(text, indent = "") {
  if (!text) return [];
  const lines = text.split("\n");
  return [
    `${indent}/**`,
    ...lines.map((l) => `${indent} *${l ? " " + l : ""}`),
    `${indent} */`,
  ];
}

// ---- Kotlin ------------------------------------------------------------------------------------

// ktfmt (googleStyle, 100-col) reflows the generated Kotlin, so emit exactly what it would produce
// — otherwise the file oscillates between this generator (checked by `--check`) and the repo-wide
// ktfmt gate. Two rules to match: KDoc is collapsed to `/** … */` when it fits on one line, else
// greedy-wrapped to 100 cols; a data class collapses onto one `@Serializable`-prefixed line when it
// has no per-parameter KDoc and fits, else `@Serializable` sits on its own line with trailing commas.
const KT_MAX = 100;

function ktWrap(words, width) {
  const lines = [];
  let cur = "";
  for (const w of words) {
    if (cur === "") cur = w;
    else if (cur.length + 1 + w.length <= width) cur += " " + w;
    else {
      lines.push(cur);
      cur = w;
    }
  }
  if (cur !== "") lines.push(cur);
  return lines;
}

function ktDoc(text, indent = "") {
  if (!text) return [];
  const paras = text.split(/\n\s*\n/).map((p) => p.split(/\s+/).filter(Boolean));
  if (paras.length === 1) {
    const oneLine = paras[0].join(" ");
    // `${indent}/** ${text} */` — collapse when the whole comment fits on one line.
    if (indent.length + 4 + oneLine.length + 3 <= KT_MAX) return [`${indent}/** ${oneLine} */`];
  }
  const width = KT_MAX - indent.length - 3; // room after the `${indent} * ` continuation prefix
  const body = [];
  paras.forEach((words, i) => {
    if (i > 0) body.push(`${indent} *`); // blank line between paragraphs
    for (const line of ktWrap(words, width)) body.push(`${indent} * ${line}`);
  });
  return [`${indent}/**`, ...body, `${indent} */`];
}

function ktType(p) {
  const scalar = { number: "Double", integer: "Int", string: "String", boolean: "Boolean" };
  if (p.kind === "array") return `List<${defs[p.item] ? p.item : scalar[p.item]}>`;
  if (p.kind === "ref") return p.ref;
  return scalar[p.kind];
}

function ktField(p) {
  const t = ktType(p);
  let decl;
  if (p.required) {
    decl = `val ${p.name}: ${t}`;
  } else if (p.isVersion) {
    decl = `val ${p.name}: ${t} = ${VERSION_CONST}`;
  } else if (p.hasScalarDefault) {
    const lit = typeof p.default === "string" ? `"${p.default}"` : `${p.default}`;
    decl = `val ${p.name}: ${t} = ${lit}`;
  } else if (p.kind === "array" && Array.isArray(p.default)) {
    decl = `val ${p.name}: ${t} = emptyList()`;
  } else {
    decl = `val ${p.name}: ${t}? = null`;
  }
  return decl;
}

function emitKotlin() {
  const out = [];
  out.push(...BANNER());
  out.push("");
  out.push(`package ${cfg["kotlin-package"]}`);
  out.push("");
  out.push("import kotlinx.serialization.Serializable");
  out.push("");
  out.push(...ktDoc(schema.description));
  out.push(`public const val ${VERSION_CONST}: Int = ${VERSION_VALUE}`);
  for (const name of typeOrder) {
    out.push("");
    out.push(...ktDoc(defs[name].description));
    const props = properties(name);
    const oneLine = `@Serializable public data class ${name}(${props.map(ktField).join(", ")})`;
    if (!props.some((p) => p.description) && oneLine.length <= KT_MAX) {
      out.push(oneLine);
    } else {
      out.push("@Serializable");
      out.push(`public data class ${name}(`);
      props.forEach((p) => {
        if (p.description) out.push(...ktDoc(p.description, "  "));
        out.push(`  ${ktField(p)},`);
      });
      out.push(")");
    }
  }
  return out.join("\n") + "\n";
}

// ---- TypeScript --------------------------------------------------------------------------------

function tsType(p) {
  const scalar = { number: "number", integer: "number", string: "string", boolean: "boolean" };
  if (p.enum) return p.enum.map((e) => `"${e}"`).join(" | ");
  if (p.kind === "array") return `${defs[p.item] ? p.item : scalar[p.item]}[]`;
  if (p.kind === "ref") return p.ref;
  return scalar[p.kind];
}

function tsField(p) {
  // Required in the TS interface when JSON-required, or an always-emitted scalar identity
  // (version/units/kind) carrying a non-null scalar default. Arrays/objects stay optional.
  const tsRequired = p.required || p.hasScalarDefault;
  let t = tsType(p);
  if (p.nullable) t = `${t} | null`;
  return `${p.name}${tsRequired ? "" : "?"}: ${t};`;
}

function emitTypeScript() {
  const out = [];
  out.push(...BANNER());
  out.push("");
  out.push(...block(schema.description).map(stripLeadingSlash));
  out.push(`export const ${VERSION_CONST} = ${VERSION_VALUE};`);
  for (const name of typeOrder) {
    out.push("");
    out.push(...block(defs[name].description));
    const props = properties(name);
    out.push(`export interface ${name} {`);
    props.forEach((p) => {
      if (p.description) out.push(...block(p.description, "    "));
      out.push(`    ${tsField(p)}`);
    });
    out.push("}");
  }
  // Shallow structural guard — logic, not shape; templated against the version const.
  out.push("");
  out.push(...block(
    "Minimal structural guard — rejects payloads the consumer can't safely render: a `version`\n" +
    `other than {@link ${VERSION_CONST}}, or missing required fields. Shallow otherwise; the viewer\n` +
    "should still tolerate missing optional fields."));
  out.push("export function isSpatialScene(value: unknown): value is SpatialScene {");
  out.push("    if (typeof value !== \"object\" || value === null) {");
  out.push("        return false;");
  out.push("    }");
  out.push("    const scene = value as Partial<SpatialScene>;");
  out.push("    return (");
  out.push("        scene.units === \"dp\" &&");
  out.push(`        scene.version === ${VERSION_CONST} &&`);
  out.push("        Array.isArray(scene.panels) &&");
  out.push("        typeof scene.camera === \"object\" &&");
  out.push("        scene.camera !== null");
  out.push("    );");
  out.push("}");
  return out.join("\n") + "\n";
}

const stripLeadingSlash = (l) => l; // banner already // comments; doc blocks stay /** */

// ---- C++ ---------------------------------------------------------------------------------------

function cppType(p) {
  const scalar = { number: "double", integer: "int", string: "std::string", boolean: "bool" };
  if (p.kind === "array") return `std::vector<${defs[p.item] ? p.item : scalar[p.item]}>`;
  if (p.kind === "ref") return p.ref;
  return scalar[p.kind];
}

function cppField(p) {
  const t = cppType(p);
  if (p.kind === "array") return `${t} ${p.name};`;
  if (p.required) return `${t} ${p.name};`;
  if (p.isVersion) return `${t} ${p.name} = ${VERSION_CONST};`;
  if (p.hasScalarDefault) {
    const lit = typeof p.default === "string" ? `"${p.default}"` : `${p.default}`;
    return `${t} ${p.name} = ${lit};`;
  }
  return `std::optional<${t}> ${p.name};`;
}

function emitCpp() {
  const ns = cfg["cpp-namespace"];
  const out = [];
  out.push(...BANNER());
  out.push("");
  out.push("#pragma once");
  out.push("");
  out.push("#include <optional>");
  out.push("#include <string>");
  out.push("#include <vector>");
  out.push("");
  out.push('#include "json.hpp"');
  out.push("");
  out.push(`namespace ${ns} {`);
  out.push("");
  out.push("using nlohmann::json;");
  out.push("");
  out.push(...block(schema.description));
  out.push(`constexpr int ${VERSION_CONST} = ${VERSION_VALUE};`);
  // structs
  for (const name of typeOrder) {
    out.push("");
    out.push(...block(defs[name].description));
    out.push(`struct ${name} {`);
    properties(name).forEach((p) => {
      if (p.description) out.push(...block(p.description, "  "));
      out.push(`  ${cppField(p)}`);
    });
    out.push("};");
  }
  // (de)serializers
  for (const name of typeOrder) {
    const props = properties(name);
    out.push("");
    out.push(`inline void from_json(const json& j, ${name}& x) {`);
    props.forEach((p) => {
      const n = p.name;
      if (p.kind === "array") {
        out.push(`  if (j.contains("${n}")) j.at("${n}").get_to(x.${n});`);
      } else if (p.required) {
        out.push(`  j.at("${n}").get_to(x.${n});`);
      } else if (p.isVersion || p.hasScalarDefault) {
        out.push(`  if (j.contains("${n}") && !j.at("${n}").is_null()) j.at("${n}").get_to(x.${n});`);
      } else {
        out.push(`  if (j.contains("${n}") && !j.at("${n}").is_null()) x.${n} = j.at("${n}").get<${cppType(p)}>();`);
      }
    });
    out.push("}");
    out.push("");
    out.push(`inline void to_json(json& j, const ${name}& x) {`);
    out.push("  j = json::object();");
    props.forEach((p) => {
      const n = p.name;
      const optional = !p.required && !p.isVersion && !p.hasScalarDefault && p.kind !== "array";
      if (optional) {
        out.push(`  if (x.${n}) j["${n}"] = *x.${n};`);
      } else {
        out.push(`  j["${n}"] = x.${n};`);
      }
    });
    out.push("}");
  }
  out.push("");
  out.push(`}  // namespace ${ns}`);
  return out.join("\n") + "\n";
}

// ---- driver ------------------------------------------------------------------------------------

const generated = {
  [OUTPUTS.kotlin]: emitKotlin(),
  [OUTPUTS.typescript]: emitTypeScript(),
  [OUTPUTS.cpp]: emitCpp(),
};

const check = process.argv.includes("--check");
let stale = [];
for (const [rel, content] of Object.entries(generated)) {
  const abs = resolve(repoRoot, rel);
  if (check) {
    let current = "";
    try { current = readFileSync(abs, "utf8"); } catch { /* missing */ }
    if (current !== content) stale.push(rel);
  } else {
    writeFileSync(abs, content);
    console.log(`wrote ${rel}`);
  }
}

if (check) {
  if (stale.length) {
    console.error("Stale generated mirrors (run: node scripts/codegen/gen-spatial-scene.mjs):");
    stale.forEach((s) => console.error(`  - ${s}`));
    process.exit(1);
  }
  console.log("SpatialScene mirrors are up to date.");
}
