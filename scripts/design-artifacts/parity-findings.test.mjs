import { test } from "node:test";
import assert from "node:assert/strict";

import {
  FINDINGS_SCHEMA,
  buildServedFindings,
  referencesByCode,
  reportUrlFor,
} from "./parity-findings.mjs";

/** Planned reference records, in the shape `planDesignReferences` returns. */
const records = [
  {
    id: "button-filled__ideal__default__light-0",
    previewId: "button-filled__ideal__default__light",
    source: { attributes: { code: "ui/Button.kt#Filled" } },
  },
  {
    id: "button-filled__ideal__default__dark-0",
    previewId: "button-filled__ideal__default__dark",
    source: { attributes: { code: "ui/Button.kt#Filled" } },
  },
  {
    id: "card__ideal__default__light-0",
    previewId: "card__ideal__default__light",
    source: { attributes: { code: "ui/Card.kt#Elevated" } },
  },
];

const set = (message) => ({
  status: "fail",
  findings: [{ kind: "token", severity: "error", message }],
});

/** What a run publishes: keyed by BOTH the compose preview id and the code handle. */
const runFindings = {
  schema: FINDINGS_SCHEMA,
  previews: {
    "ui/Button.kt#Filled": [set("padding drifted")],
    "ui.ButtonKt.Filled": [set("padding drifted")],
  },
};

const runManifest = {
  formatVersion: 1,
  entries: [
    { code: "ui/Button.kt#Filled", status: "fail", reportPath: "ui-Button-kt-Filled/report.html" },
  ],
};

const build = (over = {}) =>
  buildServedFindings({
    runManifest,
    runFindings,
    references: records,
    repoSlug: "yschimke/m3-catalog",
    branch: "design-parity/m3-catalog",
    ...over,
  });

test("referencesByCode keeps every page a code handle plans", () => {
  const byCode = referencesByCode(records);
  assert.equal(byCode.get("ui/Button.kt#Filled").length, 2);
  assert.equal(byCode.get("ui/Card.kt#Elevated").length, 1);
});

test("a verdict is re-keyed onto the sticker ids the compare page routes on", () => {
  const { document } = build();
  // NOT the run's own keys: neither the code handle nor the compose preview id is what
  // `ServeParityFindingStore` is asked for.
  assert.deepEqual(Object.keys(document.previews).sort(), [
    "button-filled__ideal__default__dark",
    "button-filled__ideal__default__light",
  ]);
  assert.equal(document.schema, FINDINGS_SCHEMA);
});

test("each page's set is scoped to the reference that page shows", () => {
  const { document } = build();
  assert.equal(
    document.previews["button-filled__ideal__default__light"][0].referenceId,
    "button-filled__ideal__default__light-0",
  );
  assert.equal(
    document.previews["button-filled__ideal__default__dark"][0].referenceId,
    "button-filled__ideal__default__dark-0",
  );
});

test("the report link points at the branch the run published to", () => {
  const { document } = build();
  assert.equal(
    document.previews["button-filled__ideal__default__light"][0].reportUrl,
    // The branch's own slash stays a path separator — that is the form GitHub serves a
    // slash-named branch under, and encoding it would 404.
    "https://github.com/yschimke/m3-catalog/blob/design-parity/m3-catalog/" +
      "ui-Button-kt-Filled/report.html",
  );
});

test("a code handle diffed against two sources keeps its verdicts apart", () => {
  // The run stamps `source` on each set precisely because these share a code handle and a
  // candidate preview id; matching on it is what stops the Figma verdict being published under
  // the Stitch board as well.
  const { document } = build({
    runManifest: {
      entries: [
        {
          code: "ui/Button.kt#Filled",
          source: "figma",
          reportPath: "ui-Button-kt-Filled/report.html",
        },
        {
          code: "ui/Button.kt#Filled",
          source: "stitch",
          reportPath: "ui-Button-kt-Filled-stitch/report.html",
        },
      ],
    },
    runFindings: {
      previews: {
        "ui/Button.kt#Filled": [
          { ...set("figma says padding"), source: "figma" },
          { ...set("stitch says radius"), source: "stitch" },
        ],
      },
    },
  });
  const sets = document.previews["button-filled__ideal__default__light"];
  // Two entries × one reference each, and each carries only its own source's finding.
  assert.deepEqual(
    sets.map((s) => s.findings[0].message),
    ["figma says padding", "stitch says radius"],
  );
  // The stamp is consumed, not republished: a set naming a reference id already implies its source.
  assert.equal(sets[0].source, undefined);
  assert.match(sets[1].reportUrl, /ui-Button-kt-Filled-stitch/);
});

test("a producer that stamps no source still publishes", () => {
  // One source is the overwhelmingly common shape, and there the filter has nothing to choose
  // between — an unstamped set must not be dropped for failing to name what it was measured against.
  const { document } = build({
    runManifest: { entries: [{ code: "ui/Button.kt#Filled", source: "figma" }] },
  });
  assert.equal(
    document.previews["button-filled__ideal__default__light"][0].findings[0].message,
    "padding drifted",
  );
});

test("a component with no published reference is reported, not published", () => {
  // Its verdict has nowhere to be shown: no reference means no comparison page.
  const { document, warnings } = build({
    runManifest: {
      entries: [{ code: "ui/Ghost.kt#Ghost", reportPath: "x/report.html" }],
    },
    runFindings: { previews: { "ui/Ghost.kt#Ghost": [set("drifted")] } },
  });
  assert.equal(document, null);
  assert.match(warnings[0], /no published reference for ui\/Ghost\.kt#Ghost/);
});

test("a run with no findings for an entry publishes nothing for it", () => {
  const { document } = build({ runFindings: { previews: {} } });
  assert.equal(document, null);
});

test("reportUrlFor refuses anything the server would refuse", () => {
  assert.equal(reportUrlFor({ repoSlug: "", branch: "b", reportPath: "r" }), null);
  assert.equal(reportUrlFor({ repoSlug: "a/b", branch: "b", reportPath: "" }), null);
  // A traversal in a producer-authored path never becomes a link.
  assert.equal(reportUrlFor({ repoSlug: "a/b", branch: "b", reportPath: "../x" }), null);
  assert.equal(reportUrlFor({ repoSlug: "a b/c", branch: "b", reportPath: "r" }), null);
  assert.ok(reportUrlFor({ repoSlug: "a/b", branch: "m", reportPath: "r" }).startsWith("https://"));
});

test("a missing report path costs the link, not the verdict", () => {
  const { document } = build({
    runManifest: { entries: [{ code: "ui/Button.kt#Filled", status: "fail" }] },
  });
  const scoped = document.previews["button-filled__ideal__default__light"][0];
  assert.equal(scoped.reportUrl, undefined);
  assert.equal(scoped.findings[0].message, "padding drifted");
});

test("a run publishing a schema this driver does not know is dropped, not relabelled", () => {
  // The join REWRITES a run's records and republishes them under FINDINGS_SCHEMA. An external
  // caller runs a release-pinned driver, so a producer that has moved on would otherwise have a
  // v2's set semantics restamped as trusted v1 by a driver too old to know the difference.
  const { document, warnings } = build({
    runFindings: { ...runFindings, schema: "compose-preview-parity-findings/v2" },
  });
  assert.equal(document, null);
  assert.match(warnings[0], /v2.*not compose-preview-parity-findings\/v1/);
});

test("a run that stamps no schema at all still publishes", () => {
  // The first producer to ship this file predates the field; absent is not a mismatch.
  const { previews } = build({ runFindings: { previews: runFindings.previews } }).document;
  const set0 = previews["button-filled__ideal__default__light"][0];
  assert.equal(set0.findings[0].message, "padding drifted");
});

test("a structurally malformed run.json drops the panel rather than throwing", () => {
  // The emitter runs under `set -e`: a `for...of` over a non-array would cost the catalog its
  // render over an optional enhancement.
  const { document, warnings } = build({
    runManifest: { entries: { code: "ui/Button.kt#Filled" } },
  });
  assert.equal(document, null);
  assert.match(warnings[0], /entries are not a list/);
});

test("a code handle named after an inherited member is a lookup miss, not a crash", () => {
  // `previews.constructor` on a plain JSON object resolves to Object's constructor, and
  // `(previews[id] ??= []).push(...)` would then throw on a perfectly valid catalog route id.
  const proto = [
    {
      id: "constructor-0",
      previewId: "constructor",
      source: { attributes: { code: "toString" } },
    },
  ];
  const { document } = build({
    references: proto,
    runManifest: { entries: [{ code: "toString", reportPath: "toString/report.html" }] },
    runFindings: { previews: { toString: [set("drifted")] } },
  });
  assert.deepEqual(Object.keys(document.previews), ["constructor"]);
  assert.equal(document.previews.constructor[0].findings[0].message, "drifted");
});

test("a code handle that only INHERITS from the run's map publishes nothing", () => {
  const { document } = build({
    runManifest: { entries: [{ code: "hasOwnProperty", reportPath: "x/report.html" }] },
    runFindings: { previews: {} },
  });
  assert.equal(document, null);
});

// ---- Reference-side anchors -------------------------------------------------------------------
//
// A run's anchors are boxes in the space the design tool's adapter captured; the reference step
// publishes that raster onto the sticker's canvas and records what it did. Applying it here is what
// keeps a `layout` finding's two highlights over the same element on both panels (#4696).

/** A layout finding anchored on BOTH panels, which is the case the offset is most misleading in. */
const anchored = {
  status: "fail",
  findings: [
    {
      kind: "layout",
      severity: "error",
      message: "padding is 24 where the design asserts 16",
      anchors: [
        { side: "reference", bounds: { x: 10, y: 20, width: 100, height: 40 }, label: "Send" },
        { side: "actual", bounds: { x: 12, y: 24, width: 96, height: 48 }, label: "Send" },
      ],
    },
  ],
};

const anchoredRun = {
  schema: FINDINGS_SCHEMA,
  previews: { "ui/Button.kt#Filled": [anchored] },
};

test("moves a reference-side anchor onto the reference as it was published", () => {
  const { document } = build({
    runFindings: anchoredRun,
    referenceTransforms: new Map([
      ["button-filled__ideal__default__light-0", { scaleX: 2, scaleY: 2, offsetX: 0, offsetY: 30 }],
    ]),
  });
  const [finding] = document.previews["button-filled__ideal__default__light"][0].findings;
  assert.deepEqual(finding.anchors[0], {
    side: "reference",
    bounds: { x: 20, y: 70, width: 200, height: 80 },
    label: "Send",
  });
});

test("leaves the candidate-side anchor alone — it is already in the render's pixels", () => {
  const { document } = build({
    runFindings: anchoredRun,
    referenceTransforms: new Map([
      ["button-filled__ideal__default__light-0", { scaleX: 2, scaleY: 2, offsetX: 0, offsetY: 30 }],
    ]),
  });
  const [finding] = document.previews["button-filled__ideal__default__light"][0].findings;
  assert.deepEqual(finding.anchors[1], anchored.findings[0].anchors[1]);
});

test("only the reference the transform names is moved", () => {
  // One code handle plans a record per sticker, and light and dark can be published through
  // different transforms — a set scoped to one must not inherit the other's.
  const { document } = build({
    runFindings: anchoredRun,
    referenceTransforms: new Map([
      ["button-filled__ideal__default__light-0", { scaleX: 2, scaleY: 2, offsetX: 0, offsetY: 30 }],
    ]),
  });
  const dark = document.previews["button-filled__ideal__default__dark"][0].findings[0];
  assert.deepEqual(dark.anchors, anchored.findings[0].anchors);
});

test("publishes exactly what it publishes today when no reference was rescaled", () => {
  // The acceptance the fix is held to: a reference the export did not move carries no transform,
  // and its verdict has to come out byte-identical.
  const before = build({ runFindings: anchoredRun }).document;
  const after = build({ runFindings: anchoredRun, referenceTransforms: new Map() }).document;
  assert.deepEqual(after, before);
  assert.deepEqual(
    before.previews["button-filled__ideal__default__light"][0].findings[0].anchors,
    anchored.findings[0].anchors,
  );
});

test("a finding with no honest anchor is not given one", () => {
  const { document } = build({
    referenceTransforms: new Map([
      ["button-filled__ideal__default__light-0", { scaleX: 2, scaleY: 2, offsetX: 0, offsetY: 30 }],
    ]),
  });
  const [finding] = document.previews["button-filled__ideal__default__light"][0].findings;
  assert.equal(finding.anchors, undefined);
});

test("drops a reference anchor the placement cropped away, keeping the candidate's", () => {
  // A cropped-away anchor points at a region this reference does not publish; the server discards a
  // negative-origin box anyway, so publishing one costs the finding its highlight silently.
  const { document } = build({
    runFindings: anchoredRun,
    referenceTransforms: new Map([
      [
        "button-filled__ideal__default__light-0",
        { scaleX: 1, scaleY: 1, offsetX: 0, offsetY: -80, canvas: { width: 300, height: 210 } },
      ],
    ]),
  });
  const [finding] = document.previews["button-filled__ideal__default__light"][0].findings;
  assert.equal(finding.anchors.length, 1);
  assert.equal(finding.anchors[0].side, "actual");
});

test("a finding whose every anchor was cropped away carries no anchors at all", () => {
  // Not an empty list: the server offers an unanchored finding as prose and never as a control.
  const referenceOnly = {
    schema: FINDINGS_SCHEMA,
    previews: {
      "ui/Button.kt#Filled": [
        {
          status: "fail",
          findings: [
            {
              kind: "layout",
              severity: "error",
              message: "padding drifted",
              anchors: [{ side: "reference", bounds: { x: 0, y: 0, width: 100, height: 40 } }],
            },
          ],
        },
      ],
    },
  };
  const { document } = build({
    runFindings: referenceOnly,
    referenceTransforms: new Map([
      [
        "button-filled__ideal__default__light-0",
        { scaleX: 1, scaleY: 1, offsetX: 0, offsetY: -80, canvas: { width: 300, height: 210 } },
      ],
    ]),
  });
  const [finding] = document.previews["button-filled__ideal__default__light"][0].findings;
  assert.equal("anchors" in finding, false);
});

test("clips a reference anchor that only partly survived the crop", () => {
  const { document } = build({
    runFindings: anchoredRun,
    referenceTransforms: new Map([
      [
        "button-filled__ideal__default__light-0",
        { scaleX: 1, scaleY: 1, offsetX: 0, offsetY: -30, canvas: { width: 300, height: 210 } },
      ],
    ]),
  });
  const [finding] = document.previews["button-filled__ideal__default__light"][0].findings;
  // Captured at y 20, height 40; the crop takes the 30 rows above the artwork, so the box loses
  // its top 10 and the remaining 30 sit flush against the top of the published canvas.
  assert.deepEqual(finding.anchors[0].bounds, { x: 10, y: 0, width: 100, height: 30 });
});
