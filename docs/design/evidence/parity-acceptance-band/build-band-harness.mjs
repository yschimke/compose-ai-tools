// Build a self-contained page that runs the real `known-differences.js` bundle against a synthetic
// catalog, so the acceptance band can be photographed as it actually renders.
//
// Usage: node build-band-harness.mjs [variant] [bundlePath] [outFile]
//
// The variants are the three shapes a reader can meet, and the two beyond the default exist because
// each is reported by an *absence* the band has to notice:
//
//   band     — two acceptances, one still matching and one that has stopped. The ordinary case.
//   refused  — a duplicated id, so the engine rejects the DOCUMENT and returns no statuses at all.
//   stalled  — the render lane answers 503, so there is no pair and every acceptance comes back
//              `out-of-scope`: the same token a record authored elsewhere gets.
//
// `bundlePath` lets the same harness photograph an older bundle, which is how a before/after for a
// rendering change is produced without checking the tree out twice.
import { writeFileSync, readFileSync } from "node:fs";
import { encodePng } from "/home/user/compose-ai-tools/scripts/design-artifacts/png-write.mjs";
import { decodePng, sha256Hex } from "/home/user/compose-ai-tools/scripts/design-artifacts/png-lite.mjs";
import { resolvePlane } from "/home/user/compose-ai-tools/scripts/design-artifacts/known-difference-plane.mjs";

const WHITE = [255, 255, 255, 255];
const BLACK = [0, 0, 0, 255];
const RED = [200, 60, 60, 255];
const GREEN = [60, 170, 90, 255];

function raster(w, h, fill) {
  const pixels = new Uint8Array(w * h * 4);
  for (let i = 0; i < w * h; i++) pixels.set(fill, i * 4);
  return { width: w, height: h, pixels };
}
function fillRect(img, b, c) {
  for (let y = b.y; y < b.y + b.height; y++)
    for (let x = b.x; x < b.x + b.width; x++) img.pixels.set(c, (y * img.width + x) * 4);
  return img;
}
const png = (img) => encodePng({ width: img.width, height: img.height, samples: img.pixels });
const b64 = (bytes) => Buffer.from(bytes).toString("base64");

const GLYPH = { x: 20, y: 16, width: 16, height: 16 };
const OTHER = { x: 44, y: 16, width: 12, height: 16 };

const reference = fillRect(raster(72, 48, WHITE), GLYPH, BLACK);
fillRect(reference, OTHER, BLACK);
const candidate = fillRect(raster(72, 48, WHITE), GLYPH, RED);
fillRect(candidate, OTHER, GREEN);

const referencePng = png(reference);
const candidatePng = png(candidate);
const { plane } = resolvePlane(decodePng(referencePng), decodePng(candidatePng));

const local = (b) => ({ x: b.x - plane.box.x, y: b.y - plane.box.y, width: b.width, height: b.height });
function maskPng(box) {
  const samples = new Uint8Array(plane.box.width * plane.box.height);
  const b = local(box);
  for (let y = b.y; y < b.y + b.height; y++)
    for (let x = b.x; x < b.x + b.width; x++) samples[y * plane.box.width + x] = 255;
  return encodePng({ width: plane.box.width, height: plane.box.height, colourType: 0, samples });
}

const [VARIANT = "band", BUNDLE = "/home/user/compose-ai-tools/cli/src/main/resources/ee/schimke/composeai/cli/serve/assets/known-differences.js", OUT = "/tmp/claude-0/-home-user/a80ed2f7-16cc-5418-810d-c29ddab48aca/scratchpad/band/band.html"] =
  process.argv.slice(2);

const glyphMask = maskPng(GLYPH);
const glyphAccepted = png(raster(GLYPH.width, GLYPH.height, RED));
const otherMask = maskPng(OTHER);
// Deliberately stale: the recorded crop is what the render used to draw, and it no longer matches.
const otherAccepted = png(raster(OTHER.width, OTHER.height, [90, 90, 200, 255]));

// The REAL digest of the reference bytes this page serves, not a placeholder. The adapter checks
// what it fetched against the digest the page hands it — a catalog that republishes in place would
// otherwise let fresh metadata meet cached pixels and gate against a generation nobody scored — so
// a harness declaring a digest that describes nothing photographs the stalled band, not the band.
const REF_SHA = sha256Hex(referencePng);
const scope = {
  system: "m3",
  component: "IconButton/Tonal",
  previewId: "iconbutton-tonal__ideal__default__light",
  referenceId: "iconbutton-tonal-ideal-light",
  variant: "ideal/default/light",
  overrides: {},
  referenceSha256: REF_SHA,
  tagIndex: {},
};
const record = (id, issue, mask, accepted, note) => ({
  id,
  issue,
  ...scope,
  tagIndex: undefined,
  mask: "mask.png",
  acceptedCandidate: "accepted-candidate.png",
  referenceSha256: REF_SHA,
  maskSha256: sha256Hex(mask),
  acceptedCandidateSha256: sha256Hex(accepted),
  plane,
  candidateTolerance: 2,
  note,
  acceptedAt: "2026-08-23T00:00:00Z",
});
const strip = (r) => {
  const { tagIndex, ...rest } = r;
  return rest;
};
const acceptances = [
  strip(record("m3-iconbutton-tonal-glyph", "https://github.com/yschimke/m3-catalog/issues/40", glyphMask, glyphAccepted, "Tonal icon button draws its glyph in onSurfaceVariant.")),
  strip(record("m3-iconbutton-tonal-badge", "https://github.com/yschimke/m3-catalog/issues/41", otherMask, otherAccepted, "Badge colour, recorded before the render changed again.")),
];
// Two records under one id. The engine rejects the whole document and reports `duplicate-id`
// attributed to the FIRST spelling seen — so the failure carries an id, exactly like a per-record
// refusal, while `statuses` is absent because nothing was judged.
if (VARIANT === "refused") acceptances.push({ ...acceptances[0] });
const document = JSON.stringify(
  { schema: "compose-preview-known-differences/v1", acceptances },
  null,
  2,
);

const routes = {
  "/m3/parity/known-differences.json": { text: document },
  "/m3/reference/ref.png": { b64: b64(referencePng) },
  // A comparison whose render lane is down: no pair, so nothing can be scored and every acceptance
  // falls back to the validation-only pass.
  "/m3/render/preview.png": VARIANT === "stalled" ? { status: 503 } : { b64: b64(candidatePng) },
  "/m3/parity/known-differences/m3-iconbutton-tonal-glyph/mask.png": { b64: b64(glyphMask) },
  "/m3/parity/known-differences/m3-iconbutton-tonal-glyph/accepted-candidate.png": { b64: b64(glyphAccepted) },
  "/m3/parity/known-differences/m3-iconbutton-tonal-badge/mask.png": { b64: b64(otherMask) },
  "/m3/parity/known-differences/m3-iconbutton-tonal-badge/accepted-candidate.png": { b64: b64(otherAccepted) },
};

const css = readFileSync("/home/user/compose-ai-tools/cli/src/main/resources/ee/schimke/composeai/cli/serve/assets/serve.css", "utf8");
const bundle = readFileSync(BUNDLE, "utf8");

const context = {
  documentUrl: "/m3/parity/known-differences.json",
  artifactBase: "/m3/parity/known-differences/",
  artifactQuery: "",
  referenceUrl: "/m3/reference/ref.png",
  candidateUrl: "/m3/render/preview.png",
  scope,
};

const page = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>acceptance band</title>
<style>${css}</style>
<style>body { margin: 0; padding: 22px 26px; } .frame { max-width: 720px; }</style>
</head><body>
<div class="frame">
  <p class="cp-reference-result" role="status">96.4% structural match · 4.31% pixels changed</p>
  <div class="cp-acceptance" id="cp-acceptance" role="status" hidden></div>
</div>
<script type="application/json" id="cp-known-differences">${JSON.stringify(context)}</script>
<script>
const ROUTES = ${JSON.stringify(routes)};
window.fetch = (input) => {
  const route = ROUTES[String(input)];
  if (!route) return Promise.resolve(new Response("not found", { status: 404 }));
  if (route.status) return Promise.resolve(new Response("no", { status: route.status }));
  if (route.text) return Promise.resolve(new Response(route.text));
  const raw = atob(route.b64);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return Promise.resolve(new Response(bytes));
};
</script>
<script>${bundle}</script>
<cp-acceptance></cp-acceptance>
</body></html>`;

writeFileSync(OUT, page);
console.log(VARIANT, "->", OUT, "plane", JSON.stringify(plane));
