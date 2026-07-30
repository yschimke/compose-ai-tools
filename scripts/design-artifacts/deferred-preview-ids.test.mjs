import { test } from "node:test";
import assert from "node:assert/strict";

import {
  deferredPreviewIds,
  previewsFromJson,
  specPreviewFunctions,
} from "./deferred-preview-ids.mjs";
import { modeOfPreviewId } from "./catalog-priority.mjs";

/** A nine-theme-ish spec: light required, every other palette deferred. */
const spec = {
  system: "compose-m3",
  modes: ["light", "dark", "highContrastDark"],
  modePriority: { light: "required", "*": "deferred" },
  groups: [
    {
      name: "Buttons",
      components: [
        { componentId: "Buttons/Filled", preview: "FilledButtonPreview" },
        {
          componentId: "Buttons/Outlined",
          preview: "OutlinedButtonPreview",
          variants: [
            { preview: "OutlinedButtonDisabledPreview", state: "disabled" },
          ],
        },
      ],
    },
  ],
};

const preview = (id, functionName) => ({ id, functionName, params: {} });

/** A discovered preview whose function takes a `@PreviewParameter` provider. */
const parameterized = (id, functionName) => ({
  id,
  functionName,
  params: { previewParameterProviderClassName: "com.example.ThemeProvider" },
});

test("modeOfPreviewId reads the trailing mode segment case-insensitively", () => {
  const modes = ["light", "dark"];
  assert.equal(modeOfPreviewId("FilledButtonPreview_Dark", modes), "dark");
  assert.equal(modeOfPreviewId("FilledButtonPreview_Light", modes), "light");
  assert.equal(modeOfPreviewId("FilledButtonPreviewDark", modes), "dark");
  assert.equal(modeOfPreviewId("FilledButtonPreview", modes), null);
});

test("modeOfPreviewId prefers the longest declared mode", () => {
  const modes = ["dark", "highContrastDark"];
  assert.equal(
    modeOfPreviewId("FilledButtonPreview_HighContrastDark", modes),
    "highContrastDark",
  );
});

test("modeOfPreviewId only matches at a segment boundary", () => {
  // A separator or a camel-case hump is a boundary (`Foo_Dark`, `FooDark`); a mode buried inside a
  // word is not, so a mode named `ark` can never claim `FooDark`.
  assert.equal(modeOfPreviewId("FooDark", ["ark"]), null);
  assert.equal(modeOfPreviewId("Foo_ark", ["ark"]), "ark");
  // The camel-hump rule is symmetric, so a short mode name does match a hump that spells it — a
  // consequence worth knowing when naming modes, and bounded by the never-empty guard below.
  assert.equal(modeOfPreviewId("SwitchOn", ["on"]), "on");
});

test("modeOfPreviewId with no declared modes matches nothing", () => {
  assert.equal(modeOfPreviewId("FilledButtonPreview_Dark", []), null);
  assert.equal(modeOfPreviewId("FilledButtonPreview_Dark", undefined), null);
});

test("specPreviewFunctions collects components and variants", () => {
  assert.deepEqual([...specPreviewFunctions(spec)], [
    "FilledButtonPreview",
    "OutlinedButtonPreview",
    "OutlinedButtonDisabledPreview",
  ]);
});

test("deferred modes' ids are excluded, the required one is kept", () => {
  const previews = [
    preview("FilledButtonPreview_Light", "FilledButtonPreview"),
    preview("FilledButtonPreview_Dark", "FilledButtonPreview"),
    preview("FilledButtonPreview_HighContrastDark", "FilledButtonPreview"),
  ];
  const { ids, keptByGuard } = deferredPreviewIds(spec, previews);
  assert.deepEqual(ids, [
    "FilledButtonPreview_Dark",
    "FilledButtonPreview_HighContrastDark",
  ]);
  assert.deepEqual(keptByGuard, []);
});

test("a function whose EVERY id is mode-deferred keeps all of them", () => {
  // The publish-side rule is "the primary sticker is never deferrable by mode"; skipping the render
  // here would turn a spec misconfiguration into a component with no pixels.
  const previews = [
    preview("FilledButtonPreview_Dark", "FilledButtonPreview"),
    preview("FilledButtonPreview_HighContrastDark", "FilledButtonPreview"),
  ];
  const { ids, keptByGuard } = deferredPreviewIds(spec, previews);
  assert.deepEqual(ids, []);
  assert.deepEqual(keptByGuard, ["FilledButtonPreview"]);
});

test("previews the spec doesn't reference are left alone", () => {
  const previews = [
    preview("WireframeOnlyPreview_Dark", "WireframeOnlyPreview"),
    preview("WireframeOnlyPreview_Light", "WireframeOnlyPreview"),
  ];
  assert.deepEqual(deferredPreviewIds(spec, previews).ids, []);
});

test("variant previews are filtered like component previews", () => {
  const previews = [
    preview("OutlinedButtonDisabledPreview_Light", "OutlinedButtonDisabledPreview"),
    preview("OutlinedButtonDisabledPreview_Dark", "OutlinedButtonDisabledPreview"),
  ];
  assert.deepEqual(deferredPreviewIds(spec, previews).ids, [
    "OutlinedButtonDisabledPreview_Dark",
  ]);
});

test("a spec that defers no mode excludes nothing", () => {
  const strict = { ...spec, modePriority: undefined };
  const previews = [
    preview("FilledButtonPreview_Light", "FilledButtonPreview"),
    preview("FilledButtonPreview_Dark", "FilledButtonPreview"),
  ];
  assert.deepEqual(deferredPreviewIds(strict, previews).ids, []);
});

test("ids with no functionName fall back to the id itself", () => {
  const flat = {
    ...spec,
    groups: [
      {
        name: "Buttons",
        components: [{ componentId: "Buttons/Filled", preview: "FilledButtonPreview_Dark" }],
      },
    ],
  };
  // Degenerate but real for a bundle/manifest that carries no functionName: the id IS the key, and a
  // lone id that is itself mode-deferred hits the never-empty guard rather than being dropped.
  const { ids, keptByGuard } = deferredPreviewIds(flat, [{ id: "FilledButtonPreview_Dark" }]);
  assert.deepEqual(ids, []);
  assert.deepEqual(keptByGuard, ["FilledButtonPreview_Dark"]);
});

test("ids are unique and sorted", () => {
  const previews = [
    preview("OutlinedButtonPreview_Light", "OutlinedButtonPreview"),
    preview("OutlinedButtonPreview_Dark", "OutlinedButtonPreview"),
    preview("FilledButtonPreview_Light", "FilledButtonPreview"),
    preview("FilledButtonPreview_Dark", "FilledButtonPreview"),
    preview("FilledButtonPreview_Dark", "FilledButtonPreview"),
  ];
  assert.deepEqual(deferredPreviewIds(spec, previews).ids, [
    "FilledButtonPreview_Dark",
    "OutlinedButtonPreview_Dark",
  ]);
});

test("a parameterized function's deferred modes become row labels", () => {
  // One discovered preview for the whole palette fan-out, so neither `dark` nor `highContrastDark`
  // appears in an id and no id-level exclusion can name them.
  const previews = [parameterized("FilledButtonPreview", "FilledButtonPreview")];
  const { ids, rows } = deferredPreviewIds(spec, previews);
  assert.deepEqual(ids, []);
  assert.deepEqual(rows, ["dark", "highContrastDark"]);
});

test("a multipreview-only catalog emits no row labels", () => {
  // Every mode is visible as an id and nothing is parameterized: the id filter is exact, so the
  // wider label tool is never reached for.
  const previews = [
    preview("FilledButtonPreview_Light", "FilledButtonPreview"),
    preview("FilledButtonPreview_Dark", "FilledButtonPreview"),
  ];
  const { ids, rows } = deferredPreviewIds(spec, previews);
  assert.deepEqual(ids, ["FilledButtonPreview_Dark"]);
  assert.deepEqual(rows, []);
});

test("a mixed catalog keeps the label a parameterized function still needs", () => {
  // The regression this rule exists for: `dark` IS visible on the multipreview function, but the
  // parameterized one renders its Dark row regardless — a module-wide "seen as an id" test would
  // suppress the label and leave that row rendering.
  const previews = [
    preview("FilledButtonPreview_Light", "FilledButtonPreview"),
    preview("FilledButtonPreview_Dark", "FilledButtonPreview"),
    parameterized("OutlinedButtonPreview", "OutlinedButtonPreview"),
  ];
  const { ids, rows } = deferredPreviewIds(spec, previews);
  assert.deepEqual(ids, ["FilledButtonPreview_Dark"]);
  assert.deepEqual(rows, ["dark", "highContrastDark"]);
});

test("a parameterized function already covered by ids needs no label", () => {
  const previews = [
    parameterized("FilledButtonPreview_Light", "FilledButtonPreview"),
    parameterized("FilledButtonPreview_Dark", "FilledButtonPreview"),
    parameterized("FilledButtonPreview_HighContrastDark", "FilledButtonPreview"),
  ];
  assert.deepEqual(deferredPreviewIds(spec, previews).rows, []);
});

test("a payload with no params at all treats every function as a candidate", () => {
  // An older or brief listing can't answer "is this parameterized?" — fall back to the wider set
  // rather than silently emitting nothing.
  const previews = [{ id: "FilledButtonPreview", functionName: "FilledButtonPreview" }];
  assert.deepEqual(deferredPreviewIds(spec, previews).rows, ["dark", "highContrastDark"]);
});

test("a spec that defers no mode emits no row labels", () => {
  const strict = { ...spec, modePriority: undefined };
  const previews = [parameterized("FilledButtonPreview", "FilledButtonPreview")];
  assert.deepEqual(deferredPreviewIds(strict, previews).rows, []);
});

test("previewsFromJson accepts every shape the pipeline produces", () => {
  const rows = [{ id: "A" }];
  assert.deepEqual(previewsFromJson(rows), rows);
  assert.deepEqual(previewsFromJson({ previews: rows }), rows);
  assert.deepEqual(previewsFromJson({ results: rows }), rows);
  assert.deepEqual(previewsFromJson({ nothing: true }), []);
});
