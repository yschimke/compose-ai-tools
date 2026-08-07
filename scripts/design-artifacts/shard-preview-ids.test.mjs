import { test } from "node:test";
import assert from "node:assert/strict";

import {
  parseIdList,
  partitionPreviewIds,
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
const plansFor = (previews, shards, deferred = []) =>
  shardRenderPlan(previews.map(preview), shards, deferred).shards.map((s) => ({
    index: s.index,
    total: shards,
    renderable: previews.length - deferred.length,
    previews: s.previews,
  }));

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
