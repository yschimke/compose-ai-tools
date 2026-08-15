import { test } from "node:test";
import assert from "node:assert/strict";

import {
  CAPTURE_MODES,
  captureMode,
  exportsNoSticker,
  noStickerPreviewNames,
} from "./capture-mode.mjs";

test("absent capture reads as static — the strict default", () => {
  assert.equal(captureMode({ componentId: "Button/Filled", preview: "FilledButton" }), "static");
  assert.equal(exportsNoSticker({ preview: "FilledButton" }), false);
  assert.equal(exportsNoSticker(undefined), false);
});

test("an explicit capture is read back", () => {
  assert.equal(captureMode({ capture: "none" }), "none");
  assert.equal(exportsNoSticker({ capture: "none" }), true);
  assert.equal(exportsNoSticker({ capture: "static" }), false);
});

test("only the declared modes exist — a typo is not an exemption", () => {
  assert.deepEqual(CAPTURE_MODES, ["static", "none"]);
  // The spec validator rejects these outright (see catalog-spec.test.mjs); the
  // consumers must not treat a near-miss as an exemption in the meantime.
  assert.equal(exportsNoSticker({ capture: "animated" }), false);
  assert.equal(exportsNoSticker({ capture: "gif" }), false);
  assert.equal(exportsNoSticker({ capture: "None" }), false);
});

test("noStickerPreviewNames collects capture:none entries from components and variants", () => {
  const spec = {
    groups: [
      {
        components: [
          { componentId: "Buttons/Filled", preview: "FilledButtonPreview" },
          {
            componentId: "Views/Hosted",
            preview: "HostedViewPreview",
            capture: "none",
            variants: [
              { preview: "HostedViewDisabledPreview", capture: "none" },
              { preview: "HostedViewPressedPreview" },
            ],
          },
        ],
      },
    ],
  };
  assert.deepEqual(noStickerPreviewNames(spec), [
    "HostedViewDisabledPreview",
    "HostedViewPreview",
  ]);
});

test("noStickerPreviewNames is empty for an annotation-first spec with no groups", () => {
  assert.deepEqual(noStickerPreviewNames({}), []);
  assert.deepEqual(noStickerPreviewNames(undefined), []);
  assert.deepEqual(noStickerPreviewNames({ groups: [{ components: [{ preview: "A" }] }] }), []);
});

test("noStickerPreviewNames refuses a function whose entries disagree", () => {
  // `select` picks one value of a multipreview's fan-out, so two entries can share one `preview`
  // and mean different renders. Exempting the function would blind the shard check to a real loss
  // of the required breakpoint, so a mixed declaration exempts nothing.
  const spec = {
    groups: [
      {
        components: [
          {
            componentId: "Cards/Large",
            preview: "CardPreview",
            select: { size: "largeRound" },
            capture: "none",
          },
          { componentId: "Cards/Small", preview: "CardPreview", select: { size: "smallRound" } },
          { componentId: "Views/Hosted", preview: "HostedViewPreview", capture: "none" },
        ],
      },
    ],
  };
  assert.deepEqual(noStickerPreviewNames(spec), ["HostedViewPreview"]);
});

test("noStickerPreviewNames keeps a function every entry declares sticker-less", () => {
  const spec = {
    groups: [
      {
        components: [
          {
            componentId: "Views/Hosted",
            preview: "HostedViewPreview",
            capture: "none",
            variants: [{ preview: "HostedViewPreview", capture: "none" }],
          },
        ],
      },
    ],
  };
  assert.deepEqual(noStickerPreviewNames(spec), ["HostedViewPreview"]);
});
