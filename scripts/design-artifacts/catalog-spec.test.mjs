import { test } from "node:test";
import assert from "node:assert/strict";

import {
  stripComments,
  blankStringContents,
  discoverPreviews,
  specPreviewRefs,
  editDistance,
  closest,
  validateSpec,
  buildSkeletonSpec,
} from "./catalog-spec.mjs";

test("stripComments removes line and block comments, keeps strings", () => {
  const src = `
    // @Preview fun Commented() {}
    /* @Preview fun AlsoCommented() {} */
    /** KDoc @Preview */
    val s = "not a // comment and not @Preview"
    @Preview @Composable fun Real() {}
  `;
  const stripped = stripComments(src);
  assert.ok(!stripped.includes("Commented"));
  assert.ok(!stripped.includes("AlsoCommented"));
  assert.ok(stripped.includes("not a // comment")); // string literal preserved
  assert.ok(stripped.includes("fun Real"));
});

test("discoverPreviews finds @Preview functions and skips commented-out ones", () => {
  const src = `
    import androidx.compose.ui.tooling.preview.Preview
    @Preview @Composable fun Alpha() {}
    // @Preview fun Ghost() {}
    @Composable fun NotAPreview() {}
    @Preview(name = "Dark", uiMode = 32) @Composable fun Beta() {}
  `;
  const { previews } = discoverPreviews([src]);
  assert.deepEqual(previews, ["Alpha", "Beta"]);
});

test("discoverPreviews resolves multipreview annotation classes (and chains)", () => {
  const decls = `
    @Preview(name = "Light")
    @Preview(name = "Dark", uiMode = 32)
    annotation class CatalogModes
    @CatalogModes
    annotation class BrandModes
  `;
  const usage = `
    @CatalogModes @Composable fun Filled() = Unit
    @BrandModes @Composable fun Branded() = Unit
    @Composable fun Plain() = Unit
  `;
  const { previews, annotations } = discoverPreviews([decls, usage]);
  assert.ok(annotations.includes("CatalogModes"));
  assert.ok(annotations.includes("BrandModes"), "chained multipreview annotation resolved");
  assert.deepEqual(previews, ["Branded", "Filled"]);
});

test("discoverPreviews honours extraAnnotations for imported multipreview markers", () => {
  const src = `@ImportedModes @Composable fun Imported() = Unit`;
  assert.deepEqual(discoverPreviews([src]).previews, []);
  assert.deepEqual(
    discoverPreviews([src], { extraAnnotations: ["ImportedModes"] }).previews,
    ["Imported"],
  );
});

test("blankStringContents empties string bodies but keeps quotes and structure", () => {
  const out = blankStringContents('val a = "has (parens) and fun x()"; val b = 1');
  assert.ok(!out.includes("(parens)"));
  assert.ok(out.includes('val a = "'));
  assert.ok(out.includes("val b = 1"));
});

test("discoverPreviews survives parens inside a @Preview string argument", () => {
  // Regression: the arg list must not end early at the '(' inside the string,
  // which would leave the fun regex staring at '(' and drop the preview.
  const src = `
    @Preview(name = "Now Playing (debug overlay)", widthDp = 400)
    @Composable
    fun NowPlaying() = Unit
  `;
  assert.deepEqual(discoverPreviews([src]).previews, ["NowPlaying"]);
});

test("discoverPreviews handles one level of nested parens in annotation args", () => {
  const src = `@Preview(widthDp = dpFrom(400)) @Composable fun Nested() = Unit`;
  assert.deepEqual(discoverPreviews([src]).previews, ["Nested"]);
});

test("discoverPreviews handles keyword modifiers and multi-line annotation args", () => {
  const src = `
    @Preview(
      name = "Big",
      widthDp = 700,
    )
    @Composable
    private fun WideOne() = Unit
  `;
  assert.deepEqual(discoverPreviews([src]).previews, ["WideOne"]);
});

test("specPreviewRefs collects component and variant previews", () => {
  const spec = {
    groups: [
      {
        name: "Buttons",
        components: [
          { componentId: "Button/Filled", preview: "Filled", variants: [{ state: "pressed", preview: "FilledPressed" }] },
        ],
      },
    ],
  };
  const refs = specPreviewRefs(spec).map((r) => r.preview);
  assert.deepEqual(refs, ["Filled", "FilledPressed"]);
});

test("editDistance and closest suggest near typos only", () => {
  assert.equal(editDistance("Filled", "Filled"), 0);
  assert.equal(editDistance("FilledButton", "FiledButton"), 1);
  assert.equal(closest("FiledButton", ["FilledButton", "OutlinedButton"]), "FilledButton");
  assert.equal(closest("CompletelyDifferent", ["FilledButton"]), null);
});

test("validateSpec flags structural problems", () => {
  const { errors } = validateSpec({ groups: [] });
  assert.ok(errors.some((e) => e.includes("`system` is required")));
  assert.ok(errors.some((e) => e.includes("`title` is required")));
  // An explicit empty `groups` is a mistake, not "annotation-supplied".
  assert.ok(errors.some((e) => e.includes("`groups`, when present, must be a non-empty array")));
});

test("validateSpec accepts a cover-sheet spec with no groups (annotation-supplied inventory)", () => {
  // A catalog whose inventory lives in `@CatalogComponent` / `@CatalogVariant` annotations carries
  // only cover-sheet fields; an absent `groups` must validate cleanly when the module is annotated.
  const { errors, warnings } = validateSpec(
    { system: "compose-m3", title: "Compose Material 3" },
    { knownPreviews: ["FilledButton", "SwitchOn"], annotatedInventory: true },
  );
  assert.deepEqual(errors, []);
  assert.deepEqual(warnings, []);
});

test("validateSpec rejects a no-groups spec when the module has no @CatalogComponent", () => {
  // Caller scanned the module and found no annotated inventory, so a `groups`-less spec has no
  // components at all — reject early instead of rendering then crashing at the join.
  const { errors } = validateSpec(
    { system: "compose-m3", title: "Compose Material 3" },
    { knownPreviews: ["FilledButton"], annotatedInventory: false },
  );
  assert.ok(errors.some((e) => e.includes("the catalog has no")));
});

test("validateSpec stays lenient on a no-groups spec when annotation state is unknown", () => {
  // Structural-only path (no source access): don't reject — the render-time generator guard is the
  // backstop for a genuinely empty inventory.
  const { errors } = validateSpec({ system: "s", title: "t" });
  assert.deepEqual(errors, []);
});

test("validateSpec flags duplicate componentId and warns on folded preview", () => {
  const spec = {
    system: "s",
    title: "T",
    groups: [
      {
        name: "G",
        components: [
          { componentId: "A", preview: "PreviewOne" },
          { componentId: "A", preview: "PreviewTwo" },
          { componentId: "B", preview: "PreviewOne" },
        ],
      },
    ],
  };
  const { errors, warnings } = validateSpec(spec);
  assert.ok(errors.some((e) => e.includes('componentId "A" is a duplicate')));
  assert.ok(warnings.some((w) => w.includes('preview "PreviewOne" is referenced 2')));
});

test("validateSpec resolves preview names against knownPreviews with a suggestion", () => {
  const spec = {
    system: "s",
    title: "T",
    groups: [{ name: "G", components: [{ componentId: "A", preview: "FiledButton" }] }],
  };
  const { errors } = validateSpec(spec, { knownPreviews: ["FilledButton", "OutlinedButton"] });
  assert.equal(errors.length, 1);
  assert.ok(errors[0].includes("matches no @Preview function"));
  assert.ok(errors[0].includes('did you mean "FilledButton"'));
});

test("validateSpec warns about @Preview functions absent from the catalog", () => {
  const spec = {
    system: "s",
    title: "T",
    groups: [{ name: "G", components: [{ componentId: "A", preview: "Used" }] }],
  };
  const { errors, warnings } = validateSpec(spec, { knownPreviews: ["Used", "Unused"] });
  assert.equal(errors.length, 0);
  assert.ok(warnings.some((w) => w.includes("Unused")));
});

test("validateSpec rejects a variant with no distinguishing axis", () => {
  const spec = {
    system: "s",
    title: "T",
    groups: [
      {
        name: "G",
        components: [{ componentId: "A", preview: "P", variants: [{ preview: "PVariant" }] }],
      },
    ],
  };
  const { errors } = validateSpec(spec);
  assert.ok(errors.some((e) => e.includes("neither `state`, `props` nor `theme`")));
  assert.ok(errors.some((e) => e.includes("overwrite the default artifact")));
});

test("validateSpec rejects unsupported variant properties", () => {
  const spec = {
    system: "s",
    title: "T",
    groups: [
      {
        name: "G",
        components: [
          {
            componentId: "A",
            preview: "P",
            variants: [{ mode: "dark", preview: "PDark" }],
          },
        ],
      },
    ],
  };
  const { errors } = validateSpec(spec);
  assert.ok(errors.some((e) => e.includes(".mode is not supported")));
});

test("validateSpec accepts a theme variant and still resolves its preview name", () => {
  const spec = {
    system: "s",
    title: "T",
    groups: [
      {
        name: "G",
        components: [
          {
            componentId: "Screen/Foo",
            preview: "FooScreen",
            variants: [{ theme: "dark", preview: "FooScreenDark" }],
          },
        ],
      },
    ],
  };
  // A theme variant is distinguishable, so no "neither state/props/theme" warning…
  const { warnings, errors } = validateSpec(spec, {
    knownPreviews: ["FooScreen", "FooScreenDark"],
  });
  assert.deepEqual(errors, []);
  assert.ok(!warnings.some((w) => w.includes("won't be distinguishable")));
});

test("validateSpec rejects a theme variant whose value is not light/dark", () => {
  const spec = {
    system: "s",
    title: "T",
    groups: [
      {
        name: "G",
        components: [
          { componentId: "A", preview: "P", variants: [{ theme: "midnight", preview: "PDark" }] },
        ],
      },
    ],
  };
  const { errors } = validateSpec(spec);
  assert.ok(errors.some((e) => e.includes('theme must be "light" or "dark"')));
});

test("buildSkeletonSpec produces an editable one-group spec", () => {
  const spec = buildSkeletonSpec({
    system: "demo",
    title: "Demo",
    module: "app",
    previews: ["Alpha", "Beta"],
    schema: "./catalog.spec.schema.json",
  });
  assert.equal(spec.$schema, "./catalog.spec.schema.json");
  assert.equal(spec.system, "demo");
  assert.equal(spec.groups.length, 1);
  assert.deepEqual(spec.groups[0].components.map((c) => c.preview), ["Alpha", "Beta"]);
  assert.deepEqual(spec.groups[0].components.map((c) => c.componentId), ["Alpha", "Beta"]);
  // A skeleton must itself be structurally valid and self-consistent.
  const { errors } = validateSpec(spec, { knownPreviews: ["Alpha", "Beta"] });
  assert.deepEqual(errors, []);
});

test("discoverPreviews flags GIF-only captures as PNG-less", () => {
  const src = `
    @Preview(name = "Toggle")
    @AnimatedPreview(durationMs = 1000, frameIntervalMs = 100)
    @Composable fun ToggleAnimatedPreview() {}

    @Preview @Composable fun Static() {}

    @Preview
    @FocusedPreview(gif = true, indices = [0, 1, 2])
    @Composable fun FocusGif() {}

    @Preview
    @ScrollingPreview(modes = [ScrollMode.GIF])
    @Composable fun ScrollGif() {}

    @Preview
    @ScrollingPreview(modes = [ScrollMode.LONG])
    @Composable fun ScrollLong() {}
  `;
  const { previews, pngLess } = discoverPreviews([src]);
  assert.deepEqual(previews, [
    "FocusGif",
    "ScrollGif",
    "ScrollLong",
    "Static",
    "ToggleAnimatedPreview",
  ]);
  // LONG and GIF are both data products written under `data/…`, never
  // `previews/<id>.png`, so neither is catalogable.
  assert.deepEqual(pngLess, ["FocusGif", "ScrollGif", "ScrollLong", "ToggleAnimatedPreview"]);
});

test("discoverPreviews keeps previews whose GIF sits alongside a static capture", () => {
  const src = `
    @Preview
    @AnimatedPreview
    @ScrollingPreview(modes = [ScrollMode.END, ScrollMode.GIF])
    @Composable fun ScrolledAndAnimated() {}

    @Preview
    @FocusedPreview(indices = [0, 1])
    @Composable fun FocusSteps() {}

    @Preview
    @ScrollingPreview
    @Composable fun DefaultScroll() {}

    @Preview
    @RoboComposePreviewOptions(manualClockOptions = [ManualClockOptions(advanceTimeMillis = 300)])
    @AnimatedPreview
    @Composable fun TimedAndAnimated() {}
  `;
  const { pngLess } = discoverPreviews([src]);
  assert.deepEqual(pngLess, []);
});

test("discoverPreviews only calls a name PNG-less when every declaration is", () => {
  const animated = `
    @Preview @AnimatedPreview @Composable fun Shared() {}
  `;
  const stat = `
    @Preview @Composable fun Shared() {}
  `;
  assert.deepEqual(discoverPreviews([animated, stat]).pngLess, []);
});

test("validateSpec rejects a component pointing at a PNG-less preview", () => {
  const spec = {
    system: "s",
    title: "T",
    groups: [
      {
        name: "Motion",
        components: [{ componentId: "Motion/Toggle", preview: "ToggleAnimatedPreview" }],
      },
    ],
  };
  const { errors } = validateSpec(spec, {
    knownPreviews: ["ToggleAnimatedPreview", "Static"],
    pngLessPreviews: ["ToggleAnimatedPreview"],
  });
  assert.equal(errors.length, 1);
  assert.ok(errors[0].includes('preview "ToggleAnimatedPreview"'));
  assert.ok(errors[0].includes("renders no static PNG"));
});

test("validateSpec rejects a PNG-less preview referenced from a variant", () => {
  const spec = {
    system: "s",
    title: "T",
    groups: [
      {
        name: "G",
        components: [
          {
            componentId: "A",
            preview: "Static",
            variants: [{ state: "pressed", preview: "PressedGif" }],
          },
        ],
      },
    ],
  };
  const { errors } = validateSpec(spec, {
    knownPreviews: ["Static", "PressedGif"],
    pngLessPreviews: ["PressedGif"],
  });
  assert.equal(errors.length, 1);
  assert.ok(errors[0].includes("variants[0]"));
});

test("validateSpec does not report PNG-less previews as coverage orphans", () => {
  const spec = {
    system: "s",
    title: "T",
    groups: [{ name: "G", components: [{ componentId: "A", preview: "Static" }] }],
  };
  const { errors, warnings } = validateSpec(spec, {
    knownPreviews: ["Static", "ToggleAnimatedPreview"],
    pngLessPreviews: ["ToggleAnimatedPreview"],
  });
  assert.deepEqual(errors, []);
  assert.deepEqual(warnings, []);
});

test("discoverPreviews keeps a singleton @FocusedPreview(gif = true) PNG-capable", () => {
  // `extractFocusGifSpec` bails below two steps (a one-frame GIF wouldn't animate),
  // so these fall back to the ordinary focus fan-out and do render a static PNG.
  const src = `
    @Preview @FocusedPreview(gif = true) @Composable fun DefaultIndex() {}
    @Preview @FocusedPreview(gif = true, indices = [2]) @Composable fun OneIndex() {}
    @Preview @FocusedPreview(gif = true, indices = [1, 1]) @Composable fun RepeatedIndex() {}
    @Preview @FocusedPreview(gif = true, traverse = [FocusDirection.Next]) @Composable fun OneStep() {}
  `;
  assert.deepEqual(discoverPreviews([src]).pngLess, []);
  // Two or more steps really is GIF-only, in either mode.
  const gifs = `
    @Preview @FocusedPreview(gif = true, indices = [0, 1]) @Composable fun TwoIndices() {}
    @Preview
    @FocusedPreview(gif = true, traverse = [FocusDirection.Next, FocusDirection.Previous])
    @Composable fun TwoSteps() {}
  `;
  assert.deepEqual(discoverPreviews([gifs]).pngLess, ["TwoIndices", "TwoSteps"]);
});

test("discoverPreviews recognises directly imported ScrollMode entries", () => {
  const src = `
    import ee.schimke.composeai.preview.ScrollMode.GIF
    @Preview @ScrollingPreview(modes = [GIF]) @Composable fun BareGif() {}
    @Preview @ScrollingPreview(modes = [LONG]) @Composable fun BareLong() {}
    @Preview @ScrollingPreview(modes = [END, GIF]) @Composable fun BareEndAndGif() {}
    @Preview @ScrollingPreview([ScrollMode.GIF]) @Composable fun PositionalGif() {}
  `;
  const { pngLess } = discoverPreviews([src]);
  assert.deepEqual(pngLess, ["BareGif", "BareLong", "PositionalGif"]);
});

test("discoverPreviews does not read a sibling argument as a scroll mode", () => {
  // `DEFAULT_GIF_FRAME_INTERVAL_MS` must not register as ScrollMode.GIF, and the
  // annotation still defaults to `[ScrollMode.END]` — a static capture.
  const src = `
    @Preview
    @ScrollingPreview(frameIntervalMs = DEFAULT_GIF_FRAME_INTERVAL_MS, maxScrollPx = 800)
    @Composable fun DefaultModeWithArgs() {}
  `;
  assert.deepEqual(discoverPreviews([src]).pngLess, []);
});

test("discoverPreviews ignores a manualClockOptions array with no stops", () => {
  // `extractRoboTimings` reads each entry's `advanceTimeMillis`, so an empty array
  // is zero timings — the animation still suppresses the static cross-product.
  const src = `
    @Preview
    @RoboComposePreviewOptions(manualClockOptions = [])
    @AnimatedPreview
    @Composable fun EmptyClockStops() {}
  `;
  assert.deepEqual(discoverPreviews([src]).pngLess, ["EmptyClockStops"]);
});
