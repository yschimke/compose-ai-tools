// Regenerates `notifications.json` from real renders of the Android sample. Mirrors the
// `a11y-wear.gen.mjs` pattern: read the rendered PNGs off `samples/android/build/compose-previews`,
// base64-encode them, emit a `setPreviews` + per-preview `updateImage` envelope.
//
// Usage:
//   node esbuild.webview.mjs                              # rebuild if you've edited webview src
//   ./gradlew :samples:android:renderPreviews            # ensure renders exist
//   node vscode-extension/preview-harness/fixtures/notifications.gen.mjs
//
// The harness uses the resulting JSON to drive the real `<preview-app>` Lit element headlessly —
// see `preview-harness/snapshot.mjs`. Add new entries here when you add a notification surface to
// the Android sample that you want covered by the panel's design contract.

import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const rendersDir = resolve(
  here,
  "..",
  "..",
  "..",
  "samples",
  "android",
  "build",
  "compose-previews",
  "renders",
);

// Four representative entries — one from each axis the panel needs to render correctly. Keeping
// the list short keeps the fixture file under ~200K of base64; expand only when a new design
// contract surface needs coverage.
//
// `idSuffix` mirrors the real `PreviewInfo.id` discovery emits. For unnamed `@Preview` the suffix
// is just the function name; `@Preview(name = "…")` appends `_<name>` UNSANITIZED (spaces and all)
// — only the `renderOutput` filename gets path-sanitised. Keeping the fixture id in sync with
// what the real manifest carries matters because panel logic (focus restore, history,
// data-product addressing, stale markers) keys off this id; a divergent fixture id passes
// silently but misses bugs that would regress real previews. Codex flagged the previous shape
// (#1266 review): `MessagingStylePreview` etc. were missing the `_<name>` suffix the real
// `NotificationStyleGallery.kt` produces.
const entries = [
  {
    idSuffix: "simpleNotificationPreview",
    fn: "simpleNotificationPreview",
    cls: "NotificationPreviewsKt",
    png: "NotificationPreviewsKt.simpleNotificationPreview.png",
    label: "@NotificationPreview · simple",
  },
  {
    idSuffix: "BigTextVariantsPreview_Arabic",
    fn: "BigTextVariantsPreview",
    cls: "NotificationVariantPreviewsKt",
    png: "NotificationVariantPreviewsKt.BigTextVariantsPreview_Arabic.png",
    label: "Variants · Arabic (RTL)",
  },
  {
    // `@Preview(name = "Messaging style")` — id keeps the space, only the PNG filename sanitises.
    idSuffix: "MessagingStylePreview_Messaging style",
    fn: "MessagingStylePreview",
    cls: "NotificationStyleGalleryKt",
    png: "NotificationStyleGalleryKt.MessagingStylePreview_Messaging_style.png",
    label: "Gallery · MessagingStyle",
  },
  {
    idSuffix: "ActionsPreview_Actions",
    fn: "ActionsPreview",
    cls: "NotificationStyleGalleryKt",
    png: "NotificationStyleGalleryKt.ActionsPreview_Actions.png",
    label: "Gallery · Actions",
  },
];

const previews = entries.map((e) => ({
  id: `com.example.sampleandroid.${e.cls}.${e.idSuffix}`,
  functionName: e.fn,
  className: `com.example.sampleandroid.${e.cls}`,
  sourceFile: `${e.cls.replace("Kt", "")}.kt`,
  params: {
    name: e.label,
    device: null,
    widthDp: 0,
    heightDp: 0,
    fontScale: 1.0,
    showSystemUi: false,
    showBackground: false,
    backgroundColor: 0,
    uiMode: 0,
    locale: null,
    group: null,
  },
  captures: [
    {
      advanceTimeMillis: null,
      scroll: null,
      renderOutput: `renders/${e.png}`,
      label: "",
    },
  ],
}));

const updateImages = entries.map((e) => ({
  command: "updateImage",
  previewId: `com.example.sampleandroid.${e.cls}.${e.idSuffix}`,
  captureIndex: 0,
  imageData: readFileSync(resolve(rendersDir, e.png)).toString("base64"),
}));

const fixture = {
  name: "notifications",
  description:
    "Notification preview surfaces in the panel: collapsed notification (`@NotificationPreview`), a localised + RTL multi-preview variant, MessagingStyle conversation, and a notification with an action button row. Confirms the panel renders notification-shaped previews identically to composable ones — there's no `PreviewKind`-aware path in the extension.",
  dataset: {
    earlyFeatures: "false",
    minimalMode: "false",
  },
  messages: [
    {
      command: "setPreviews",
      moduleDir: "/workspace/samples/android",
      heavyStaleIds: [],
      previews,
    },
    ...updateImages,
  ],
};

writeFileSync(
  resolve(here, "notifications.json"),
  JSON.stringify(fixture, null, 4) + "\n",
);
console.log(`Wrote notifications.json — ${previews.length} previews`);
