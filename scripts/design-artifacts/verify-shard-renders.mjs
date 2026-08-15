/**
 * Fail a sharded render that lost work, at the point the loss is still attributable.
 *
 * Run after `compose-preview bundle merge`, before the catalog is generated:
 *
 *   node verify-shard-renders.mjs --bundle bundle.png shard-plan-1.json shard-plan-2.json …
 *
 * `shard-preview-ids.mjs --verify` already cross-checks the shards' *plans* — same discovered set,
 * pairwise disjoint, complete cover. This checks the *outcome*, which is a different question and
 * the one that went unasked: m3-catalog run 31217598543 passed the plan check (`6 shard(s) cover
 * 1095 preview(s) exactly once`) and merged 267 of those 1095, because each shard's unanchored
 * exclusion list also deleted the `_VARIANT_` fan-out of every id it excluded (m3-catalog#15). The
 * anchored `=<id>` form (#3561) fixed that cause; nothing yet noticed the effect, and the run was
 * green.
 *
 * The glue is thin on purpose — both halves it composes are pure and unit-tested without an
 * `npm ci` (`capturedPreviewIds` in `bundle-previews.mjs`, `verifyShardRenders` in
 * `shard-preview-ids.mjs`). Only the bundle read needs the export engine, which is why this lives in
 * its own file rather than as another mode on `shard-preview-ids.mjs`: that module is deliberately
 * dependency-free so a shard job can run it without installing anything.
 */

import { existsSync, readFileSync } from "node:fs";
import { parseArgs } from "node:util";

import { readPreviewBundle, rawPreviewIdForEntry } from "@design-parity/candidate";

import { bundleCapturedSemantics, capturedPreviewIds } from "./bundle-previews.mjs";
import { noStickerPreviewNames } from "./capture-mode.mjs";
import { verifyShardRenders } from "./shard-preview-ids.mjs";

const { values, positionals } = parseArgs({
  allowPositionals: true,
  options: { bundle: { type: "string" }, spec: { type: "string" } },
});

if (!values.bundle || positionals.length === 0) {
  console.error(
    "usage: verify-shard-renders.mjs --bundle <merged-bundle.png> [--spec <catalog.spec.json>] " +
      "<shard-plan.json>…",
  );
  process.exit(2);
}

const plans = positionals.map((file) => JSON.parse(readFileSync(file, "utf8")));
const bundle = await readPreviewBundle(values.bundle);
const captured = capturedPreviewIds(bundle, rawPreviewIdForEntry);
// `--with-semantics` is best-effort: a failed daemon open leaves the pack exiting 0 with no
// semantics anywhere, and a raster-less preview then has no artifact for a reason that is not
// sharding. Tell the check, so it declines to judge rather than crying wolf.
const semanticsRan = bundleCapturedSemantics(bundle);

// The per-preview version of the same caveat. A `"capture": "none"` entry is the one class with no
// render-side artifact to fall back on (a GIF capture still leaves its `.gif`, a token sheet its
// `.catalog.json`), so an individual semantics miss leaves it looking exactly like a preview an
// exclusion ate. The spec declares them, so exempt them rather than guess. Optional and
// non-fatal: without a readable spec the check simply keeps its old, slightly noisier reading.
let exemptIds = [];
if (values.spec) {
  if (existsSync(values.spec)) {
    const noSticker = new Set(noStickerPreviewNames(JSON.parse(readFileSync(values.spec, "utf8"))));
    // Through `rawPreviewIdForEntry`, exactly as `capturedPreviewIds` does. Bundle entries are keyed
    // by a filename-safe id while shard plans carry the canonical discovery id, so an id needing
    // sanitising (a space, say) would otherwise be exempted under a name no plan mentions — the
    // exemption would silently do nothing for precisely the ids most likely to need it.
    exemptIds = bundle.previews
      .filter((preview) => noSticker.has(preview.functionName ?? preview.id))
      .map((preview) => rawPreviewIdForEntry(bundle, preview));
    if (exemptIds.length > 0) {
      console.error(
        `verify-shard-renders: ${exemptIds.length} preview(s) declared \`"capture": "none"\` are ` +
          `exempt — they export no sticker by design.`,
      );
    }
  } else {
    console.error(`verify-shard-renders: no spec at ${values.spec}; not exempting any preview.`);
  }
}

const { ok, problems, notes } = verifyShardRenders(plans, captured, { semanticsRan, exemptIds });

for (const note of notes) {
  console.error(`::warning title=Shard render check disarmed::verify-shard-renders: ${note}`);
}

if (!ok) {
  for (const problem of problems) {
    console.error(`verify-shard-renders: ${problem}`);
  }
  console.error(
    "verify-shard-renders: refusing to publish — the merged bundle is missing previews the shards " +
      "were assigned. A preview skipped by `--exclude-preview-id` comes back with no artifact at " +
      "all, so this is what an over-matching exclusion looks like: check that every emitted " +
      "exclusion is `=`-anchored and that the CLI is new enough to honour the anchor. Re-run with " +
      "`render-shards: 1` to publish while that is diagnosed.",
  );
  process.exit(1);
}

const planned = plans.reduce((n, p) => n + (p?.previews?.length ?? 0), 0);
if (notes.length === 0) {
  console.error(
    `verify-shard-renders: all ${planned} preview(s) the ${plans.length} shard(s) planned came ` +
      `back in the merged bundle`,
  );
}
