/**
 * Write a published catalog's `parity/findings.json` — the verdict behind the preview server's
 * **Design parity** panel on every focused comparison.
 *
 *     node emit-parity-findings.mjs --out <bundle dir> --repo <repo root> \
 *       [--design-map design-map.json] [--spec catalog.spec.json] \
 *       [--parity-branch design-parity/<system>] [--source-repo owner/name] [--strict]
 *
 * `--out` is the staged bundle the workflow is about to publish to `design-artifacts/<system>`;
 * this adds one file and leaves the rest alone. Safe to run unconditionally: a repo whose parity
 * run has never published a branch simply writes nothing, and its comparisons serve exactly as they
 * did before the panel existed.
 *
 * ## Why this exists as a publish step
 *
 * The findings are produced by a design-parity run, which publishes them to its OWN branch
 * (`design-parity/<system>`, beside the per-component reports) keyed by the ids a run knows: the
 * fully-qualified compose preview id and the design-map code handle. The compare page routes on
 * neither — it routes on the catalog's sticker id, which is minted here, at publish. So the join
 * happens here, in the one job that holds both the run's output and the catalog it will be shown
 * against. See `parity-findings.mjs` for the re-keying rules.
 *
 * The same shape as `emit-parity-activity.mjs` next door, and for the same reason: the serve host
 * has no checkout and cannot read another branch.
 *
 * It also runs after `emit-design-references.mjs`, and now depends on that ordering for a second
 * reason: a finding's reference-side anchor is measured in the space the design tool captured, and
 * the reference step publishes the raster onto the sticker's canvas. The transform it recorded in
 * `references/index.json` is read here and applied to those anchors (#4696).
 *
 * ## Failure posture
 *
 * Fail-soft, matching its neighbours and the server's own reader. No parity branch, an unreadable
 * `findings.json`, a component with no published reference — each is a `::warning::` and the rest
 * publishes, because a verdict panel is an enhancement and must never cost a catalog its render.
 * `--strict` turns any skipped record into a non-zero exit for a repo that wants the panel gated.
 */
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

import { stripComments } from "./catalog-spec.mjs";
import {
  REFERENCES_DIR,
  planDesignReferences,
  referenceTransforms,
} from "./design-references.mjs";
import {
  FINDINGS_DIR,
  FINDINGS_FILE,
  RUN_FINDINGS_FILE,
  RUN_MANIFEST_FILE,
  buildServedFindings,
} from "./parity-findings.mjs";

function arg(name, def = undefined) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : def;
}

const OUT = arg("out");
const REPO = path.resolve(arg("repo", "."));
const DESIGN_MAP = arg("design-map", "design-map.json");
const SPEC = arg("spec", "catalog.spec.json");
const SOURCE_REPO = arg("source-repo", process.env.GITHUB_REPOSITORY || "");
const STRICT = process.argv.includes("--strict");

if (!OUT) {
  console.error("emit-parity-findings: --out <bundle dir> is required");
  process.exit(2);
}

let skipped = 0;
const warn = (message) => {
  skipped += 1;
  console.log(`::warning::parity-findings: ${message}`);
};

const readJson = (file) => JSON.parse(fs.readFileSync(file, "utf8"));

const catalogPath = path.join(OUT, "catalog.json");
if (!fs.existsSync(catalogPath)) {
  console.error(`parity-findings: ${catalogPath} is missing — run after the catalog export`);
  process.exit(2);
}
const catalog = readJson(catalogPath);

const designMapPath = path.resolve(REPO, DESIGN_MAP);
if (!fs.existsSync(designMapPath)) {
  console.log("parity-findings: no design-map.json — nothing to publish");
  process.exit(0);
}
const designMap = readJson(designMapPath);

const specPath = path.resolve(REPO, SPEC);
const spec = fs.existsSync(specPath)
  ? JSON.parse(stripComments(fs.readFileSync(specPath, "utf8")))
  : null;

/**
 * The branch the run published to. Defaults to `design-parity/<system>`, mirroring the
 * `design-artifacts/<system>` branch this bundle is headed for — the two are per-system twins, so
 * a catalog that renders several systems out of one repo keeps their verdicts apart.
 */
const system = catalog?.system ?? catalog?.meta?.system ?? "";
const PARITY_BRANCH = arg("parity-branch", system ? `design-parity/${system}` : "");
if (!PARITY_BRANCH) {
  console.log("parity-findings: no parity branch to read — nothing to publish");
  process.exit(0);
}

/**
 * Read one file out of the parity branch without checking it out.
 *
 * `git show <ref>:<path>` against the fetched remote ref: the branch is a sibling of the one this
 * job is standing on, and a checkout would cost the working tree the catalog was just built in.
 * Every failure here is expected and normal — a repo that has never run parity, a fork with no
 * access to fetch, a shallow clone — so it is a silent null and the caller warns once.
 */
function showFromBranch(ref, file) {
  try {
    return execFileSync("git", ["show", `${ref}:${file}`], {
      cwd: REPO,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
      maxBuffer: 64 * 1024 * 1024,
    });
  } catch {
    return null;
  }
}

// The branch is not in a shallow clone by default; fetch it before reading. Best-effort for the
// same reasons the activity lane's deepen is: a private caller checked out without credentials
// cannot fetch, and the panel is then simply absent.
for (const ref of [`refs/remotes/origin/${PARITY_BRANCH}`, PARITY_BRANCH]) {
  if (showFromBranch(ref, RUN_MANIFEST_FILE)) break;
  try {
    execFileSync("git", ["fetch", "--depth=1", "origin", `+${PARITY_BRANCH}:${ref}`], {
      cwd: REPO,
      stdio: "ignore",
    });
    break;
  } catch {
    // Try the next spelling, then give up below.
  }
}

const ref =
  [`refs/remotes/origin/${PARITY_BRANCH}`, PARITY_BRANCH].find((candidate) =>
    showFromBranch(candidate, RUN_MANIFEST_FILE),
  ) ?? null;

if (!ref) {
  console.log(`parity-findings: no ${PARITY_BRANCH} branch to read — nothing to publish`);
  process.exit(0);
}

const parse = (name) => {
  const raw = showFromBranch(ref, name);
  if (raw === null) return null;
  try {
    return JSON.parse(raw);
  } catch (error) {
    warn(`${PARITY_BRANCH}:${name} is not readable JSON (${error.message})`);
    return null;
  }
};

const runManifest = parse(RUN_MANIFEST_FILE);
const runFindings = parse(RUN_FINDINGS_FILE);
if (!runManifest || !runFindings) {
  // A run that found nothing publishes no `findings.json` at all, which is the common and correct
  // case for a catalog at parity — not a warning.
  //
  // `skipped` separates that from the other way to arrive here: `parse` warned because the file IS
  // there and is not readable JSON. Absent is normal and exits 0 even under `--strict`; unreadable
  // is exactly what a caller who asked for the panel to be gated wants to hear about.
  console.log(`parity-findings: ${PARITY_BRANCH} publishes no findings — nothing to publish`);
  process.exit(STRICT && skipped > 0 ? 1 : 0);
}

// The same planner that mints `references/index.json`, so a verdict is keyed to exactly the
// reference ids the compare page will offer. Its warnings belong to the reference step, which has
// already reported them; only the join's own drops are reported here.
const { records } = planDesignReferences({ designMap, spec, catalog });

/**
 * What the reference step did to each reference's pixels, read back out of the manifest it just
 * wrote a few steps up in the same job.
 *
 * A run's reference-side anchors are boxes in the space the design tool's adapter captured; a
 * reference this export resampled, reduced or letterboxed is published in a different one, and the
 * highlight then sits off the element the finding names (#4696). Read rather than recomputed
 * because only the step that moved the pixels knows how far it moved them — the plan above says
 * what a reference SHOULD be, not what the raster turned out to need.
 *
 * Absent or unreadable ⇒ an empty map, which is the identity: the same anchors this published
 * before the transform existed, never a guessed one.
 */
let transforms = new Map();
const referenceIndex = path.join(OUT, REFERENCES_DIR, "index.json");
if (fs.existsSync(referenceIndex)) {
  try {
    transforms = referenceTransforms(readJson(referenceIndex));
  } catch (error) {
    warn(
      `${REFERENCES_DIR}/index.json is not readable (${error.message}); anchors are published ` +
        `in the space the run captured them in`,
    );
  }
}

const { document, warnings, mapped } = buildServedFindings({
  runManifest,
  runFindings,
  references: records,
  referenceTransforms: transforms,
  repoSlug: SOURCE_REPO,
  branch: PARITY_BRANCH,
});
for (const message of warnings) warn(message);

if (!document) {
  console.log("parity-findings: no verdict reached a published comparison — nothing to publish");
  process.exit(STRICT && skipped > 0 ? 1 : 0);
}

const dir = path.join(OUT, FINDINGS_DIR);
fs.mkdirSync(dir, { recursive: true });
fs.writeFileSync(path.join(dir, FINDINGS_FILE), `${JSON.stringify(document, null, 2)}\n`);
console.log(
  `parity-findings: published ${Object.keys(document.previews).length} comparison(s) ` +
    `from ${mapped} mapping(s) on ${PARITY_BRANCH}`,
);

if (STRICT && skipped > 0) {
  console.error(`parity-findings: --strict and ${skipped} record(s) were skipped`);
  process.exit(1);
}
