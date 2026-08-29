/**
 * Unit tests for the Code Connect publish resolution: Figma file tree → name index → node-id-bound
 * mappings → `send_code_connect_mappings` payload. The REST fetch + file IO are a thin shell over
 * these pure functions and are not exercised here.
 *
 * Run with `node --test scripts/design-artifacts/`.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import {
  bindingExpression,
  buildBoundTemplate,
  codeConnectTemplate,
  extractSlotManifest,
  fileKeyFromArg,
  indexNodesByName,
  resolveMappings,
  toSendMappingsPayload,
  variantPropsByName,
} from "./publish-code-connect.mjs";

test("codeConnectTemplate wraps a snippet in a figma.code parserless template", () => {
  const t = codeConnectTemplate("Foo(\n    bar = /* Baz */,\n)");
  assert.match(t, /export default figma\.code`Foo\(/);
  assert.match(t, /const figma = require\('figma'\)/);
});

test("variantPropsByName indexes component sets, ignores plain frames", () => {
  const doc = {
    children: [
      {
        type: "COMPONENT_SET",
        name: "DeviceSummaryCard",
        componentPropertyDefinitions: { State: { type: "VARIANT", variantOptions: ["Loading", "Populated"] } },
      },
      { type: "FRAME", name: "Just A Sticker", children: [] },
    ],
  };
  const idx = variantPropsByName(doc);
  assert.equal(idx.size, 1);
  assert.ok(idx.get("DeviceSummaryCard").State);
  assert.equal(idx.get("Just A Sticker"), undefined);
});

test("extractSlotManifest reads raw REST SLOT nodes without a plugin export", () => {
  const figmaFile = {
    version: "123",
    components: {
      "9:1": { key: "button-key", name: "Button" },
    },
    document: {
      id: "0:0",
      children: [
        {
          id: "1:1",
          name: "Card/Slots",
          type: "COMPONENT",
          absoluteBoundingBox: { x: 100, y: 200, width: 320, height: 180 },
          componentPropertyDefinitions: {
            "Content#1:2": {
              type: "SLOT",
              defaultValue: { guid: { sessionID: -1, localID: -1 } },
              description: "Composable body content",
              preferredValues: [{ type: "COMPONENT", key: "button-key" }],
              slotSettings: {
                stretchChildOnInsert: true,
                minChildren: 0,
                maxChildren: 2,
                allowPreferredValuesOnly: true,
              },
            },
          },
          children: [
            {
              id: "1:2",
              name: "Content",
              type: "SLOT",
              componentPropertyReferences: { slotContentId: "Content#1:2" },
              absoluteBoundingBox: { x: 124, y: 248, width: 272, height: 96 },
              layoutMode: "VERTICAL",
              layoutSizingHorizontal: "FILL",
              layoutSizingVertical: "HUG",
              clipsContent: true,
              children: [
                {
                  id: "2:1",
                  name: "Primary button",
                  type: "INSTANCE",
                  componentId: "9:1",
                },
              ],
            },
          ],
        },
      ],
    },
  };
  const result = extractSlotManifest("FILE", figmaFile, [
    {
      nodeId: "1:1",
      componentId: "card-slots",
      figmaLayerName: "Card/Slots",
      componentName: "CardSlots",
    },
  ]);

  assert.equal(result.schema, "compose-preview-figma-slots/v1");
  assert.equal(result.version, "123");
  assert.equal(result.hosts.length, 1);
  assert.deepEqual(result.hosts[0].slots[0], {
    name: "Content",
    propertyKey: "Content#1:2",
    nodeId: "1:2",
    description: "Composable body content",
    bounds: { x: 24, y: 48, width: 272, height: 96 },
    layout: {
      mode: "VERTICAL",
      horizontal: "FILL",
      vertical: "HUG",
      clipsContent: true,
    },
    preferredValues: [{ type: "COMPONENT", key: "button-key" }],
    settings: {
      stretchChildOnInsert: true,
      minChildren: 0,
      maxChildren: 2,
      allowPreferredValuesOnly: true,
    },
    children: [
      {
        nodeId: "2:1",
        name: "Primary button",
        type: "INSTANCE",
        componentNodeId: "9:1",
        componentKey: "button-key",
      },
    ],
  });
});

test("extractSlotManifest falls back to slot names and relative transforms", () => {
  const result = extractSlotManifest(
    "FILE",
    {
      document: {
        id: "0:0",
        children: [
          {
            id: "1:1",
            name: "Host",
            type: "FRAME",
            children: [
              {
                id: "1:2",
                name: "Actions",
                type: "SLOT",
                relativeTransform: [
                  [1, 0, 12],
                  [0, 1, 20],
                ],
                size: { x: 120, y: 40 },
                children: [],
              },
            ],
          },
        ],
      },
    },
    [{ nodeId: "1:1", componentId: "host", componentName: "Host" }],
  );

  assert.equal(result.hosts[0].slots[0].name, "Actions");
  assert.equal(result.hosts[0].slots[0].propertyKey, "Actions");
  assert.deepEqual(result.hosts[0].slots[0].bounds, {
    x: 12,
    y: 20,
    width: 120,
    height: 40,
  });
});

test("bindingExpression maps variant/boolean/text to figma.properties.*", () => {
  assert.equal(
    bindingExpression("State", { type: "VARIANT", variantOptions: ["Loading", "Populated"] }, { type: "DeviceState" }),
    'figma.properties.enum("State", {"Loading":"DeviceState.Loading","Populated":"DeviceState.Populated"})',
  );
  assert.equal(bindingExpression("Disabled", { type: "BOOLEAN" }, {}), 'figma.properties.boolean("Disabled")');
  assert.equal(bindingExpression("Label", { type: "TEXT" }, {}), 'figma.properties.string("Label")');
  assert.equal(bindingExpression("Icon", { type: "INSTANCE_SWAP" }, {}), null);
  // A property name with an apostrophe stays valid JS (JSON-escaped, not `'...'`).
  assert.equal(
    bindingExpression("Owner's state", { type: "BOOLEAN" }, {}),
    'figma.properties.boolean("Owner\'s state")',
  );
});

test("buildBoundTemplate interpolates matched params, keeps TODO for the rest", () => {
  const built = buildBoundTemplate(
    "DeviceSummaryCard",
    [
      { name: "state", type: "DeviceState", hasDefault: false },
      { name: "title", type: "String", hasDefault: false }, // no matching Figma prop → TODO
    ],
    { State: { type: "VARIANT", variantOptions: ["Loading", "Populated"] } },
  );
  assert.deepEqual(built.boundProps, ["State"]);
  assert.match(built.template, /state = \$\{figma\.properties\.enum\("State"/);
  assert.match(built.template, /title = TODO\("String"\)/);
});

test("buildBoundTemplate binds a composable lambda to a native Figma slot", () => {
  const built = buildBoundTemplate(
    "Card",
    [
      {
        name: "content",
        type: "@Composable () -> Unit",
        hasDefault: false,
        composableSlot: true,
      },
    ],
    { "Content#7:1": { type: "SLOT" } },
  );

  assert.deepEqual(built.boundProps, ["Content"]);
  assert.match(built.template, /const slot_content = figma\.selectedInstance\.getSlot\("Content"\)/);
  assert.match(built.template, /content = \{ \$\{slot_content\} \}/);
});

test("buildBoundTemplate returns null when nothing binds (plain catalog case)", () => {
  assert.equal(
    buildBoundTemplate("Foo", [{ name: "bar", type: "Baz", hasDefault: false }], {}),
    null,
  );
});

test("toSendMappingsPayload prefers a prop-bound template and records props", () => {
  const payload = toSendMappingsPayload(
    "K",
    [
      {
        nodeId: "1:1",
        figmaLayerName: "DeviceSummaryCard",
        componentName: "DeviceSummaryCard",
        source: "s",
        label: "Compose",
        codeSnippet: 'DeviceSummaryCard(\n    state = TODO("DeviceState"),\n)',
        imports: ["import x.DeviceSummaryCard"],
        parameters: [{ name: "state", type: "DeviceState", hasDefault: false }],
      },
    ],
    new Map([["DeviceSummaryCard", { State: { type: "VARIANT", variantOptions: ["Loading", "Populated"] } }]]),
  );
  const m = payload.mappings[0];
  assert.match(m.template, /figma\.properties\.enum\("State"/);
  assert.deepEqual(JSON.parse(m.templateDataJson).props, ["State"]);
});

test("toSendMappingsPayload turns a codeSnippet into template + templateDataJson", () => {
  const payload = toSendMappingsPayload("K", [
    {
      nodeId: "1:1",
      componentName: "Foo",
      source: "s",
      label: "Compose",
      codeSnippet: "Foo(\n    bar = /* Baz */,\n)",
      imports: ["import com.x.Foo"],
    },
  ]);
  const m = payload.mappings[0];
  assert.match(m.template, /figma\.code`Foo\(/);
  assert.deepEqual(JSON.parse(m.templateDataJson), { isParserless: true, imports: ["import com.x.Foo"] });
});

test("fileKeyFromArg accepts a bare key or a /design/ URL", () => {
  assert.equal(fileKeyFromArg("gYzowY4cQ7rNr2gYoco1M6"), "gYzowY4cQ7rNr2gYoco1M6");
  assert.equal(
    fileKeyFromArg("https://www.figma.com/design/gYzowY4cQ7rNr2gYoco1M6/Name?node-id=0-1"),
    "gYzowY4cQ7rNr2gYoco1M6",
  );
  assert.equal(fileKeyFromArg(null), null);
});

// A minimal Figma file document: pages → card wrapper → inner sticker frame.
const doc = {
  id: "0:0",
  name: "Document",
  children: [
    {
      id: "0:1",
      name: "Page 1",
      children: [
        {
          id: "14:22",
          name: "Card: DeviceSummaryCard/Populated",
          children: [{ id: "11:6", name: "DeviceSummaryCard/Populated", children: [] }],
        },
        {
          id: "14:32",
          name: "Card: ContactRow/Variants",
          children: [{ id: "11:8", name: "ContactRow/Variants", children: [] }],
        },
      ],
    },
  ],
};

test("indexNodesByName walks the whole tree and collects ids per name", () => {
  const index = indexNodesByName(doc);
  assert.deepEqual(index.get("DeviceSummaryCard/Populated"), ["11:6"]);
  assert.deepEqual(index.get("Card: ContactRow/Variants"), ["14:32"]);
  assert.equal(index.get("Nope"), undefined);
});

test("indexNodesByName collects multiple ids for a repeated name", () => {
  const dup = {
    children: [
      { id: "1", name: "Dup", children: [] },
      { id: "2", name: "Dup", children: [] },
    ],
  };
  assert.deepEqual(indexNodesByName(dup).get("Dup"), ["1", "2"]);
});

const manifest = {
  mappings: [
    {
      componentId: "DeviceSummaryCard/Populated",
      figmaLayerName: "DeviceSummaryCard/Populated",
      componentName: "DeviceSummaryCardPopulatedPreview",
      source: "https://github.com/o/r/blob/main/app/Foo.kt",
      label: "Compose",
    },
    {
      componentId: "Missing/One",
      figmaLayerName: "Missing/One",
      componentName: "MissingOnePreview",
      source: "app",
      label: "Compose",
    },
  ],
};

test("resolveMappings binds present names to node ids and reports absent ones", () => {
  const { resolved, unresolved, ambiguous } = resolveMappings(manifest, indexNodesByName(doc));
  assert.equal(resolved.length, 1);
  assert.equal(resolved[0].nodeId, "11:6");
  assert.equal(resolved[0].componentName, "DeviceSummaryCardPopulatedPreview");
  assert.deepEqual(unresolved, ["Missing/One"]);
  assert.deepEqual(ambiguous, []);
});

test("resolveMappings flags an ambiguous name but still binds the first id", () => {
  const dupDoc = {
    children: [
      { id: "a", name: "DeviceSummaryCard/Populated", children: [] },
      { id: "b", name: "DeviceSummaryCard/Populated", children: [] },
    ],
  };
  const { resolved, ambiguous } = resolveMappings(manifest, indexNodesByName(dupDoc));
  assert.equal(resolved[0].nodeId, "a");
  assert.equal(ambiguous.length, 1);
  assert.deepEqual(ambiguous[0], { name: "DeviceSummaryCard/Populated", ids: ["a", "b"] });
});

test("toSendMappingsPayload shapes the send_code_connect_mappings argument object", () => {
  const { resolved } = resolveMappings(manifest, indexNodesByName(doc));
  const payload = toSendMappingsPayload("FILEKEY", resolved);
  assert.equal(payload.fileKey, "FILEKEY");
  // Top-level nodeId is the first mapping's node (an anchor the tool requires).
  assert.equal(payload.nodeId, "11:6");
  assert.deepEqual(payload.mappings, [
    {
      nodeId: "11:6",
      componentName: "DeviceSummaryCardPopulatedPreview",
      source: "https://github.com/o/r/blob/main/app/Foo.kt",
      label: "Compose",
    },
  ]);
});

test("toSendMappingsPayload passes through a template when present", () => {
  const payload = toSendMappingsPayload("K", [
    { nodeId: "1:1", componentName: "X", source: "s", label: "Compose", template: "figma.code`X()`" },
  ]);
  assert.equal(payload.mappings[0].template, "figma.code`X()`");
});
