#!/usr/bin/env node
/**
 * Resolve a `code-connect.json` manifest (emitted by `figma-code-connect-emit.mjs`) against a Figma
 * file's layer tree and produce the `send_code_connect_mappings` payload — the last mile that binds
 * each Compose component to a real Figma node id.
 *
 * Two-step flow:
 *   1. export     generate-design-catalog.mjs writes code-connect.json onto design-artifacts/<system>
 *   2. import     a designer imports that catalog into a Figma file (frames named by componentId)
 *   3. publish    THIS script resolves componentId → nodeId and emits the mappings payload
 *
 *   node scripts/design-artifacts/publish-code-connect.mjs \
 *     --manifest <path to code-connect.json> \
 *     --file <figma file key or /design/ URL> \
 *     --out   <path to write send-mappings.json>   # else printed to stdout
 *
 * Node-name resolution uses the Figma REST API (`GET /v1/files/:key`, `X-Figma-Token: $FIGMA_TOKEN`)
 * — which any readable file allows. The resulting `send-mappings.json` is the exact argument object
 * for Figma's MCP `send_code_connect_mappings` tool (or feed the same mappings to `figma connect`).
 * **Actually creating the Code Connect records requires a Figma Org/Enterprise plan with a Dev/Full
 * seat** — this script only prepares the payload, so it is safe to run (and test the resolution) on
 * any plan.
 *
 * The tree-walk, name index, and payload shaping are pure functions (unit-tested); only the REST
 * fetch + file IO live in the CLI shell at the bottom.
 */
import { readFile, writeFile } from "node:fs/promises";
import { parseArgs } from "node:util";

/** Extract a Figma file key from a bare key or a `figma.com/design/:key/...` URL. */
export function fileKeyFromArg(arg) {
  if (!arg) return null;
  const m = String(arg).match(/\/design\/([A-Za-z0-9]+)/);
  return m ? m[1] : String(arg);
}

/**
 * Index every node in a Figma file document by its layer `name` → list of node ids, walking the
 * `document.children` tree (pages → frames → …). A name can repeat (the catalog wraps each sticker
 * in a `Card: <id>` frame that contains the bare `<id>` frame), so ids are collected into an array
 * and the caller decides how to disambiguate.
 */
export function indexNodesByName(document) {
  const byName = new Map();
  const visit = (node) => {
    if (!node || typeof node !== "object") return;
    if (typeof node.name === "string" && node.id) {
      const ids = byName.get(node.name);
      if (ids) ids.push(node.id);
      else byName.set(node.name, [node.id]);
    }
    for (const child of node.children ?? []) visit(child);
  };
  visit(document);
  return byName;
}

/**
 * Resolve each manifest mapping's `figmaLayerName` to a node id via [nameIndex].
 *
 * Returns `{ resolved, unresolved, ambiguous }`:
 * - `resolved` — mappings that matched exactly one node, each with `nodeId` filled in.
 * - `unresolved` — mappings whose layer name is absent from the file (nothing to bind).
 * - `ambiguous` — mappings whose name matched multiple nodes; the FIRST id is used (and the mapping
 *   still lands in `resolved`), but it is also reported here so the caller can warn. The importer
 *   names the inner sticker frame and its `Card:` wrapper differently, so an exact componentId match
 *   is normally unique; a genuine duplicate (the same component twice on the board) is the ambiguous
 *   case worth surfacing.
 */
export function resolveMappings(manifest, nameIndex) {
  const resolved = [];
  const unresolved = [];
  const ambiguous = [];
  for (const mapping of manifest.mappings ?? []) {
    const ids = nameIndex.get(mapping.figmaLayerName);
    if (!ids || ids.length === 0) {
      unresolved.push(mapping.figmaLayerName);
      continue;
    }
    if (ids.length > 1) ambiguous.push({ name: mapping.figmaLayerName, ids: [...ids] });
    resolved.push({ ...mapping, nodeId: ids[0] });
  }
  return { resolved, unresolved, ambiguous };
}

/** Escape a code string for safe embedding inside a JS template literal (`figma.code` ...``). */
function escapeForTemplateLiteral(code) {
  return code.replace(/\\/g, "\\\\").replace(/`/g, "\\`").replace(/\$\{/g, "\\${");
}

/**
 * Wrap a rendered Kotlin call site in a Code Connect **parserless template** — executable JS that
 * emits the snippet via `figma.code`, the form Figma's Dev Mode / MCP renders. Imports are carried
 * separately in `templateDataJson`, per the `send_code_connect_mappings` contract.
 */
export function codeConnectTemplate(codeSnippet) {
  return "const figma = require('figma')\nexport default figma.code`" + escapeForTemplateLiteral(codeSnippet) + "`";
}

/**
 * Shape resolved mappings into the argument object for Figma's `send_code_connect_mappings` MCP
 * tool: `{ fileKey, nodeId, mappings: [{ nodeId, componentName, source, label, template?,
 * templateDataJson? }] }`. `nodeId` at the top level is required by the tool and set to the first
 * mapping's node (an anchor); the per-mapping `nodeId`s carry the real bindings.
 *
 * When a mapping carries a `codeSnippet` (a real call site, from the emit step), it is turned into a
 * `figma.code` template + `templateDataJson` (`isParserless` + `imports`) so Dev Mode shows the real
 * call, not just the component name. An explicit `m.template` overrides the generated one.
 */
export function toSendMappingsPayload(fileKey, resolved) {
  const mappings = resolved.map((m) => {
    const out = {
      nodeId: m.nodeId,
      componentName: m.componentName,
      source: m.source,
      label: m.label,
    };
    const template = m.template ?? (m.codeSnippet ? codeConnectTemplate(m.codeSnippet) : null);
    if (template) {
      out.template = template;
      out.templateDataJson = JSON.stringify({ isParserless: true, imports: m.imports ?? [] });
    }
    return out;
  });
  return {
    fileKey,
    nodeId: mappings[0]?.nodeId ?? "",
    mappings,
  };
}

// --- CLI shell ----------------------------------------------------------------

async function fetchFigmaFile(fileKey, token) {
  const res = await fetch(`https://api.figma.com/v1/files/${fileKey}`, {
    headers: { "X-Figma-Token": token },
  });
  if (!res.ok) {
    throw new Error(`Figma API ${res.status} ${res.statusText} for file ${fileKey}`);
  }
  return res.json();
}

async function main() {
  const { values } = parseArgs({
    options: {
      manifest: { type: "string" },
      file: { type: "string" },
      out: { type: "string" },
    },
  });
  if (!values.manifest || !values.file) {
    console.error(
      "usage: publish-code-connect --manifest <code-connect.json> --file <key|/design/ URL> [--out <send-mappings.json>]",
    );
    process.exit(2);
  }
  const token = process.env.FIGMA_TOKEN;
  if (!token) {
    console.error("FIGMA_TOKEN env var is required (a Figma personal access token that can read the file).");
    process.exit(2);
  }
  const manifest = JSON.parse(await readFile(values.manifest, "utf8"));
  const fileKey = fileKeyFromArg(values.file);
  const doc = await fetchFigmaFile(fileKey, token);
  const nameIndex = indexNodesByName(doc.document);
  const { resolved, unresolved, ambiguous } = resolveMappings(manifest, nameIndex);

  for (const name of unresolved) {
    console.warn(`[code-connect] no node named "${name}" in file ${fileKey} — skipped`);
  }
  for (const a of ambiguous) {
    console.warn(`[code-connect] "${a.name}" matched ${a.ids.length} nodes; using ${a.ids[0]}`);
  }

  // Nothing resolved ⇒ every layer name was absent (wrong file, or the board's frames were renamed).
  // A `send_code_connect_mappings` payload needs at least one real node id — the top-level `nodeId` is
  // required — so writing one with an empty anchor + no mappings would be a success-looking invalid
  // artifact. Fail loudly instead of emitting it.
  if (resolved.length === 0) {
    console.error(
      `[code-connect] resolved 0/${manifest.mappings?.length ?? 0} mapping(s) in file ${fileKey} — ` +
        "no layer names matched. Check the file is the imported catalog and its frames are named by " +
        "componentId. Nothing written.",
    );
    process.exit(1);
  }

  const payload = toSendMappingsPayload(fileKey, resolved);
  const json = `${JSON.stringify(payload, null, 2)}\n`;
  if (values.out) {
    await writeFile(values.out, json, "utf8");
    console.log(
      `[code-connect] resolved ${resolved.length}/${manifest.mappings?.length ?? 0} mapping(s) → ${values.out}`,
    );
  } else {
    process.stdout.write(json);
  }
  console.log(
    "[code-connect] hand this to Figma's send_code_connect_mappings MCP tool (or `figma connect`) " +
      "on an Org/Enterprise plan to publish.",
  );
}

// Only run the CLI when executed directly (not when imported by tests).
if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((err) => {
    console.error(err.message ?? err);
    process.exit(1);
  });
}
