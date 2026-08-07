import { test } from "node:test";
import assert from "node:assert/strict";

import {
  findPreview,
  previewIdAliases,
  resolvePreviewId,
  sanitizeBundleEntryId,
} from "./preview-id-alias.mjs";

test("sanitizeBundleEntryId mirrors the plugin's substitution", () => {
  // Spaces are the case that actually bit: `@Preview(name = "Extra Large Round")`.
  assert.equal(
    sanitizeBundleEntryId("com.example.FooKt.Bar_Extra Large Round"),
    "com.example.FooKt.Bar_Extra_Large_Round",
  );
  // Dots and dashes are preserved — they never need quoting, and collapsing them would merge ids
  // the plugin deliberately keeps distinct.
  assert.equal(sanitizeBundleEntryId("Foo_wearos-small-round"), "Foo_wearos-small-round");
  assert.equal(sanitizeBundleEntryId('Foo_tile (light) "a/b"'), "Foo_tile__light___a_b_");
  // Idempotent: an already-sanitised id is left alone.
  const clean = "com.example.FooKt.Bar_Small_Round";
  assert.equal(sanitizeBundleEntryId(clean), clean);
});

test("aliases come from the manifest's parallel raw/bundle id arrays", () => {
  const aliases = previewIdAliases({
    previewIds: ["Foo_Small_Round", "Foo_Large_Round", "Bar"],
    rawPreviewIds: ["Foo_Small Round", "Foo_Large Round", "Bar"],
  });
  assert.equal(aliases.get("Foo_Small Round"), "Foo_Small_Round");
  assert.equal(aliases.get("Foo_Large Round"), "Foo_Large_Round");
  // An id that needed no sanitising is not recorded — it resolves by exact match.
  assert.equal(aliases.has("Bar"), false);
});

test("a manifest with no rawPreviewIds, or mismatched lengths, yields no aliases", () => {
  // Bundles packed before `rawPreviewIds` existed: fall back to the character substitution rather
  // than zip arrays that cannot be aligned.
  assert.equal(previewIdAliases({ previewIds: ["Foo_A_B"] }).size, 0);
  assert.equal(previewIdAliases(undefined).size, 0);
  assert.equal(
    previewIdAliases({ previewIds: ["Foo_A_B", "Bar"], rawPreviewIds: ["Foo_A B"] }).size,
    0,
    "a length mismatch must not pair a raw id with the wrong preview",
  );
});

test("the alias wins over re-deriving the sanitised form, so collision suffixes survive", () => {
  // `assignBundleEntryIds` disambiguates two raw ids that sanitise identically ("A B" / "A_B") by
  // suffixing the loser. Character substitution alone would send both to `Foo_A_B` and point the
  // second candidate at the first one's preview.
  const aliases = previewIdAliases({
    previewIds: ["Foo_A_B", "Foo_A_B_1"],
    rawPreviewIds: ["Foo_A B", "Foo_A_B"],
  });
  assert.equal(resolvePreviewId("Foo_A B", aliases), "Foo_A_B");
  assert.equal(resolvePreviewId("Foo_A_B", aliases), "Foo_A_B_1");
});

test("a declared alias beats an exact match that belongs to a colliding sibling", () => {
  // raw "Foo_A B" bundles as Foo_A_B; raw "Foo_A_B" is then disambiguated to Foo_A_B_1. The second
  // candidate's RAW id is also an exact key for the FIRST candidate's preview, so an exact-first
  // lookup hands it the wrong preview's params — the wrong breakpoint, silently.
  const previews = [
    { id: "Foo_A_B", params: { device: "id:wearos_small_round" } },
    { id: "Foo_A_B_1", params: { device: "id:wearos_large_round" } },
  ];
  const byId = new Map(previews.map((p) => [p.id, p]));
  const aliases = previewIdAliases({
    previewIds: ["Foo_A_B", "Foo_A_B_1"],
    rawPreviewIds: ["Foo_A B", "Foo_A_B"],
  });

  assert.equal(findPreview(byId, "Foo_A B", aliases)?.id, "Foo_A_B");
  assert.equal(
    findPreview(byId, "Foo_A_B", aliases)?.id,
    "Foo_A_B_1",
    "the alias must win over the exact key that belongs to the sibling",
  );
});

test("findPreview matches either spelling of the id", () => {
  const previews = [
    { id: "com.example.FooKt.Bar_Small_Round", params: { device: "id:wearos_small_round" } },
    { id: "com.example.FooKt.Plain", params: {} },
  ];
  const byId = new Map(previews.map((p) => [p.id, p]));
  const aliases = previewIdAliases({
    previewIds: ["com.example.FooKt.Bar_Small_Round", "com.example.FooKt.Plain"],
    rawPreviewIds: ["com.example.FooKt.Bar_Small Round", "com.example.FooKt.Plain"],
  });

  // The raw id the candidate reader hands back — the lookup that previously missed.
  assert.equal(
    findPreview(byId, "com.example.FooKt.Bar_Small Round", aliases)?.id,
    "com.example.FooKt.Bar_Small_Round",
  );
  // The sanitised id still matches exactly.
  assert.equal(
    findPreview(byId, "com.example.FooKt.Bar_Small_Round", aliases)?.id,
    "com.example.FooKt.Bar_Small_Round",
  );
  // No aliases (older bundle) — the character substitution carries it.
  assert.equal(
    findPreview(byId, "com.example.FooKt.Bar_Small Round", undefined)?.id,
    "com.example.FooKt.Bar_Small_Round",
  );
  // An id belonging to no preview stays unresolved rather than matching something adjacent.
  assert.equal(findPreview(byId, "com.example.FooKt.Missing", aliases), undefined);
  assert.equal(findPreview(byId, undefined, aliases), undefined);
});
