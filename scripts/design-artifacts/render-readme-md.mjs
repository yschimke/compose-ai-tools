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

import { DEFAULT_PREVIEW_BASE, liveSessionUrl } from "./live-preview.mjs";

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
 * @param {object} opts { imageCount, wireframeCount, repo?, previewBase? }
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
  const previewBase = opts.previewBase ?? DEFAULT_PREVIEW_BASE;
  const liveUrl = liveSessionUrl(previewBase, system);

  const imageCount = opts.imageCount ?? components.reduce((n, c) => n + (c.images?.length ?? 0), 0);
  const wireframeCount = opts.wireframeCount ?? 0;
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

  const files = [
    [`\`index.html\``, `Self-contained gallery — [open via htmlpreview](${indexUrl})`],
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

## 🎛 Customise live

**[▶ Open this catalog in the live preview server](${liveUrl})**

The same rendered components, served live by \`compose-preview serve --catalogs ${system}\` —
open one, then change the theme, locale, font scale, or device and watch it
re-render. Every entry in \`catalog.json\` carries a per-variant \`livePreview\`
deep link to its exact preview on the same server, so browsing this branch and
customising the live render are two ends of one workflow.

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
