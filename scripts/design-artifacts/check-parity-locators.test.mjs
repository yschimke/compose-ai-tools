import assert from "node:assert/strict";
import test from "node:test";
import {
  cellOfPreviewId,
  cellsFromManifest,
  checkLocators,
  nearestCells,
} from "./check-parity-locators.mjs";

const manifest = (...previews) => ({
  previews: previews.map(([componentId, id]) => ({ id, catalog: { componentId } })),
});

const body = (fields) =>
  "```compose-parity-locator/v1\n" +
  Object.entries(fields)
    .map(([key, value]) => `${key}: ${value}`)
    .join("\n") +
  "\n```\n";

const locator = (overrides = {}) =>
  body({
    repository: "yschimke/wear-m3-catalog",
    system: "remote-m3",
    component: "AppCard",
    scope: "component",
    preview: "appcard__ideal__outlined__compact",
    reference: "appcard__ideal__outlined__compact",
    variant: "ideal/outlined/compact",
    overrides: "{}",
    ...overrides,
  });

const cells = manifest(
  ["AppCard", "…CatalogPreviewsKt.AppCardRemote_width=227dp_VARIANT_outlined"],
  ["AppCard", "…CatalogPreviewsKt.AppCardRemote_width=227dp_VARIANT_icon-outlined-gallery-2"],
  ["AppCard", "…CatalogPreviewsKt.AppCardRemote_width=227dp"],
);

test("a preview id's cell is its third segment, breakpoint or not", () => {
  assert.equal(cellOfPreviewId("appcard__ideal__outlined__compact"), "outlined");
  assert.equal(cellOfPreviewId("titlecard__ideal__title-and-subtitle"), "title-and-subtitle");
  assert.equal(cellOfPreviewId("appcard__ideal"), null);
  assert.equal(cellOfPreviewId(""), null);
});

test("a manifest preview with no _VARIANT_ is the component's `default` cell", () => {
  assert.deepEqual(cellsFromManifest(cells).get("AppCard"), new Set([
    "outlined",
    "icon-outlined-gallery-2",
    "default",
  ]));
});

test("previews with no componentId are not cells of anything", () => {
  assert.equal(cellsFromManifest({ previews: [{ id: "Sample_VARIANT_x" }] }).size, 0);
});

test("a locator naming a drawn cell passes", () => {
  const result = checkLocators({
    issues: [{ number: 1, body: locator() }],
    manifests: new Map([["remote-m3", cellsFromManifest(cells)]]),
  });
  assert.deepEqual(result.failures, []);
  assert.equal(result.checked, 1);
});

test("a renamed cell fails, and the message names the cell it probably moved to", () => {
  const result = checkLocators({
    issues: [
      {
        number: 284,
        body: locator({
          preview: "appcard__ideal__outline-icon-gallery-2__compact",
          reference: "appcard__ideal__outline-icon-gallery-2__compact",
        }),
      },
    ],
    manifests: new Map([["remote-m3", cellsFromManifest(cells)]]),
  });
  assert.equal(result.failures.length, 1);
  assert.equal(result.failures[0].number, 284);
  assert.match(result.failures[0].message, /draws no cell "outline-icon-gallery-2"/);
  assert.match(result.failures[0].message, /did you mean "icon-outlined-gallery-2"/);
});

test("preview and reference are one check when they are the same string", () => {
  const same = checkLocators({
    issues: [{ number: 1, body: locator({ preview: "appcard__ideal__gone__compact", reference: "appcard__ideal__gone__compact" }) }],
    manifests: new Map([["remote-m3", cellsFromManifest(cells)]]),
  });
  assert.equal(same.checked, 1);
  assert.equal(same.failures.length, 1);
});

test("a reference pinned apart from the preview is checked in its own right", () => {
  const result = checkLocators({
    issues: [{ number: 1, body: locator({ reference: "appcard__ideal__gone__compact" }) }],
    manifests: new Map([["remote-m3", cellsFromManifest(cells)]]),
  });
  assert.equal(result.checked, 2);
  assert.equal(result.failures.length, 1);
  assert.equal(result.failures[0].field, "reference");
});

test("with a baseline, an id that was served and is not drawn now is this change's failure", () => {
  const result = checkLocators({
    issues: [{ number: 284, body: locator({ preview: "appcard__ideal__outline__compact", reference: "appcard__ideal__outline__compact" }) }],
    manifests: new Map([["remote-m3", cellsFromManifest(cells)]]),
    published: new Map([["remote-m3", new Set(["appcard__ideal__outline__compact"])]]),
  });
  assert.equal(result.failures.length, 1);
  assert.equal(result.warnings.length, 0);
});

test("with a baseline, an id that was never served is a warning rather than this PR's fault", () => {
  const result = checkLocators({
    issues: [{ number: 9, body: locator({ preview: "appcard__ideal__typo__compact", reference: "appcard__ideal__typo__compact" }) }],
    manifests: new Map([["remote-m3", cellsFromManifest(cells)]]),
    published: new Map([["remote-m3", new Set(["appcard__ideal__outlined__compact"])]]),
  });
  assert.deepEqual(result.failures, []);
  assert.equal(result.warnings.length, 1);
  assert.equal(result.warnings[0].number, 9);
});

test("without a baseline every unresolvable id fails, which is what a scheduled run wants", () => {
  const result = checkLocators({
    issues: [{ number: 9, body: locator({ preview: "appcard__ideal__typo__compact", reference: "appcard__ideal__typo__compact" }) }],
    manifests: new Map([["remote-m3", cellsFromManifest(cells)]]),
  });
  assert.equal(result.failures.length, 1);
  assert.deepEqual(result.warnings, []);
});

test("a component this lane does not build warns rather than fails", () => {
  // `:remote-catalog` builds CheckboxButton and EdgeButton on its snapshot lane only, and a run
  // discovers one lane at a time — so the released-lane manifest is legitimately missing components
  // the delivery branch serves. Three real issues failed on exactly this before it was narrowed.
  const result = checkLocators({
    issues: [{ number: 1, body: locator({ component: "CheckboxButton" }) }],
    manifests: new Map([["remote-m3", cellsFromManifest(cells)]]),
    published: new Map([["remote-m3", new Set(["appcard__ideal__outlined__compact"])]]),
  });
  assert.deepEqual(result.failures, []);
  assert.equal(result.unverified, 1);
  assert.equal(result.checked, 0);
  assert.match(
    result.warnings[0].message,
    /no component "CheckboxButton" in the remote-m3 manifest — not built on the discovered lane/,
  );
});

test("a locator for a system this repository does not build is skipped, not assumed good", () => {
  const result = checkLocators({
    issues: [{ number: 1, body: locator({ system: "some-other-system" }) }],
    manifests: new Map([["remote-m3", cellsFromManifest(cells)]]),
  });
  assert.deepEqual(result.failures, []);
  assert.equal(result.skipped, 1);
  assert.equal(result.checked, 0);
});

test("bodies with no locator, or a damaged one, are left to the emitter to report", () => {
  const result = checkLocators({
    issues: [
      { number: 1, body: "no locator here" },
      { number: 2, body: "```compose-parity-locator/v1\nrepository: x\n" },
      { number: 3, body: null },
    ],
    manifests: new Map([["remote-m3", cellsFromManifest(cells)]]),
  });
  assert.deepEqual(result.failures, []);
  assert.equal(result.checked, 0);
});

test("a CRLF body parses — the report form writes them, and one hid a real break", () => {
  const crlf = locator().replace(/\n/g, "\r\n");
  const result = checkLocators({
    issues: [{ number: 1, body: crlf }],
    manifests: new Map([["remote-m3", cellsFromManifest(cells)]]),
  });
  assert.deepEqual(result.failures, []);
  assert.equal(result.checked, 1);
});

test("a near-miss is offered only when it is actually near", () => {
  assert.deepEqual(nearestCells("outlined", new Set(["outline", "icon", "default"])), ["outline"]);
  assert.deepEqual(nearestCells("outlined", new Set(["gallery-1", "default"])), []);
});
