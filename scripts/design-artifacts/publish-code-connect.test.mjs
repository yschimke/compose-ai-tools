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
  fileKeyFromArg,
  indexNodesByName,
  resolveMappings,
  toSendMappingsPayload,
} from "./publish-code-connect.mjs";

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
