import assert from "node:assert/strict";
import { test } from "node:test";

import { generationDensity } from "./rc-document-header.mjs";

function modernHeader(properties) {
  const body = Buffer.concat(
    properties.map(({ type, key, value }) => {
      const payload = Buffer.alloc(4);
      if (type === 1) payload.writeFloatBE(value);
      else payload.writeInt32BE(value);
      const item = Buffer.alloc(4);
      item.writeUInt16BE((type << 10) | key);
      item.writeUInt16BE(payload.length, 2);
      return Buffer.concat([item, payload]);
    }),
  );
  const header = Buffer.alloc(17);
  header.writeInt32BE(0x048c0001, 1);
  header.writeInt32BE(properties.length, 13);
  return Buffer.concat([header, body]);
}

test("reads generation density from a modern Remote Compose header", () => {
  const bytes = modernHeader([
    { type: 0, key: 5, value: 840 },
    { type: 1, key: 7, value: 2.625 },
  ]);
  assert.equal(generationDensity(bytes), 2.625);
});

test("falls back for legacy, absent, invalid, and truncated density properties", () => {
  assert.equal(generationDensity(Buffer.alloc(32)), 2);
  assert.equal(generationDensity(modernHeader([{ type: 0, key: 5, value: 840 }])), 2);
  assert.equal(generationDensity(modernHeader([{ type: 1, key: 7, value: 0 }])), 2);
  assert.equal(generationDensity(modernHeader([{ type: 1, key: 7, value: 3 }]).subarray(0, 20)), 2);
});
