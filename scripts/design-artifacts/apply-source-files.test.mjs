import { test } from "node:test";
import assert from "node:assert/strict";

import { applySourceFiles } from "./apply-source-files.mjs";

const comp = (componentId, extra = {}) => ({
  componentId,
  images: [],
  ...extra,
});
const manifest = (components) => ({
  schema: "design-parity-catalog/v1",
  system: "s",
  components,
});
const byFn = (entries) =>
  new Map(
    entries.map(([fn, sf, bodyLine]) => [fn, { sourceFile: sf, bodyLine }]),
  );

test("stamps each component's sourceFile from its spec function's discovery path", () => {
  const spec = {
    groups: [
      {
        name: "Buttons",
        components: [
          { componentId: "Buttons/Filled", preview: "FilledButtonPreview" },
        ],
      },
      {
        name: "Cards",
        components: [
          { componentId: "Cards/Elevated", preview: "ElevatedCardPreview" },
        ],
      },
    ],
  };
  const m = manifest([comp("Buttons/Filled"), comp("Cards/Elevated")]);
  const sources = byFn([
    ["FilledButtonPreview", "src/main/kotlin/com/example/Buttons.kt"],
    ["ElevatedCardPreview", "src/main/kotlin/com/example/Cards.kt"],
  ]);

  const stamped = applySourceFiles(m, spec, sources);

  assert.equal(stamped, 2);
  assert.equal(
    m.components[0].sourceFile,
    "src/main/kotlin/com/example/Buttons.kt",
  );
  assert.equal(
    m.components[1].sourceFile,
    "src/main/kotlin/com/example/Cards.kt",
  );
});

test("leaves a component untouched when its function carried no recorded path", () => {
  const spec = {
    groups: [
      {
        name: "Buttons",
        components: [
          { componentId: "Buttons/Filled", preview: "FilledButtonPreview" },
        ],
      },
    ],
  };
  const m = manifest([comp("Buttons/Filled")]);
  // Discovery recorded the function but with no sourceFile (undefined).
  const sources = new Map([["FilledButtonPreview", { sourceFile: undefined }]]);

  const stamped = applySourceFiles(m, spec, sources);

  assert.equal(stamped, 0);
  assert.equal("sourceFile" in m.components[0], false);
});

test("never clobbers a sourceFile already on the component", () => {
  const spec = {
    groups: [
      {
        name: "Buttons",
        components: [
          { componentId: "Buttons/Filled", preview: "FilledButtonPreview" },
        ],
      },
    ],
  };
  const m = manifest([
    comp("Buttons/Filled", { sourceFile: "already/There.kt" }),
  ]);
  const sources = byFn([
    ["FilledButtonPreview", "src/main/kotlin/com/example/Buttons.kt"],
  ]);

  const stamped = applySourceFiles(m, spec, sources);

  assert.equal(stamped, 0);
  assert.equal(m.components[0].sourceFile, "already/There.kt");
});

test("adds a matching module to a sourceFile already preserved by the exporter", () => {
  const spec = {
    groups: [
      {
        name: "TV",
        components: [{ componentId: "tv/Main", preview: "MainPreview" }],
      },
    ],
  };
  const m = manifest([
    comp("tv/Main", { sourceFile: "src/main/kotlin/Main.kt" }),
  ]);
  const sources = new Map([
    ["MainPreview", { sourceFile: "src/main/kotlin/Main.kt", module: ":tv" }],
  ]);

  assert.equal(applySourceFiles(m, spec, sources), 0);
  assert.equal(m.components[0].sourceModule, ":tv");
});

test("leaves manifest components absent from the spec untouched", () => {
  const spec = {
    groups: [
      {
        name: "Buttons",
        components: [
          { componentId: "Buttons/Filled", preview: "FilledButtonPreview" },
        ],
      },
    ],
  };
  const m = manifest([comp("Buttons/Filled"), comp("Orphan/NotInSpec")]);
  const sources = byFn([
    ["FilledButtonPreview", "src/main/kotlin/com/example/Buttons.kt"],
  ]);

  applySourceFiles(m, spec, sources);

  assert.equal(
    m.components[0].sourceFile,
    "src/main/kotlin/com/example/Buttons.kt",
  );
  assert.equal("sourceFile" in m.components[1], false);
});

test("is idempotent — a second pass stamps nothing", () => {
  const spec = {
    groups: [
      {
        name: "Buttons",
        components: [
          { componentId: "Buttons/Filled", preview: "FilledButtonPreview" },
        ],
      },
    ],
  };
  const m = manifest([comp("Buttons/Filled")]);
  const sources = byFn([
    ["FilledButtonPreview", "src/main/kotlin/com/example/Buttons.kt"],
  ]);

  assert.equal(applySourceFiles(m, spec, sources), 1);
  assert.equal(applySourceFiles(m, spec, sources), 0);
  assert.equal(
    m.components[0].sourceFile,
    "src/main/kotlin/com/example/Buttons.kt",
  );
});

test("is a no-op with an empty / missing lookup and tolerates empty inputs", () => {
  const spec = {
    groups: [
      {
        name: "Buttons",
        components: [
          { componentId: "Buttons/Filled", preview: "FilledButtonPreview" },
        ],
      },
    ],
  };
  assert.equal(
    applySourceFiles(manifest([comp("Buttons/Filled")]), spec, new Map()),
    0,
  );
  assert.equal(
    applySourceFiles(manifest([comp("Buttons/Filled")]), spec, undefined),
    0,
  );
  assert.equal(
    applySourceFiles({ components: [] }, {}, byFn([["X", "a.kt"]])),
    0,
  );
  assert.equal(applySourceFiles({}, { groups: [] }, byFn([["X", "a.kt"]])), 0);
});

test("stamps bodyLine alongside sourceFile so the playground can open one declaration", () => {
  const spec = {
    groups: [
      {
        name: "Buttons",
        components: [
          { componentId: "Buttons/Filled", preview: "FilledButtonPreview" },
        ],
      },
    ],
  };
  const m = manifest([comp("Buttons/Filled")]);

  applySourceFiles(
    m,
    spec,
    byFn([["FilledButtonPreview", "src/main/kotlin/Buttons.kt", 81]]),
  );

  assert.equal(m.components[0].sourceFile, "src/main/kotlin/Buttons.kt");
  assert.equal(m.components[0].bodyLine, 81);
});

test("stamps the owning Gradle module alongside a repository-wide source path", () => {
  const spec = {
    groups: [
      {
        name: "TV",
        components: [{ componentId: "tv/Main", preview: "MainPreview" }],
      },
    ],
  };
  const m = manifest([comp("tv/Main")]);
  const sources = new Map([
    [
      "MainPreview",
      { sourceFile: "src/main/kotlin/Main.kt", bodyLine: 9, module: ":tv" },
    ],
  ]);

  applySourceFiles(m, spec, sources);

  assert.equal(m.components[0].sourceModule, ":tv");
  assert.equal(m.components[0].sourceFile, "src/main/kotlin/Main.kt");
});

test("omits bodyLine when discovery recorded none, rather than writing a bogus zero", () => {
  // An older bundle carries no `bodyLine`; the server reads its absence as "seed the whole file",
  // so a 0 or null here would be a line number that points at nothing.
  const spec = {
    groups: [
      {
        name: "Buttons",
        components: [
          { componentId: "Buttons/Filled", preview: "FilledButtonPreview" },
        ],
      },
    ],
  };
  const m = manifest([comp("Buttons/Filled")]);

  applySourceFiles(
    m,
    spec,
    byFn([["FilledButtonPreview", "src/main/kotlin/Buttons.kt", undefined]]),
  );

  assert.equal(m.components[0].sourceFile, "src/main/kotlin/Buttons.kt");
  assert.equal("bodyLine" in m.components[0], false);
});

test("never stamps a bodyLine without a sourceFile to resolve it against", () => {
  const spec = {
    groups: [
      {
        name: "Buttons",
        components: [
          { componentId: "Buttons/Filled", preview: "FilledButtonPreview" },
        ],
      },
    ],
  };
  const m = manifest([comp("Buttons/Filled")]);

  applySourceFiles(m, spec, byFn([["FilledButtonPreview", undefined, 81]]));

  assert.equal(m.components[0].sourceFile, undefined);
  assert.equal("bodyLine" in m.components[0], false);
});

test("stamps the root project's empty directory rather than treating it as absent", () => {
  // `""` is the ROOT project — already repository-relative — while `undefined` is a bundle packed
  // before the field existed. Requiring a non-empty value stamped nothing for the root case.
  const manifest = { components: [{ componentId: "A", images: [] }] };
  const spec = {
    groups: [{ components: [{ componentId: "A", preview: "Sticker" }] }],
  };
  applySourceFiles(
    manifest,
    spec,
    new Map([
      [
        "Sticker",
        {
          sourceFile: "app/Preview.kt",
          module: ":",
          directory: "",
          functionName: "Sticker",
        },
      ],
    ]),
  );
  assert.equal(manifest.components[0].sourceDirectory, "");
  assert.equal(manifest.components[0].sourceFunction, "Sticker");
});

test("stamps nothing for a bundle that never recorded a directory", () => {
  const manifest = { components: [{ componentId: "A", images: [] }] };
  const spec = {
    groups: [{ components: [{ componentId: "A", preview: "Sticker" }] }],
  };
  applySourceFiles(
    manifest,
    spec,
    new Map([["Sticker", { sourceFile: "app/Preview.kt", module: ":app" }]]),
  );
  assert.equal("sourceDirectory" in manifest.components[0], false);
  assert.equal("sourceFunction" in manifest.components[0], false);
});

test("a spec-declared sourceFile overrides the discovery join", () => {
  // The samples-catalog shape: the `@Preview` really does live in the generated wrapper, and the
  // reader wants the sample it delegates to. Discovery is not wrong here — it is answering a
  // different question — so this is precedence, not a fallback for a gap.
  const spec = {
    groups: [
      {
        name: "Button",
        components: [
          {
            componentId: "Button/ButtonSample",
            preview: "ButtonSamplePreview",
            sourceFile: "src/main/kotlin/upstream/androidx/.../ButtonSample.kt",
            bodyLine: 77,
          },
        ],
      },
    ],
  };
  const m = manifest([comp("Button/ButtonSample")]);
  const sources = byFn([
    ["ButtonSamplePreview", "src/main/kotlin/generated/SamplePreviews.kt", 62],
  ]);

  assert.equal(applySourceFiles(m, spec, sources), 1);
  assert.equal(
    m.components[0].sourceFile,
    "src/main/kotlin/upstream/androidx/.../ButtonSample.kt",
  );
  assert.equal(m.components[0].bodyLine, 77);
});

test("a declared path beats one the exporter already preserved", () => {
  // The branch below this one exists to never clobber an exporter's own value. An override has to
  // outrank that too, or the fix would silently stop working the day the pinned exporter starts
  // preserving `sourceFile` — the one change that is otherwise pure improvement.
  const spec = {
    groups: [
      {
        components: [
          {
            componentId: "Button/ButtonSample",
            preview: "ButtonSamplePreview",
            sourceFile: "src/main/kotlin/upstream/ButtonSample.kt",
            bodyLine: 77,
          },
        ],
      },
    ],
  };
  const m = manifest([
    comp("Button/ButtonSample", {
      sourceFile: "src/main/kotlin/generated/SamplePreviews.kt",
      bodyLine: 62,
    }),
  ]);

  applySourceFiles(m, spec, byFn([["ButtonSamplePreview", "x.kt", 1]]));
  assert.equal(m.components[0].sourceFile, "src/main/kotlin/upstream/ButtonSample.kt");
  assert.equal(m.components[0].bodyLine, 77);
});

test("a declaration with no line clears a line belonging to the other file", () => {
  // A `bodyLine` from the generated wrapper would slice the panel at an arbitrary point of the
  // sample's file — a wrong picture rather than a missing one.
  const spec = {
    groups: [
      {
        components: [
          {
            componentId: "Button/ButtonSample",
            preview: "ButtonSamplePreview",
            sourceFile: "src/main/kotlin/upstream/ButtonSample.kt",
          },
        ],
      },
    ],
  };
  const m = manifest([comp("Button/ButtonSample")]);

  applySourceFiles(
    m,
    spec,
    byFn([["ButtonSamplePreview", "src/main/kotlin/generated/SamplePreviews.kt", 62]]),
  );
  assert.equal(m.components[0].sourceFile, "src/main/kotlin/upstream/ButtonSample.kt");
  assert.equal("bodyLine" in m.components[0], false);
});

test("an empty declared path is not a declaration", () => {
  // `""` is a spec someone half-filled, not an instruction to blank the link. It falls through to
  // the ordinary join rather than overriding it with nothing.
  const spec = {
    groups: [
      {
        components: [
          { componentId: "C", preview: "P", sourceFile: "" },
        ],
      },
    ],
  };
  const m = manifest([comp("C")]);
  applySourceFiles(m, spec, byFn([["P", "src/main/kotlin/Real.kt", 9]]));
  assert.equal(m.components[0].sourceFile, "src/main/kotlin/Real.kt");
  assert.equal(m.components[0].bodyLine, 9);
});

test("an override is idempotent — a second pass stamps nothing new", () => {
  const spec = {
    groups: [
      {
        components: [
          {
            componentId: "C",
            preview: "P",
            sourceFile: "src/main/kotlin/upstream/ButtonSample.kt",
            bodyLine: 77,
          },
        ],
      },
    ],
  };
  const m = manifest([comp("C")]);
  const sources = byFn([["P", "src/main/kotlin/generated/SamplePreviews.kt", 62]]);
  assert.equal(applySourceFiles(m, spec, sources), 1);
  assert.equal(applySourceFiles(m, spec, sources), 0);
  assert.equal(m.components[0].bodyLine, 77);
});
