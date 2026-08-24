/**
 * The conformance runner for `compose-preview-known-differences/v1`.
 *
 * This is the deliverable batch 04 exists for: a committed, language-neutral fixture set plus a
 * runner that fails loudly, so batch 05's two engines have something to be measured against on day
 * one rather than a prose description to interpret. The same device already keeps
 * `parity-activity.mjs` and `ServeParityActivityStore` honest — one committed fixture, two
 * languages, both tests load it.
 *
 * **The fixtures are the contract; this file is one of its three runners.** The other two are
 * `design-parity`'s own suite and the server projector's Kotlin tests, and neither can be written
 * against a runner that quietly reinterprets the fixture tree. So everything here reads the tree the
 * way any runtime would: `case.json` for the comparison and the catalog, `known-differences.json`
 * for the document, `artifacts/<id>/…` for the rasters, `expected.json` for the verdict. No
 * JavaScript-shaped assumptions are baked into the directory layout.
 *
 * `expected.json` is a **partial** pin, and its `pins` array says which keys are normative. A key
 * listed there must match exactly; a key that is absent is not pinned by any batch *yet* — the score
 * stages (`raw` / `accepted` / `unaccepted`) are the ones batch 05 adds, over these same cases.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import {
  closeSync,
  cpSync,
  mkdirSync,
  openSync,
  readSync,
  mkdtempSync,
  symlinkSync,
  writeFileSync,
  readFileSync,
  readdirSync,
  existsSync,
  realpathSync,
  rmSync,
  statSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { createHash } from "node:crypto";
import { dirname, join, sep } from "node:path";
import { fileURLToPath } from "node:url";

import { MAX_CONFORMING_HEADER_BYTES, decodePng, padPngTo } from "./png-lite.mjs";
import {
  BUDGET,
  CANDIDATE_TOLERANCE_RANGE,
  CAUSE_ORDER,
  ELEMENT_TOLERANCE_RANGE,
  REASON_ORDER,
  enclosingBox,
  evaluateKnownDifferences,
  isSafeArtifactPath,
  isSafeId,
  issueKey,
  locallyResolvedIssues,
  parseIssue,
  resampleArea,
} from "./known-differences.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "fixtures", "known-differences");
const CASES = join(ROOT, "cases");
const RESAMPLE = join(ROOT, "resample");
const ROUNDING = join(ROOT, "rounding");

const index = JSON.parse(readFileSync(join(ROOT, "index.json"), "utf8"));

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

/**
 * Materialise a case's artifacts.
 *
 * `synthesize` is how a fixture expresses a file too big to commit: pad the named base file to
 * `padTo` bytes. The padding goes inside the compressed stream — empty stored deflate blocks and
 * zero-length `IDAT` chunks — so the artifact stays a PNG a strict decoder accepts and decodes to
 * exactly the image its base does, and the only thing it changes is the encoded byte length, which
 * is the one thing the case is about. It is `padPngTo` on both sides rather than a rule spelled
 * twice, because a recipe two runtimes materialise differently is not a recipe.
 */
function artifactReader(caseDir, synthesize) {
  const synthesised = new Map();
  for (const recipe of synthesize ?? []) {
    const base = new Uint8Array(readFileSync(join(caseDir, recipe.from)));
    synthesised.set(recipe.path, padPngTo(base, recipe.padTo));
  }
  // A case with no artifacts at all has no `artifacts/` directory, so the root is resolved leniently:
  // every path beneath a root that does not exist is a missing file, which is what the reader
  // reports anyway.
  const artifactsDir = join(caseDir, "artifacts");
  const root = existsSync(artifactsDir) ? realpathSync(artifactsDir) : artifactsDir;
  return (path, options) => {
    // **The prefix is served, not simulated.** Slicing a `readFileSync` would satisfy every
    // assertion in this file while allocating exactly the bytes the prefix exists to avoid, and the
    // one place that is observable is here — the same reason the byte cap is asserted on the reader
    // rather than on the verdict. `openSync` + `readSync` reads `prefix` bytes and no more, and the
    // full size comes from the `stat` that was already being taken for the cap.
    const prefix = options?.prefix;
    // **POSIX-separated, deliberately.** `join` would emit backslashes on Windows, and this string is
    // used three ways that all assume `/`: as the key into the synthesised map (whose keys come from
    // `case.json`, which is POSIX by definition), as the argument to `join` below (which normalises
    // separators itself, so it does not need them), and as the exact-case comparison, which splits on
    // `/`. Building it with `join` broke all three on Windows at once — the synthesised lookup missed
    // every recipe, so the byte-cap fixtures read a file that is not there and failed as
    // `artifact-unreadable` instead of testing the boundary they exist for.
    const relative = `artifacts/${path}`;
    // The cap applies to synthesised bytes too. Exempting them would leave the reader's own bound
    // untested — the only case big enough to reach it is the synthesised one — and the module's
    // length check would quietly stand in for it, which is a fixture passing for the wrong reason.
    if (synthesised.has(relative)) {
      const bytes = synthesised.get(relative);
      if (bytes.length > BUDGET.maxArtifactBytes) return { error: "artifact-too-large" };
      // Synthesised bytes are already in memory, so there is nothing to avoid allocating — but the
      // *answer* still has to have the shape a prefix read promises, or the byte-cap fixtures would
      // be the only ones handing the evaluator a whole file where every other case hands it a
      // prefix, and the difference would be invisible.
      return prefix === undefined
        ? bytes
        : { bytes: bytes.subarray(0, prefix), byteLength: bytes.length };
    }
    const full = join(caseDir, relative);
    if (!existsSync(full)) return null;
    // **The reader discharges the two obligations the grammar cannot.** Containment is resolved, not
    // lexical — a symlink inside an acceptance directory is exactly what a segment check cannot
    // see — and the size is taken from a `stat` so an oversized file is refused *before* it is
    // allocated, rather than measured after being read into memory.
    const resolved = realpathSync(full);
    // **Contained in *this acceptance's* directory, not merely somewhere under the root.** A symlink
    // at `artifacts/a/link` pointing into `artifacts/b` resolves inside the global root, so a
    // root-only check lets acceptance `a` read `b`'s bytes — and then the exact-case check below
    // reports it as `artifact-unreadable`, where the contract says `path-not-contained`. The bound is
    // the fixed `<root>/<id>/` the path is addressed against.
    //
    // **The base is resolved too, and the comparison is case-sensitive.** Both halves matter, and an
    // earlier revision got this wrong in a way worth recording: it compared the two paths
    // *case-folded*, to stop a wrongly-cased `<id>` — which has not escaped anything — being
    // reported here instead of by the exact-case check below. That works, and it also folds together
    // two genuinely different directories on a case-sensitive host. `/tmp/A/…/artifacts/id` and
    // `/tmp/a/…/artifacts/id` are not the same tree, and a symlink into the parallel one passed
    // containment — while the exact-case check could not catch it either, since that compares only
    // the `artifacts/<id>/<path>` *suffix* and the difference is in an ancestor.
    //
    // Resolving the base gets both properties without folding anything: on a case-insensitive host
    // `realpath` reports the committed spelling for the requested `<id>` whatever case it was asked
    // for, so the wrongly-cased request is *contained* here and falls through to the exact-case check
    // that owns it; on a case-sensitive host the two ancestors resolve to different strings and the
    // escape is refused. `realpath` is doing the normalising, which is what it is for.
    const requestedAcceptance = join(root, path.split("/")[0]);
    const acceptance = existsSync(requestedAcceptance)
      ? realpathSync(requestedAcceptance)
      : requestedAcceptance;
    const within = (target, base) => target === base || target.startsWith(base + sep);
    if (!within(resolved, acceptance)) {
      return { error: "path-not-contained" };
    }
    // **Exact case, the reader's third obligation.** On a case-insensitive filesystem `MASK.png`
    // opens the committed `mask.png`, so this runner would evaluate a record a Linux checkout
    // reports as `artifact-unreadable` — the divergence would be in the *runner*, invisible to
    // every fixture. `realpath` reports the on-disk spelling, so comparing it against the requested
    // one is the check, and it costs nothing where the filesystem is already case-sensitive.
    //
    // **Which also means CI does not exercise it.** On the Linux runner `MASK.png` simply does not
    // exist, so `artifact-unreadable-case-differs` passes with or without this line; the guard earns
    // its keep only on a macOS or Windows checkout, where its absence would make this runner
    // disagree with CI. Recorded rather than claimed as covered.
    if (!resolved.endsWith(sep + relative.split("/").join(sep))) return null;
    const stats = statSync(resolved);
    // Contained, correctly spelled, and still not an artifact — a directory is not bytes. It failed
    // at the open, like any other contained path that cannot be read, so it takes that token rather
    // than containment's: containment is exactly what it did *not* fail.
    if (!stats.isFile()) return null;
    if (stats.size > BUDGET.maxArtifactBytes) return { error: "artifact-too-large" };
    if (prefix === undefined) return new Uint8Array(readFileSync(resolved));
    const buffer = Buffer.alloc(Math.min(prefix, stats.size));
    const handle = openSync(resolved, "r");
    try {
      let read = 0;
      // `readSync` may return short of the buffer without being at EOF, so it is looped rather than
      // called once — a partial first read would otherwise hand back a prefix of the prefix and turn
      // a legal palette artifact into `header-invalid` on whichever platform happened to do it.
      while (read < buffer.length) {
        const n = readSync(handle, buffer, read, buffer.length - read, read);
        if (n === 0) break;
        read += n;
      }
      return { bytes: new Uint8Array(buffer.subarray(0, read)), byteLength: stats.size };
    } finally {
      closeSync(handle);
    }
  };
}

/**
 * Decode the comparison's canonical-plane rasters.
 *
 * `case.json` names them by filename rather than embedding them, which is what keeps the tree
 * language-neutral: every runtime resolves the name against the case directory and decodes it with
 * whatever it has. They arrive at the evaluator **already resampled** — the portable kernel is
 * pinned by its own fixture group, so a resampler divergence fails there rather than surfacing here
 * as a wrong verdict.
 */
function withCanonicalRasters(caseDir, comparison) {
  if (!comparison) return null;
  const load = (name) =>
    name ? decodePng(new Uint8Array(readFileSync(join(caseDir, name)))) : null;
  return {
    ...comparison,
    canonicalReference: load(comparison.canonicalReference),
    canonicalCandidate: load(comparison.canonicalCandidate),
  };
}

const caseIds = readdirSync(CASES).sort();

test("the committed tree still matches its recipe", () => {
  // The fixtures are generated, and "generated" is only true while something checks it. A
  // hand-edited case would otherwise survive indefinitely, pinning bytes nobody can re-derive —
  // which is exactly the state the other two runners cannot audit from their own repositories.
  const scratch = mkdtempSync(join(tmpdir(), "known-differences-"));
  try {
    execFileSync(process.execPath, [join(dirname(fileURLToPath(import.meta.url)), "build-known-difference-fixtures.mjs")], {
      env: { ...process.env, KNOWN_DIFFERENCE_FIXTURE_ROOT: scratch },
      stdio: "ignore",
    });
    assert.deepEqual(digestTree(scratch), digestTree(ROOT));
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

function digestTree(root) {
  const entries = [];
  const walk = (relative) => {
    for (const name of readdirSync(join(root, relative), { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      const next = relative ? `${relative}/${name.name}` : name.name;
      if (name.isDirectory()) walk(next);
      else entries.push(`${next} ${createHash("sha256").update(readFileSync(join(root, next))).digest("hex")}`);
    }
  };
  walk("");
  return entries;
}

test("every case directory is listed in index.json, and vice versa", () => {
  assert.deepEqual(caseIds, index.cases.map((entry) => entry.id).sort());
  assert.deepEqual(
    readdirSync(RESAMPLE).sort(),
    index.resample.map((entry) => entry.id).sort(),
  );
  assert.deepEqual(
    readdirSync(ROUNDING).sort(),
    index.rounding.map((entry) => entry.id).sort(),
  );
  assert.deepEqual(
    readdirSync(join(ROOT, "scoring")).sort(),
    index.scoring.map((entry) => entry.id).sort(),
  );
  assert.deepEqual(
    readdirSync(join(ROOT, "plane")).sort(),
    index.plane.map((entry) => entry.id).sort(),
  );
  assert.deepEqual(
    readdirSync(join(ROOT, "tag-projection")).sort(),
    index.tagProjection.map((entry) => entry.id).sort(),
  );
});

for (const id of caseIds) {
  const caseDir = join(CASES, id);
  const meta = readJson(join(caseDir, "case.json"));
  const expected = readJson(join(caseDir, "expected.json"));

  test(`conformance: ${id} — ${meta.title}`, () => {
    const documentText = readFileSync(join(caseDir, "known-differences.json"), "utf8");
    const result = evaluateKnownDifferences({
      documentText,
      readArtifact: artifactReader(caseDir, meta.synthesize),
      comparison: withCanonicalRasters(caseDir, meta.comparison),
      catalog: meta.catalog,
    });

    for (const pin of expected.pins) {
      switch (pin) {
        case "statuses":
          assert.deepEqual(result.statuses, expected.statuses);
          break;
        case "statusesAbsent":
          assert.equal(
            result.statuses === undefined,
            expected.statusesAbsent,
            "`statuses` is absent entirely for a document-level rejection — 'no acceptance was " +
              "evaluated' and 'every acceptance was valid' must not serialise the same way",
          );
          break;
        case "validationFailures":
          assert.deepEqual(result.validationFailures, expected.validationFailures);
          break;
        case "survivingMaskIds":
          // The seam between the gates and the score. `survivingMasks` is the union
          // `known-difference-score.mjs` suppresses, and "survivor" means status `valid` — not
          // "reached the end of the gates", which is the reading that leaves a `resolved` mask
          // suppressing its neighbours. Pinned as ids here and as pixels in the `scoring/` group,
          // so neither fixture asserts the other's half.
          assert.deepEqual(
            result.survivingMasks.map((entry) => entry.id),
            expected.survivingMaskIds,
          );
          break;
        case "validationFailureCount":
          assert.equal(result.validationFailures.length, expected.validationFailureCount);
          break;
        case "statusCounts": {
          const counts = {};
          for (const entry of Object.values(result.statuses ?? {})) {
            counts[entry.status] = (counts[entry.status] ?? 0) + 1;
          }
          assert.deepEqual(counts, expected.statusCounts);
          break;
        }
        case "locallyResolvedIssues": {
          const records = JSON.parse(documentText).acceptances;
          assert.deepEqual(locallyResolvedIssues(records, result.statuses), expected.locallyResolvedIssues);
          break;
        }
        default:
          assert.fail(`unknown pin \`${pin}\` in ${id}/expected.json`);
      }
    }
  });
}

for (const id of readdirSync(RESAMPLE).sort()) {
  const dir = join(RESAMPLE, id);
  const meta = readJson(join(dir, "case.json"));
  const expected = readJson(join(dir, "expected.json"));

  test(`resample: ${id} — ${meta.title}`, () => {
    const source = decodePng(new Uint8Array(readFileSync(join(dir, "source.png"))));
    const out = resampleArea(source, meta.target.width, meta.target.height);
    assert.equal(out.width, expected.width);
    assert.equal(out.height, expected.height);
    const actual = [];
    for (let i = 0; i < expected.pixels.length; i++) actual.push([...out.pixels.subarray(i * 4, i * 4 + 4)]);
    assert.deepEqual(actual, expected.pixels);
  });
}

for (const id of readdirSync(ROUNDING).sort()) {
  const dir = join(ROUNDING, id);
  const meta = readJson(join(dir, "case.json"));
  const expected = readJson(join(dir, "expected.json"));

  test(`rounding: ${id} — ${meta.title}`, () => {
    assert.deepEqual(enclosingBox(meta.box), expected);
  });
}

// -----------------------------------------------------------------------------------------------
// Properties the fixture tree cannot express, because they are about the *result structure* rather
// than about any one document.
// -----------------------------------------------------------------------------------------------

test("`statuses` never reaches the prototype, even for a reserved id", () => {
  const documentText = readFileSync(
    join(CASES, "id-not-safe-proto", "known-differences.json"),
    "utf8",
  );
  const meta = readJson(join(CASES, "id-not-safe-proto", "case.json"));
  const result = evaluateKnownDifferences({
    documentText,
    readArtifact: artifactReader(join(CASES, "id-not-safe-proto"), meta.synthesize),
    comparison: withCanonicalRasters(join(CASES, "id-not-safe-proto"), meta.comparison),
  });
  assert.ok(Object.hasOwn(result.statuses, "__proto__"), "the id must be an own property");
  assert.equal({}.polluted, undefined);
  assert.equal(Object.getPrototypeOf(result.statuses.__proto__), Object.prototype);
});

test("an over-budget document stops reading artifacts, and nothing is retained across the split", () => {
  // The one observable half of the preflight's resource contract. An allocation bound is not
  // expressible as a verdict — a compression bomb and an honest oversize give the same
  // `header-invalid` — but *how many artifacts were fetched* runs through the injected seam, so the
  // short-circuit can be asserted directly.
  const reads = [];
  const readArtifact = (path) => {
    reads.push(path);
    const file = join(CASES, "pilot-40-iconbutton-tonal-glyph", "artifacts", path);
    return existsSync(file) ? new Uint8Array(readFileSync(file)) : null;
  };

  // Well past the axis cap on the very first record, so the document is doomed before the second is
  // reached — and every later artifact must go unread. `document-axis-over-cap` already ships a mask
  // whose header declares 8193 px, which is what makes this cheap to state.
  const overDir = join(CASES, "document-axis-over-cap");
  const overRecord = JSON.parse(readFileSync(join(overDir, "known-differences.json"), "utf8")).acceptances[0];
  const overReads = [];
  const overBudget = evaluateKnownDifferences({
    documentText: JSON.stringify({
      schema: "compose-preview-known-differences/v1",
      acceptances: [0, 1, 2, 3].map((i) => ({ ...overRecord, id: `over-${i}` })),
    }),
    readArtifact: (path) => {
      overReads.push(path);
      const file = join(overDir, "artifacts", path.replace(/^over-\d+\//, "m3-iconbutton-tonal-glyph/"));
      return existsSync(file) ? new Uint8Array(readFileSync(file)) : null;
    },
    comparison: null,
  });
  assert.equal(overBudget.statuses, undefined, "the document is rejected");
  assert.deepEqual(overBudget.validationFailures, [{ reason: "document-too-large" }]);
  assert.deepEqual(
    [...new Set(overReads.map((path) => path.split("/")[0]))],
    ["over-0"],
    "only the first record's artifacts are ever fetched — the rest are never read at all",
  );

  // And a document rejected on *identity* fetches nothing at all: the verdict is reached before the
  // preflight loop starts, so every artifact read would be discarded.
  const identityReads = [];
  const duplicated = evaluateKnownDifferences({
    documentText: JSON.stringify({
      schema: "compose-preview-known-differences/v1",
      acceptances: [overRecord, { ...overRecord }],
    }),
    readArtifact: (path) => {
      identityReads.push(path);
      return null;
    },
    comparison: null,
  });
  assert.deepEqual(duplicated.validationFailures, [
    { id: overRecord.id, reason: "duplicate-id" },
  ]);
  assert.deepEqual(identityReads, [], "a document rejected on identity reads no artifacts");

  // And the happy path reads each artifact exactly twice — once for the header preflight, once to
  // hash and decode — never retaining the bytes of one record while another is preflighted.
  evaluateKnownDifferences({
    documentText: readFileSync(
      join(CASES, "pilot-40-iconbutton-tonal-glyph", "known-differences.json"),
      "utf8",
    ),
    readArtifact,
    comparison: null,
  });
  const counts = new Map();
  for (const path of reads) counts.set(path, (counts.get(path) ?? 0) + 1);
  assert.deepEqual([...counts.values()], [2, 2], "two artifacts, each read once per phase");
});

test("an artifact that changes between the two reads is refused, not trusted", () => {
  // Not expressible as a fixture: the tree would have to hand out different bytes on the second
  // read. `readArtifact` is the seam that makes it testable, and the case is real — the reader may
  // be network-backed, or the tree may move under a long evaluation. Checking only presence and
  // hashes on the re-read would let an artifact that grew past the byte cap, or whose header now
  // declares an over-budget raster, walk through caps applied to bytes nobody decodes any more.
  const caseDir = join(CASES, "pilot-40-iconbutton-tonal-glyph");
  const meta = readJson(join(caseDir, "case.json"));
  const documentText = readFileSync(join(caseDir, "known-differences.json"), "utf8");
  const honest = artifactReader(caseDir, meta.synthesize);

  const swapped = readJson(join(CASES, "dimension-mismatch-mask-against-plane", "case.json"));
  const otherMask = new Uint8Array(
    readFileSync(
      join(CASES, "dimension-mismatch-mask-against-plane", "artifacts", "m3-iconbutton-tonal-glyph", "mask.png"),
    ),
  );
  assert.ok(swapped, "the 20x20 mask from the dimension-mismatch case stands in for a changed file");

  let masksRead = 0;
  const unstable = (path) => {
    if (path.endsWith("mask.png") && ++masksRead === 2) return otherMask;
    return honest(path);
  };

  const result = evaluateKnownDifferences({
    documentText,
    readArtifact: unstable,
    comparison: withCanonicalRasters(caseDir, meta.comparison),
  });
  assert.deepEqual(result.statuses, {
    "m3-iconbutton-tonal-glyph": { status: "refused", reasons: ["artifact-unreadable"] },
  });
});

test("the reader's own refusals reach the result as their proper tokens", () => {
  // The two obligations the lexical grammar cannot discharge: a resolved path that leaves the root,
  // and a file too large to allocate. Only the reader knows either before the bytes exist, so it
  // reports them — and this asserts they arrive as `path-not-contained` and `artifact-too-large`
  // rather than being flattened into "could not read it".
  const caseDir = join(CASES, "pilot-40-iconbutton-tonal-glyph");
  const meta = readJson(join(caseDir, "case.json"));
  const documentText = readFileSync(join(caseDir, "known-differences.json"), "utf8");
  const honest = artifactReader(caseDir, meta.synthesize);

  for (const token of ["path-not-contained", "artifact-too-large"]) {
    const result = evaluateKnownDifferences({
      documentText,
      readArtifact: (path) => (path.endsWith("mask.png") ? { error: token } : honest(path)),
      comparison: withCanonicalRasters(caseDir, meta.comparison),
    });
    assert.deepEqual(result.statuses, {
      "m3-iconbutton-tonal-glyph": { status: "refused", reasons: [token] },
    });
  }

  // A token the reader is not entitled to establish is not trusted into the result.
  const invented = evaluateKnownDifferences({
    documentText,
    readArtifact: (path) => (path.endsWith("mask.png") ? { error: "valid" } : honest(path)),
    comparison: withCanonicalRasters(caseDir, meta.comparison),
  });
  assert.deepEqual(invented.statuses, {
    "m3-iconbutton-tonal-glyph": { status: "refused", reasons: ["artifact-unreadable"] },
  });
});

test("a reader that ignores the prefix reaches the same verdict on every case", () => {
  // **The prefix is a resource optimisation, never a verdict.** `{ prefix: N }` is a request, and a
  // host may not be able to honour it — the browser host in `cli/serve-web/` fetches whole artifacts
  // and hands them straight over, because `readArtifact` is synchronous and its prefetch cannot know
  // which records will survive the preflight. Left to the reader alone that host would walk a chunk
  // the prefix stops at and reach `decode-failed` where this runner reaches `header-invalid`: one
  // contract, two engines, different answers on the same bytes — the exact divergence this whole
  // document exists to prevent, reintroduced by the mechanism meant to bound a read.
  //
  // So the engine caps the header pass's view itself, and this asserts the property that buys: every
  // committed case reaches its pinned verdict through a reader that ignores the option entirely.
  // Caught in review rather than here, which is why it is a whole-tree sweep and not two cases.
  const wholeFileReader = (caseDir, synthesize) => {
    const honest = artifactReader(caseDir, synthesize);
    return (path) => {
      // No second argument passed on, so the reader takes its whole-file branch — the shape a host
      // that predates the option, or cannot range-request, would return for both passes.
      const answer = honest(path);
      return answer;
    };
  };

  for (const id of caseIds) {
    const caseDir = join(CASES, id);
    const meta = readJson(join(caseDir, "case.json"));
    const expected = readJson(join(caseDir, "expected.json"));
    const result = evaluateKnownDifferences({
      documentText: readFileSync(join(caseDir, "known-differences.json"), "utf8"),
      readArtifact: wholeFileReader(caseDir, meta.synthesize),
      comparison: withCanonicalRasters(caseDir, meta.comparison),
      catalog: meta.catalog,
    });
    if (expected.pins.includes("statuses")) {
      assert.deepEqual(result.statuses, expected.statuses, `${id} diverges without a prefix read`);
    }
    if (expected.pins.includes("validationFailures")) {
      assert.deepEqual(
        result.validationFailures,
        expected.validationFailures,
        `${id} diverges without a prefix read`,
      );
    }
  }
});

test("a prefix answer is judged on the artifact's length, not on how much of it was served", () => {
  // The reason `{ prefix: N }` can coexist with an 8 MiB cap at all: the reader reports the size of
  // the *whole* file alongside the bytes it served, so the cap is still enforced on the artifact.
  // Not expressible as a fixture, and for a specific reason — the reference reader discharges its own
  // obligation and refuses an oversized file before this module ever sees it, so the module's
  // backstop is only reachable through a reader that reported past the cap anyway. Left untested it
  // was measurable: rewriting the check to read `bytes.length` instead of `byteLength` passed all
  // 183 cases, which is a cap that has quietly stopped applying to every prefix read.
  const caseDir = join(CASES, "pilot-40-iconbutton-tonal-glyph");
  const meta = readJson(join(caseDir, "case.json"));
  const documentText = readFileSync(join(caseDir, "known-differences.json"), "utf8");
  const honest = artifactReader(caseDir, meta.synthesize);

  // Reported on **both** passes, deliberately. `samePreflight` compares every header field, and the
  // reader's `byteLength` is one of them — so a reader that claims one size for the header pass and
  // another for the decode pass is an artifact that changed between the two reads, which is
  // `artifact-unreadable` before any of this is reached. Faking it on one pass alone would test that
  // rule over again instead of the cap.
  const withReportedSize = (byteLength) => (path, options) => {
    const answer = honest(path, options);
    const bytes = answer instanceof Uint8Array ? answer : answer?.bytes;
    if (!path.endsWith("mask.png") || !bytes) return answer;
    return { bytes, byteLength };
  };

  // **Counted, not just asserted on the verdict.** The token alone does not pin this: a check that
  // reads `bytes.length` in the header pass still refuses the record in the *decode* pass, with the
  // same `artifact-too-large`, so the mutation survives an assertion on `statuses`. What differs is
  // the phase — and therefore whether the oversized raster was charged against the pixel budget
  // before being refused. A record refused in the header pass is never re-read, so the read count is
  // where the phase is observable.
  const reads = [];
  const counting = (byteLength) => (path, options) => {
    reads.push(path);
    return withReportedSize(byteLength)(path, options);
  };

  const over = evaluateKnownDifferences({
    documentText,
    readArtifact: counting(BUDGET.maxArtifactBytes + 1),
    comparison: withCanonicalRasters(caseDir, meta.comparison),
  });
  assert.deepEqual(over.statuses, {
    "m3-iconbutton-tonal-glyph": { status: "refused", reasons: ["artifact-too-large"] },
  });
  assert.equal(reads.length, 2, "refused in the header pass, so neither artifact is read a second time");

  // Inclusive, like every other ceiling in the budget: exactly the cap is legal.
  const exactly = evaluateKnownDifferences({
    documentText,
    readArtifact: withReportedSize(BUDGET.maxArtifactBytes),
    comparison: withCanonicalRasters(caseDir, meta.comparison),
  });
  assert.deepEqual(exactly.statuses, {
    "m3-iconbutton-tonal-glyph": { status: "valid" },
  });

  // A reader claiming the file is smaller than the bytes it just served is not describing anything,
  // and trusting it is how a prefix answer would walk past the cap by understating the artifact.
  // `artifact-unreadable` rather than `artifact-too-large`: nothing about the size was established.
  for (const byteLength of [0, -1, 1.5, "8", null]) {
    const nonsense = evaluateKnownDifferences({
      documentText,
      readArtifact: withReportedSize(byteLength),
      comparison: withCanonicalRasters(caseDir, meta.comparison),
    });
    assert.deepEqual(
      nonsense.statuses,
      { "m3-iconbutton-tonal-glyph": { status: "refused", reasons: ["artifact-unreadable"] } },
      `a byteLength of ${byteLength} establishes nothing`,
    );
  }
});

test("the reference reader refuses an oversized artifact without handing back its bytes", () => {
  // Asserted on the reader directly, because it is not distinguishable by *verdict*: the module's
  // own length check produces the same `artifact-too-large` either way. What differs is whether the
  // bytes were ever materialised, and the only place that is observable is the reader's return
  // value. Same shape as the compression-bound: the guard is a resource property, so it is tested
  // where the resource is, not through a fixture.
  const caseDir = join(CASES, "artifact-too-large");
  const meta = readJson(join(caseDir, "case.json"));
  const read = artifactReader(caseDir, meta.synthesize);
  assert.deepEqual(read("m3-iconbutton-tonal-glyph/mask.png"), { error: "artifact-too-large" });
  assert.ok(
    read("m3-iconbutton-tonal-glyph/accepted-candidate.png") instanceof Uint8Array,
    "the artifact inside the cap still comes back as bytes",
  );
});

test("the reference reader discovers an escape that no path grammar can see", () => {
  // **The one obligation the committed tree cannot express.** Every `path-not-contained` fixture is
  // refused *lexically* — `..`, an absolute path, a backslash, a reserved segment — so an engine
  // that validates the grammar and never resolves the path passes all of them, and then follows a
  // symlink straight out of the acceptance it was reading. That is the case containment exists for,
  // and a committed symlink cannot state it: git materialises one as a text file wherever the
  // checkout has no symlink support, which would turn this into a `header-invalid` on Windows.
  //
  // So it is built here, against the real reader, with real `realpath` resolution. Two escapes: one
  // into a *sibling acceptance* (inside the artifacts root, which a root-only containment check
  // wrongly admits) and one out of the root entirely.
  const scratch = mkdtempSync(join(tmpdir(), "known-differences-symlink-"));
  try {
    const artifacts = join(scratch, "artifacts");
    mkdirSync(join(artifacts, "a"), { recursive: true });
    mkdirSync(join(artifacts, "b"), { recursive: true });
    const target = join(artifacts, "b", "mask.png");
    writeFileSync(target, Buffer.from([137, 80, 78, 71]));
    const outside = join(scratch, "outside.png");
    writeFileSync(outside, Buffer.from([137, 80, 78, 71]));

    let supported = true;
    try {
      symlinkSync(target, join(artifacts, "a", "sibling.png"));
      symlinkSync(outside, join(artifacts, "a", "outside.png"));
    } catch {
      // A host without permission to create symlinks (unprivileged Windows) cannot run this.
      supported = false;
    }
    if (!supported) return;

    const read = artifactReader(scratch, []);
    assert.deepEqual(
      read("a/sibling.png"),
      { error: "path-not-contained" },
      "a symlink into another acceptance is contained in the root and still an escape",
    );
    // **An ancestor differing only by case is a different tree, on a case-sensitive host.** This is
    // the escape a case-folded containment check admits: the resolved path and the acceptance base
    // fold to the same string, and the exact-case check cannot see it either, because that compares
    // only the `artifacts/<id>/<path>` suffix — which matches exactly. Skipped where the filesystem
    // cannot hold both spellings, since there the two names *are* one directory and nothing escaped.
    const parallel = join(scratch, "..", `${scratch.split(sep).pop().toUpperCase()}`);
    let parallelIsDistinct = false;
    try {
      mkdirSync(join(parallel, "artifacts", "a"), { recursive: true });
      writeFileSync(join(parallel, "artifacts", "a", "mask.png"), Buffer.from([137, 80, 78, 71]));
      parallelIsDistinct = realpathSync(parallel) !== realpathSync(scratch);
    } catch {
      parallelIsDistinct = false;
    }
    if (parallelIsDistinct) {
      symlinkSync(join(parallel, "artifacts", "a", "mask.png"), join(artifacts, "a", "parallel.png"));
      assert.deepEqual(
        read("a/parallel.png"),
        { error: "path-not-contained" },
        "an ancestor differing only in case is a different tree, not a spelling of this one",
      );
      rmSync(parallel, { recursive: true, force: true });
    }
    assert.deepEqual(
      read("a/outside.png"),
      { error: "path-not-contained" },
      "a symlink out of the artifacts root is an escape",
    );
    // The control: the same reader, the same directory, an ordinary file.
    writeFileSync(join(artifacts, "a", "own.png"), Buffer.from([137, 80, 78, 71]));
    assert.ok(
      read("a/own.png") instanceof Uint8Array,
      "an ordinary file beside the symlinks still reads",
    );
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test("the reason and cause orderings are the ones the contract lists", () => {
  // Pinned here rather than only implicitly through the fixtures: an ordering nobody asserts is an
  // ordering two engines can serialise differently while every single-token case still passes.
  assert.equal(REASON_ORDER[0], "document-unreadable");
  assert.equal(REASON_ORDER[REASON_ORDER.length - 1], "acceptance-is-noop");
  assert.equal(new Set(REASON_ORDER).size, REASON_ORDER.length);
  assert.deepEqual(CAUSE_ORDER, [
    "reference-changed",
    "plane-changed",
    "candidate-changed",
    "element-ambiguous",
    "element-moved",
  ]);
});

test("the budget constants are the ones `v1` names", () => {
  assert.deepEqual(BUDGET, {
    maxDocumentBytes: 1024 * 1024,
    maxAcceptances: 256,
    maxPixels: 128_000_000,
    maxAxis: 8192,
    maxArtifactBytes: 8 * 1024 * 1024,
    maxPreflightBytes: 4096,
  });
  // The prefix is a fixed constant because two engines choosing their own would disagree exactly on
  // the files that put the most in front of their image data. This asserts the number *and* the
  // property that licenses it: it is chosen with room to spare over what a conforming header can
  // occupy, not fitted to whatever the fixtures happen to contain.
  assert.equal(MAX_CONFORMING_HEADER_BYTES, 1089);
  assert.ok(BUDGET.maxPreflightBytes > MAX_CONFORMING_HEADER_BYTES);
  assert.deepEqual(CANDIDATE_TOLERANCE_RANGE, [0, 8]);
  assert.deepEqual(ELEMENT_TOLERANCE_RANGE, [0, 0.25]);
});

test("the JSON Schema and the module agree on every number `v1` fixes", () => {
  // The schema pins the document's shape and the module pins its verdicts, and the two carry the
  // same constants in two places — which is one place too many unless something checks it. A schema
  // that let a 257th acceptance or a tolerance of 9 through would put the two consumers of this
  // repo's contract on different ceilings.
  const schema = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), "known-differences.schema.json"), "utf8"));
  assert.equal(schema.properties.acceptances.maxItems, BUDGET.maxAcceptances);
  const acceptance = schema.$defs.acceptance.properties;
  assert.equal(acceptance.candidateTolerance.minimum, CANDIDATE_TOLERANCE_RANGE[0]);
  assert.equal(acceptance.candidateTolerance.maximum, CANDIDATE_TOLERANCE_RANGE[1]);
  assert.equal(acceptance.candidateTolerance.type, "integer");
  const tolerance = schema.$defs.element.properties.tolerance;
  assert.equal(tolerance.minimum, ELEMENT_TOLERANCE_RANGE[0]);
  assert.equal(tolerance.maximum, ELEMENT_TOLERANCE_RANGE[1]);
  assert.equal(schema.properties.schema.const, JSON.parse(readFileSync(join(ROOT, "index.json"), "utf8")).schema);
});

test("an issue arriving in several spellings is one group", () => {
  const spellings = [
    "https://github.com/yschimke/m3-catalog/issues/42",
    "https://www.github.com/YSchimke/m3-catalog/issues/42/",
    "http://github.com/yschimke/m3-catalog/issues/42#issuecomment-9",
  ];
  const keys = new Set(spellings.map((url) => issueKey(parseIssue(url))));
  assert.deepEqual([...keys], ["yschimke/m3-catalog#42"]);
  assert.equal(parseIssue("https://github.com/yschimke/m3-catalog/pull/42"), null);

  // Percent-encoding and host case are two more spellings of one issue. A regex over the raw string
  // keys them separately, which lets one subset look independently resolved.
  assert.equal(
    issueKey(parseIssue("https://GitHub.com/%79schimke/m3-catalog/issues/42")),
    "yschimke/m3-catalog#42",
  );
  assert.equal(parseIssue("https://github.com.evil.test/yschimke/m3-catalog/issues/42"), null);
  assert.equal(parseIssue("https://github.com/yschimke/m3-catalog/issues/0"), null);
});

test("ids and artifact paths refuse the shapes the contract names", () => {
  for (const bad of ["__proto__", "constructor", "prototype", ".", "..", "a/b", "a b", "a\\b", ""]) {
    assert.equal(isSafeId(bad), false, `\`${bad}\` must not be a safe id`);
  }
  assert.equal(isSafeId("m3-iconbutton-tonal-glyph"), true);
  // Only *canonical integers* are the map-key hazard, so only they are refused.
  for (const bad of ["0", "10", "-3"]) {
    assert.equal(isSafeId(bad), false, `\`${bad}\` is a canonical integer and must not be a safe id`);
  }
  // `NaN` and `Infinity` round-trip through `Number` unchanged, which an earlier
  // `String(Number(id)) !== id` spelling mistook for integer-like. Neither is an array-index
  // property nor a reserved key, and a leading-zero spelling is not canonical either.
  for (const fine of ["NaN", "Infinity", "-Infinity", "007", "2024-fix", "1e3", "1.5"]) {
    assert.equal(isSafeId(fine), true, `\`${fine}\` is not a canonical integer and must be a safe id`);
  }
  for (const bad of ["../x.png", "/x.png", "a\\b.png", "a#b.png", "a?b.png", "a%20b.png", "a b.png", "./x.png"]) {
    assert.equal(isSafeArtifactPath(bad), false, `\`${bad}\` must not be a safe artifact path`);
  }
  assert.equal(isSafeArtifactPath("mask.png"), true);
  assert.equal(isSafeArtifactPath("nested/mask.png"), true);
});
