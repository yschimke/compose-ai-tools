# Live-lane frame counters on `/status` (#4281)

`serve-status` page fixture captured through the `pages-snapshot` harness
(`HARNESS_FIXTURE=serve-status npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot -g "serve-status"`),
`before.light.png` from `origin/main` and `after.light.png` from this branch. Nothing else on the
page moves.

| file | what to read |
| --- | --- |
| `before.light.png` | the summary grid runs `Active streams: 2` straight into `Theme optimiser gate`. Two sockets are open; the page says nothing about what either of them is achieving, because streamed frames never pass through `ServeRenderHost.render` and so never reach `renderStats`. |
| `after.light.png` | a `LIVE FRAMES` card sits beside it: `4.0 fps · p50 250ms · 1042 painted · 388 unchanged · 8 kB/frame`. The fps is derived from the median gap between painted frames — what a viewer got, not what the frame loop asked for — and the painted/heartbeat split says whether a low reading is the render loop or the idle backoff doing its job. |

The card is rendered from `LiveFramePerfSnapshot` (`liveFrameText` in `ServeHttpServer`), the same
object `/status.json` publishes as `liveFrames`; it is absent entirely until a live socket has
opened, so a server nobody has streamed from looks exactly as it did before.
