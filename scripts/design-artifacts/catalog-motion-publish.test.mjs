import assert from "node:assert/strict";
import { test } from "node:test";

import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  motionPublishPath,
  motionSiblingPreviewId,
  planMotionPublish,
  publishMotionArtifacts,
} from "./catalog-motion-publish.mjs";

test("the sibling still is the artifact's own leaf", () => {
  assert.equal(motionSiblingPreviewId("previews/Switch_Dark.apng"), "Switch_Dark");
  assert.equal(motionSiblingPreviewId("previews/Spinner_Light.gif"), "Spinner_Light");
});

test("a disambiguated capture is stripped back to reach the still", () => {
  // A motion capture that had to share its filename space renders under a suffixed name, but the
  // sticker it sits beside is still the unsuffixed preview. `_interaction` is what discovery
  // appends to an interaction sharing with a GIF-producing peer; `_anim` is what it appends to an
  // animation sharing with a scroll / timing fan-out or a `@FocusedPreview(gif = true)`.
  assert.equal(motionSiblingPreviewId("previews/Spinner_Dark_interaction.apng"), "Spinner_Dark");
  assert.equal(motionSiblingPreviewId("previews/Spinner_Dark_anim.gif"), "Spinner_Dark");
});

test("a still is not a motion artifact", () => {
  assert.equal(motionSiblingPreviewId("previews/Switch_Dark.png"), null);
  assert.equal(motionSiblingPreviewId(undefined), null);
});

test("the published path is the sibling sticker's, under motion/", () => {
  assert.equal(
    motionPublishPath(
      "Switch/On",
      "previews/Switch_Dark.apng",
      "images/switch-on/ideal__default__dark.png",
    ),
    "motion/switch-on/ideal__default__dark.apng",
  );
});

test("the sticker's full variant name is inherited, not re-derived", () => {
  // Props, size and locale segments ride along untouched — the whole point of deriving from the
  // still rather than restating `buildCatalog`'s naming scheme.
  assert.equal(
    motionPublishPath(
      "Button/Filled",
      "previews/Button_Dark.gif",
      "images/button-filled/ideal__default__dark__compact__content-icon-label.png",
    ),
    "motion/button-filled/ideal__default__dark__compact__content-icon-label.gif",
  );
});

test("two captures on one function keep separate names", () => {
  // Discovery suffixes BOTH sides when a function's captures share a filename space, and the
  // published names have to stay as distinct as the bundle's — otherwise one silently overwrites
  // the other on the branch.
  const still = "images/spinner/ideal__default__dark.png";
  assert.equal(
    motionPublishPath("Spinner", "previews/Spinner_Dark.gif", still),
    "motion/spinner/ideal__default__dark.gif",
  );
  assert.equal(
    motionPublishPath("Spinner", "previews/Spinner_Dark_interaction.apng", still),
    "motion/spinner/ideal__default__dark__interaction.apng",
  );
  assert.equal(
    motionPublishPath("Spinner", "previews/Spinner_Dark_anim.gif", still),
    "motion/spinner/ideal__default__dark__anim.gif",
  );
});

test("an _anim animation joins its sticker rather than falling back to the bundle leaf", () => {
  // The regression the fallback would have hidden: an `@AnimatedPreview` sharing its function with
  // a scroll / timing fan-out publishes as `<previewId>_anim.gif`, and a helper that stripped only
  // `_interaction` would miss the join and quietly name it from the bundle instead of the sticker.
  const { moves, unresolved } = planMotionPublish({
    components: [
      {
        componentId: "Spinner",
        images: [{ previewId: "Spinner_Dark", path: "images/spinner/ideal__default__dark.png" }],
        motion: [{ path: "previews/Spinner_Dark_anim.gif", kind: "animation", theme: "dark" }],
      },
    ],
  });
  assert.equal(unresolved, 0);
  assert.equal(moves[0].viaSibling, true);
  assert.equal(moves[0].target, "motion/spinner/ideal__default__dark__anim.gif");
});

test("an artifact with no sibling still publishes under its own leaf", () => {
  assert.equal(
    motionPublishPath("Switch/On", "previews/Switch_Dark.apng", undefined),
    "motion/switch-on/Switch_Dark.apng",
  );
});

test("planMotionPublish joins each artifact to its still through previewId", () => {
  const manifest = {
    components: [
      {
        componentId: "Switch/On",
        images: [
          { previewId: "Switch_Light", path: "images/switch-on/ideal__default__light.png" },
          { previewId: "Switch_Dark", path: "images/switch-on/ideal__default__dark.png" },
        ],
        motion: [{ path: "previews/Switch_Dark.apng", kind: "interaction", theme: "dark" }],
      },
    ],
  };
  const { moves, unresolved } = planMotionPublish(manifest);
  assert.equal(unresolved, 0);
  assert.deepEqual(
    moves.map(({ componentId, source, target, viaSibling }) => ({
      componentId,
      source,
      target,
      viaSibling,
    })),
    [
      {
        componentId: "Switch/On",
        source: "previews/Switch_Dark.apng",
        target: "motion/switch-on/ideal__default__dark.apng",
        viaSibling: true,
      },
    ],
  );
  // The entry itself is handed back so the caller can rewrite it in place.
  assert.equal(moves[0].entry, manifest.components[0].motion[0]);
});

test("an unbridged image leaves the artifact unresolved but still published", () => {
  const { moves, unresolved } = planMotionPublish({
    components: [
      {
        componentId: "Switch/On",
        images: [{ path: "images/switch-on/ideal__default__dark.png" }],
        motion: [{ path: "previews/Switch_Dark.apng", kind: "interaction" }],
      },
    ],
  });
  assert.equal(unresolved, 1);
  assert.equal(moves[0].target, "motion/switch-on/Switch_Dark.apng");
  assert.equal(moves[0].viaSibling, false);
});

test("a component with no motion contributes nothing", () => {
  assert.deepEqual(
    planMotionPublish({
      components: [{ componentId: "Card", images: [{ path: "images/card/ideal__default.png" }] }],
    }),
    { moves: [], unresolved: 0 },
  );
  assert.deepEqual(planMotionPublish(undefined), { moves: [], unresolved: 0 });
});

test("re-planning an already-published manifest is a no-op", () => {
  // The pass rewrites `path` in place, so a second run over the same catalog.json must not
  // re-prefix it into `motion/motion/…`.
  const manifest = {
    components: [
      {
        componentId: "Switch/On",
        images: [{ previewId: "Switch_Dark", path: "images/switch-on/ideal__default__dark.png" }],
        motion: [{ path: "motion/switch-on/ideal__default__dark.apng", kind: "interaction" }],
      },
    ],
  };
  assert.deepEqual(planMotionPublish(manifest), { moves: [], unresolved: 0 });
});

/** Runs [body] against a throwaway output directory. */
async function withOutDir(body) {
  const out = await mkdtemp(join(tmpdir(), "motion-publish-"));
  try {
    await body(out);
  } finally {
    await rm(out, { recursive: true, force: true });
  }
}

const manifestWithCapture = () => ({
  components: [
    {
      componentId: "Switch/On",
      images: [{ previewId: "Switch_Dark", path: "images/switch-on/ideal__default__dark.png" }],
      motion: [{ path: "previews/Switch_Dark.apng", kind: "interaction", theme: "dark" }],
    },
  ],
});

test("publishing writes the bytes to the branch and repoints the declaration", async () => {
  await withOutDir(async (out) => {
    const manifest = manifestWithCapture();
    const bytes = new Uint8Array([0x89, 0x50, 0x4e, 0x47]);
    const result = await publishMotionArtifacts(
      manifest,
      { "previews/Switch_Dark.apng": bytes },
      out,
    );

    assert.deepEqual(result, { published: 1, unresolved: 0, missing: [] });
    assert.equal(
      manifest.components[0].motion[0].path,
      "motion/switch-on/ideal__default__dark.apng",
    );
    assert.deepEqual(
      new Uint8Array(await readFile(join(out, "motion/switch-on/ideal__default__dark.apng"))),
      bytes,
    );
    // The caption and kind ride through untouched — only `path` is rewritten.
    assert.equal(manifest.components[0].motion[0].kind, "interaction");
  });
});

test("a declaration the bundle has no bytes for is dropped, not published as a 404", async () => {
  await withOutDir(async (out) => {
    const manifest = manifestWithCapture();
    const result = await publishMotionArtifacts(manifest, {}, out);

    assert.deepEqual(result, {
      published: 0,
      unresolved: 0,
      missing: ["previews/Switch_Dark.apng"],
    });
    // The whole axis went with it rather than being left as an empty array.
    assert.equal("motion" in manifest.components[0], false);
  });
});

test("publishing twice is idempotent", async () => {
  await withOutDir(async (out) => {
    const manifest = manifestWithCapture();
    const entries = { "previews/Switch_Dark.apng": new Uint8Array([1, 2, 3]) };
    await publishMotionArtifacts(manifest, entries, out);
    const again = await publishMotionArtifacts(manifest, entries, out);

    assert.deepEqual(again, { published: 0, unresolved: 0, missing: [] });
    assert.equal(
      manifest.components[0].motion[0].path,
      "motion/switch-on/ideal__default__dark.apng",
    );
  });
});

test("the join holds for the fully-qualified ids a real catalog carries", () => {
  // `bridgeLivePreviewIds` stamps the *daemon* preview id, which is the bundle's own
  // `preview.id` — fully qualified, dots and all. The bundle names its entries from the same id,
  // so the leaf-minus-extension join still lands; this is the shape published catalogs actually
  // carry (compose-m3's Switch/On), not the bare ids the unit cases above use for legibility.
  const id = "com.example.designcatalogm3.CatalogSelectionKt.SwitchOn_Dark";
  assert.equal(motionSiblingPreviewId(`previews/${id}.apng`), id);
  assert.equal(motionSiblingPreviewId(`previews/${id}_interaction.apng`), id);
  assert.equal(motionSiblingPreviewId(`previews/${id}_anim.gif`), id);

  const manifest = {
    components: [
      {
        componentId: "Switch/On",
        images: [
          { previewId: id, path: "images/switch-on/ideal__default__dark.png" },
          {
            previewId: `${id}_VARIANT_off`,
            path: "images/switch-on/ideal__off__dark.png",
          },
        ],
        motion: [{ path: `previews/${id}.apng`, kind: "interaction", theme: "dark" }],
      },
    ],
  };
  const { moves, unresolved } = planMotionPublish(manifest);
  assert.equal(unresolved, 0);
  assert.equal(moves[0].target, "motion/switch-on/ideal__default__dark.apng");
});
