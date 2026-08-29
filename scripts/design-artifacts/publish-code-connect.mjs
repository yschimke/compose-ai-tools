#!/usr/bin/env node
/**
 * Resolve a `code-connect.json` manifest (emitted by `figma-code-connect-emit.mjs`) against a Figma
 * file's layer tree and produce the `send_code_connect_mappings` payload — the last mile that binds
 * each Compose component to a real Figma node id. The same pass extracts native Figma SLOT nodes
 * into `figma-slots.json`, without requiring the repository's Figma plugin to touch the file.
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
import { dirname, join } from "node:path";
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
 * Index a Figma document's **component sets / components** by name → their
 * `componentPropertyDefinitions` (variant / boolean / text properties). This is where the props a
 * call site can bind to live — a real design-system `Button` component set carries `State`, `Size`,
 * etc. NOTE: the code-led rendered catalog is plain frames with *no* component sets, so this is empty
 * for it; variant binding activates when publishing against an actual Figma design system.
 */
export function variantPropsByName(document) {
  const byName = new Map();
  const visit = (node) => {
    if (!node || typeof node !== "object") return;
    if (
      (node.type === "COMPONENT_SET" || node.type === "COMPONENT") &&
      node.componentPropertyDefinitions &&
      typeof node.name === "string"
    ) {
      byName.set(node.name, node.componentPropertyDefinitions);
    }
    for (const child of node.children ?? []) visit(child);
  };
  visit(document);
  return byName;
}

/** Build an id → node index for one Figma REST document tree. */
function indexDocument(document) {
  const nodes = new Map();
  const visit = (node) => {
    if (!node || typeof node !== "object") return;
    if (node.id) nodes.set(node.id, node);
    for (const child of node.children ?? []) visit(child);
  };
  visit(document);
  return nodes;
}

const finiteNumber = (value) => (Number.isFinite(value) ? value : null);

function nodeBounds(node) {
  const box = node?.absoluteBoundingBox;
  if (box && [box.x, box.y, box.width, box.height].every(Number.isFinite)) {
    return { x: box.x, y: box.y, width: box.width, height: box.height };
  }
  const transform = node?.relativeTransform;
  const x = finiteNumber(transform?.[0]?.[2]);
  const y = finiteNumber(transform?.[1]?.[2]);
  const width = finiteNumber(node?.size?.x ?? node?.width);
  const height = finiteNumber(node?.size?.y ?? node?.height);
  return x == null || y == null || width == null || height == null ? null : { x, y, width, height };
}

function relativeBounds(node, host) {
  const bounds = nodeBounds(node);
  const hostBounds = nodeBounds(host);
  if (!bounds) return null;
  if (!hostBounds || !node?.absoluteBoundingBox || !host?.absoluteBoundingBox) return bounds;
  return {
    x: bounds.x - hostBounds.x,
    y: bounds.y - hostBounds.y,
    width: bounds.width,
    height: bounds.height,
  };
}

function slotPropertyDefinitions(node) {
  return Object.entries(node?.componentPropertyDefinitions ?? {}).filter(
    ([, definition]) => definition?.type === "SLOT",
  );
}

function slotPropertyForNode(slot, ancestors) {
  const propertyKey = slot.componentPropertyReferences?.slotContentId;
  for (const ancestor of ancestors) {
    const definitions = slotPropertyDefinitions(ancestor);
    if (propertyKey) {
      const exact = definitions.find(([key]) => key === propertyKey);
      if (exact) return exact;
    }
    const byName = definitions.find(([key]) => normalizeName(propDisplayName(key)) === normalizeName(slot.name));
    if (byName) return byName;
  }
  return [propertyKey ?? slot.name ?? "slot", { type: "SLOT" }];
}

function preferredValues(definition) {
  return (definition?.preferredValues ?? [])
    .filter(
      (value) => (value?.type === "COMPONENT" || value?.type === "COMPONENT_SET") && typeof value.key === "string",
    )
    .map(({ type, key }) => ({ type, key }));
}

function slotSettings(definition) {
  const settings = definition?.slotSettings;
  if (!settings || typeof settings !== "object") return undefined;
  const out = {};
  for (const key of [
    "stretchChildOnInsert",
    "displayEmptyByDefault",
    "minChildren",
    "maxChildren",
    "allowPreferredValuesOnly",
  ]) {
    if (settings[key] !== undefined) out[key] = settings[key];
  }
  return Object.keys(out).length ? out : undefined;
}

function definedProperties(source, keys) {
  const out = {};
  for (const key of keys) {
    if (source?.[key] !== undefined && source[key] !== null) out[key] = source[key];
  }
  return out;
}

function slotChild(child, components) {
  const component = child.type === "INSTANCE" ? components?.[child.componentId] : null;
  return {
    nodeId: child.id,
    name: child.name ?? child.id,
    type: child.type ?? "UNKNOWN",
    ...(child.componentId ? { componentNodeId: child.componentId } : {}),
    ...(component?.key ? { componentKey: component.key } : {}),
  };
}

/**
 * Extract native Figma slots from the raw REST payload. Figma's published REST schema currently
 * omits SLOT, but the service returns SLOT nodes and SLOT component-property definitions. This
 * deliberately duck-types those fields so new files work without a plugin/export pass.
 *
 * Only roots already resolved through code-connect.json become hosts. That keeps the output joined
 * to catalog component ids and avoids treating unrelated library components in a large Figma file
 * as part of this catalog.
 */
export function extractSlotManifest(fileKey, figmaFile, resolved) {
  const document = figmaFile?.document ?? figmaFile;
  const nodes = indexDocument(document);
  const components = figmaFile?.components ?? {};
  const hosts = [];

  for (const mapping of resolved) {
    const host = nodes.get(mapping.nodeId);
    if (!host) continue;
    const slots = [];
    const visit = (node, ancestors) => {
      const nextAncestors = [node, ...ancestors];
      if (node.type === "SLOT") {
        const [propertyKey, definition] = slotPropertyForNode(node, ancestors);
        const values = preferredValues(definition);
        const settings = slotSettings(definition);
        const bounds = relativeBounds(node, host);
        const constraints = definedProperties(node, [
          "minWidth",
          "maxWidth",
          "minHeight",
          "maxHeight",
          "layoutAlign",
          "layoutGrow",
          "preserveRatio",
        ]);
        slots.push({
          name: propDisplayName(propertyKey || node.name || "slot"),
          propertyKey,
          nodeId: node.id,
          ...(typeof definition.description === "string" && definition.description
            ? { description: definition.description }
            : {}),
          ...(bounds ? { bounds } : {}),
          layout: {
            mode: node.layoutMode ?? "NONE",
            horizontal: node.layoutSizingHorizontal ?? "FIXED",
            vertical: node.layoutSizingVertical ?? "FIXED",
            clipsContent: node.clipsContent === true,
            ...definedProperties(node, [
              "primaryAxisAlignItems",
              "counterAxisAlignItems",
              "itemSpacing",
              "paddingLeft",
              "paddingTop",
              "paddingRight",
              "paddingBottom",
            ]),
          },
          ...(Object.keys(constraints).length ? { constraints } : {}),
          ...(values.length ? { preferredValues: values } : {}),
          ...(settings ? { settings } : {}),
          children: (node.children ?? []).map((child) => slotChild(child, components)),
        });
        return; // A Figma slot cannot contain another slot; don't infer one from arbitrary content.
      }
      for (const child of node.children ?? []) visit(child, nextAncestors);
    };
    for (const child of host.children ?? []) visit(child, [host]);
    if (slots.length === 0) continue;
    hosts.push({
      componentId: mapping.componentId ?? mapping.figmaLayerName,
      componentName: mapping.componentName,
      nodeId: host.id,
      name: host.name ?? mapping.figmaLayerName,
      ...(nodeBounds(host) ? { bounds: nodeBounds(host) } : {}),
      slots,
    });
  }

  return {
    schema: "compose-preview-figma-slots/v1",
    fileKey,
    ...(figmaFile?.version ? { version: figmaFile.version } : {}),
    hosts,
  };
}

/** Normalized key for matching a Figma property name to a Kotlin parameter name. */
const normalizeName = (s) => String(s ?? "").toLowerCase().replace(/[^a-z0-9]/g, "");

/** Strip Figma's `#nodeId` suffix from a non-variant property key to get its display name. */
const propDisplayName = (key) => key.split("#")[0];

/** A safe Kotlin/Figma identifier — guards the string-built template against injection. */
const isSafeIdent = (s) => /^[A-Za-z_][A-Za-z0-9_]*$/.test(s);

/**
 * The `figma.properties.*` expression that binds a Kotlin parameter to a Figma property, or null for
 * an unsupported/instance-swap property. A VARIANT (enum) maps each option to a best-effort code
 * value `Type.Option` for the developer to confirm; BOOLEAN → `figma.properties.boolean`; TEXT →
 * `figma.properties.string`.
 */
export function bindingExpression(name, def, param) {
  const type = def?.type;
  if (type === "VARIANT") {
    const options = Array.isArray(def.variantOptions) ? def.variantOptions : [];
    const enumType = param?.type && isSafeIdent(param.type) ? param.type : null;
    const map = {};
    for (const opt of options) {
      const optIdent = String(opt).replace(/[^A-Za-z0-9_]/g, "");
      map[opt] = enumType && optIdent ? `${enumType}.${optIdent}` : optIdent || String(opt);
    }
    // JSON.stringify the display name (not `'${name}'`) so an apostrophe/backslash in a Figma-authored
    // property name can't break out of the JS string and produce an invalid template.
    return `figma.properties.enum(${JSON.stringify(name)}, ${JSON.stringify(map)})`;
  }
  if (type === "BOOLEAN") return `figma.properties.boolean(${JSON.stringify(name)})`;
  if (type === "TEXT") return `figma.properties.string(${JSON.stringify(name)})`;
  return null; // INSTANCE_SWAP and anything else: no scalar binding.
}

/**
 * Build a `figma.code` template whose required parameters are **bound to Figma properties** where a
 * property name matches the parameter name. Bound params interpolate a live `figma.properties.*`
 * expression; unmatched params keep their `TODO("Type")` / `{ }` placeholder. Returns
 * `{ template, boundProps }`, or null when nothing bound (caller falls back to the static template).
 */
export function buildBoundTemplate(componentName, parameters = [], propDefs = {}) {
  if (!isSafeIdent(componentName)) return null;
  // Figma property display-name (normalized) → { name, def }.
  const propByNorm = new Map();
  for (const [key, def] of Object.entries(propDefs)) {
    const dn = propDisplayName(key);
    propByNorm.set(normalizeName(dn), { name: dn, def });
  }
  const required = parameters.filter((p) => !p.hasDefault);
  const boundProps = [];
  const declarations = [];
  const lines = required.map((p) => {
    const match = isSafeIdent(p.name) ? propByNorm.get(normalizeName(p.name)) : undefined;
    if (p.composableSlot && match?.def?.type === "SLOT") {
      const variable = `slot_${p.name}`;
      declarations.push(`const ${variable} = figma.selectedInstance.getSlot(${JSON.stringify(match.name)})`);
      boundProps.push(match.name);
      return `    ${p.name} = { \${${variable}} },`;
    }
    const expr = match ? bindingExpression(match.name, match.def, p) : null;
    if (expr) {
      boundProps.push(match.name);
      return `    ${p.name} = \${${expr}},`;
    }
    if (p.composableSlot) return `    ${p.name} = { },`;
    return `    ${p.name} = TODO(${JSON.stringify(p.type ?? "")}),`;
  });
  if (boundProps.length === 0) return null;
  const body =
    lines.length === 0 ? `${componentName}()` : `${componentName}(\n${lines.join("\n")}\n)`;
  const preamble = ["const figma = require('figma')", ...declarations].join("\n");
  const template = `${preamble}\nexport default figma.code\`${body}\``;
  return { template, boundProps };
}

/**
 * Shape resolved mappings into the argument object for Figma's `send_code_connect_mappings` MCP
 * tool: `{ fileKey, nodeId, mappings: [{ nodeId, componentName, source, label, template?,
 * templateDataJson? }] }`. `nodeId` at the top level is required by the tool and set to the first
 * mapping's node (an anchor); the per-mapping `nodeId`s carry the real bindings.
 *
 * Template precedence per mapping: an explicit `m.template` wins; else, when the file has variant
 * properties for the component AND the mapping carries `parameters`, a **prop-bound** `figma.code`
 * template (`figma.properties.*` interpolated per matching param); else the static call site from
 * `m.codeSnippet`. `templateDataJson` carries `isParserless`, `imports`, and any bound `props`.
 */
export function toSendMappingsPayload(fileKey, resolved, propsByName = new Map()) {
  const mappings = resolved.map((m) => {
    const out = {
      nodeId: m.nodeId,
      componentName: m.componentName,
      source: m.source,
      label: m.label,
    };
    const propDefs = propsByName.get(m.figmaLayerName) ?? propsByName.get(m.componentName);
    const bound =
      !m.template && propDefs && m.parameters?.length
        ? buildBoundTemplate(m.componentName, m.parameters, propDefs)
        : null;
    const template =
      m.template ?? bound?.template ?? (m.codeSnippet ? codeConnectTemplate(m.codeSnippet) : null);
    if (template) {
      out.template = template;
      const data = { isParserless: true, imports: m.imports ?? [] };
      if (bound?.boundProps.length) data.props = bound.boundProps;
      out.templateDataJson = JSON.stringify(data);
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
      "slots-out": { type: "string" },
    },
  });
  if (!values.manifest || !values.file) {
    console.error(
      "usage: publish-code-connect --manifest <code-connect.json> --file <key|/design/ URL> " +
        "[--out <send-mappings.json>] [--slots-out <figma-slots.json>]",
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

  // Variant properties from the file's component sets, so a matching parameter binds to a Figma prop
  // (figma.properties.*) instead of a TODO. Empty for the code-led rendered catalog (plain frames).
  const propsByName = variantPropsByName(doc.document);
  const payload = toSendMappingsPayload(fileKey, resolved, propsByName);
  const slots = extractSlotManifest(fileKey, doc, resolved);
  const boundCount = payload.mappings.filter((m) => {
    try {
      return (m.templateDataJson && JSON.parse(m.templateDataJson).props?.length) > 0;
    } catch {
      return false;
    }
  }).length;
  const json = `${JSON.stringify(payload, null, 2)}\n`;
  if (values.out) {
    await writeFile(values.out, json, "utf8");
    console.log(
      `[code-connect] resolved ${resolved.length}/${manifest.mappings?.length ?? 0} mapping(s), ` +
        `${boundCount} with bound variant props → ${values.out}`,
    );
  } else {
    process.stdout.write(json);
  }
  const slotsOut = values["slots-out"] ?? (values.out ? join(dirname(values.out), "figma-slots.json") : null);
  if (slotsOut) {
    await writeFile(slotsOut, `${JSON.stringify(slots, null, 2)}\n`, "utf8");
    console.log(
      `[code-connect] native slots → ${slots.hosts.length} host(s), ` +
        `${slots.hosts.reduce((count, host) => count + host.slots.length, 0)} slot(s) → ${slotsOut}`,
    );
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
