import { test } from "node:test";
import assert from "node:assert/strict";

import {
  parseIdList,
  partitionPreviewIds,
  previewNameMatches,
  renderableDigest,
  shardRenderPlan,
  verifyShardPlans,
} from "./shard-preview-ids.mjs";

const preview = (id) => ({ id, functionName: id.replace(/_(Light|Dark)$/, "") });

test("round-robins the sorted id list so a heavy group is spread, not clustered", () => {
  // Ids sort by group, so contiguous blocks would put every Template preview in one shard.
  const ids = [
    "ButtonA_Light",
    "ButtonB_Light",
    "ButtonC_Light",
    "TemplateA_Light",
    "TemplateB_Light",
    "TemplateC_Light",
  ];
  const [first, second, third] = partitionPreviewIds(ids, 3);
  assert.deepEqual(first, ["ButtonA_Light", "TemplateA_Light"]);
  assert.deepEqual(second, ["ButtonB_Light", "TemplateB_Light"]);
  assert.deepEqual(third, ["ButtonC_Light", "TemplateC_Light"]);
});

test("the partition is a disjoint cover of every id", () => {
  const ids = Array.from({ length: 47 }, (_, i) => `P${String(i).padStart(2, "0")}`);
  const partitions = partitionPreviewIds(ids, 6);
  assert.equal(partitions.length, 6);
  assert.deepEqual(partitions.flat().sort(), [...ids].sort());
  assert.equal(new Set(partitions.flat()).size, ids.length, "no id appears twice");
  const sizes = partitions.map((p) => p.length);
  assert.ok(Math.max(...sizes) - Math.min(...sizes) <= 1, `balanced within one: ${sizes}`);
});

test("is deterministic regardless of the order discovery reported ids in", () => {
  const ids = ["c", "a", "d", "b"];
  assert.deepEqual(partitionPreviewIds(ids, 2), partitionPreviewIds([...ids].reverse(), 2));
});

test("clamps the shard count to the preview count rather than planning an empty shard", () => {
  // An empty shard's exclusion list would name every preview, which composePreviewRender rejects.
  const partitions = partitionPreviewIds(["a", "b", "c"], 6);
  assert.equal(partitions.length, 3);
  assert.ok(partitions.every((p) => p.length > 0));
});

test("each shard excludes exactly the previews the other shards render", () => {
  const previews = ["a", "b", "c", "d"].map(preview);
  const plan = shardRenderPlan(previews, 2);

  assert.equal(plan.total, 2);
  assert.equal(plan.renderable, 4);
  assert.deepEqual(plan.shards[0].previews, ["a", "c"]);
  assert.deepEqual(plan.shards[0].exclude, ["b", "d"]);
  assert.deepEqual(plan.shards[1].previews, ["b", "d"]);
  assert.deepEqual(plan.shards[1].exclude, ["a", "c"]);
  // Union of what the shards render is the whole renderable set — nothing is silently dropped.
  assert.deepEqual(plan.shards.flatMap((s) => s.previews).sort(), ["a", "b", "c", "d"]);
});

test("deferred ids are excluded in EVERY shard and take no share of the partition", () => {
  // The mode deferral and the partition are both exclusions; the deferred ids must not compete for
  // a slot, or one shard renders fewer previews than the others for no reason.
  const previews = ["a_Light", "a_Dark", "b_Light", "b_Dark"].map(preview);
  const plan = shardRenderPlan(previews, 2, ["a_Dark", "b_Dark"]);

  assert.equal(plan.renderable, 2, "only the light previews are rendered at all");
  assert.deepEqual(plan.shards[0].previews, ["a_Light"]);
  assert.deepEqual(plan.shards[1].previews, ["b_Light"]);
  for (const shard of plan.shards) {
    assert.ok(shard.exclude.includes("a_Dark"), `shard ${shard.index} defers a_Dark`);
    assert.ok(shard.exclude.includes("b_Dark"), `shard ${shard.index} defers b_Dark`);
  }
  assert.deepEqual(plan.shards[0].exclude, ["a_Dark", "b_Dark", "b_Light"]);
});

test("a deferred id discovery never saw is still excluded", () => {
  // Exclusion polarity: a pattern matching nothing renders more, never less. A spec that has drifted
  // ahead of the code must not fail the plan.
  const plan = shardRenderPlan(["a", "b"].map(preview), 2, ["ghost"]);
  assert.ok(plan.shards.every((s) => s.exclude.includes("ghost")));
  assert.equal(plan.renderable, 2);
});

test("one shard means one exclusion list holding only the deferred ids", () => {
  const plan = shardRenderPlan(["a", "b"].map(preview), 1, ["c"]);
  assert.equal(plan.total, 1);
  assert.deepEqual(plan.shards[0].previews, ["a", "b"]);
  assert.deepEqual(plan.shards[0].exclude, ["c"]);
});

test("de-duplicates ids discovery reported twice", () => {
  const plan = shardRenderPlan([preview("a"), preview("a"), preview("b")], 2);
  assert.equal(plan.renderable, 2);
  assert.deepEqual(plan.shards.flatMap((s) => s.previews).sort(), ["a", "b"]);
});

test("no previews yields no shards, so the caller can refuse instead of rendering nothing", () => {
  assert.deepEqual(partitionPreviewIds([], 4), []);
  const plan = shardRenderPlan([], 4);
  assert.equal(plan.total, 0);
  assert.deepEqual(plan.shards, []);
});

test("ignores malformed discovery entries", () => {
  const plan = shardRenderPlan([{ id: "a" }, {}, { id: "" }, null, { id: "b" }], 2);
  assert.equal(plan.renderable, 2);
  assert.deepEqual(plan.shards.flatMap((s) => s.previews).sort(), ["a", "b"]);
});

test("parseIdList reads both the comma form and a newline-separated file", () => {
  assert.deepEqual(parseIdList("a,b , c"), ["a", "b", "c"]);
  assert.deepEqual(parseIdList("a\nb\n\n"), ["a", "b"]);
  assert.deepEqual(parseIdList(""), []);
  assert.deepEqual(parseIdList(undefined), []);
});

/** The plan records the shards upload, as `shardRenderPlan` would have produced them. */
const plansFor = (previews, shards, deferred = []) => {
  const plan = shardRenderPlan(previews.map(preview), shards, deferred);
  return plan.shards.map((s) => ({
    index: s.index,
    total: shards,
    renderable: plan.renderable,
    digest: plan.digest,
    previews: s.previews,
  }));
};

test("verifyShardPlans accepts a disjoint cover of the discovered set", () => {
  const { ok, problems } = verifyShardPlans(plansFor(["a", "b", "c", "d"], 2));
  assert.deepEqual(problems, []);
  assert.ok(ok);
});

test("verifyShardPlans accepts plans that arrive out of order", () => {
  const plans = plansFor(["a", "b", "c", "d"], 2).reverse();
  assert.ok(verifyShardPlans(plans).ok);
});

test("verifyShardPlans catches a gap — the failure the completeness gate would only hint at", () => {
  const plans = plansFor(["a", "b", "c", "d"], 2);
  plans[1].previews = plans[1].previews.slice(1);
  const { ok, problems } = verifyShardPlans(plans);
  assert.equal(ok, false);
  assert.match(problems.join("\n"), /rendered 3 preview\(s\), but discovery found 4/);
});

test("verifyShardPlans catches an overlap", () => {
  const plans = plansFor(["a", "b", "c", "d"], 2);
  plans[1].previews = [...plans[1].previews, "a"];
  const { ok, problems } = verifyShardPlans(plans);
  assert.equal(ok, false);
  assert.match(problems.join("\n"), /preview a was rendered by shards 1 and 2/);
});

test("verifyShardPlans catches shards that discovered different worlds", () => {
  const plans = plansFor(["a", "b", "c", "d"], 2);
  plans[0].renderable = 5;
  const { ok, problems } = verifyShardPlans(plans);
  assert.equal(ok, false);
  assert.match(problems.join("\n"), /different numbers of renderable previews/);
});

test("verifyShardPlans catches a shard whose bundle never arrived", () => {
  const plans = plansFor(["a", "b", "c", "d"], 2).slice(0, 1);
  const { ok, problems } = verifyShardPlans(plans);
  assert.equal(ok, false);
  assert.match(problems.join("\n"), /expected 2 shard plan\(s\), got 1/);
});

test("verifyShardPlans rejects an empty set of plans", () => {
  assert.deepEqual(verifyShardPlans([]), {
    ok: false,
    problems: ["no shard plans were uploaded"],
  });
});

test("verifyShardPlans is satisfied by the plans a deferring catalog produces", () => {
  // Deferred ids are in no shard's partition, and `renderable` counts only what renders — so the
  // cover check must not expect them back.
  const plans = plansFor(["a_Light", "a_Dark", "b_Light", "b_Dark"], 2, ["a_Dark", "b_Dark"]);
  assert.ok(verifyShardPlans(plans).ok, JSON.stringify(plans));
});

// --- the pre-flight's positive function-name filter -----------------------------------------

test("previewNameMatches ports the renderer's plain equality-or-substring rule", () => {
  assert.ok(previewNameMatches([], "Anything"), "an empty filter keeps everything");
  assert.ok(previewNameMatches(["FilledButtonPreview"], "FilledButtonPreview"));
  assert.ok(previewNameMatches(["FilledButton"], "FilledButtonPreview"), "substring matches");
  assert.equal(previewNameMatches(["OutlinedButton"], "FilledButtonPreview"), false);
  assert.ok(previewNameMatches([" FilledButton ", ""], "FilledButtonPreview"), "trims and skips blanks");
});

test("previewNameMatches matches the package-qualified name too", () => {
  const cls = "com.example.ui.ButtonsKt";
  assert.ok(previewNameMatches(["com.example.ui.FilledButtonPreview"], "FilledButtonPreview", cls));
  assert.ok(previewNameMatches(["com.example.ui"], "FilledButtonPreview", cls), "package substring");
  assert.equal(previewNameMatches(["com.other.ui.FilledButtonPreview"], "FilledButtonPreview", cls), false);
});

test("previewNameMatches anchors a glob instead of substring-matching it", () => {
  assert.ok(previewNameMatches(["Filled*Preview"], "FilledButtonPreview"));
  assert.ok(previewNameMatches(["*ButtonPreview"], "FilledButtonPreview"));
  assert.equal(previewNameMatches(["Filled*"], "OutlinedFilledButtonPreview"), false, "anchored");
  assert.ok(previewNameMatches(["FilledButtonPreview?"], "FilledButtonPreview2"));
  // A dot in an FQN glob is a literal, not "any char".
  assert.equal(previewNameMatches(["com.example.*"], "comXexample.Foo", ""), false);
});

test("the render filter drops non-required functions BEFORE the partition is drawn", () => {
  // Without this, a shard could be handed a share made entirely of deferred-function ids: it would
  // report work to do, exclude every id the name filter kept, and the render would refuse a
  // selection that produces nothing.
  const previews = [
    { id: "Keep_Light", functionName: "KeepPreview" },
    { id: "Keep_Dark", functionName: "KeepPreview" },
    { id: "Drop_Light", functionName: "DropPreview" },
    { id: "Drop_Dark", functionName: "DropPreview" },
  ];
  const plan = shardRenderPlan(previews, 2, [], ["KeepPreview"]);

  assert.equal(plan.renderable, 2);
  assert.equal(plan.filteredOut, 2);
  assert.deepEqual(plan.shards.flatMap((s) => s.previews).sort(), ["Keep_Dark", "Keep_Light"]);
  for (const shard of plan.shards) {
    assert.equal(shard.exclude.some((id) => id.startsWith("Drop_")), false,
      "a filtered-out id needs no exclusion — the name filter already drops it");
  }
});

test("a filter that would leave a shard empty clamps the shard count instead of failing it", () => {
  const previews = [
    { id: "Keep_Light", functionName: "KeepPreview" },
    ...Array.from({ length: 10 }, (_, i) => ({ id: `Drop${i}`, functionName: "DropPreview" })),
  ];
  const plan = shardRenderPlan(previews, 6, [], ["KeepPreview"]);
  assert.equal(plan.total, 1, "one renderable preview means one real shard");
  assert.deepEqual(plan.shards[0].previews, ["Keep_Light"]);
  assert.deepEqual(plan.shards[0].exclude, [], "nothing left to exclude");
});

test("the render filter and modePriority deferral compose", () => {
  const previews = [
    { id: "Keep_Light", functionName: "KeepPreview" },
    { id: "Keep_Dark", functionName: "KeepPreview" },
    { id: "Drop_Light", functionName: "DropPreview" },
  ];
  const plan = shardRenderPlan(previews, 2, ["Keep_Dark"], ["KeepPreview"]);
  assert.equal(plan.renderable, 1);
  assert.deepEqual(plan.shards[0].previews, ["Keep_Light"]);
  assert.deepEqual(plan.shards[0].exclude, ["Keep_Dark"]);
});

// --- discovered-set agreement, not just cardinality -----------------------------------------

test("renderableDigest is order-independent and set-sensitive", () => {
  assert.equal(renderableDigest(["a", "b"]), renderableDigest(["b", "a"]));
  assert.notEqual(renderableDigest(["a", "b"]), renderableDigest(["a", "c"]));
});

test("verifyShardPlans catches same-sized but DIFFERENT discovered sets", () => {
  // The count check passes here — two plans, `renderable: 2`, partitions ["a"] and ["d"], disjoint,
  // union of size two. Only the digest sees that the runners discovered different worlds.
  const plans = [
    { index: 1, total: 2, renderable: 2, digest: renderableDigest(["a", "b"]), previews: ["a"] },
    { index: 2, total: 2, renderable: 2, digest: renderableDigest(["c", "d"]), previews: ["d"] },
  ];
  const { ok, problems } = verifyShardPlans(plans);
  assert.equal(ok, false);
  assert.match(problems.join("\n"), /different preview SETS/);
});

test("verifyShardPlans rejects plans with no digest at all", () => {
  const plans = [
    { index: 1, total: 2, renderable: 2, previews: ["a"] },
    { index: 2, total: 2, renderable: 2, previews: ["b"] },
  ];
  const { ok, problems } = verifyShardPlans(plans);
  assert.equal(ok, false);
  assert.match(problems.join("\n"), /no renderable digest/);
});
