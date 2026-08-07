import { test } from "node:test";
import assert from "node:assert/strict";

import {
  ACTIVITY_SCHEMA,
  GAP_KINDS,
  GIT_LOG_FORMAT,
  buildActivity,
  catalogRouteIds,
  codeEventsFrom,
  figmaCommentEventsFrom,
  figmaVersionEventsFrom,
  indexDesignMap,
  mappingGaps,
  parseGitLog,
  routeIdResolver,
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
  const index = indexDesignMap(designMap, {
    componentIdFor: (previewId) => (previewId.includes("FilledButton") ? "Button/Filled" : null),
  });

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

/**
 * A published catalog, in the shape the export writes it: every image carries BOTH the discovery
 * `previewId` a design map names and the `path` the serve route id is derived from.
 */
const catalog = {
  components: [
    {
      componentId: "Button/Filled",
      images: [
        {
          path: "images/button-filled/ideal__default__light.png",
          previewId: "sections.ButtonsKt.FilledButton_Light",
        },
      ],
    },
    {
      componentId: "Switch/On",
      images: [
        {
          path: "images/switch-on/ideal__default__light.png",
          // Sanitised in-bundle form: the design map writes the RAW id with a space.
          previewId: "sections.SwitchesKt.SwitchOn_Small_Round",
        },
      ],
    },
  ],
};

test("catalogRouteIds maps a discovery id onto the route id the server keys previews by", () => {
  const { exact, sanitised } = catalogRouteIds(catalog);

  assert.equal(
    exact.get("sections.ButtonsKt.FilledButton_Light"),
    "button-filled__ideal__default__light",
  );
  // A design map carries the raw id; the catalog carries the sanitised one. Both must resolve, or
  // a `@Preview(name = "Small Round")` silently loses its links.
  assert.equal(
    sanitised.get("sections.SwitchesKt.SwitchOn_Small_Round"),
    "switch-on__ideal__default__light",
  );
  const resolve = routeIdResolver(catalog);
  assert.equal(
    resolve("sections.SwitchesKt.SwitchOn_Small Round"),
    "switch-on__ideal__default__light",
  );
  // An id the catalog doesn't publish resolves to null, never to itself: emitting an unmatchable
  // id is exactly the bug this exists to prevent.
  assert.equal(resolve("sections.GoneKt.Removed"), null);
});

test("an ambiguous sanitised key resolves to nothing rather than the wrong preview", () => {
  // `"A B"` and `"A/B"` both sanitise to `A_B`; the plugin's `assignBundleEntryIds` keeps the base
  // for the first claimant and suffixes the rest, so raw→sanitised is not invertible here. Guessing
  // would point a commit at the wrong component's comparison — silently, and confidently.
  const collided = {
    components: [
      {
        componentId: "Foo",
        images: [
          { path: "images/foo-a/ideal__default__light.png", previewId: "pkg.FooKt.Bar_A_B" },
          { path: "images/foo-b/ideal__default__light.png", previewId: "pkg.FooKt.Bar_A_B_1" },
        ],
      },
    ],
  };
  const { ambiguous } = catalogRouteIds(collided);
  assert.deepEqual([...ambiguous], []);

  // The real collision: two images whose previewIds sanitise to one key.
  const real = {
    components: [
      {
        componentId: "Foo",
        images: [
          { path: "images/foo-a/ideal__default__light.png", previewId: "pkg.FooKt.Bar_A B" },
          { path: "images/foo-b/ideal__default__light.png", previewId: "pkg.FooKt.Bar_A/B" },
        ],
      },
    ],
  };
  const resolve = routeIdResolver(real);
  // Each id still resolves EXACTLY — the exact map is consulted first, so a collision elsewhere
  // never degrades an id that needs no sanitising.
  assert.equal(resolve("pkg.FooKt.Bar_A B"), "foo-a__ideal__default__light");
  assert.equal(resolve("pkg.FooKt.Bar_A/B"), "foo-b__ideal__default__light");
  // …but a third spelling that only reaches them through the lossy key gets nothing, not a guess.
  assert.equal(resolve("pkg.FooKt.Bar_A\tB"), null);
  assert.deepEqual([...catalogRouteIds(real).ambiguous], ["pkg.FooKt.Bar_A_B"]);
});

test("one preview rendered into several images is not a collision", () => {
  const resolve = routeIdResolver({
    components: [
      {
        componentId: "Foo",
        images: [
          { path: "images/foo/ideal__default__light.png", previewId: "pkg.FooKt.Bar" },
          { path: "images/foo/ideal__default__light.png", previewId: "pkg.FooKt.Bar" },
        ],
      },
    ],
  });
  assert.equal(resolve("pkg.FooKt.Bar"), "foo__ideal__default__light");
});

test("events carry ROUTE ids, not the discovery ids the design map names", () => {
  const index = indexDesignMap(designMap, { routeIdFor: routeIdResolver(catalog) });
  const events = codeEventsFrom(
    [
      {
        sha: "a".repeat(40),
        at: "2026-08-05T10:00:00+00:00",
        subject: "fix(button): padding",
        files: ["catalog/src/main/kotlin/sections/Buttons.kt"],
      },
    ],
    index,
  );

  // `ServeParityDashboard` filters these against the live `ServePreview.id`. A discovery id would
  // match nothing and every inbound link on the page would silently disappear.
  assert.deepEqual(events[0].previewIds, ["button-filled__ideal__default__light"]);
});

test("a comment's pinned node resolves to a route id too", () => {
  const index = indexDesignMap(designMap, { routeIdFor: routeIdResolver(catalog) });
  const events = figmaCommentEventsFrom(
    {
      comments: [
        {
          id: "1",
          created_at: "2026-08-04T08:00:00Z",
          message: "2dp short",
          client_meta: { node_id: "57994:2227" },
        },
      ],
    },
    index,
  );

  assert.deepEqual(events[0].previewIds, ["button-filled__ideal__default__light"]);
});

test("a mapping the catalog no longer publishes contributes no preview id to an event", () => {
  const index = indexDesignMap(
    { components: [{ code: "ui/Gone.kt#Gone", previewId: "app.GoneKt.Gone" }] },
    { routeIdFor: routeIdResolver(catalog) },
  );
  const events = codeEventsFrom(
    [{ sha: "a".repeat(40), at: "2026-08-05T10:00:00+00:00", subject: "x", files: ["ui/Gone.kt"] }],
    index,
  );

  assert.equal(events.length, 0, "no resolvable preview ⇒ the commit is not a mapped-file commit");
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

test("mappingGaps derives an unrendered reference from the published manifest", () => {
  // Both entries are mapped and both previews are published, but only Buttons made it into
  // `references/index.json` — so the Switches raster failed to render. Without this derivation the
  // `unrendered-reference` kind would be documented and unreachable, because the reference step's
  // warnings are gone by the time this runs.
  const gaps = mappingGaps({
    designMap,
    catalogPreviewIds: [
      "sections.ButtonsKt.FilledButton_Light",
      "sections.SwitchesKt.SwitchOn_Light",
    ],
    referenceManifest: {
      schema: "compose-preview-references/v1",
      references: [
        {
          id: "button",
          source: {
            attributes: { code: "catalog/src/main/kotlin/sections/Buttons.kt#FilledButton" },
          },
        },
      ],
    },
  });

  assert.deepEqual(
    gaps.map((g) => [g.kind, g.code]),
    [
      [
        GAP_KINDS.UNRENDERED_REFERENCE,
        "catalog/src/main/kotlin/sections/Switches.kt#SwitchOn",
      ],
    ],
  );
});

test("a sanitised catalog id is not mistaken for a dangling mapping", () => {
  // The design map writes the RAW discovery id; the catalog carries the sanitised in-bundle form.
  // Comparing them literally reports a healthy mapping as dangling AND suppresses the
  // unrendered-reference check for it — one false finding and one missed one, same mismatch.
  const spaced = {
    components: [
      {
        code: "ui/Foo.kt#Bar",
        ref: "figma:abc/1:2",
        previewId: "pkg.FooKt.Bar_Small Round",
      },
    ],
  };

  assert.deepEqual(
    mappingGaps({ designMap: spaced, catalogPreviewIds: ["pkg.FooKt.Bar_Small_Round"] }),
    [],
    "the same preview written two ways is not a dangling mapping",
  );

  // …and with the reference manifest proving the raster failed, the RIGHT finding now surfaces.
  const gaps = mappingGaps({
    designMap: spaced,
    catalogPreviewIds: ["pkg.FooKt.Bar_Small_Round"],
    referenceManifest: { references: [] },
  });
  assert.deepEqual(
    gaps.map((g) => g.kind),
    [GAP_KINDS.UNRENDERED_REFERENCE],
  );
});

test("no reference manifest means no derived gaps — absent is not the same as empty", () => {
  const gaps = mappingGaps({
    designMap,
    catalogPreviewIds: [
      "sections.ButtonsKt.FilledButton_Light",
      "sections.SwitchesKt.SwitchOn_Light",
    ],
  });

  assert.deepEqual(gaps, [], "a tokenless run must not report every mapping as unrendered");
});

test("a dangling entry is reported as dangling, not as unrendered", () => {
  // Its preview isn't published at all, so its missing reference is a symptom, not the finding.
  const gaps = mappingGaps({
    designMap,
    catalogPreviewIds: ["sections.ButtonsKt.FilledButton_Light"],
    referenceManifest: { references: [] },
  });

  assert.deepEqual(
    gaps.map((g) => g.kind),
    [GAP_KINDS.DANGLING_MAPPING, GAP_KINDS.UNRENDERED_REFERENCE],
    "Buttons is published but unrendered; Switches is dangling and reported only as such",
  );
  assert.equal(gaps[0].previewId, "sections.SwitchesKt.SwitchOn_Light");
  assert.equal(gaps[1].code, "catalog/src/main/kotlin/sections/Buttons.kt#FilledButton");
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
