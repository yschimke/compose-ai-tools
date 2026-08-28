import { writeFileSync } from "node:fs";
import { renderCrossSystemHtml } from "/home/user/compose-ai-tools/scripts/design-artifacts/render-cross-system-html.mjs";

/** A flat coloured PNG-ish swatch as an inline SVG data URI, labelled so the pick is visible. */
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
      componentId: "Home/List",
      group: "Screens",
      images: [
        img({ path: swatch("large 240", "#3b5bdb", 240, 120), size: "largeRound", width: 240, height: 120 }),
        img({ path: swatch("small 192", "#2b8a3e", 192, 96), size: "smallRound", width: 192, height: 96 }),
      ],
      variants: [
        // Distinguished by `select` alone.
        { preview: "HomeListViewPreview", select: { size: "smallRound" }, parallel: "Home" },
      ],
    },
    {
      componentId: "Button/Filled",
      group: "Buttons",
      images: [
        img({ path: swatch("pressed small", "#c2255c", 192, 96), state: "pressed", props: { size: "small" }, width: 192, height: 96 }),
        img({ path: swatch("pressed large", "#e8590c", 240, 120), state: "pressed", props: { size: "large" }, width: 240, height: 120 }),
      ],
      variants: [
        // Two variants sharing a state, differing only by props.
        { preview: "ButtonPressedSmall", state: "pressed", props: { size: "small" }, parallel: "Button/Filled" },
        { preview: "ButtonPressedLarge", state: "pressed", props: { size: "large" }, parallel: "Button/Filled" },
        // A stated absence with an authored reason.
        {
          preview: "ButtonTextless",
          state: "textless",
          parallel: "Button/Filled",
          noReference: "the kit exports no Text=No cell",
        },
      ],
    },
  ],
};

const otherComponents = [
  { componentId: "Home", group: "Screens", caption: "Home list." },
  { componentId: "Button/Filled", group: "Buttons", caption: "Filled button." },
];

const otherManifest = {
  system: "wear-m3",
  components: [
    { componentId: "Home", images: [img({ path: swatch("wear home", "#495057", 192, 96), width: 192, height: 96 })] },
    { componentId: "Button/Filled", images: [img({ path: swatch("wear button", "#495057", 192, 96), width: 192, height: 96 })] },
  ],
};

const html = renderCrossSystemHtml(catalog, {
  parallelById: { "Home/List": "Home", "Button/Filled": "Button/Filled" },
  otherComponents,
  otherManifest,
  otherSystem: "wear-m3",
  otherTitle: "Wear Compose Material 3",
  repo: "yschimke/compose-ai-tools",
  designRefById: new Map([
    ["Home/List", { url: swatch("kit", "#868e96", 192, 96), from: "kit" }],
    ["Button/Filled", { url: swatch("kit", "#868e96", 192, 96), from: "kit" }],
  ]),
});
writeFileSync(process.argv[2], html);
