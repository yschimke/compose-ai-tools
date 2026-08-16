/**
 * Score a published design reference against the sticker it will be compared with, at publish time.
 *
 * The viewer has always been able to answer "how close is this render to its spec?" — but only
 * after a visitor entered the spec lane and waited for two rasters to decode and normalise. So the
 * one fact a design catalog exists to report was the one fact no page showed at rest, and the chip
 * that led to it said only "Figma". Carrying the number in `references/index.json` puts it on the
 * chip on first paint, for free, on every page that has a reference.
 *
 * ## Why this drives a browser
 *
 * The scorer is not a pixel diff. `format-compare.js` crops both sides to their content box,
 * redraws them into one shared box, converts to luma, and walks an edge-tolerant search with a
 * positional cost — so a Figma export at a different scale or padding than the render is compared
 * on what it *looks like* rather than on where its bytes happen to sit. Restating that in Node
 * would be a second implementation of a subtle algorithm, and the moment the two disagree the chip
 * contradicts the readout it links to — the worst possible failure for a number whose entire job is
 * to be trusted at a glance.
 *
 * So this loads the viewer's own asset into a blank page and calls it. There is exactly one
 * scorer, and the baked number is the number the lane would compute. Chromium is already this
 * script's rasteriser for HTML references, so it is not a new dependency — only a newly
 * unconditional one.
 *
 * ## Failure posture
 *
 * Fail-soft, like every other part of the reference lane: no browser, an undecodable pair, a
 * scorer that throws ⇒ no `match` on that record, and the reference publishes exactly as it did
 * before this file existed. The chip then shows the plain provider label and the lane computes the
 * score live on entry, which is the behaviour every published catalog has today.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * The viewer asset, read from the CLI resources it is served from — NOT a copy. A copy is the
 * thing this module exists to avoid; if this path ever stops resolving, scoring must go dark
 * rather than fall back to some other implementation of the same question.
 */
const COMPARE_ASSET = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../../cli/src/main/resources/ee/schimke/composeai/cli/serve/assets/format-compare.js",
);

/**
 * Below this a content-box proportion difference is rasteriser noise.
 *
 * MIRRORED from `cli/serve-web/src/compare/thresholds.ts`, which the ported browser surfaces share.
 * This driver runs at publish time under plain node with no build step, so it cannot import from
 * `src/` — the copy stays, and so does this pointer.
 */
const GEOMETRY_REPORT_THRESHOLD = 2;

/** A PNG file as the `data:` URL the in-page scorer loads through its own `loadImage`. */
function dataUri(file) {
  return `data:image/png;base64,${fs.readFileSync(file).toString("base64")}`;
}

/**
 * Round to `places`, as a number. The manifest is read by a chip that prints one decimal; carrying
 * seventeen significant digits would only make the published JSON churn on rasteriser noise.
 */
function round(value, places) {
  const factor = 10 ** places;
  return Math.round(value * factor) / factor;
}

/**
 * A scorer bound to one browser page, so a catalog's whole reference set is scored in a single
 * context: launching Chromium costs more than every comparison in m3-catalog put together
 * (120 pairs in ~18s against a ~1s launch).
 *
 * Construct through [openScorer]; `close()` when the run is done.
 */
class DesignReferenceScorer {
  constructor(page) {
    this.page = page;
  }

  /**
   * `{ percent, changedPercent, geometry }` for one pair, or null when it cannot be scored.
   *
   * `percent` is the structural match the lane's readout prints, `changedPercent` the share of
   * pixels the magenta delta map marks, and `geometry` the content-box proportion difference —
   * omitted below the threshold at which it is reporting the rasteriser rather than the design.
   */
  async score(referencePng, actualPng) {
    if (!fs.existsSync(referencePng) || !fs.existsSync(actualPng)) return null;
    const result = await this.page.evaluate(
      async ([reference, actual]) => {
        const api = window.ComposePreviewCompare;
        if (!api) return null;
        const images = await Promise.all([api.loadImage(reference), api.loadImage(actual)]);
        const score = await api.scoreImages(images[0], images[1]);
        // The delta map is drawn only to be counted — the count is what the readout pairs with the
        // match percentage, and a chip that carried one without the other would be reporting half
        // a comparison. The canvas is never attached to the document.
        const frames = await api.normaliseImageUrls(reference, actual);
        const changed = api.diffCanvases(
          frames.reference,
          frames.candidate,
          document.createElement("canvas"),
        );
        const pixels = frames.width * frames.height;
        return {
          percent: score.percent,
          geometry: score.geometry,
          changedPercent: pixels ? (changed * 100) / pixels : 0,
        };
      },
      [dataUri(referencePng), dataUri(actualPng)],
    );
    if (!result || !Number.isFinite(result.percent)) return null;
    return {
      percent: round(result.percent, 2),
      changedPercent: round(result.changedPercent, 2),
      ...(result.geometry >= GEOMETRY_REPORT_THRESHOLD
        ? { geometry: round(result.geometry, 1) }
        : {}),
    };
  }

  async close() {
    await this.browser?.close();
  }
}

/**
 * Open a scorer, or null when one can't be had — no Playwright, no Chromium, no viewer asset.
 *
 * Null is a normal outcome, not an error: a fork with no browser installed still publishes its
 * references, just without the baked number. The caller warns once and carries on.
 */
export async function openScorer({ executablePath, log = () => {} } = {}) {
  if (!fs.existsSync(COMPARE_ASSET)) {
    log(`cannot score references: ${path.basename(COMPARE_ASSET)} is not where this driver expects`);
    return null;
  }
  let chromium;
  try {
    ({ chromium } = await import("playwright"));
  } catch {
    log("cannot score references: playwright is not installed in this run");
    return null;
  }
  let browser;
  try {
    browser = await chromium.launch({
      headless: true,
      ...(executablePath ? { executablePath } : {}),
      args: ["--no-sandbox"],
    });
    const page = await browser.newPage();
    // A blank document, not a served page: the scorer needs a canvas and an `Image`, nothing else.
    // `format-compare.js` assigns `window.ComposePreviewCompare` before any of its page-specific
    // blocks, and every one of those is guarded on an element this document does not have — so it
    // loads here as the pure API it also is.
    await page.setContent("<!doctype html><meta charset=\"utf-8\"><title>score</title>");
    await page.addScriptTag({ content: fs.readFileSync(COMPARE_ASSET, "utf8") });
    const ready = await page.evaluate(() => typeof window.ComposePreviewCompare?.scoreImages);
    if (ready !== "function") {
      await browser.close();
      log("cannot score references: the viewer's comparison API did not load");
      return null;
    }
    const scorer = new DesignReferenceScorer(page);
    scorer.browser = browser;
    return scorer;
  } catch (error) {
    await browser?.close().catch(() => {});
    log(`cannot score references: ${error.message}`);
    return null;
  }
}

/**
 * The verdict band a match percentage falls in: `match`, `close`, or `off`.
 *
 * Restated in `ServeWeb.kt` (the chip's colour) — the thresholds live here because this is where
 * the number is minted, and they are chosen from the distribution a real catalog produces rather
 * than from round numbers. Across m3-catalog's 120 published pairs the median is 99.70% and 72 sit
 * at or above 99.5%, so `match` is the "nothing to look at" majority; the 8 below 97% are the
 * genuine divergences (a 57.98% corner-radius sheet, a 72.80% colour grid, an 85.75% type scale).
 *
 * Exported so the driver's tests can pin the bands against those same numbers.
 */
export function matchBand(percent) {
  if (!Number.isFinite(percent)) return null;
  if (percent >= 99.5) return "match";
  if (percent >= 97) return "close";
  return "off";
}
