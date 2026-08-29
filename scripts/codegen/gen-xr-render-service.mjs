#!/usr/bin/env node
// Single-source codegen for the XR render service RPC surface.
//
// Source of truth: schema/xr-render-service.schema.json. This script templates that schema into
// the Kotlin and C++ mirrors so the two sides of the boundary cannot drift. Run it after editing
// the schema; CI runs it with --check to fail if a committed mirror is stale.
//
//   node scripts/codegen/gen-xr-render-service.mjs          # (re)write the mirrors
//   node scripts/codegen/gen-xr-render-service.mjs --check  # fail if any mirror is out of date
//
// Sibling of gen-spatial-scene.mjs and deliberately built the same way — Node stdlib only, bespoke
// to this schema, emitting the idiomatic shape each language already uses. The two generate
// different KINDS of thing: that one emits the SpatialScene *data types*, this one emits the
// *protocol vocabulary* (method names, parameter keys, result keys, capability keys, error codes,
// and the service version). Names are what drift across a repository boundary — a renamed method
// or param key compiles on both sides and fails at runtime — so names are what get single-sourced.
//
// Why constants rather than generated request/response structs: `main.cpp` reads params inline off
// nlohmann `json` and `XrServerClient` builds them with `buildJsonObject`, both of which stay
// readable. Replacing every literal with a generated constant removes the drift without
// restructuring either handler. See schema/README.md.

import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, "..", "..");
const schemaPath = resolve(repoRoot, "schema/xr-render-service.schema.json");
const schema = JSON.parse(readFileSync(schemaPath, "utf8"));
const cfg = schema["x-codegen"];
const svc = schema["x-service"];
const VERSION_CONST = cfg["version-const"];

const OUTPUTS = {
  kotlin:
    "renderers/xr-client/src/main/kotlin/ee/schimke/composeai/renderer/xr/client/XrRenderService.kt",
  cpp: "renderers/xr-composite/src/xr_render_service.hpp",
  // The serve smoke harness was the third hand-maintained copy of this vocabulary. It gets a
  // generated mirror too, so a renamed method breaks the harness at import rather than leaving it
  // asserting against a protocol nobody speaks any more.
  python: "renderers/xr-composite/test/xr_render_service.py",
};

const BANNER = () => [
  `// GENERATED FILE — DO NOT EDIT.`,
  `// Source of truth: schema/xr-render-service.schema.json`,
  `// Regenerate: node scripts/codegen/gen-xr-render-service.mjs (CI checks with --check).`,
];

// ---- naming ------------------------------------------------------------------------------------

// `xr/updatePanels` -> `UpdatePanels`; `render` -> `Render`. The `xr/` prefix is transport
// vocabulary, not part of the identifier, so it is dropped rather than mangled into the name.
const identOf = (method) => {
  const bare = method.includes("/") ? method.slice(method.indexOf("/") + 1) : method;
  return bare.charAt(0).toUpperCase() + bare.slice(1);
};

// Parameter/result/capability keys are already lowerCamelCase JSON keys; reuse them verbatim as
// the constant's own name so a reader can map constant to wire key without a lookup table.
const upperSnake = (s) => s.replace(/([a-z0-9])([A-Z])/g, "$1_$2").toUpperCase();

// Collect every distinct parameter key across all methods and the notification, so both sides
// share ONE set of key constants. The keys mean the same thing wherever they appear (`sessionId`
// is `sessionId`), and per-method duplicates would invite them to diverge.
function paramKeys() {
  const keys = new Map();
  for (const [name, m] of Object.entries(svc.methods)) {
    for (const [k, desc] of Object.entries(m.params ?? {})) {
      if (!keys.has(k)) keys.set(k, { desc, seenIn: [name] });
      else keys.get(k).seenIn.push(name);
    }
  }
  for (const [name, n] of Object.entries(svc.notifications)) {
    for (const [k, desc] of Object.entries(n.params ?? {})) {
      if (!keys.has(k)) keys.set(k, { desc, seenIn: [name] });
      else keys.get(k).seenIn.push(name);
    }
  }
  return keys;
}

function resultKeys() {
  const keys = new Map();
  for (const [name, m] of Object.entries(svc.methods)) {
    for (const [k, desc] of Object.entries(m.result ?? {})) {
      if (!keys.has(k)) keys.set(k, { desc, seenIn: [name] });
      else keys.get(k).seenIn.push(name);
    }
  }
  return keys;
}

// ---- doc comments ------------------------------------------------------------------------------

// Matches gen-spatial-scene.mjs's ktfmt-aware wrapping: ktfmt (googleStyle, 100 cols) reflows the
// generated Kotlin, so emit exactly what it would produce or the file oscillates between this
// generator's --check and the repo-wide ktfmt gate.
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
    if (indent.length + 4 + oneLine.length + 3 <= KT_MAX) return [`${indent}/** ${oneLine} */`];
  }
  const width = KT_MAX - indent.length - 3;
  const body = [];
  paras.forEach((words, i) => {
    if (i > 0) body.push(`${indent} *`);
    for (const line of ktWrap(words, width)) body.push(`${indent} * ${line}`);
  });
  return [`${indent}/**`, ...body, `${indent} */`];
}

function cppDoc(text, indent = "") {
  if (!text) return [];
  return [
    `${indent}/**`,
    ...text.split("\n").map((l) => `${indent} *${l ? " " + l : ""}`),
    `${indent} */`,
  ];
}

// ---- Kotlin ------------------------------------------------------------------------------------

function emitKotlin() {
  const obj = cfg["kotlin-object"];
  const out = [];
  out.push(...BANNER());
  out.push("");
  out.push(`package ${cfg["kotlin-package"]}`);
  out.push("");
  out.push(...ktDoc(schema.description));
  out.push(`public object ${obj} {`);
  out.push(...ktDoc(svc["version-description"], "  "));
  out.push(`  public const val ${VERSION_CONST}: Int = ${svc.version}`);
  out.push("");
  out.push(...ktDoc(svc["min-supported-version-description"], "  "));
  out.push(`  public const val MIN_SUPPORTED_${VERSION_CONST}: Int = ${svc["min-supported-version"]}`);
  out.push("");
  out.push(...ktDoc(`Value of \`initialize\`'s \`serverInfo.name\`.`, "  "));
  out.push(`  public const val SERVER_NAME: String = "${svc["server-name"]}"`);
  out.push("");
  out.push(...ktDoc(svc["default-session-id-description"], "  "));
  out.push(`  public const val DEFAULT_SESSION_ID: String = "${svc["default-session-id"]}"`);

  out.push("");
  out.push(...ktDoc("Method names. Each is the exact JSON-RPC `method` string.", "  "));
  out.push("  public object Method {");
  for (const [name, m] of Object.entries(svc.methods)) {
    out.push(...ktDoc(m.description, "    "));
    out.push(`    public const val ${upperSnake(identOf(name))}: String = "${name}"`);
    if (m.alias) {
      out.push(
        ...ktDoc(`Accepted alias for [${upperSnake(identOf(name))}].`, "    "),
      );
      out.push(`    public const val ${upperSnake(identOf(name))}_ALIAS: String = "${m.alias}"`);
    }
  }
  out.push("  }");

  out.push("");
  out.push(...ktDoc("Notification names — server-pushed, never answered.", "  "));
  out.push("  public object Notification {");
  for (const [name, n] of Object.entries(svc.notifications)) {
    out.push(...ktDoc(n.description, "    "));
    out.push(`    public const val ${upperSnake(name)}: String = "${name}"`);
  }
  out.push("  }");

  out.push("");
  out.push(...ktDoc("Request/notification parameter keys, shared across every method that uses them.", "  "));
  out.push("  public object Param {");
  for (const [k, v] of paramKeys()) {
    out.push(...ktDoc(v.desc, "    "));
    out.push(`    public const val ${upperSnake(k)}: String = "${k}"`);
  }
  out.push("  }");

  out.push("");
  out.push(...ktDoc("Response result keys.", "  "));
  out.push("  public object Result {");
  for (const [k, v] of resultKeys()) {
    out.push(...ktDoc(v.desc, "    "));
    out.push(`    public const val ${upperSnake(k)}: String = "${k}"`);
  }
  out.push("  }");

  out.push("");
  out.push(...ktDoc("Keys of `initialize`'s `capabilities` object.", "  "));
  out.push("  public object Capability {");
  for (const [k, desc] of Object.entries(svc.capabilities)) {
    out.push(...ktDoc(desc, "    "));
    out.push(`    public const val ${upperSnake(k)}: String = "${k}"`);
  }
  out.push("  }");

  out.push("");
  out.push(...ktDoc("JSON-RPC error codes this service returns.", "  "));
  out.push("  public object ErrorCode {");
  for (const [k, e] of Object.entries(svc.errors)) {
    out.push(...ktDoc(e.description, "    "));
    out.push(`    public const val ${upperSnake(k)}: Int = ${e.code}`);
  }
  out.push("  }");
  out.push("}");
  return out.join("\n") + "\n";
}

// ---- C++ ---------------------------------------------------------------------------------------

function emitCpp() {
  const ns = cfg["cpp-namespace"];
  const inner = cfg["cpp-guard"];
  const out = [];
  out.push(...BANNER());
  out.push("");
  out.push("#pragma once");
  out.push("");
  // `const char*` rather than `std::string_view`: these constants are used both as nlohmann
  // json KEYS and in `==` against `std::string`. nlohmann only accepts a string_view key on
  // recent versions with C++17 detection enabled, while `const char*` works on every version
  // and in both positions.
  out.push("");
  out.push(`namespace ${ns}::${inner} {`);
  out.push("");
  out.push(...cppDoc(schema.description));
  out.push("");
  out.push(...cppDoc(svc["version-description"]));
  out.push(`constexpr int ${VERSION_CONST} = ${svc.version};`);
  out.push("");
  out.push(...cppDoc(svc["min-supported-version-description"]));
  out.push(`constexpr int kMinSupported${VERSION_CONST
    .split("_").map((w) => w.charAt(0) + w.slice(1).toLowerCase()).join("")} = ${svc["min-supported-version"]};`);
  out.push("");
  out.push(...cppDoc("Value of `initialize`'s `serverInfo.name`."));
  out.push(`constexpr const char* kServerName = "${svc["server-name"]}";`);
  out.push("");
  out.push(...cppDoc(svc["default-session-id-description"]));
  out.push(`constexpr const char* kDefaultSessionId = "${svc["default-session-id"]}";`);

  const section = (title, entries) => {
    out.push("");
    out.push(...cppDoc(title));
    for (const [constName, value, desc] of entries) {
      out.push(...cppDoc(desc));
      out.push(`constexpr const char* ${constName} = "${value}";`);
    }
  };

  section(
    "Method names. Each is the exact JSON-RPC `method` string.",
    Object.entries(svc.methods).flatMap(([name, m]) => {
      const rows = [[`kMethod${identOf(name)}`, name, m.description]];
      if (m.alias) {
        rows.push([
          `kMethod${identOf(name)}Alias`,
          m.alias,
          `Accepted alias for kMethod${identOf(name)}.`,
        ]);
      }
      return rows;
    }),
  );

  section(
    "Notification names — server-pushed, never answered.",
    Object.entries(svc.notifications).map(([name, n]) => [
      `kNotification${identOf(name)}`,
      name,
      n.description,
    ]),
  );

  section(
    "Request/notification parameter keys, shared across every method that uses them.",
    [...paramKeys()].map(([k, v]) => [`kParam${identOf(k)}`, k, v.desc]),
  );

  section(
    "Response result keys.",
    [...resultKeys()].map(([k, v]) => [`kResult${identOf(k)}`, k, v.desc]),
  );

  section(
    "Keys of `initialize`'s `capabilities` object.",
    Object.entries(svc.capabilities).map(([k, desc]) => [`kCapability${identOf(k)}`, k, desc]),
  );

  out.push("");
  out.push(...cppDoc("JSON-RPC error codes this service returns."));
  for (const [k, e] of Object.entries(svc.errors)) {
    out.push(...cppDoc(e.description));
    out.push(`constexpr int kError${identOf(k)} = ${e.code};`);
  }

  out.push("");
  out.push(`}  // namespace ${ns}::${inner}`);
  return out.join("\n") + "\n";
}

// ---- Python ------------------------------------------------------------------------------------

function pyDoc(text, indent = "") {
  if (!text) return [];
  return text.split("\n").map((l) => `${indent}#${l ? " " + l : ""}`);
}

function emitPython() {
  const out = [];
  out.push(...BANNER().map((l) => l.replace(/^\/\/ ?/, "# ").trimEnd()));
  out.push("");
  out.push('"""' + schema.title + '.');
  out.push("");
  out.push(schema.description);
  out.push('"""');
  out.push("");
  out.push(...pyDoc(svc["version-description"]));
  out.push(`${VERSION_CONST} = ${svc.version}`);
  out.push("");
  out.push(...pyDoc(svc["min-supported-version-description"]));
  out.push(`MIN_SUPPORTED_${VERSION_CONST} = ${svc["min-supported-version"]}`);
  out.push("");
  out.push('# Value of `initialize`\'s `serverInfo.name`.');
  out.push(`SERVER_NAME = "${svc["server-name"]}"`);
  out.push("");
  out.push(...pyDoc(svc["default-session-id-description"]));
  out.push(`DEFAULT_SESSION_ID = "${svc["default-session-id"]}"`);

  const dict = (title, rows) => {
    out.push("");
    out.push(...pyDoc(title));
    for (const [name, value, desc] of rows) {
      out.push(...pyDoc(desc));
      out.push(`${name} = "${value}"`);
    }
  };

  dict(
    "Method names. Each is the exact JSON-RPC `method` string.",
    Object.entries(svc.methods).flatMap(([name, m]) => {
      const rows = [[`METHOD_${upperSnake(identOf(name))}`, name, m.description]];
      if (m.alias) {
        rows.push([
          `METHOD_${upperSnake(identOf(name))}_ALIAS`,
          m.alias,
          `Accepted alias for METHOD_${upperSnake(identOf(name))}.`,
        ]);
      }
      return rows;
    }),
  );
  dict(
    "Notification names — server-pushed, never answered.",
    Object.entries(svc.notifications).map(([name, n]) => [
      `NOTIFICATION_${upperSnake(name)}`,
      name,
      n.description,
    ]),
  );
  dict(
    "Request/notification parameter keys, shared across every method that uses them.",
    [...paramKeys()].map(([k, v]) => [`PARAM_${upperSnake(k)}`, k, v.desc]),
  );
  dict(
    "Response result keys.",
    [...resultKeys()].map(([k, v]) => [`RESULT_${upperSnake(k)}`, k, v.desc]),
  );
  dict(
    "Keys of `initialize`'s `capabilities` object.",
    Object.entries(svc.capabilities).map(([k, desc]) => [`CAPABILITY_${upperSnake(k)}`, k, desc]),
  );

  out.push("");
  out.push(...pyDoc("JSON-RPC error codes this service returns."));
  for (const [k, e] of Object.entries(svc.errors)) {
    out.push(...pyDoc(e.description));
    out.push(`ERROR_${upperSnake(k)} = ${e.code}`);
  }
  return out.join("\n") + "\n";
}

// ---- driver ------------------------------------------------------------------------------------

const generated = {
  [OUTPUTS.kotlin]: emitKotlin(),
  [OUTPUTS.cpp]: emitCpp(),
  [OUTPUTS.python]: emitPython(),
};

const check = process.argv.includes("--check");
const stale = [];
for (const [rel, content] of Object.entries(generated)) {
  const abs = resolve(repoRoot, rel);
  if (check) {
    let current = "";
    try {
      current = readFileSync(abs, "utf8");
    } catch {
      /* missing counts as stale */
    }
    if (current !== content) stale.push(rel);
  } else {
    writeFileSync(abs, content);
    console.log(`wrote ${rel}`);
  }
}

if (check) {
  if (stale.length) {
    console.error(
      "Stale generated mirrors (run: node scripts/codegen/gen-xr-render-service.mjs):",
    );
    stale.forEach((s) => console.error(`  - ${s}`));
    process.exit(1);
  }
  console.log("XR render service mirrors are up to date.");
}
