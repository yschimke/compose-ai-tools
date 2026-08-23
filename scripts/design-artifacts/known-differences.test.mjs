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
import { mkdtempSync, readFileSync, readdirSync, existsSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { createHash } from "node:crypto";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { decodePng } from "./png-lite.mjs";
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
 * `synthesize` is how a fixture expresses a file too big to commit: append `padZerosTo - length`
 * zero bytes to the named base file. Trailing bytes after `IEND` are ignored by every decoder and
 * never reached by a preflight that stops at the first `IDAT`, so the only thing they change is the
 * encoded byte length — which is the one thing the case is about.
 */
function artifactReader(caseDir, synthesize) {
  const synthesised = new Map();
  for (const recipe of synthesize ?? []) {
    const base = readFileSync(join(caseDir, recipe.from));
    const padded = new Uint8Array(recipe.padZerosTo);
    padded.set(base, 0);
    synthesised.set(recipe.path, padded);
  }
  return (path) => {
    const relative = join("artifacts", path);
    if (synthesised.has(relative)) return synthesised.get(relative);
    const full = join(caseDir, relative);
    // A path that escapes the artifact tree is a missing file here, not a traversal: the contract
    // refuses it as `path-not-contained` long before anything is opened.
    if (!full.startsWith(join(caseDir, "artifacts"))) return null;
    if (!existsSync(full)) return null;
    return new Uint8Array(readFileSync(full));
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
    maxAcceptances: 256,
    maxPixels: 128_000_000,
    maxAxis: 8192,
    maxArtifactBytes: 8 * 1024 * 1024,
  });
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
  for (const bad of ["../x.png", "/x.png", "a\\b.png", "a#b.png", "a?b.png", "a%20b.png", "a b.png", "./x.png"]) {
    assert.equal(isSafeArtifactPath(bad), false, `\`${bad}\` must not be a safe artifact path`);
  }
  assert.equal(isSafeArtifactPath("mask.png"), true);
  assert.equal(isSafeArtifactPath("nested/mask.png"), true);
});
