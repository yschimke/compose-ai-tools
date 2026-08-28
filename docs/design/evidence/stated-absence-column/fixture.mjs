/**
 * The cross-system page for a catalog that resolves NO design reference and states why.
 *
 * The case the kit column was dropped on: `designRefById` is an empty map — the generator looked
 * and nothing resolved — while components declare `noReference`, an authored finding rather than a
 * gap. Deliberately no component here carries a resolved reference, because one that did used to be
 * the only reason the column appeared at all.
 *
 * Renders through this checkout's `render-cross-system-html.mjs` by default;
 * `CROSS_SYSTEM_RENDERER` points it at a copy pinned to a baseline commit for the before shot.
 */
import { writeFileSync } from "node:fs";

const RENDERER =
  process.env.CROSS_SYSTEM_RENDERER ??
  new URL("../../../../scripts/design-artifacts/render-cross-system-html.mjs", import.meta.url)
    .pathname;
const { renderCrossSystemHtml } = await import(RENDERER);

const swatch = (label, fill, w, h) =>
  "data:image/svg+xml;utf8," +
  encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}"><rect width="100%" height="100%" fill="${fill}"/><text x="50%" y="52%" text-anchor="middle" font-family="system-ui" font-size="18" fill="#fff">${label}</text></svg>`,
  );

const img = (o) => ({ variant: "ideal", state: "default", theme: "light", ...o });

const catalog = {
  system: "remote-m3",
  title: "Remote Compose Material 3",
  components: [
    {
      componentId: "Button/Filled",
      group: "Buttons",
      noReference: "the kit retired this button in the 2026 refresh",
      images: [img({ path: swatch("filled", "#3b5bdb", 200, 100), width: 200, height: 100 })],
    },
    {
      componentId: "Progress/Segmented",
      group: "Progress",
      noReference: "the kit exports no Segments=13 cell, so the sibling's render is the honest one",
      images: [img({ path: swatch("segmented", "#2b8a3e", 200, 100), width: 200, height: 100 })],
    },
    {
      // No stated reason: the plain gap, so the two read differently on the same page.
      componentId: "Shader/Linear",
      group: "Shaders",
      images: [img({ path: swatch("shader", "#c2255c", 200, 100), width: 200, height: 100 })],
    },
  ],
};

const otherComponents = [
  { componentId: "Button/Filled", group: "Buttons", caption: "Filled button." },
  { componentId: "Progress/Segmented", group: "Progress", caption: "Segmented progress." },
  { componentId: "Shader/Linear", group: "Shaders", caption: "Linear gradient." },
];

const otherManifest = {
  system: "wear-m3",
  components: otherComponents.map((c) => ({
    componentId: c.componentId,
    images: [img({ path: swatch("wear", "#495057", 192, 96), width: 192, height: 96 })],
  })),
};

const html = renderCrossSystemHtml(catalog, {
  parallelById: Object.fromEntries(otherComponents.map((c) => [c.componentId, c.componentId])),
  otherComponents,
  otherManifest,
  otherSystem: "wear-m3",
  otherTitle: "Wear Compose Material 3",
  repo: "yschimke/compose-ai-tools",
  // The whole point: the column is ON and nothing resolved.
  designRefById: new Map(),
});
writeFileSync(process.argv[2], html);
