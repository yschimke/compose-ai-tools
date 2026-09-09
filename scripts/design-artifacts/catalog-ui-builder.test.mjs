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
      templates: [],
      missingTemplates: [],
      unsafeTemplates: [],
    });
    // Byte-for-byte, not re-serialised: the generator produced it and the pipeline is a courier.
    assert.equal(await readFile(join(out, UI_BUILDER_FILE), "utf8"), catalog);
  });
});

test("template designs the catalog names are carried out with it", async () => {
  await withOutDir(async (out) => {
    // A path in `templates` is branch-relative and the publish flow snapshots `out/` wholesale, so
    // a design that is not written here is a 404 in the New design chooser — advertised by the
    // catalog and absent from the branch.
    const design = '{"schema":"compose-ui-builder-design/v1","nodes":[]}';
    const withTemplate = JSON.stringify({
      ...JSON.parse(catalog),
      statusSemantics: {
        ...JSON.parse(catalog).statusSemantics,
        templates: ["ui-builder/designs/wear-list.json"],
      },
    });

    const published = await publishUiBuilderCatalog(
      {
        [UI_BUILDER_FILE]: bytes(withTemplate),
        "ui-builder/designs/wear-list.json": bytes(design),
      },
      out,
    );

    assert.deepEqual(published.templates, ["ui-builder/designs/wear-list.json"]);
    assert.deepEqual(published.missingTemplates, []);
    assert.equal(
      await readFile(join(out, "ui-builder/designs/wear-list.json"), "utf8"),
      design,
    );
  });
});

test("a template path escaping the output directory is refused, not written", async () => {
  await withOutDir(async (out) => {
    // A bundle is not a trusted document. `ui-builder/../../catalog.json` joined to the output
    // directory writes outside it, or over another generated artifact.
    const escaping = JSON.stringify({
      ...JSON.parse(catalog),
      statusSemantics: {
        ...JSON.parse(catalog).statusSemantics,
        templates: ["ui-builder/../../escaped.json", "/etc/passwd"],
      },
    });

    const published = await publishUiBuilderCatalog(
      {
        [UI_BUILDER_FILE]: bytes(escaping),
        "ui-builder/../../escaped.json": bytes("{}"),
        "/etc/passwd": bytes("{}"),
      },
      out,
    );

    assert.deepEqual(published.unsafeTemplates, [
      "ui-builder/../../escaped.json",
      "/etc/passwd",
    ]);
    assert.deepEqual(published.templates, []);
    await assert.rejects(() => stat(join(out, "..", "..", "escaped.json")));
    // The catalog itself still publishes; a refused path is reported, not fatal.
    assert.equal(await readFile(join(out, UI_BUILDER_FILE), "utf8"), escaping);
  });
});

test("a template the bundle does not carry is reported rather than silently advertised", async () => {
  await withOutDir(async (out) => {
    // Still published: the catalog is readable and every other template still opens. But the one
    // that is missing is named, because the alternative is somebody clicking it and getting a 404
    // with nothing anywhere saying why.
    const withTemplate = JSON.stringify({
      ...JSON.parse(catalog),
      statusSemantics: {
        ...JSON.parse(catalog).statusSemantics,
        templates: ["ui-builder/designs/absent.json"],
      },
    });

    const published = await publishUiBuilderCatalog(
      { [UI_BUILDER_FILE]: bytes(withTemplate) },
      out,
    );

    assert.deepEqual(published.missingTemplates, ["ui-builder/designs/absent.json"]);
    assert.deepEqual(published.templates, []);
    await assert.rejects(() => stat(join(out, "ui-builder/designs/absent.json")));
    // The catalog itself is still there — a missing template is not a reason to publish nothing.
    assert.equal(await readFile(join(out, UI_BUILDER_FILE), "utf8"), withTemplate);
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

test("an array where an object belongs is refused", () => {
  // `typeof [] === "object"`, so the structural checks admitted one. The root already refused an
  // array for exactly that reason and the two nested checks did not, which let a hand-made bundle
  // carrying `statusSemantics: []` be written to the branch and stamped on `catalog.json` as a
  // usable catalog — a file every reader would then fail to make sense of.
  const arrayed = {
    schema: "compose-ui-builder-catalog/v1",
    catalog: { id: "m3-catalog" },
    statusSemantics: [],
  };
  assert.equal(parseUiBuilderCatalog(bytes(JSON.stringify(arrayed))), null);

  const arrayedCatalog = {
    schema: "compose-ui-builder-catalog/v1",
    catalog: [],
    statusSemantics: { platform: "mobile" },
  };
  assert.equal(parseUiBuilderCatalog(bytes(JSON.stringify(arrayedCatalog))), null);
});
