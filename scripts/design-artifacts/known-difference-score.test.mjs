/**
 * The conformance runner for the separated-plane score — batch 05's half of
 * `compose-preview-known-differences/v1`.
 *
 * Split from `known-differences.test.mjs` for the same reason the fixtures are: the gate cases are
 * handed canonical planes and no source rasters, so they have nothing to score, and the scoring
 * cases start from a *given* surviving union rather than re-deriving it. A divergence then fails at
 * the stage that caused it, which is the whole point of paying for intermediate pins at all.
 *
 * As in the gate suite, the fixture tree is read the way any runtime would read it — `case.json` for
 * the geometry and the masks, `expected.json` for the verdict and which of its keys are normative —
 * so `design-parity`'s suite and the server's Kotlin tests can be written against it unchanged.
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import { decodePng } from "./png-lite.mjs";
import { SCORE_TUNING } from "./known-difference-tuning.mjs";
import { REGIONS, scoreComparison } from "./known-difference-score.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const SCORING = join(HERE, "fixtures", "known-differences", "scoring");

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function readPng(path) {
  return decodePng(new Uint8Array(readFileSync(path)));
}

for (const id of readdirSync(SCORING).sort()) {
  const dir = join(SCORING, id);
  const meta = readJson(join(dir, "case.json"));
  const expected = readJson(join(dir, "expected.json"));

  test(`scoring: ${id} — ${meta.title}`, () => {
    const result = scoreComparison({
      reference: readPng(join(dir, meta.reference)),
      candidate: readPng(join(dir, meta.candidate)),
      referenceBox: meta.referenceBox,
      candidateBox: meta.candidateBox,
      plane: meta.plane,
      masks: meta.masks.map((path) => readPng(join(dir, path))),
    });

    for (const pin of expected.pins) {
      switch (pin) {
        case "scorePlane":
          assert.deepEqual(result.stages.plane, expected.scorePlane);
          break;
        case "presence": {
          // The **scored** set: present on both sides. A coordinate one side did not draw into has
          // nothing to compare against, and the two disagree only where a footprint straddles the
          // region boundary on one side alone.
          const counts = {};
          for (const region of REGIONS) counts[region] = scoredCount(result, region);
          assert.deepEqual(counts, expected.presence);
          break;
        }
        case "samples":
          for (const sample of expected.samples) {
            const plane = result.stages.regions[sample.region][sample.side];
            const index = sample.y * plane.width + sample.x;
            assert.equal(
              Boolean(plane.present[index]),
              sample.present,
              `${sample.region}/${sample.side} (${sample.x},${sample.y}) presence`,
            );
            if (!sample.present) continue;
            assert.deepEqual(
              [...plane.pixels.subarray(index * 4, index * 4 + 4)],
              sample.rgba,
              `${sample.region}/${sample.side} (${sample.x},${sample.y}) pixels`,
            );
          }
          break;
        case "scores":
          for (const [key, value] of Object.entries(expected.scores)) {
            // `epsilon` rather than an exact compare: a luminance is a float dot product, so an
            // engine agreeing on the algorithm lands within a double's rounding of the declared
            // decimal. An engine *disagreeing* about the algorithm misses by orders of magnitude
            // more than this, which is what keeps the tolerance from hiding anything.
            assert.ok(
              Math.abs(result[key] - value) <= expected.epsilon,
              `${key}: expected ${value}, got ${result[key]}`,
            );
          }
          break;
        case "rawEqualsUnaccepted":
          // Bit for bit, not within epsilon. I6 is an identity of stages, and "close enough" is
          // exactly the reading that lets a shortcut path for `raw` survive.
          assert.equal(result.raw === result.unaccepted, expected.rawEqualsUnaccepted);
          break;
        default:
          assert.fail(`unknown pin \`${pin}\` in scoring/${id}/expected.json`);
      }
    }
  });
}

function scoredCount(result, region) {
  const planes = result.stages.regions[region];
  let count = 0;
  for (let i = 0; i < planes.reference.present.length; i++) {
    if (planes.reference.present[i] && planes.candidate.present[i]) count++;
  }
  return count;
}

// -----------------------------------------------------------------------------------------------
// The mirror. Not expressible as a fixture, because it is about two files agreeing rather than about
// any comparison.
// -----------------------------------------------------------------------------------------------

test("the offline tuning constants mirror the browser's", () => {
  // `cli/serve-web/src/scorer/tuning.ts` is what the live scorer imports and where each number's
  // rationale is written down; `known-difference-tuning.mjs` is what the offline engines read. Every
  // one of them is load-bearing to the number that comes out, so a value changed on one side and not
  // the other is a silent divergence between the browser and the offline run — the exact failure
  // "two engines, one semantics" exists to prevent, and one no fixture can catch, since both engines
  // would be measured against expectations generated with their own constants.
  const source = readFileSync(join(HERE, "..", "..", "cli", "serve-web", "src", "scorer", "tuning.ts"), "utf8");
  const numberOf = (name) => {
    const match = new RegExp(`export const ${name}\\s*=\\s*(-?[0-9.]+)`).exec(source);
    assert.ok(match, `tuning.ts no longer exports ${name}`);
    return Number(match[1]);
  };
  for (const name of [
    "MAX_SIDE",
    "EDGE_SEARCH_RADIUS",
    "EDGE_POSITION_COST",
    "EDGE_GRADIENT_THRESHOLD",
    "LUMA_TOLERANCE",
    "FULL_DIFFERENCE_DELTA",
    "CONTENT_DILATION",
  ]) {
    assert.equal(numberOf(name), SCORE_TUNING[name], `${name} disagrees with tuning.ts`);
  }

  // The grounds are CSS strings there and RGB triples here, so they are compared by colour rather
  // than by spelling — the offline engine has no canvas to hand a string to.
  const grounds = /COMPARISON_GROUNDS[^=]*=\s*\[([^\]]*)\]/.exec(source);
  assert.ok(grounds, "tuning.ts no longer exports COMPARISON_GROUNDS");
  const hexes = [...grounds[1].matchAll(/#([0-9a-fA-F]{6})/g)].map(([, hex]) => [
    Number.parseInt(hex.slice(0, 2), 16),
    Number.parseInt(hex.slice(2, 4), 16),
    Number.parseInt(hex.slice(4, 6), 16),
  ]);
  assert.deepEqual(hexes, SCORE_TUNING.COMPARISON_GROUNDS);
});
