import { test } from "node:test";
import assert from "node:assert/strict";

import {
  ACTIVITY_SCHEMA,
  GAP_KINDS,
  GIT_LOG_FORMAT,
  buildActivity,
  codeEventsFrom,
  figmaCommentEventsFrom,
  figmaVersionEventsFrom,
  indexDesignMap,
  mappingGaps,
  parseGitLog,
} from "./parity-activity.mjs";

/** A design map in the shape m3-catalog publishes: repo-relative code handles + figma refs. */
const designMap = {
  components: [
    {
      code: "catalog/src/main/kotlin/sections/Buttons.kt#FilledButton",
      source: "figma",
      ref: "figma:ocdac/57994:2227",
      previewId: "sections.ButtonsKt.FilledButton_Light",
    },
    {
      code: "catalog/src/main/kotlin/sections/Switches.kt#SwitchOn",
      source: "figma",
      ref: "figma:ocdac/51592:4768",
      previewId: "sections.SwitchesKt.SwitchOn_Light",
    },
  ],
};

test("indexDesignMap joins by source path and by node id in both spellings", () => {
  const index = indexDesignMap(designMap);

  assert.deepEqual(index.byPath.get("catalog/src/main/kotlin/sections/Buttons.kt"), {
    previewIds: ["sections.ButtonsKt.FilledButton_Light"],
    components: ["FilledButton"],
  });
  // Figma's API returns `57994:2227`; its URLs (and some maps) write `57994-2227`. Both resolve.
  assert.deepEqual(index.byNode.get("57994:2227").previewIds, [
    "sections.ButtonsKt.FilledButton_Light",
  ]);
  assert.deepEqual(index.byNode.get("57994-2227").previewIds, [
    "sections.ButtonsKt.FilledButton_Light",
  ]);
});

test("indexDesignMap prefers a catalog component id over the code handle's member", () => {
  const index = indexDesignMap(designMap, (previewId) =>
    previewId.includes("FilledButton") ? "Button/Filled" : null,
  );

  assert.deepEqual(index.byPath.get("catalog/src/main/kotlin/sections/Buttons.kt").components, [
    "Button/Filled",
  ]);
});

test("indexDesignMap handles the per-axis array form of previewId and ref", () => {
  const index = indexDesignMap({
    components: [
      {
        code: "ui/Device.kt#DeviceBody",
        ref: [
          { ref: "figma:abc/10:2", theme: "light" },
          { ref: "figma:abc/10:9", theme: "dark" },
        ],
        previewId: [
          { previewId: "app.DeviceKt.Light", theme: "light" },
          { previewId: "app.DeviceKt.Dark", theme: "dark" },
        ],
      },
    ],
  });

  assert.deepEqual(index.byPath.get("ui/Device.kt").previewIds, [
    "app.DeviceKt.Light",
    "app.DeviceKt.Dark",
  ]);
  assert.deepEqual(index.byNode.get("10:9").previewIds, ["app.DeviceKt.Light", "app.DeviceKt.Dark"]);
});

test("codeEventsFrom keeps commits that touch mapped files and drops the rest", () => {
  const index = indexDesignMap(designMap);
  const commits = [
    {
      sha: "a".repeat(40),
      at: "2026-08-05T10:00:00+00:00",
      author: "yschimke",
      subject: "fix(button): padding",
      files: ["catalog/src/main/kotlin/sections/Buttons.kt"],
    },
    {
      sha: "b".repeat(40),
      at: "2026-08-04T10:00:00+00:00",
      subject: "chore: bump gradle",
      files: ["gradle/libs.versions.toml"],
    },
  ];

  const events = codeEventsFrom(commits, index);
  assert.equal(events.length, 1);
  assert.deepEqual(events[0].previewIds, ["sections.ButtonsKt.FilledButton_Light"]);
  assert.deepEqual(events[0].components, ["FilledButton"]);

  // …and a catalog with no design map still gets a feed when the driver asks for one.
  assert.equal(codeEventsFrom(commits, index, { keepUnmapped: true }).length, 2);
});

test("figmaCommentEventsFrom resolves a pinned node back to the previews it specifies", () => {
  const index = indexDesignMap(designMap);
  const events = figmaCommentEventsFrom(
    {
      comments: [
        {
          id: "9182",
          created_at: "2026-08-04T08:00:00Z",
          message: "  The track reads 2dp short.  ",
          user: { handle: "Dana", email: "dana@example.com" },
          client_meta: { node_id: "51592:4768" },
        },
      ],
    },
    index,
  );

  assert.equal(events.length, 1);
  assert.equal(events[0].message, "The track reads 2dp short.");
  assert.deepEqual(events[0].previewIds, ["sections.SwitchesKt.SwitchOn_Light"]);
  assert.equal(events[0].author, "Dana");
  // The feed lands on a public server: a display name is useful, an email address is not.
  assert.equal(JSON.stringify(events[0]).includes("dana@example.com"), false);
});

test("figmaCommentEventsFrom drops replies and blank messages, and marks resolution", () => {
  const events = figmaCommentEventsFrom(
    {
      comments: [
        { id: "1", created_at: "2026-08-04T08:00:00Z", message: "opener", resolved_at: "2026-08-05T08:00:00Z" },
        { id: "2", created_at: "2026-08-04T09:00:00Z", message: "a reply", parent_id: "1" },
        { id: "3", created_at: "2026-08-04T09:00:00Z", message: "   " },
        { id: "4", message: "undated" },
      ],
    },
    indexDesignMap(designMap),
  );

  assert.deepEqual(
    events.map((e) => e.id),
    ["1"],
  );
  assert.equal(events[0].resolved, true);
});

test("a comment pinned to nothing still becomes a row, with no inbound links", () => {
  const events = figmaCommentEventsFrom(
    { comments: [{ id: "1", created_at: "2026-08-04T08:00:00Z", message: "file-level note" }] },
    indexDesignMap(designMap),
  );

  assert.equal(events.length, 1);
  assert.deepEqual(events[0].previewIds, []);
  assert.equal("nodeId" in events[0], false);
});

test("figmaVersionEventsFrom keeps labelled and autosaved versions alike", () => {
  const events = figmaVersionEventsFrom({
    versions: [
      {
        id: "392",
        created_at: "2026-08-05T11:40:00Z",
        label: "Buttons pass",
        description: "spec update",
        user: { handle: "Dana" },
      },
      { id: "393", created_at: "2026-08-05T12:00:00Z" },
      { id: "394" },
    ],
  });

  assert.deepEqual(
    events.map((e) => e.id),
    ["392", "393"],
  );
  assert.equal(events[0].author, "Dana");
  assert.equal("label" in events[1], false);
});

test("mappingGaps reports a design-map entry the catalog no longer publishes", () => {
  const gaps = mappingGaps({
    designMap,
    catalogPreviewIds: ["sections.ButtonsKt.FilledButton_Light"],
  });

  assert.deepEqual(
    gaps.map((g) => g.kind),
    [GAP_KINDS.DANGLING_MAPPING],
  );
  assert.equal(gaps[0].previewId, "sections.SwitchesKt.SwitchOn_Light");
});

test("mappingGaps does NOT report unmapped previews — the server derives those live", () => {
  const gaps = mappingGaps({
    designMap: { components: [] },
    catalogPreviewIds: ["a", "b", "c"],
  });

  assert.deepEqual(gaps, [], "publishing coverage here would let a stale feed contradict the catalog");
});

test("mappingGaps reports a published Figma component nothing maps to", () => {
  const gaps = mappingGaps({
    designMap,
    catalogPreviewIds: [
      "sections.ButtonsKt.FilledButton_Light",
      "sections.SwitchesKt.SwitchOn_Light",
    ],
    figmaComponents: [
      { node_id: "57994:2227", name: "Button/Filled", fileKey: "ocdac" },
      { node_id: "51827:5859", name: "Bottom sheet", fileKey: "ocdac" },
    ],
  });

  assert.deepEqual(
    gaps.map((g) => [g.kind, g.component]),
    [[GAP_KINDS.UNMAPPED_DESIGN_NODE, "Bottom sheet"]],
  );
  assert.equal(gaps[0].ref, "figma:ocdac/51827:5859");
});

test("mappingGaps passes through references the raster step dropped", () => {
  const gaps = mappingGaps({
    designMap: { components: [] },
    droppedReferences: [
      { reason: "Figma render returned no image.", ref: "figma:ocdac/51159:5105" },
    ],
  });

  assert.deepEqual(
    gaps.map((g) => g.kind),
    [GAP_KINDS.UNRENDERED_REFERENCE],
  );
});

test("buildActivity emits the schema the server requires", () => {
  const activity = buildActivity({
    generatedAt: "2026-08-06T09:12:00Z",
    windowDays: 30,
    repo: "yschimke/m3-catalog",
    ref: "main",
    codeEvents: [{ sha: "a".repeat(40), subject: "x", at: "2026-08-05T10:00:00Z" }],
    figmaFileKey: "ocdac",
    figmaComments: [{ id: "1", at: "2026-08-04T08:00:00Z", message: "y" }],
  });

  assert.equal(activity.schema, ACTIVITY_SCHEMA);
  assert.equal(activity.code.repo, "yschimke/m3-catalog");
  assert.equal(activity.figma.fileKey, "ocdac");
});

test("buildActivity returns null when there is nothing to publish", () => {
  assert.equal(buildActivity({ generatedAt: "2026-08-06T09:12:00Z" }), null);
  assert.equal(buildActivity(), null);
});

test("buildActivity omits a lane that produced nothing rather than emitting it empty", () => {
  const activity = buildActivity({
    codeEvents: [{ sha: "a".repeat(40), subject: "x", at: "2026-08-05T10:00:00Z" }],
  });

  assert.equal("figma" in activity, false);
  assert.equal("gaps" in activity, false);
});

test("parseGitLog reads the record format GIT_LOG_FORMAT asks for", () => {
  // Exactly what `git log --pretty=GIT_LOG_FORMAT --name-only` prints, delimiters and all.
  assert.equal(GIT_LOG_FORMAT, "%x1e%H%x1f%aI%x1f%an%x1f%s");
  const stdout =
    "\x1eaaa\x1f2026-08-05T10:00:00+00:00\x1fyschimke\x1ffix: a thing\n" +
    "catalog/Buttons.kt\ncatalog/Switches.kt\n" +
    "\x1ebbb\x1f2026-08-04T10:00:00+00:00\x1fDana\x1fchore: nothing\n";

  const commits = parseGitLog(stdout);
  assert.equal(commits.length, 2);
  assert.deepEqual(commits[0], {
    sha: "aaa",
    at: "2026-08-05T10:00:00+00:00",
    author: "yschimke",
    subject: "fix: a thing",
    files: ["catalog/Buttons.kt", "catalog/Switches.kt"],
  });
  assert.deepEqual(commits[1].files, []);
});

test("parseGitLog keeps a subject containing the field separator's printable lookalikes", () => {
  const stdout = "\x1eaaa\x1f2026-08-05T10:00:00+00:00\x1fyschimke\x1ffix: a|b, c\x1fd\n";
  // A subject cannot contain \x1f, so anything after the fourth field belongs to the subject.
  assert.equal(parseGitLog(stdout)[0].subject, "fix: a|b, c\x1fd");
});
