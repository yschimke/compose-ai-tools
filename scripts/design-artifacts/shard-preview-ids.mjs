/**
 * Partition a catalog's discovered preview ids across N parallel render shards.
 *
 * The design-artifacts render is one serial `bundle pack`, and it used to grow linearly with the
 * preview count: measured on m3-catalog, `render_minutes ≈ 3.7 + 2.15s × previews`. Past ~1500
 * previews that blew `render-timeout`, and past ~2400 the job timeout — so the marginal 2.15s had to
 * be divided across jobs. This module decides who renders what.
 *
 * **That cost model is superseded and the shard count derived from it is not currently trusted.**
 * Captures now draw on a warm renderer rather than forking a JVM each time (#3548), which collapsed
 * the marginal term and left the ~3.7 min fixed configure + compile that every shard pays in full
 * untouched. Cheaper marginal work with unchanged fixed cost moves the optimum *down*, so the old
 * "six, not sixteen" conclusion no longer follows from anything measured. #3559 tracks re-measuring
 * it. Nothing here assumes a particular count — the partition is correct at any N — but do not read
 * the arithmetic above as current guidance.
 *
 * The mechanism is exclusion, not selection: each shard runs the SAME `bundle pack` with
 * `--exclude-preview-id <everything that isn't mine>`, which is documented to leave the excluded
 * previews listed in the bundle (addressable, just without a baked PNG). So every shard emits a
 * structurally identical bundle — same `previews.json`, same manifest, same re-render classpath —
 * differing only in which `previews/<id>.*` slots are filled, which is exactly what
 * `compose-preview bundle merge` unions back together.
 *
 * Four decisions worth stating, because each has a wrong-looking-right alternative:
 *
 *  - **Partition by preview id, never by `@Preview` function name.** One function expands to a
 *    30-cell matrix (m3-catalog's icon buttons) while its neighbour expands to two; a name split is
 *    wildly unbalanced, and the slowest shard sets the wall clock.
 *  - **Round-robin over the SORTED id list, not contiguous blocks.** Render cost per preview is not
 *    uniform — a `showSystemUi = true` scaffold costs far more than a 32dp extra-small button — and
 *    ids sort together by group, so contiguous blocks cluster the template-heavy groups into one
 *    shard. Round-robin spreads them. It is not bin-packing, and does not try to be: bin-packing
 *    from recorded per-preview times is only worth it once a straggler actually shows up.
 *  - **Every emitted exclusion is `=`-anchored.** `--exclude-preview-id` matches a plain pattern by
 *    equality OR substring, and ids are hierarchical (`<base>_<variant>`), so a base id is always a
 *    substring of its own fan-out: a shard excluding another shard's `SwitchOn_Light` also deleted
 *    every `SwitchOn_Light_VARIANT_*` it was itself assigned. That cost m3-catalog three quarters of
 *    its renders — 267 captured of 1095 assigned — silently, on a green run, which is why
 *    `render-shards` was pinned back to 1. The asymmetry is the point: substring matching
 *    over-selects harmlessly on the INCLUDE axis and silently deletes work on the EXCLUDE axis, so
 *    it is unusable with any generated id list, which is exactly what a sharder produces. The `=`
 *    prefix (#3561) matches the id exactly. It is emitted here, at the boundary where ids become
 *    CLI patterns, rather than carried through the partition — everything upstream stays plain ids.
 *  - **Deferred ids are removed BEFORE partitioning, then re-excluded in every shard.** A
 *    `modePriority` deferral (issue #2966) and the partition are both expressed as exclusions, so
 *    the naive union would hand deferred ids a share of the partition and leave one shard rendering
 *    fewer previews than the others for no reason. Removing them first means the shards balance
 *    over the set that is actually going to render, and the deferral still applies within each.
 *
 * One axis this cannot balance: a `@PreviewParameter` provider's rows. Discovery emits one id for
 * the parameterized function and the renderer expands the rows later, so such a preview travels
 * whole — it lands in one shard carrying however many rows it expands to. That is correct (the rows
 * must not be split across bundles) but it is the most likely source of a straggler shard.
 *
 * Pure and dependency-free (node built-ins only) so it unit-tests without an `npm ci`, like its
 * sibling `deferred-preview-ids.mjs`. The CLI wrapper at the bottom only runs when this file is
 * executed directly.
 */

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { createHash } from "node:crypto";
import { parseArgs } from "node:util";
import { previewsFromJson } from "./deferred-preview-ids.mjs";

/**
 * Port of the renderer's `PreviewNameFilter.matches` — the `--preview` / `-PcomposePreview.filter`
 * selector — so the partition can see the same preview set the render will.
 *
 * The pre-flight emits a positive **function-name** filter when a spec defers a whole entry
 * (`renderFilterPatterns`), and the shard render passes it as `ORG_GRADLE_PROJECT_composePreview.filter`.
 * Partitioning the *unfiltered* discovery output against that would be a live bug, not a rounding
 * error: a shard whose share happened to be all deferred-function ids would report work to do, then
 * exclude every id the name filter kept — and `composePreviewRender` rejects a selection that
 * renders nothing.
 *
 * Semantics, matched deliberately rather than approximated (both directions are wrong: too
 * permissive re-opens the empty-render bug, too strict silently drops a sticker from every shard):
 *  - a pattern containing `*` or `?` is anchored and full-matched as a glob;
 *  - a pattern without them matches on equality **or substring**;
 *  - either candidate name counts — the simple function name or `<package>.<functionName>`;
 *  - matching is case-sensitive, any pattern keeps the preview, and an empty list keeps everything.
 */
export function previewNameMatches(patterns, functionName, className = "") {
  const cleaned = (patterns ?? []).map((p) => String(p).trim()).filter((p) => p.length > 0);
  if (cleaned.length === 0) return true;
  const simple = String(functionName ?? "");
  const pkg = String(className ?? "").includes(".")
    ? String(className).slice(0, String(className).lastIndexOf("."))
    : "";
  const fq = pkg.length > 0 ? `${pkg}.${simple}` : simple;
  return cleaned.some((pattern) => {
    if (pattern.includes("*") || pattern.includes("?")) {
      const regex = new RegExp(
        `^${pattern.replace(/[.+^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*").replace(/\?/g, ".")}$`,
      );
      return regex.test(simple) || regex.test(fq);
    }
    return (
      simple === pattern || fq === pattern || simple.includes(pattern) || fq.includes(pattern)
    );
  });
}

/**
 * The prefix that makes an `--exclude-preview-id` pattern an exact-id match rather than a substring
 * one. Mirrors `PreviewNameFilter.ANCHOR` / `PackPreviewIdExclusions.ANCHOR` (#3561); it cannot
 * collide with a real pattern because no discovered id can begin with it — ids derive from Kotlin
 * identifiers and are path-sanitised.
 */
export const ANCHOR = "=";

/**
 * Turn plain preview ids into anchored `--exclude-preview-id` patterns.
 *
 * Applied at the one boundary where an id becomes a CLI pattern. An id that is already anchored is
 * left alone, so this is idempotent and a caller that anchored upstream is not double-prefixed.
 *
 * @param {string[]} ids plain preview ids.
 * @returns {string[]} the same ids, each `=`-anchored.
 */
export function anchorExclusions(ids) {
  return (ids ?? [])
    .filter((id) => typeof id === "string" && id.length > 0)
    .map((id) => (id.startsWith(ANCHOR) ? id : `${ANCHOR}${id}`));
}

/**
 * A stable fingerprint of the renderable id set, carried in every shard's plan so the merge can
 * check that the shards discovered the *same* previews rather than merely the same NUMBER of them.
 * Two runners that saw `["a"]` and `["d"]` are disjoint with a union of size two, which a count
 * comparison calls agreement and a digest comparison does not.
 */
export function renderableDigest(ids) {
  return createHash("sha256").update([...(ids ?? [])].sort().join("\n")).digest("hex").slice(0, 16);
}

/**
 * Round-robin [ids] (sorted, de-duplicated) into [shards] partitions.
 *
 * Returns at most `min(shards, ids.length)` partitions and never an empty one: a shard with nothing
 * to render would be handed an exclusion list naming every preview, and `composePreviewRender`
 * rejects that outright ("--exclude-preview-id excluded every one of the N previews"). Clamping is
 * the right response — a catalog with 3 previews and `render-shards: 6` wants 3 shards, not a
 * failure.
 *
 * @param {string[]} ids every discovered, renderable preview id.
 * @param {number} shards requested shard count.
 * @returns {string[][]} one sorted partition per shard.
 */
export function partitionPreviewIds(ids, shards) {
  const sorted = [...new Set((ids ?? []).filter((id) => typeof id === "string" && id.length > 0))]
    .sort();
  const count = Math.max(1, Math.min(Math.floor(shards) || 1, sorted.length));
  if (sorted.length === 0) return [];
  const out = Array.from({ length: count }, () => []);
  sorted.forEach((id, i) => out[i % count].push(id));
  return out;
}

/**
 * The full render plan for a sharded run: what each shard renders, and the `--exclude-preview-id`
 * list that makes it render only that.
 *
 * `previews` are plain ids (they are compared, counted and cross-checked); `exclude` are `=`-anchored
 * CLI patterns (they are passed to a matcher). Keeping the two shapes distinct is deliberate — see
 * the anchoring note in the header for what an unanchored exclusion list costs.
 *
 * Three things are removed from the partition before it is drawn, each for the same reason — a
 * shard must never be handed a share that the render will not actually produce:
 *  - ids of functions the **name filter** drops (an entry-level `priority: "deferred"`);
 *  - ids `modePriority` **defers**;
 * and both are then excluded in *every* shard, so the two levers compose instead of competing.
 *
 * @param {Array<{id: string, functionName?: string, className?: string}>} previews discovered
 *   previews (from `compose-preview list --json`).
 * @param {number} shards requested shard count.
 * @param {string[]} deferred ids already excluded by `modePriority` — dropped from the partition and
 *   re-added to every shard's exclusion list.
 * @param {string[]} renderFilter the pre-flight's positive function-name patterns
 *   (`renderFilterPatterns`); empty ⇒ every discovered preview renders.
 * @returns {{shards: Array<{index: number, previews: string[], exclude: string[]}>, total: number,
 *   renderable: number, digest: string, deferred: string[], filteredOut: number}} `index` is
 *   1-based (it is what a human reads in the Actions matrix); `total` is the effective shard count
 *   after clamping.
 */
export function shardRenderPlan(previews, shards, deferred = [], renderFilter = []) {
  const deferredSet = new Set(deferred ?? []);
  const selected = (previews ?? []).filter((p) =>
    previewNameMatches(renderFilter, p?.functionName ?? p?.id, p?.className),
  );
  const all = selected
    .map((p) => p?.id)
    .filter((id) => typeof id === "string" && id.length > 0);
  const renderable = [...new Set(all)].filter((id) => !deferredSet.has(id)).sort();
  const partitions = partitionPreviewIds(renderable, shards);
  // Only the ids this shard is NOT rendering, plus the deferred ones, each `=`-anchored so it
  // matches that id and not its variants (see the header). Ids the caller listed as deferred but
  // discovery never saw are kept in the exclusion list anyway: exclusion polarity means a pattern
  // matching nothing renders MORE, never less, so a stale entry costs time, not a sticker.
  return {
    shards: partitions.map((mine, i) => {
      const own = new Set(mine);
      return {
        index: i + 1,
        previews: mine,
        exclude: anchorExclusions(
          [...renderable.filter((id) => !own.has(id)), ...deferredSet].sort(),
        ),
      };
    }),
    total: partitions.length,
    renderable: renderable.length,
    digest: renderableDigest(renderable),
    filteredOut: new Set((previews ?? []).map((p) => p?.id)).size - new Set(all).size,
    deferred: [...deferredSet].sort(),
  };
}

/** Parse a comma/newline-separated id list (a file's contents or a flag value) into ids. */
export function parseIdList(text) {
  return String(text ?? "")
    .split(/[,\n]/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

/**
 * Cross-check the per-shard plans a completed matrix uploaded, before their bundles are merged.
 *
 * Every shard derives its own partition from its own `compose-preview list --json`, which is what
 * keeps the pipeline free of a serial discover-then-fan-out prefix — but it also means nothing has
 * yet checked that the shards agreed. They agree by construction (same commit, same module, and the
 * partition sorts its input), and a disagreement is not hypothetical enough to leave undiagnosed: it
 * would surface as an unbaked preview and reach the operator as a completeness-gate failure naming
 * a component, with no hint that the shards saw different worlds.
 *
 * Checks, in the order they'd bite:
 *  - every shard planned the same shard count, and discovered the **same id set** — compared by
 *    `digest`, not by count, because two runners that saw `["a"]` and `["b"]` are disjoint with a
 *    union of the right size, which a count comparison happily calls agreement;
 *  - the partitions are pairwise disjoint (an overlap is wasted render time, and `bundle merge`
 *    would silently pick a winner);
 *  - the partitions cover the whole renderable set (a gap is a missing sticker).
 *
 * @param {Array<{index: number, total: number, renderable: number, digest?: string,
 *   previews: string[]}>} plans
 * @returns {{ok: boolean, problems: string[]}} `problems` is empty iff the merge is safe.
 */
export function verifyShardPlans(plans) {
  const problems = [];
  const list = [...(plans ?? [])].sort((a, b) => (a?.index ?? 0) - (b?.index ?? 0));
  if (list.length === 0) return { ok: false, problems: ["no shard plans were uploaded"] };

  const totals = new Set(list.map((p) => p?.total));
  if (totals.size > 1) {
    problems.push(`shards disagree on the shard count: ${[...totals].join(", ")}`);
  }
  const renderables = new Set(list.map((p) => p?.renderable));
  if (renderables.size > 1) {
    problems.push(
      `shards discovered different numbers of renderable previews: ${[...renderables].join(", ")}`,
    );
  }
  // The set, not just its size. Same count with different members is the failure a count check
  // cannot see, and it is the one that would corrupt the merge quietly: the base's manifest expects
  // an id nothing baked, while an unrelated artifact rides in from another shard.
  const digests = new Set(list.map((p) => p?.digest).filter((d) => typeof d === "string"));
  if (digests.size > 1) {
    problems.push(
      `shards discovered different preview SETS (renderable digests ${[...digests].join(", ")})`,
    );
  } else if (digests.size === 0) {
    problems.push("shard plans carry no renderable digest — they predate the set comparison");
  }
  if (list.length !== (list[0]?.total ?? list.length)) {
    problems.push(`expected ${list[0]?.total} shard plan(s), got ${list.length}`);
  }

  const seen = new Map();
  for (const plan of list) {
    for (const id of plan?.previews ?? []) {
      if (seen.has(id)) {
        problems.push(`preview ${id} was rendered by shards ${seen.get(id)} and ${plan.index}`);
      } else {
        seen.set(id, plan.index);
      }
    }
  }
  const expected = list[0]?.renderable ?? 0;
  if (seen.size !== expected) {
    problems.push(
      `the shards between them rendered ${seen.size} preview(s), but discovery found ${expected}`,
    );
  }
  return { ok: problems.length === 0, problems };
}

// --- CLI ----------------------------------------------------------------------
// Two modes, one for each end of the matrix.
//
// Plan ONE shard, run inside that shard's own render job:
//   node shard-preview-ids.mjs --previews discovered.json --shards 6 --index 2 \
//     [--exclude-file mode-filter.txt] [--render-filter-file render-filter.txt] \
//     --out exclude.txt --plan-out shard-plan.json
// Writes the comma-separated `--exclude-preview-id` list this shard passes to `bundle pack`, and a
// small plan record for the merge-side cross-check. Prints the number of previews this shard renders
// — 0 means "there was nothing left for you", which a caller should treat as "skip the render", not
// as an error.
//
// VERIFY the plans a finished matrix produced, run before merging its bundles:
//   node shard-preview-ids.mjs --verify shard-plan-1.json shard-plan-2.json …
// Exits non-zero, naming the disagreement, if the shards did not between them render exactly the
// discovered set once each.
if (import.meta.url === `file://${process.argv[1]}`) {
  const { values, positionals } = parseArgs({
    allowPositionals: true,
    options: {
      previews: { type: "string" },
      shards: { type: "string" },
      index: { type: "string" },
      "exclude-file": { type: "string" },
      "render-filter-file": { type: "string" },
      out: { type: "string" },
      "plan-out": { type: "string" },
      verify: { type: "boolean", default: false },
    },
  });

  if (values.verify) {
    const plans = positionals.map((f) => JSON.parse(readFileSync(f, "utf8")));
    const { ok, problems } = verifyShardPlans(plans);
    if (!ok) {
      for (const problem of problems) {
        console.error(`shard-preview-ids: ${problem}`);
      }
      console.error(
        "shard-preview-ids: refusing to merge — the shards did not cover the discovered previews " +
          "exactly once. Re-run the render; if it repeats, discovery is not reproducible across " +
          "runners and the partition cannot be derived per shard.",
      );
      process.exit(1);
    }
    console.error(
      `shard-preview-ids: ${plans.length} shard(s) cover ${plans[0]?.renderable ?? 0} preview(s) ` +
        `exactly once`,
    );
  } else {
    if (!values.previews || !values.shards || !values.index || !values.out) {
      console.error(
        "usage: shard-preview-ids.mjs --previews <list.json> --shards <n> --index <k> " +
          "--out <exclude.txt> [--plan-out <plan.json>] [--exclude-file <ids.txt>] " +
          "[--render-filter-file <patterns.txt>]\n" +
          "       shard-preview-ids.mjs --verify <plan.json>…",
      );
      process.exit(2);
    }
    const previews = previewsFromJson(JSON.parse(readFileSync(values.previews, "utf8")));
    const deferred = values["exclude-file"]
      ? parseIdList(readFileSync(values["exclude-file"], "utf8"))
      : [];
    // The pre-flight's positive function-name filter, when a spec defers a whole entry. The render
    // applies it too, so the partition has to see the same set or a shard can end up with a share
    // the render will not produce.
    const renderFilter = values["render-filter-file"]
      ? parseIdList(readFileSync(values["render-filter-file"], "utf8"))
      : [];
    const requested = Number(values.shards);
    const index = Number(values.index);
    const plan = shardRenderPlan(previews, requested, deferred, renderFilter);
    if (plan.filteredOut > 0 && index === 1) {
      console.error(
        `shard-preview-ids: the render filter drops ${plan.filteredOut} discovered preview(s) ` +
          `before partitioning (${renderFilter.length} pattern(s)).`,
      );
    }
    const mine = plan.shards.find((s) => s.index === index);

    if (plan.total < requested && index === 1) {
      console.error(
        `shard-preview-ids: ${requested} shards requested but only ${plan.renderable} renderable ` +
          `preview(s) — ${plan.total} shard(s) will render, the rest are no-ops.`,
      );
    }
    if (!mine) {
      // More shards than previews: this one has nothing to do. Its exclusion list would name every
      // preview, which `composePreviewRender` rejects outright — so say "0" and let the workflow
      // skip the render rather than fail it.
      writeFileSync(values.out, "");
      if (values["plan-out"]) {
        writeFileSync(
          values["plan-out"],
          `${JSON.stringify({ index, total: plan.total, renderable: plan.renderable, digest: plan.digest, previews: [] }, null, 2)}\n`,
        );
      }
      console.error(`shard-preview-ids: shard ${index} of ${plan.total} has no previews to render.`);
      console.log("0");
    } else {
      writeFileSync(values.out, mine.exclude.join(","));
      if (values["plan-out"]) {
        writeFileSync(
          values["plan-out"],
          `${JSON.stringify({ index, total: plan.total, renderable: plan.renderable, digest: plan.digest, previews: mine.previews }, null, 2)}\n`,
        );
      }
      console.error(
        `shard-preview-ids: shard ${index}/${plan.total} renders ${mine.previews.length} of ` +
          `${plan.renderable} preview(s), excluding ${mine.exclude.length}` +
          (plan.deferred.length > 0 ? ` (${plan.deferred.length} deferred by the spec)` : ""),
      );
      console.log(String(mine.previews.length));
    }
  }
}
