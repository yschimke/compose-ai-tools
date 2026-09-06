import assert from "node:assert/strict";
import { test } from "node:test";

import { mkdtemp, readFile, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  COMPONENT_RECORD_FILE,
  parseComponentRecord,
  publishComponentRecord,
} from "./catalog-component-record.mjs";

async function withOutDir(body) {
  const out = await mkdtemp(join(tmpdir(), "component-record-"));
  try {
    await body(out);
  } finally {
    await rm(out, { recursive: true, force: true });
  }
}

const record = JSON.stringify({
  schemaVersion: 2,
  module: ":catalog",
  variant: "main",
  components: [
    {
      canonicalId: "catalog/dev.example.CardKt.SessionCard",
      componentIds: [],
      symbol: {
        jvmOwner: "dev.example.CardKt",
        callable: "dev.example.SessionCard",
        name: "SessionCard",
        origin: "PROJECT",
      },
      code: { call: "SessionCard()", imports: ["dev.example.SessionCard"] },
      signatureKnown: true,
    },
  ],
});

test("the bundle's record is copied to the branch root and described for the manifest", async () => {
  await withOutDir(async (out) => {
    const bytes = new TextEncoder().encode(record);
    const published = await publishComponentRecord(
      { "bundle.json": new Uint8Array([123, 125]), [COMPONENT_RECORD_FILE]: bytes },
      out,
    );

    assert.deepEqual(published, { path: "components.json", schemaVersion: 2, components: 1 });
    // Byte-for-byte: the consumer parses the same file the plugin wrote, not a re-serialisation
    // that could reorder or drop what this script does not understand.
    assert.equal(await readFile(join(out, "components.json"), "utf8"), record);
  });
});

test("a bundle without a record publishes nothing and says so", async () => {
  await withOutDir(async (out) => {
    // A bundle packed by a plugin from before component records existed.
    assert.equal(await publishComponentRecord({ "bundle.json": new Uint8Array([123, 125]) }, out), null);
    await assert.rejects(stat(join(out, "components.json")));
  });
});

test("an entry that is not a record is left in the bundle rather than published", async () => {
  await withOutDir(async (out) => {
    for (const bytes of [
      new TextEncoder().encode("not json"),
      new TextEncoder().encode("[]"),
      new TextEncoder().encode('{"components":[]}'),
      new TextEncoder().encode('{"schemaVersion":"2","components":[]}'),
    ]) {
      assert.equal(await publishComponentRecord({ [COMPONENT_RECORD_FILE]: bytes }, out), null);
    }
    await assert.rejects(stat(join(out, "components.json")));
  });
});

test("a newer schema is published, not refused", () => {
  // The consumer names the versions it generates from; this script only checks the shape.
  const parsed = parseComponentRecord(
    new TextEncoder().encode('{"schemaVersion":7,"components":[],"future":true}'),
  );
  assert.equal(parsed.schemaVersion, 7);
});
