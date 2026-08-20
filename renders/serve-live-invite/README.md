# Inviting the live lane from the preview itself (#4287)

Both frames are the `serve-viewer-catalog-knobs` page fixture in light theme, captured through the
`pages-snapshot` harness (`npx playwright test -c preview-harness/playwright.config.mjs
pages-snapshot -g "serve-viewer-catalog-knobs · light"`) — `before.light.png` from `origin/main`,
`after.light.png` from this branch. Nothing else on the page moves.

| file | what to read |
| --- | --- |
| `before.light.png` | the chip reads `Snapshot` — a noun beside a status dot, which is the grammar of a readout — and the stage says nothing at all. The only route into the live lane is that chip. |
| `after.light.png` | the chip reads `Snapshot ▸ Live`, so it names where a click *goes* as well as where it is, and the stage carries the grid's own `click for live` badge over the render. A click on the picture enters the lane. |

The badge is the same `.cp-live-hint` the catalog grid overlays on its cards (`CatalogLive.ts`);
only the wording differs, because the gesture does — one click here, a long press there.

Both affordances are withdrawn (along with the chip's verb) whenever there is no live lane to
enter, an interactive lane is already painting, or a fixed-frame lane — the imported spec, the
usage source, a recorded capture — is on the stage. One predicate decides all three:
`liveInviteAvailable()` in `cli/serve-web/src/viewer/laneState.ts`.
