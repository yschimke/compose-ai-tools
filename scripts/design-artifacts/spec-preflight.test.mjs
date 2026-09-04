import { test } from "node:test";
import assert from "node:assert/strict";

import {
  ERROR,
  errorsOf,
  exclusionPatterns,
  functionOf,
  imagesByFunction,
  matchesExclusion,
  preflightSpec,
  previewImages,
} from "./spec-preflight.mjs";
import { foldVariants } from "./catalog-variants.mjs";
import { outputAxisKey } from "./catalog-variants.mjs";

const preview = (id, extra = {}) => ({ id, functionName: id.split(".").pop(), ...extra });

const spec = (components, extra = {}) => ({
  system: "test",
  groups: [{ name: "Components", components }],
  ...extra,
});

const kinds = (report) => report.findings.map((finding) => finding.kind);

test("matchesExclusion mirrors the CLI's anchored / glob / substring rules", () => {
  // Anchored: exact, so a base id never drags its fan-out along.
  assert.equal(matchesExclusion("=Switch_Dark", "Switch_Dark"), true);
  assert.equal(matchesExclusion("=Switch_Dark", "Switch_Dark_VARIANT_off"), false);
  // Glob: anchored at both ends, and a `.` in a package-qualified id stays literal.
  assert.equal(matchesExclusion("activity__*", "activity__MainActivity"), true);
  assert.equal(matchesExclusion("a?c", "abc"), true);
  assert.equal(matchesExclusion("a?c", "abcd"), false);
  assert.equal(matchesExclusion("com.a*", "comXa1"), false);
  // Plain: equality OR substring.
  assert.equal(matchesExclusion("Dark", "com.app.HomeKt.Home_Dark"), true);
  assert.equal(matchesExclusion("Home_Dark", "com.app.HomeKt.Home_Dark"), true);
  assert.equal(matchesExclusion("", "anything"), false);
});

test("exclusionPatterns splits and trims a comma list the way --exclude-preview-id does", () => {
  assert.deepEqual(exclusionPatterns(["a, b", "", " c "]), ["a", "b", "c"]);
  assert.deepEqual(exclusionPatterns("activity__*,apptour__*"), ["activity__*", "apptour__*"]);
});

test("functionOf prefers functionName and falls back to the id", () => {
  assert.equal(functionOf({ id: "com.a.HomeKt.Home_Dark", functionName: "Home" }), "Home");
  assert.equal(functionOf({ id: "Home" }), "Home");
});

test("a spec preview no manifest function answers to is reported as missing", () => {
  const report = preflightSpec(spec([{ componentId: "Home", preview: "HomeScren" }]), [
    preview("com.a.HomeKt.HomeScreen", { functionName: "HomeScreen" }),
  ]);
  assert.deepEqual(kinds(report), ["missing-preview"]);
  assert.match(report.findings[0].message, /Home \(component "HomeScren"\)/);
});

test("a variant's preview is resolved too, and a qualified multi-module name still matches", () => {
  const previews = [
    preview("com.a.HomeKt.Home", { functionName: "Home" }),
    preview("com.a.HomeKt.HomePressed", { functionName: "HomePressed" }),
  ];
  const ok = preflightSpec(
    spec([
      {
        componentId: "Home",
        // The repository-wide catalog rewrites a colliding name to `<module>::<function>`.
        preview: ":feature:home::Home",
        variants: [{ preview: "HomePressed", state: "pressed" }],
      },
    ]),
    previews,
  );
  assert.deepEqual(kinds(ok), []);

  const typo = preflightSpec(
    spec([
      { componentId: "Home", preview: "Home", variants: [{ preview: "HomePressd", state: "pressed" }] },
    ]),
    previews,
  );
  assert.deepEqual(kinds(typo), ["missing-preview"]);
  assert.equal(typo.findings[0].entry, "variant");
});

test("an exclusion pattern matching nothing is reported, with the id shape as the hint", () => {
  // The underscores-vs-spaces mistake: the id carries the @Preview(name = …) verbatim.
  const report = preflightSpec(spec([{ componentId: "Home", preview: "Home" }]), [
    preview("com.a.HomeKt.Home", { functionName: "Home" }),
    preview("com.a.HomeKt.Home Font scale 1.5x", { functionName: "Home" }),
  ], { excludePatterns: ["Home_Font_scale"] });
  assert.deepEqual(kinds(report), ["unmatched-exclusion"]);
  assert.deepEqual(report.patterns, [{ pattern: "Home_Font_scale", matches: 0 }]);
});

test("a pattern's match count is reported per pattern, not over the union", () => {
  // `Home` already covers every id `Home_Dark` would, so the union would hide the dead pattern.
  const report = preflightSpec(spec([{ componentId: "Home", preview: "Home" }]), [
    preview("com.a.HomeKt.Home_Light", { functionName: "Home" }),
  ], { excludePatterns: ["Home", "Home_Dark"] });
  assert.deepEqual(report.patterns, [
    { pattern: "Home", matches: 1 },
    { pattern: "Home_Dark", matches: 0 },
  ]);
  assert.ok(kinds(report).includes("unmatched-exclusion"));
});

test("a claimed function whose every id is excluded is reported apart from a missing one", () => {
  const report = preflightSpec(spec([{ componentId: "Home", preview: "Home" }]), [
    preview("com.a.HomeKt.Home", { functionName: "Home" }),
  ], { excludePatterns: ["Home"] });
  assert.deepEqual(kinds(report), ["excluded-claimed"]);
  assert.match(report.findings[0].message, /every discovered id of this function is excluded/);
});

test("the three-arm locale fan-out collides on output axes once one arm is excluded", () => {
  // The DroidKaigi case (#5059): a locale multipreview whose catalog declares no locale axis. Two
  // cycles to discover from renders; decidable here in one pass.
  const previews = ["ja", "en", "ar"].map((locale) =>
    preview(`com.a.HomeKt.Home_${locale}`, {
      functionName: "Home",
      params: { locale, name: locale },
    }),
  );
  const report = preflightSpec(spec([{ componentId: "Home", preview: "Home" }]), previews, {
    excludePatterns: ["=com.a.HomeKt.Home_ja"],
  });
  assert.deepEqual(kinds(report), ["duplicate-output-axes"]);
  assert.deepEqual(report.findings[0].previewIds, [
    "com.a.HomeKt.Home_en",
    "com.a.HomeKt.Home_ar",
  ]);
  // Excluding two of the three arms leaves one sticker and nothing to report.
  const narrowed = preflightSpec(spec([{ componentId: "Home", preview: "Home" }]), previews, {
    excludePatterns: ["=com.a.HomeKt.Home_ja", "=com.a.HomeKt.Home_ar"],
  });
  assert.deepEqual(kinds(narrowed), []);
});

test("every collision is collected, not just the first", () => {
  const previews = ["a", "b", "c"].map((tag) =>
    preview(`com.a.HomeKt.Home_${tag}`, { functionName: "Home", params: { locale: tag } }),
  );
  const report = preflightSpec(spec([{ componentId: "Home", preview: "Home" }]), previews);
  assert.deepEqual(kinds(report), ["duplicate-output-axes", "duplicate-output-axes"]);
});

test("a declared mode, a light/dark suffix and a uiMode night bit each separate two arms", () => {
  const declared = preflightSpec(
    spec([{ componentId: "Home", preview: "Home" }], { modes: ["light", "dark"] }),
    [
      preview("com.a.HomeKt.Home_Light", { functionName: "Home" }),
      preview("com.a.HomeKt.Home_Dark", { functionName: "Home" }),
    ],
  );
  assert.deepEqual(kinds(declared), []);

  // The same ids with no `modes` in the spec still separate on the light/dark suffix.
  const undeclared = preflightSpec(spec([{ componentId: "Home", preview: "Home" }]), [
    preview("com.a.HomeKt.Home_Light", { functionName: "Home" }),
    preview("com.a.HomeKt.Home_Dark", { functionName: "Home" }),
  ]);
  assert.deepEqual(kinds(undeclared), []);

  // A hand-written `@Preview(uiMode = UI_MODE_NIGHT_YES)` carries the theme only in the annotation.
  const uiMode = preflightSpec(spec([{ componentId: "Home", preview: "Home" }]), [
    preview("com.a.HomeKt.Home", { functionName: "Home", params: { uiMode: 0x11 } }),
    preview("com.a.HomeKt.HomeNight", { functionName: "Home", params: { uiMode: 0x21 } }),
  ]);
  assert.deepEqual(kinds(uiMode), []);
});

test("a declared breakpoint separates two device arms; an undeclared one does not", () => {
  const previews = [
    preview("com.a.HomeKt.Home_small", {
      functionName: "Home",
      params: { device: "id:wearos_small_round", widthDp: 192 },
    }),
    preview("com.a.HomeKt.Home_large", {
      functionName: "Home",
      params: { device: "id:wearos_large_round", widthDp: 227 },
    }),
  ];
  const declared = preflightSpec(
    spec([{ componentId: "Home", preview: "Home" }], {
      breakpoints: [
        { size: "smallRound", device: "id:wearos_small_round" },
        { size: "largeRound", device: "id:wearos_large_round" },
      ],
    }),
    previews,
  );
  assert.deepEqual(kinds(declared), []);

  // With no breakpoints the two renders land on one axis — the collapse `undeclaredBreakpointDevices`
  // warns about at render time, decidable here before the render starts.
  assert.deepEqual(
    kinds(preflightSpec(spec([{ componentId: "Home", preview: "Home" }]), previews)),
    ["duplicate-output-axes"],
  );
});

test("a `select` splits one fan-out across two components without colliding", () => {
  const previews = [
    preview("com.a.HomeKt.Home_small", { functionName: "Home", params: { widthDp: 192 } }),
    preview("com.a.HomeKt.Home_large", { functionName: "Home", params: { widthDp: 227 } }),
  ];
  const report = preflightSpec(
    spec(
      [
        { componentId: "Home/Small", preview: "Home", select: { size: "smallRound" } },
        { componentId: "Home/Large", preview: "Home", select: { size: "largeRound" } },
      ],
      {
        breakpoints: [
          { size: "smallRound", widthDp: 192 },
          { size: "largeRound", widthDp: 227 },
        ],
      },
    ),
    previews,
  );
  assert.deepEqual(kinds(report), []);
});

test("a same-function variant the default images already satisfy folds nothing in", () => {
  // Mirrors foldVariants: re-tagging the very image it matched would invent a collision.
  const previews = [
    preview("com.a.HomeKt.Home", { functionName: "Home" }),
    preview("com.a.HomeKt.Home_VARIANT_off", { functionName: "Home" }),
  ];
  const report = preflightSpec(
    spec([
      {
        componentId: "Home",
        preview: "Home",
        variants: [{ preview: "Home", state: "off" }],
      },
    ]),
    previews,
  );
  assert.deepEqual(kinds(report), []);
});

test("a state variant backed by its own function is re-tagged and does not collide", () => {
  const report = preflightSpec(
    spec([
      {
        componentId: "Home",
        preview: "Home",
        variants: [{ preview: "HomePressed", state: "pressed" }],
      },
    ]),
    [
      preview("com.a.HomeKt.Home", { functionName: "Home" }),
      preview("com.a.HomeKt.HomePressed", { functionName: "HomePressed" }),
    ],
  );
  assert.deepEqual(kinds(report), []);
});

test("two variants tagged with the same state collide on one sticker path", () => {
  const report = preflightSpec(
    spec([
      {
        componentId: "Home",
        preview: "Home",
        variants: [
          { preview: "HomeOff", state: "off" },
          { preview: "HomeUnchecked", state: "off" },
        ],
      },
    ]),
    [
      preview("com.a.HomeKt.Home", { functionName: "Home" }),
      preview("com.a.HomeKt.HomeOff", { functionName: "HomeOff" }),
      preview("com.a.HomeKt.HomeUnchecked", { functionName: "HomeUnchecked" }),
    ],
  );
  assert.deepEqual(kinds(report), ["duplicate-output-axes"]);
});

test("the preflight key agrees with the fold's own duplicate guard", () => {
  // One assertion, two implementations: whatever `foldVariants` throws on, this reports.
  const component = {
    componentId: "Home",
    preview: "Home",
    variants: [
      { preview: "HomeOff", state: "off" },
      { preview: "HomeUnchecked", state: "off" },
    ],
  };
  const image = (state) => ({ variant: "ideal", state, props: {} });
  assert.throws(
    () =>
      foldVariants([image("default")], component, {
        get: (fn) => ({ images: [image("default")] }),
      }),
    /produces duplicate output axes/,
  );
  assert.equal(outputAxisKey(image("off")), outputAxisKey({ state: "off" }));
});

test("exact render duplicates are collapsed the way applyCatalogPreviewAxes collapses them", () => {
  // Wear stacking @WearPreviewDevices on @WearPreviewFontScales emits the same small-round /
  // default-font render twice. The join drops the repeat; reporting it as a collision would fail
  // every Wear catalog.
  const previews = [
    preview("com.a.HomeKt.Home_small", { functionName: "Home", params: { widthDp: 192 } }),
    preview("com.a.HomeKt.Home_small_1x", {
      functionName: "Home",
      params: { widthDp: 192, fontScale: 1, name: "Font scale 1x" },
    }),
  ];
  assert.deepEqual(kinds(preflightSpec(spec([{ componentId: "Home", preview: "Home" }]), previews)), []);
  // A non-default font scale becomes a props axis instead, so it stays a distinct sticker.
  const scaled = [
    previews[0],
    preview("com.a.HomeKt.Home_small_2x", {
      functionName: "Home",
      params: { widthDp: 192, fontScale: 2 },
    }),
  ];
  assert.deepEqual(kinds(preflightSpec(spec([{ componentId: "Home", preview: "Home" }]), scaled)), []);
});

test("previewImages emits one image per capture", () => {
  const images = previewImages(
    preview("com.a.HomeKt.Home", {
      params: { widthDp: 192 },
      captures: [{ params: { fontScale: 1 } }, { params: { fontScale: 2 } }],
    }),
    { modes: [] },
  );
  assert.equal(images.length, 2);
  assert.deepEqual(images[0].props, {});
  assert.deepEqual(images[1].props, { fontScale: "2.0" });
});

test("imagesByFunction groups on the function, not the id", () => {
  const byFunction = imagesByFunction(
    [
      preview("com.a.HomeKt.Home_Light", { functionName: "Home" }),
      preview("com.a.HomeKt.Home_Dark", { functionName: "Home" }),
      preview("com.a.OtherKt.Other", { functionName: "Other" }),
    ],
    { modes: ["light", "dark"] },
  );
  assert.deepEqual([...byFunction.keys()], ["Home", "Other"]);
  assert.equal(byFunction.get("Home").length, 2);
});

test("ids no component claims are reported as information, never as a finding", () => {
  const report = preflightSpec(spec([{ componentId: "Home", preview: "Home" }]), [
    preview("com.a.HomeKt.Home", { functionName: "Home" }),
    preview("com.a.OtherKt.Other", { functionName: "Other" }),
  ]);
  assert.deepEqual(kinds(report), []);
  assert.deepEqual(report.unclaimed, ["com.a.OtherKt.Other"]);
  assert.deepEqual(report.counts, {
    previews: 2,
    excluded: 0,
    survivors: 2,
    claimedFunctions: 1,
    unclaimed: 1,
  });
});

test("errorsOf keeps only what the report is sure about", () => {
  const report = preflightSpec(spec([{ componentId: "Home", preview: "Nope" }]), [
    preview("com.a.HomeKt.Home", { functionName: "Home" }),
  ]);
  assert.equal(errorsOf(report).length, 1);
  assert.equal(errorsOf(report)[0].severity, ERROR);
  assert.equal(errorsOf({ findings: [{ severity: "info" }] }).length, 0);
});

test("a spec with no groups and an empty manifest report nothing", () => {
  assert.deepEqual(kinds(preflightSpec({ system: "test" }, [])), []);
  assert.deepEqual(preflightSpec(undefined, undefined).counts.previews, 0);
});
