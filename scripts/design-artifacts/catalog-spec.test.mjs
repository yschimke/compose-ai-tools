import { test } from "node:test";
import assert from "node:assert/strict";

import {
  stripComments,
  blankStringContents,
  discoverPreviews,
  discoverComponentIds,
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

test("specPreviewRefs collects component, motion, and variant previews", () => {
  const spec = {
    groups: [
      {
        name: "Buttons",
        components: [
          {
            componentId: "Button/Filled",
            preview: "Filled",
            motionPreview: "FilledMotion",
            variants: [{ state: "pressed", preview: "FilledPressed" }],
          },
        ],
      },
    ],
  };
  const refs = specPreviewRefs(spec).map((r) => r.preview);
  assert.deepEqual(refs, ["Filled", "FilledMotion", "FilledPressed"]);
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

test("discoverComponentIds reads the ids off @CatalogComponent", () => {
  const source = `
    @CatalogComponent(
      id = "Template/AppScaffold",
      group = "Scaffold templates",
      caption = "Full-screen layout (with the OS status bar).",
    )
    @Composable fun AppScaffoldTemplate() {}
    @CatalogComponent(id = "Button/Filled", group = "Buttons")
    @Composable fun FilledButton() {}
    // @CatalogComponent(id = "Commented/Out")
  `;
  assert.deepEqual(discoverComponentIds([source]), ["Button/Filled", "Template/AppScaffold"]);
});

test("discoverComponentIds reads a multiline positional id without swallowing later args", () => {
  // The argument list starts with a newline + indent, so the id is NOT at offset 0; a pattern loose
  // enough to skip that must not then mistake the positional `group` for the id.
  const source = `
    @CatalogComponent(
      "Button/Filled",
      "Buttons",
    )
    @Composable fun FilledButton() {}
  `;
  assert.deepEqual(discoverComponentIds([source]), ["Button/Filled"]);
});

test("validateSpec resolves display.hero against annotated componentIds", () => {
  // A cover-sheet spec's hero names a componentId that exists only in the module's annotations.
  const opts = { knownPreviews: ["AppScaffoldTemplate"], annotatedInventory: true };
  const spec = { system: "compose-m3", title: "T", display: { hero: "Template/AppScaffold" } };
  assert.deepEqual(
    validateSpec(spec, { ...opts, knownComponentIds: ["Template/AppScaffold"] }).errors,
    [],
  );
  // A hero matching nothing is a silent fall-through at serve time, so fail here instead.
  const { errors } = validateSpec(
    { ...spec, display: { hero: "Template/AppScafold" } },
    { ...opts, knownComponentIds: ["Template/AppScaffold"] },
  );
  assert.ok(errors.some((e) => e.includes('display.hero "Template/AppScafold"')));
  assert.ok(errors.some((e) => e.includes('did you mean "Template/AppScaffold"')));
});

test("validateSpec stays lenient on display.hero with no module scan", () => {
  // Structural-only path: the candidate set is only half the picture, so don't reject.
  const spec = { system: "s", title: "t", display: { hero: "Whatever" } };
  assert.deepEqual(validateSpec(spec).errors, []);
});

test("validateSpec resolves display.hero against the spec's own componentIds", () => {
  const spec = {
    system: "remote-m3",
    title: "T",
    display: { hero: "Template/WatchScreen" },
    groups: [
      {
        name: "Scaffold templates",
        components: [{ componentId: "Template/WatchScreen", preview: "WatchScreenRemote" }],
      },
    ],
  };
  assert.deepEqual(validateSpec(spec, { knownPreviews: ["WatchScreenRemote"] }).errors, []);
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
  assert.ok(errors.some((e) => e.includes("neither `state`, `props`, `theme` nor `select`")));
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

test("validateSpec accepts a separate PNG-less motion preview", () => {
  const spec = {
    system: "s",
    title: "T",
    groups: [
      {
        name: "Motion",
        components: [
          {
            componentId: "Motion/Spinner",
            preview: "Spinner",
            motionPreview: "SpinnerMotion",
          },
        ],
      },
    ],
  };
  const { errors, warnings } = validateSpec(spec, {
    knownPreviews: ["Spinner", "SpinnerMotion"],
    pngLessPreviews: ["SpinnerMotion"],
  });
  assert.deepEqual(errors, []);
  assert.deepEqual(warnings, []);
});

test("validateSpec rejects an unknown separate motion preview", () => {
  const spec = {
    system: "s",
    title: "T",
    groups: [
      {
        name: "Motion",
        components: [
          {
            componentId: "Motion/Spinner",
            preview: "Spinner",
            motionPreview: "MissingMotion",
          },
        ],
      },
    ],
  };
  const { errors } = validateSpec(spec, { knownPreviews: ["Spinner", "SpinnerMotion"] });
  assert.equal(errors.length, 1);
  assert.ok(errors[0].includes('motion preview "MissingMotion"'));
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

test('validateSpec accepts a PNG-less preview declared `capture: "none"`', () => {
  const spec = {
    system: "s",
    title: "T",
    groups: [
      {
        name: "Motion",
        section: "Animations",
        components: [
          {
            componentId: "Motion/Toggle",
            preview: "ToggleAnimatedPreview",
            capture: "none",
          },
        ],
      },
    ],
  };
  const { errors } = validateSpec(spec, {
    knownPreviews: ["ToggleAnimatedPreview", "Static"],
    pngLessPreviews: ["ToggleAnimatedPreview"],
  });
  assert.deepEqual(errors, []);
});

test('validateSpec still rejects an undeclared ref to a preview another entry declared "none"', () => {
  const spec = {
    system: "s",
    title: "T",
    groups: [
      {
        name: "G",
        components: [
          { componentId: "A", preview: "Gif", capture: "none" },
          { componentId: "B", preview: "Gif" },
        ],
      },
    ],
  };
  const { errors } = validateSpec(spec, {
    knownPreviews: ["Gif"],
    pngLessPreviews: ["Gif"],
  });
  // One error for the undeclared entry, naming ITS path — not the declared one's.
  const pngLess = errors.filter((e) => e.includes("renders no static PNG"));
  assert.equal(pngLess.length, 1);
  assert.ok(pngLess[0].includes("components[1]"));
});

test("validateSpec rejects a capture value that isn't a declared mode", () => {
  const spec = {
    system: "s",
    title: "T",
    groups: [
      {
        name: "G",
        components: [
          { componentId: "A", preview: "P", capture: "gif" },
          {
            componentId: "B",
            preview: "Q",
            variants: [{ state: "pressed", preview: "R", capture: true }],
          },
          // "animated" is not a mode — it reads as an exemption to a human but would fall through to
          // the strict `"static"` default and sink the publish on the entry it was meant to exempt.
          { componentId: "C", preview: "S", capture: "animated" },
        ],
      },
    ],
  };
  const { errors } = validateSpec(spec);
  assert.equal(errors.length, 3);
  assert.ok(errors[0].includes("components[0].capture must be one of"));
  assert.ok(errors[1].includes("variants[0].capture must be one of"));
  assert.ok(errors[2].includes("components[2].capture must be one of"));
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

// --- render priority (issue #2950) -------------------------------------------------------------

/** A minimal, otherwise-valid spec with the given components in one group. */
function prioritySpec(components, extra = {}) {
  return {
    system: "demo",
    title: "Demo",
    groups: [{ name: "Components", components }],
    ...extra,
  };
}

test("validateSpec accepts priority on components and variants", () => {
  const { errors } = validateSpec(
    prioritySpec([
      { componentId: "A", preview: "Alpha", priority: "required" },
      { componentId: "B", preview: "Beta", priority: "deferred" },
      {
        componentId: "C",
        preview: "Gamma",
        variants: [{ preview: "GammaOff", state: "off", priority: "deferred" }],
      },
    ]),
  );
  assert.deepEqual(errors, []);
});

test("validateSpec rejects an unrecognised priority value", () => {
  // Deliberately an error: `entryPriority` reads anything unknown as `required`, so a typo would
  // otherwise silently bake the entry and look like the deferral saved nothing.
  const { errors } = validateSpec(
    prioritySpec([{ componentId: "A", preview: "Alpha", priority: "optional" }]),
  );
  assert.equal(errors.length, 1);
  assert.match(errors[0], /priority must be one of "required", "deferred"/);
});

test("validateSpec rejects an unrecognised priority on a variant", () => {
  const { errors } = validateSpec(
    prioritySpec([
      { componentId: "A", preview: "Alpha", variants: [{ preview: "X", state: "off", priority: "later" }] },
    ]),
  );
  assert.equal(errors.length, 1);
  assert.match(errors[0], /variants\[0\]\.priority must be one of/);
});

test("validateSpec checks modePriority shape and values", () => {
  assert.match(
    validateSpec(prioritySpec([], { modePriority: ["light"] })).errors[0],
    /`modePriority` must be an object/,
  );
  assert.match(
    validateSpec(
      prioritySpec([{ componentId: "A", preview: "Alpha" }], { modePriority: { dark: "maybe" } }),
    ).errors[0],
    /modePriority\["dark"\] must be one of/,
  );
});

test("validateSpec warns about a modePriority mode the spec never declares", () => {
  const { warnings } = validateSpec(
    prioritySpec([{ componentId: "A", preview: "Alpha" }], {
      modes: ["light", "dark"],
      modePriority: { drak: "deferred" },
    }),
  );
  assert.ok(warnings.some((w) => /names mode\(s\) not in `modes`: drak/.test(w)));
});

test("validateSpec warns when modePriority defers every declared mode", () => {
  const { warnings, errors } = validateSpec(
    prioritySpec([{ componentId: "A", preview: "Alpha" }], {
      modes: ["light", "dark"],
      modePriority: { "*": "deferred" },
    }),
  );
  assert.deepEqual(errors, []);
  assert.ok(warnings.some((w) => /defers every declared mode/.test(w)));
});

test("validateSpec rejects axis deferral when the publish has no live path", () => {
  // `modePriority` genuinely thins the published set, so publishing it without a live path would drop
  // coverage no serve host could produce.
  const spec = prioritySpec([{ componentId: "A", preview: "Alpha" }], {
    modes: ["light", "dark"],
    modePriority: { light: "required", dark: "deferred" },
  });
  const { errors } = validateSpec(spec, { liveBundle: false });
  assert.equal(errors.length, 1);
  assert.match(errors[0], /no live path/);
  // With a live path, and with the option omitted entirely (the lenient default), it passes.
  assert.deepEqual(validateSpec(spec, { liveBundle: true }).errors, []);
  assert.deepEqual(validateSpec(spec).errors, []);
});

test("validateSpec rejects entry deferral when the publish has no live path", () => {
  // An entry-deferred component has no `images[]` record at all, so the live lane
  // (`catalog.json` `deferred[]` → `ServeCatalogStore`, #2965) is the ONLY way a viewer reaches it.
  // Without a live path it is coverage dropped outright, which is the one thing deferral must not be.
  const spec = prioritySpec([
    { componentId: "A", preview: "Alpha" },
    { componentId: "B", preview: "Beta", priority: "deferred" },
  ]);
  const { errors } = validateSpec(spec, { liveBundle: false });
  assert.equal(errors.length, 1);
  assert.match(errors[0], /no live path/);
  assert.deepEqual(validateSpec(spec, { liveBundle: true }).errors, []);
  // A variant-level deferral is the same trade, and gated the same way.
  const variantSpec = prioritySpec([
    {
      componentId: "A",
      preview: "Alpha",
      variants: [{ preview: "AlphaOff", state: "off", priority: "deferred" }],
    },
  ]);
  assert.match(validateSpec(variantSpec, { liveBundle: false }).errors[0], /no live path/);
});

test("validateSpec rejects an all-deferred catalog once it knows groups is the whole inventory (#2993)", () => {
  // Every entry deferred → the render filter would be empty → both workflows read that as
  // render-everything, and the published bundle carries no baked stickers. Fires only when the
  // module was scanned and has no @CatalogComponent (`annotatedInventory: false`), i.e. `groups` is
  // the complete inventory.
  const spec = prioritySpec([
    { componentId: "A", preview: "Alpha", priority: "deferred" },
    { componentId: "B", preview: "Beta", priority: "deferred" },
  ]);
  assert.match(validateSpec(spec, { annotatedInventory: false }).errors[0], /defers every entry/);
  // Still rejected with a live path present — an empty sticker sheet is wrong regardless.
  assert.match(
    validateSpec(spec, { annotatedInventory: false, liveBundle: true }).errors[0],
    /defers every entry/,
  );
  // A component whose only variants are deferred inherits the deferral, so it is all-deferred too
  // (the case #2991 widened). Live path present, so the no-live-path gate stays quiet.
  const inherited = prioritySpec([
    {
      componentId: "A",
      preview: "Alpha",
      priority: "deferred",
      variants: [{ preview: "AlphaOff", state: "off" }],
    },
  ]);
  assert.match(
    validateSpec(inherited, { annotatedInventory: false, liveBundle: true }).errors[0],
    /defers every entry/,
  );
  // One required entry left is enough — no all-deferred error.
  const mixed = prioritySpec([
    { componentId: "A", preview: "Alpha" },
    { componentId: "B", preview: "Beta", priority: "deferred" },
  ]);
  assert.ok(
    !validateSpec(mixed, { annotatedInventory: false, liveBundle: true }).errors.some((e) =>
      /defers every entry/.test(e),
    ),
  );
});

test("validateSpec does not flag an all-deferred spec that may have annotation-supplied required entries (#2993)", () => {
  // A hybrid catalog can leave its required entries in @CatalogComponent annotations and place only
  // deferred overrides in `spec.groups`; the driver merges those in as `required`. So when the caller
  // can't rule that out — `annotatedInventory` is `true` or unknown — the all-deferred check must stay
  // quiet, or it blocks a supported config before rendering.
  const spec = prioritySpec(
    [{ componentId: "A", preview: "Alpha", priority: "deferred" }],
    // A live path so the no-live-path gate doesn't fire and mask what we're asserting.
  );
  assert.ok(
    !validateSpec(spec, { annotatedInventory: true, liveBundle: true }).errors.some((e) =>
      /defers every entry/.test(e),
    ),
    "annotation inventory present → not all-deferred",
  );
  assert.ok(
    !validateSpec(spec, { liveBundle: true }).errors.some((e) => /defers every entry/.test(e)),
    "annotation state unknown → stay lenient",
  );
});

test("validateSpec reports the groups-shape error, not a TypeError, on malformed groups (#2993)", () => {
  // The all-deferred predicate walks `groups`; a non-array `groups` (or a group whose `components`
  // isn't an array) must not throw before the structural shape check reports it.
  assert.doesNotThrow(() => validateSpec({ system: "s", title: "t", groups: {} }, { annotatedInventory: false }));
  const { errors } = validateSpec({ system: "s", title: "t", groups: {} }, { annotatedInventory: false });
  assert.ok(errors.some((e) => /`groups`, when present, must be a non-empty array/.test(e)));
  assert.ok(!errors.some((e) => /defers every entry/.test(e)));
});

test("validateSpec still resolves a deferred entry's preview name against the module", () => {
  // A deferred entry is rendered by the serve host from the same module, so a name that matches
  // nothing is just as broken — it simply breaks on a viewer's request instead of in CI.
  const { errors } = validateSpec(
    prioritySpec([{ componentId: "A", preview: "Ghost", priority: "deferred" }]),
    { knownPreviews: ["Alpha"] },
  );
  assert.equal(errors.length, 1);
  assert.match(errors[0], /preview "Ghost" .* matches no @Preview function/);
});

test("validateSpec accepts modePriority on a cover-sheet spec with no groups", () => {
  const { errors } = validateSpec(
    { system: "demo", title: "Demo", modes: ["light", "dark"], modePriority: { dark: "deferred" } },
    { liveBundle: true },
  );
  assert.deepEqual(errors, []);
});

test("validateSpec accepts completeness.exemptSemantics, including on a cover-sheet spec", () => {
  assert.deepEqual(
    validateSpec(
      prioritySpec([{ componentId: "A", preview: "Alpha" }], {
        completeness: { exemptSemantics: ["*Activity", "app/getting-started"] },
      }),
    ).errors,
    [],
  );
  // The repository-wide shape this exists for: inventory from annotations, cover sheet only here.
  assert.deepEqual(
    validateSpec({
      system: "demo",
      title: "Demo",
      completeness: { exemptSemantics: ["*Activity"] },
    }).errors,
    [],
  );
});

test("validateSpec rejects a malformed completeness block", () => {
  // A consumer ignores what it can't read, so a typo would surface as the gate failing on the very
  // entry the exemption was written to excuse.
  assert.match(
    validateSpec(prioritySpec([], { completeness: ["*Activity"] })).errors[0],
    /`completeness` must be an object/,
  );
  assert.match(
    validateSpec(prioritySpec([], { completeness: { exemptSemantics: "*Activity" } })).errors[0],
    /`completeness.exemptSemantics` must be an array/,
  );
  assert.match(
    validateSpec(prioritySpec([], { completeness: { exemptSemantics: ["", 3] } })).errors[0],
    /completeness\.exemptSemantics\[0\] must be a non-empty componentId pattern/,
  );
  assert.match(
    validateSpec(prioritySpec([], { completeness: { exemptMissing: ["*"] } })).errors[0],
    /completeness\.exemptMissing is not a known field/,
  );
});

test("validateSpec accepts a $comment beside the exemptions", () => {
  // The reason an entry is exempt belongs next to the list; JSON has nowhere else to put it.
  assert.deepEqual(
    validateSpec(
      prioritySpec([{ componentId: "A", preview: "Alpha" }], {
        completeness: {
          $comment: "Synthetic Activity renders: cold start, no data, near-empty frame.",
          exemptSemantics: ["*Activity"],
        },
      }),
    ).errors,
    [],
  );
});

// --- select: one breakpoint of a multipreview, without splitting the function ---

const wearSpecWith = (components) => ({
  system: "confetti-wear",
  title: "Confetti Wear",
  library: ["androidx.wear.compose:compose-material3"],
  groups: [{ name: "Screens", components }],
});

test("validateSpec accepts two entries splitting one multipreview by select", () => {
  const { errors, warnings } = validateSpec(
    wearSpecWith([
      { componentId: "Home/Small", preview: "HomeListViewPreview", select: { size: "smallRound" } },
      { componentId: "Home/Large", preview: "HomeListViewPreview", select: { size: "largeRound" } },
    ]),
  );
  assert.deepEqual(errors, []);
  assert.ok(
    !warnings.some((w) => w.includes("fold into one sticker")),
    "distinct selections are the supported split, not a copy-paste bug",
  );
});

test("validateSpec still warns when one of the references selects nothing", () => {
  const { warnings } = validateSpec(
    wearSpecWith([
      { componentId: "Home", preview: "HomeListViewPreview" },
      { componentId: "Home/Large", preview: "HomeListViewPreview", select: { size: "largeRound" } },
    ]),
  );
  // The unselected entry folds in every render, including the one its sibling selected — so the
  // two really do double up and the warning still earns its place.
  assert.ok(warnings.some((w) => w.includes("fold into one sticker")));
});

test("validateSpec rejects a select.size that names no declared breakpoint", () => {
  const { errors } = validateSpec(
    wearSpecWith([
      { componentId: "Home", preview: "HomeListViewPreview", select: { size: "largeRund" } },
    ]),
  );
  assert.ok(
    errors.some((e) => e.includes('select.size "largeRund"') && e.includes('did you mean "largeRound"')),
    `expected a suggestion, got ${JSON.stringify(errors)}`,
  );
});

test("validateSpec rejects an unknown select axis and a malformed select", () => {
  const { errors } = validateSpec(
    wearSpecWith([
      { componentId: "A", preview: "P", select: { theme: "dark" } },
      { componentId: "B", preview: "Q", select: "largeRound" },
      { componentId: "C", preview: "R", select: {} },
    ]),
  );
  assert.ok(errors.some((e) => e.includes("select.theme is not a selectable axis")));
  assert.ok(errors.some((e) => e.includes("select must be an object")));
  assert.ok(errors.some((e) => e.includes("select is empty")));
});

test("validateSpec accepts a select as a variant's only distinguishing axis", () => {
  const { errors } = validateSpec(
    wearSpecWith([
      {
        componentId: "Home",
        preview: "HomeListViewPreview",
        variants: [{ preview: "HomeListViewLoading", select: { size: "largeRound" } }],
      },
    ]),
  );
  assert.deepEqual(errors, []);
});

test("validateSpec checks select.size against an explicit breakpoint table", () => {
  const spec = {
    system: "s",
    title: "T",
    breakpoints: [{ size: "compact", widthDp: 412 }],
    groups: [
      { name: "G", components: [{ componentId: "A", preview: "P", select: { size: "expanded" } }] },
    ],
  };
  assert.ok(validateSpec(spec).errors.some((e) => e.includes('select.size "expanded"')));
});

test("validateSpec leaves select.size unchecked for a catalog with no size axis", () => {
  const spec = {
    system: "s",
    title: "T",
    groups: [
      { name: "G", components: [{ componentId: "A", preview: "P", select: { size: "whatever" } }] },
    ],
  };
  assert.deepEqual(validateSpec(spec).errors, []);
});

test("discoverComponentIds reads the annotated id whether or not the component fans out", () => {
  const source = `
    @CatalogComponent(
      id = "Layout/List",
      group = "Layout",
      perBreakpoint = true,
    )
    @Composable fun ListLayout() {}
    @CatalogComponent(id = "Button/Filled")
    @Composable fun FilledButton() {}
  `;
  // WHICH breakpoints a `perBreakpoint` component fans out to comes from its renders, which this
  // build-free source scan can't see — so it reports the annotated id and the hero check below
  // resolves a `<id>/<breakpoint>` hero on its parent.
  assert.deepEqual(discoverComponentIds([source]), ["Button/Filled", "Layout/List"]);
});

test("validateSpec resolves a per-breakpoint hero on its parent id", () => {
  const { errors } = validateSpec(
    { system: "wear-m3", title: "Wear M3", display: { hero: "Layout/List/largeRound" } },
    {
      knownPreviews: ["ListLayout"],
      knownComponentIds: discoverComponentIds([
        `@CatalogComponent(id = "Layout/List", perBreakpoint = true)
         @Composable fun ListLayout() {}`,
      ]),
      annotatedInventory: true,
    },
  );
  assert.deepEqual(errors, []);
});

test("validateSpec still rejects a hero that matches nothing at all", () => {
  const { errors } = validateSpec(
    { system: "wear-m3", title: "Wear M3", display: { hero: "Nope/Missing" } },
    {
      knownPreviews: ["ListLayout"],
      knownComponentIds: discoverComponentIds([
        `@CatalogComponent(id = "Layout/List", perBreakpoint = true)
         @Composable fun ListLayout() {}`,
      ]),
      annotatedInventory: true,
    },
  );
  assert.ok(errors.some((e) => e.includes('display.hero "Nope/Missing" matches no componentId')));
});
