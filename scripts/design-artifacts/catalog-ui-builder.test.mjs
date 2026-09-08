import assert from "node:assert/strict";
import { test } from "node:test";

import { mkdtemp, readFile, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  UI_BUILDER_FILE,
  parseUiBuilderCatalog,
  publishUiBuilderCatalog,
} from "./catalog-ui-builder.mjs";

async function withOutDir(body) {
  const out = await mkdtemp(join(tmpdir(), "ui-builder-catalog-"));
  try {
    await body(out);
  } finally {
    await rm(out, { recursive: true, force: true });
  }
}

const catalog = JSON.stringify({
  schema: "compose-ui-builder-catalog/v1",
  catalog: {
    id: "wear-m3",
    title: "M3 Wear OS Apps Design Kit",
    platform: "wear",
    platformLabel: "Wear",
  },
  record: { file: "components.json", schemaVersion: 2, components: 148 },
  statusSemantics: {
    platform: "wear",
    platformLabel: "Wear",
    componentMenu: { groupOrder: ["Screens", "Layout"], components: {} },
    builtins: { "wear-m3/screen-scaffold": { role: "screen-root" } },
    components: {
      "wear-m3/checkbox-button": {
        record: ":catalog/androidx.wear.compose.material3.CheckboxButtonKt.CheckboxButton",
        canvas: "placeholder",
      },
    },
  },
  diagnostics: [
    { code: "component.canvas.unclaimed", subject: "wear-m3/card", message: "…" },
  ],
});

const bytes = (text) => new TextEncoder().encode(text);

test("the bundle's builder catalog is copied to the branch root and described for the manifest", async () => {
  await withOutDir(async (out) => {
    const published = await publishUiBuilderCatalog(
      { [UI_BUILDER_FILE]: bytes(catalog), "bundle.json": bytes("{}") },
      out,
    );

    assert.deepEqual(published, {
      path: "ui-builder.json",
      schema: "compose-ui-builder-catalog/v1",
      catalogId: "wear-m3",
      platform: "wear",
      components: 1,
      builtins: 1,
      diagnostics: 1,
    });
    // Byte-for-byte, not re-serialised: the generator produced it and the pipeline is a courier.
    assert.equal(await readFile(join(out, UI_BUILDER_FILE), "utf8"), catalog);
  });
});

test("a bundle from a catalog that authors no policy publishes nothing", async () => {
  await withOutDir(async (out) => {
    // The case that makes this contract cost zero for every catalog that has not adopted it: no
    // policy, no generated file, no bundle entry, nothing written and nothing on the manifest.
    assert.equal(await publishUiBuilderCatalog({ "bundle.json": bytes("{}") }, out), null);
    await assert.rejects(() => stat(join(out, UI_BUILDER_FILE)));
  });
});

test("an unreadable or structurally wrong entry publishes nothing", async () => {
  await withOutDir(async (out) => {
    assert.equal(await publishUiBuilderCatalog({ [UI_BUILDER_FILE]: bytes("{") }, out), null);
    assert.equal(
      await publishUiBuilderCatalog({ [UI_BUILDER_FILE]: bytes('{"schema":"x"}') }, out),
      null,
    );
    assert.equal(
      await publishUiBuilderCatalog(
        { [UI_BUILDER_FILE]: bytes('{"schema":"x","catalog":{"id":""},"statusSemantics":{}}') },
        out,
      ),
      null,
    );
    await assert.rejects(() => stat(join(out, UI_BUILDER_FILE)));
  });
});

test("an unknown future schema is published rather than refused", () => {
  // Deliberately not a version check. The file is read by builders of several vintages the
  // publisher cannot upgrade, so refusing a newer plugin's catalog here would report it as a
  // broken bundle — the rule the readers themselves follow.
  const future = parseUiBuilderCatalog(
    bytes(
      JSON.stringify({
        schema: "compose-ui-builder-catalog/v9",
        catalog: { id: "material4-catalog" },
        statusSemantics: { platform: "mobile" },
        somethingNobodyHasWrittenYet: true,
      }),
    ),
  );

  assert.equal(future.catalog.id, "material4-catalog");
});

test("counts default to zero rather than throwing on a minimal catalog", () => {
  const minimal = {
    schema: "compose-ui-builder-catalog/v1",
    catalog: { id: "m3-catalog" },
    statusSemantics: { platform: "mobile" },
  };
  const parsed = parseUiBuilderCatalog(bytes(JSON.stringify(minimal)));
  assert.equal(parsed.catalog.id, "m3-catalog");
});
