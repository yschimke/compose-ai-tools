/**
 * Render the `README.md` committed into each `design-artifacts/<system>` branch.
 * It is the landing page a designer sees first on GitHub: a prominent
 * htmlpreview link to the browsable `index.html`, an at-a-glance summary, a
 * per-group component breakdown, and provenance — all derived from the same
 * in-memory `catalog` the driver already built (see generate-design-catalog.mjs).
 *
 * Pure + dependency-free: takes the catalog plus the image/wireframe counts the
 * driver computed, and returns a Markdown string. Written into `out/` next to
 * `index.html`, so the force-pushing publish step republishes it on every run —
 * the README survives branch regeneration instead of being clobbered.
 */

import { DEFAULT_PREVIEW_BASE, hasWasmTier, liveSessionUrl, wasmLiveUrl } from "./live-preview.mjs";
import { slug } from "./render-wireframe-svg.mjs";

const DEFAULT_REPO = "yschimke/compose-ai-tools";

/** Escape the few characters that would break a Markdown table cell. Backslash
 *  first, so a literal `\` in the input can't combine with the escapes we add. */
function cell(s) {
  return String(s ?? "")
    .replace(/\\/g, "\\\\")
    .replace(/\|/g, "\\|")
    .replace(/\n/g, "<br>");
}

/** Component counts per group, ordered most-populous first (stable on ties). */
function groupCounts(components) {
  const counts = new Map();
  for (const c of components) {
    const g = c.group ?? "Components";
    counts.set(g, (counts.get(g) ?? 0) + 1);
  }
  return [...counts.entries()].sort((a, b) => b[1] - a[1]);
}

/**
 * @param {object} catalog the in-memory catalog (system, title, library, …)
 * @param {object} opts { imageCount, wireframeCount, figmaSvgCount?, repo?, previewBase? }
 * @returns {string} README.md contents
 */
export function renderReadmeMd(catalog, opts = {}) {
  const repo = opts.repo ?? DEFAULT_REPO;
  const components = catalog.components ?? [];
  // buildCatalog() returns { meta, components }; writeCatalog() flattens meta
  // into catalog.json. Normalise so this works on either shape.
  const meta = catalog.meta ?? catalog;
  const system = meta.system ?? "catalog";
  const title = meta.title ?? system;
  const branch = `design-artifacts/${system}`;
  const indexUrl = `https://htmlpreview.github.io/?https://github.com/${repo}/blob/${branch}/index.html`;
  const compareUrl = `https://htmlpreview.github.io/?https://github.com/${repo}/blob/${branch}/compare.html`;
  const crossSystem = opts.crossSystem;
  const matchesUrl = `https://htmlpreview.github.io/?https://github.com/${repo}/blob/${branch}/matches.html`;
  const previewBase = opts.previewBase ?? DEFAULT_PREVIEW_BASE;
  const liveUrl = liveSessionUrl(previewBase, system);

  // Compose Multiplatform catalogs (e.g. compose-m3) also render *in the
  // browser* via Kotlin/Wasm — no server round-trip. Wear stays server-only.
  // Catalog components are keyed by `componentId` (e.g. "Button/Filled"); the
  // Wasm registry + route use its slug, so build the demo link off the slugged
  // first component id.
  const firstComponentId = components[0]?.componentId;
  const firstComponentSlug = firstComponentId ? slug(firstComponentId) : undefined;
  const wasmSection =
    hasWasmTier(system) && firstComponentSlug
      ? `
## 🌐 Run it in your browser (Kotlin/Wasm)

**[▶ Open ${cell(firstComponentId)} live in the browser](${wasmLiveUrl(previewBase, system, firstComponentSlug)})**

This catalog's \`material3\` components also compile to **Kotlin/Wasm** and run
*client-side* in the browser sandbox — no server render, interactive (toggle the
switches, drag the sliders). The preview server mounts this tier for
\`${system}\` at \`/wasm/${system}/?id=<component>\`; append \`&uiMode=dark\` for the
dark scheme.
`
      : "";

  const imageCount = opts.imageCount ?? components.reduce((n, c) => n + (c.images?.length ?? 0), 0);
  const wireframeCount = opts.wireframeCount ?? 0;
  const figmaSvgCount = opts.figmaSvgCount ?? 0;
  const greenlineCount = components.filter((c) => (c.greenlines?.length ?? 0) > 0).length;
  const library = Array.isArray(meta.library) ? meta.library : meta.library ? [meta.library] : [];
  const generated = (meta.generatedAt ?? "").slice(0, 10);
  const schema = catalog.schema ?? meta.schema ?? "design-parity-catalog/v1";
  const renderer = meta.renderer;
  const primaryLib = library[0] ?? system;

  const glance = [
    ["Components", `**${components.length}**`],
    ["Rendered images (PNG)", `**${imageCount}**`],
    ["Editable wireframes (SVG)", `**${wireframeCount}**`],
    ["Editable design vectors (figma-svg)", `**${figmaSvgCount}**`],
    ["Components with a11y greenlines", `**${greenlineCount}**`],
    ["Library", library.map((l) => `\`${l}\``).join("<br>") || "—"],
    ["Renderer", renderer ? cell(renderer) : "—"],
    ["Schema", `\`${schema}\``],
    ["Generated", generated || "—"],
  ]
    .map(([k, v]) => `| ${k} | ${v} |`)
    .join("\n");

  const groups = groupCounts(components)
    .map(([g, n]) => `| ${cell(g)} | ${n} |`)
    .join("\n");

  const crossSection = crossSystem
    ? `
## ↔ Compare across systems

**[▶ Open the ${cell(title)} ↔ ${cell(crossSystem.title)} matches (htmlpreview)](${matchesUrl})**

Every component paired with its counterpart in **${cell(crossSystem.title)}**, side by side — the
authored \`parallel\` mapping in the catalog spec, rendered as a cross-system contact sheet. Both
sides are static thumbnails — this branch's baked render on the left, the ${cell(crossSystem.system)}
render baked from its own \`design-artifacts/${cell(crossSystem.system)}\` branch on the right — and each
links to the live preview server on click.
`
    : "";

  const files = [
    [`\`index.html\``, `Self-contained gallery — [open via htmlpreview](${indexUrl})`],
    [
      `\`compare.html\``,
      `PNG↔SVG comparison with a live structural-similarity score — [open via htmlpreview](${compareUrl})`,
    ],
    ...(crossSystem
      ? [
          [
            `\`matches.html\``,
            `Cross-system component pairing vs \`${crossSystem.system}\` — [open via htmlpreview](${matchesUrl})`,
          ],
        ]
      : []),
    [
      `\`catalog.json\``,
      `Machine-readable catalog (\`${schema}\`): components, variants, design tokens, greenlines, and per-variant \`livePreview\` deep links`,
    ],
    [`\`images/\``, "Rendered PNGs — the source of truth for each variant"],
    [`\`wireframes/\``, "One editable SVG per component (layout-inspector tree → token-styled shapes)"],
  ]
    .map(([k, v]) => `| ${k} | ${v} |`)
    .join("\n");

  return `# ${title} — design artifacts

Importable sticker-sheet for **\`${primaryLib}\`**, rendered from the committed
\`@Preview\` catalog in [\`${repo}\`](https://github.com/${repo}). This branch is a
**generated delivery artifact** — browse it in the page below, or pull it into
Figma / Stitch / Claude Design.

## 🔎 Browse the catalog

**[▶ Open the rendered catalog (htmlpreview)](${indexUrl})**

A self-contained gallery — one card per component with its rendered PNG,
dimensions, accessibility greenlines, and a link to an editable SVG wireframe.

## 🔬 Compare PNG vs SVG

**[▶ Open the PNG↔SVG comparison (htmlpreview)](${compareUrl})**

Every component on one row: its rendered **PNG** beside its editable **figma-svg**
re-rasterized by the browser, plus a live **structural-similarity (SSIM)** match
score — so you can eyeball vector fidelity across the whole system at once and
spot which stickers drift. The score is pre-blurred and downscaled, so a
half-pixel rasterizer offset doesn't read as a mismatch.
${crossSection}
## 🎛 Customise live

**[▶ Open this catalog in the live preview server](${liveUrl})**

The same rendered components, served live by \`compose-preview serve --catalogs ${system}\` —
open one, then change the theme, locale, font scale, or device and watch it
re-render. Every entry in \`catalog.json\` carries a per-variant \`livePreview\`
deep link to its exact preview on the same server, so browsing this branch and
customising the live render are two ends of one workflow.
${wasmSection}
## At a glance

| | |
| --- | --- |
${glance}

## Components by group

| Group | Count |
| --- | ---: |
${groups}

## What's in this branch

| Path | What it is |
| --- | --- |
${files}

## Using it

- **Figma / Stitch / Claude Design** — import \`catalog.json\` + \`images/\` as a sticker sheet.
- **Browse** — open \`index.html\` through htmlpreview (link above), or clone the branch and open it locally.
- **Customise** — open the [live preview server](${liveUrl}) (or any image's \`livePreview\` link in \`catalog.json\`) to re-render a component under different themes / locales / devices.
- **Adopt structure** — the \`wireframes/*.svg\` are plain vector files; drop one into any editor to start from the real layout instead of tracing a screenshot.

## Provenance

Generated by the [\`Design Artifacts\`](https://github.com/${repo}/actions/workflows/design-artifacts.yml)
workflow: \`compose-preview bundle pack\` → catalog-export driver → force-push to
this branch. The render is the source of truth.

> ⚠️ **This branch is regenerated (force-pushed) from \`main\`** — weekly and after
> catalog/renderer changes. Don't commit work here by hand; it will be
> overwritten on the next run.
`;
}
