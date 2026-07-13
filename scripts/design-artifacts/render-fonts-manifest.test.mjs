import { test } from "node:test";
import assert from "node:assert/strict";

import { buildFontsManifest, fontsPayloadsFromBundle } from "./render-fonts-manifest.mjs";

const ALL_FILES = new Set([
  "Roboto-Regular.ttf",
  "Roboto-Medium.ttf",
  "NotoSerif-Regular.ttf",
  "DroidSansMono.ttf",
]);

const entry = (requestedFamily, weight = 400, style = "normal") => ({
  requestedFamily,
  resolvedFamily: requestedFamily,
  weight,
  style,
});

test("default + generic families map onto the vendored files", () => {
  const payloads = [
    { fonts: [entry("FontFamily.Default", 400), entry("FontFamily.Default", 500)] },
    { fonts: [entry("serif", 400)] },
    { fonts: [entry("monospace", 400)] },
  ];
  const { manifest, warnings } = buildFontsManifest(payloads, ALL_FILES);
  assert.equal(warnings.length, 0);
  assert.deepEqual(manifest, {
    version: 1,
    families: [
      {
        name: "Roboto",
        role: "default",
        fonts: [
          { file: "Roboto-Regular.ttf", weight: 400 },
          { file: "Roboto-Medium.ttf", weight: 500 },
        ],
      },
      {
        name: "monospace",
        role: "generic",
        fonts: [{ file: "DroidSansMono.ttf", weight: 400 }],
      },
      {
        name: "serif",
        role: "generic",
        fonts: [{ file: "NotoSerif-Regular.ttf", weight: 400 }],
      },
    ],
  });
});

test("weights snap to the nearest vendored file and dedupe", () => {
  const payloads = [
    { fonts: [entry("FontFamily.Default", 700), entry("FontFamily.Default", 600)] },
  ];
  const { manifest } = buildFontsManifest(payloads, ALL_FILES);
  assert.deepEqual(manifest.families[0].fonts, [{ file: "Roboto-Medium.ttf", weight: 500 }]);
});

test("unknown families warn once and drop; the rest of the manifest survives", () => {
  const payloads = [
    {
      fonts: [
        entry("FontFamily.Default"),
        entry("res/font/inter_regular"),
        entry("res/font/inter_regular", 700),
      ],
    },
  ];
  const { manifest, warnings } = buildFontsManifest(payloads, ALL_FILES);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /inter_regular/);
  assert.deepEqual(
    manifest.families.map((f) => f.name),
    ["Roboto"],
  );
});

test("a vendored-file gap warns and drops just that family", () => {
  const payloads = [{ fonts: [entry("FontFamily.Default"), entry("serif")] }];
  const files = new Set(["Roboto-Regular.ttf", "Roboto-Medium.ttf"]);
  const { manifest, warnings } = buildFontsManifest(payloads, files);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /NotoSerif-Regular\.ttf/);
  assert.deepEqual(
    manifest.families.map((f) => f.name),
    ["Roboto"],
  );
});

test("the default family is always included when anything was recorded", () => {
  const payloads = [{ fonts: [entry("monospace")] }];
  const { manifest } = buildFontsManifest(payloads, ALL_FILES);
  assert.deepEqual(
    manifest.families.map((f) => [f.name, f.role]),
    [
      ["Roboto", "default"],
      ["monospace", "generic"],
    ],
  );
});

const gf = (name, weight = 400, style = "normal") =>
  entry(
    `Font(GoogleFont("${name}", bestEffort=true), weight=FontWeight(weight=${weight}), ` +
      `style=${style === "italic" ? "Italic" : "Normal"}, fontVariationSettings=Settings(settings=[]))`,
    weight,
    style,
  );

test("downloadable GoogleFonts become named families keyed by display name", () => {
  const files = new Set([
    ...ALL_FILES,
    "space-grotesk-400.ttf",
    "space-grotesk-700.ttf",
    "orbitron-500.ttf",
  ]);
  const payloads = [
    { fonts: [gf("Space Grotesk", 400), gf("Space Grotesk", 700), gf("Orbitron", 500)] },
  ];
  const { manifest, warnings } = buildFontsManifest(payloads, files);
  assert.equal(warnings.length, 0);
  // Roboto (default) is always present; named families follow, alphabetically.
  assert.deepEqual(
    manifest.families.map((f) => [f.name, f.role]),
    [
      ["Roboto", "default"],
      ["Orbitron", "named"],
      ["Space Grotesk", "named"],
    ],
  );
  assert.deepEqual(manifest.families[2].fonts, [
    { file: "space-grotesk-400.ttf", weight: 400 },
    { file: "space-grotesk-700.ttf", weight: 700 },
  ]);
});

test("a named GoogleFont face with no vendored file warns and drops just that face", () => {
  const files = new Set([...ALL_FILES, "space-grotesk-400.ttf"]);
  const payloads = [{ fonts: [gf("Space Grotesk", 400), gf("Space Grotesk", 700)] }];
  const { manifest, warnings } = buildFontsManifest(payloads, files);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /space-grotesk-700\.ttf/);
  assert.deepEqual(manifest.families.find((f) => f.name === "Space Grotesk").fonts, [
    { file: "space-grotesk-400.ttf", weight: 400 },
  ]);
});

test("italic GoogleFont faces vendor with the -italic stem and carry style:italic", () => {
  // The Wasm manifest bridge keys style off `f.style`, so an italic face must emit
  // `style: "italic"` (not an `italic` boolean) or it registers as a normal face.
  const files = new Set([...ALL_FILES, "space-grotesk-400-italic.ttf"]);
  const payloads = [{ fonts: [gf("Space Grotesk", 400, "italic")] }];
  const { manifest } = buildFontsManifest(payloads, files);
  assert.deepEqual(manifest.families.find((f) => f.name === "Space Grotesk").fonts, [
    { file: "space-grotesk-400-italic.ttf", weight: 400, style: "italic" },
  ]);
});

test("no recorded usage keeps the committed manifest (null)", () => {
  assert.equal(buildFontsManifest([], ALL_FILES).manifest, null);
  assert.equal(buildFontsManifest([{ fonts: [] }], ALL_FILES).manifest, null);
});

test("fontsPayloadsFromBundle reads sidecars and skips corrupt ones", () => {
  const enc = (s) => new TextEncoder().encode(s);
  const bundle = {
    previews: [{ id: "A" }, { id: "B" }, { id: "C" }],
    entries: {
      "previews/A.fonts.json": enc('{"fonts":[{"requestedFamily":"serif","weight":400}]}'),
      "previews/B.fonts.json": enc("not json"),
    },
  };
  const payloads = fontsPayloadsFromBundle(bundle);
  assert.equal(payloads.length, 1);
  assert.equal(payloads[0].fonts[0].requestedFamily, "serif");
});
