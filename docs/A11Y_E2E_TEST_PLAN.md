# A11y subscription end-to-end test plan

## Goal

Automate the two preview scenarios that PR 1 (`subscription-driven a11y`) was
verified against manually, so future regressions in the chip → daemon →
attachment → webview chain surface in CI without a human looking at the
panel.

## What PR 1 wired

Clicking an Accessibility chip in the focus inspector posts
`setDataExtensionEnabled` to the host. The host:

1. Sends `data/subscribe` to the daemon via `DaemonScheduler.setDataProductSubscription`.
2. Fires a fast-tier `renderNow` for the preview (PR 1d's gap-bridge).
3. Daemon's `JsonRpcServer.encodeRenderPayload` injects `mode=a11y` into the
   payload because the preview is subscribed.
4. Renderer flips `LocalInspectionMode = false` and writes ATF + hierarchy
   artefacts.
5. `renderFinished` fires; `attachmentsFor` ships the subscribed kinds.
6. `daemonScheduler.handleRenderFinished` forwards via `onDataProductsAttached`.
7. `extension.ts` decodes payloads and posts `updateA11y` to the panel.
8. `webview/preview/applyA11yUpdate` mutates the per-card cache; `<preview-card>`'s
   `_repaintA11yOverlaysFromCache` paints `.a11y-overlay` (findings) +
   `.a11y-hierarchy-overlay` (translucent boxes from nodes).

There are five hand-off points where a regression can hide; today we only
catch four of them indirectly via unit tests.

## Scenarios to automate

### Scenario A — "hierarchy boxes paint for a clean preview"

- **Sample**: `samples:wear` `ActivityListPreview` (`Devices - Large Round`).
- **Click**: focus inspector "Accessibility hierarchy" chip ON.
- **Expected daemon-stderr lines**: `mode=a11y`, `phase=a11y.done findings=0
  nodes=12`, `phase=complete`, `[renderFinished] ... attachments=[a11y/hierarchy]`.
- **Expected host-channel lines**: `[daemon] onDataProductsAttached ...
  kinds=[a11y/hierarchy]`, `[daemon] decoded a11y for ... findings=<none>
  nodes=12`.
- **Expected webview state**: `.a11y-hierarchy-overlay` element exists under
  the `ActivityListPreview` card with 12 child boxes.

### Scenario B — "findings legend + red box paints for a deliberately broken preview"

- **Sample**: `samples:wear` `BadWearButtonPreview` (`Devices - Small Round`).
- **Click**: "Accessibility findings" chip ON.
- **Expected daemon-stderr**: `mode=a11y`, `phase=a11y.done findings=1
  nodes=1`, `[renderFinished] ... attachments=[a11y/atf]`.
- **Expected host-channel**: `onDataProductsAttached ... kinds=[a11y/atf]`,
  `decoded a11y for ... findings=1 nodes=<none>`.
- **Expected webview state**: `.a11y-legend` element with `.a11y-row` children
  matching the finding count (1, level=ERROR, type
  `SpeakableTextPresentCheck`); `.a11y-overlay` element with 1 finding box
  (`.a11y-box.a11y-level-error`).

### Scenario C (regression-only) — "toggling chip OFF tears down the layer"

- After A, click "Accessibility hierarchy" chip OFF.
- **Expected host-channel**: no extra daemon traffic logged.
- **Expected webview state**: `.a11y-hierarchy-overlay` is **removed** from
  the card (or empty), driven by `applyA11yUpdate`'s nodes-empty branch.

## Test harness — extending what's there

The existing `vscode-extension/src/test/electron/suite/e2e.test.ts` runs
against `samples:cmp` through real Gradle. It boots the extension host,
reveals the panel, runs `triggerRefresh`, and asserts on `setPreviews`
messages. The pieces it gives us for free:

- `ComposePreviewTestApi` — exported from `extension.ts`, exposes
  `injectGradleApi`, `triggerRefresh`, `getReceivedMessages`,
  `getPostedMessages`, `resetMessages`.
- A spawned extension host with a real `vscode` API.
- The output channel logs are accessible via the API or via reading the
  `Compose Preview` channel.

What the new tests need that's not there yet:

1. **Wear sample seeded into the e2e fixture set.** Today only `samples:cmp`
   is wired into the test workspace. The wear sample needs to be added to the
   workspace folders that the test opens (or the test needs to switch to a
   workspace that already contains it — the repo root is a candidate, since
   `samples/wear` is part of the build).
2. **A way to drive a chip click without DevTools.** Two options:
   - **Direct host call** — extend `ComposePreviewTestApi` with
     `triggerSetDataExtensionEnabled(previewId, kind, enabled)`. Bypasses the
     webview button click but exercises the same handler chain
     (`handleSetDataExtensionEnabled` → `setDataProductSubscription` → daemon).
     Cheap, deterministic; good enough to catch wire-side regressions.
   - **Webview-message simulation** — call `panel.postMessage` (or whatever
     the test API exposes) with `{command: "setDataExtensionEnabled",
     previewId, kind, enabled: true}`. Slightly closer to the real path; same
     handler.
   The first option is simpler — pick that.
3. **Daemon profile.** The daemon's a11y extension is registered as inactive
   metadata; clients flip it on via `extensions/enable`. The Gradle / CLI
   path mirrors that with the `composeai.a11y.enabled` sysprop forwarded
   from `previewExtensions.a11y { enableAllChecks() }` (or the
   `composePreview.previewExtensions.a11y.enableAllChecks` property the
   CLI's `compose-preview a11y` and `--with-extension a11y` flags set). The
   real-Gradle test uses `RealGradleApi`; the wear module's
   `daemon-launch.json` is regenerated by the plugin and the test should
   pass the opt-in property explicitly when it wants ATF artefacts.
4. **Output-channel assertion helpers.** `vscode.OutputChannel` doesn't
   expose its buffered lines directly to a test. Two routes:
   - Inject a recording logger via the test API (extend
     `ComposePreviewTestApi.injectLogger` analogously to `injectGradleApi`)
     and assert on the captured lines.
   - Subscribe to the daemon-events callbacks directly via the API and assert
     on their payloads instead of stringly-typed log lines. Cleaner; doesn't
     depend on log-line wording surviving.
5. **Webview-DOM assertion access.** The webview runs in a separate context
   from the extension host. Two routes:
   - Use `vscode.window.activeWebviewPanel`'s `webview.html` snapshot or
     `postMessage`/round-trip a "give me your DOM" probe. Hacky.
   - Have the webview post `webviewA11yState` messages whenever
     `applyA11yUpdate` mutates the cache (mirrors the existing
     `webviewPreviewsRendered` ack), asserting on those instead of DOM.
     Cleaner.

## Implementation order

1. **`ComposePreviewTestApi.triggerSetDataExtensionEnabled` + ack channel.**
   Tiny extension to extension.ts. Lets us drive chip toggles deterministically.
2. **Wear sample in the e2e workspace.** Open `:samples:wear/.../Previews.kt`
   instead of `:samples:cmp/.../Previews.kt` for the new tests.
3. **Webview ack messages.** Have `applyA11yUpdate` post a `webviewA11yState`
   summarising `(previewId, findingsCount, nodesCount)` after every successful
   update. The test API records inbound messages already; assert on those.
4. **Scenario A test.** `setDataExtensionEnabled(activityListId, "a11y/hierarchy", true)`
   → `waitFor` a `webviewA11yState` with `nodesCount: 12`. Pass/fail.
5. **Scenario B test.** Same but for BadWearButton + `a11y/atf` + `findingsCount: 1`.
6. **Scenario C test.** `setDataExtensionEnabled(..., enabled: false)` →
   `webviewA11yState` with `nodesCount: 0`.

## What this catches

- Wire regressions: `extensions/list+enable` ordering (#9a7b2e1b),
  `ExtensionsEnableResult` shape mismatch (#ac02d680), `RenderFinishedParams.dataProducts`
  serialization drops, etc.
- Daemon regressions: mode injection (#c6a0c6f8), runAccessibility resolution
  (#337abb13), attachmentsFor reading from disk, registry capability advertising.
- Host regressions: subscribe-fire-and-forget plus follow-up renderNow ordering
  (#b1f0777a), data-product decode + post.
- Webview regressions: chip teardown (#75a47381), legend/overlay paint, cache
  mutations.

## Out of scope (deferred follow-ups)

- The hierarchy-driven labelled legend (per-node label + role list, color-matched
  swatches) — see todo "Hierarchy-driven *named* legend".
- The `Accessibility overlay` chip displaying the daemon-rendered overlay PNG
  in the panel — see todo "Wire 'Accessibility overlay' chip ...".
- Visual-diff testing (compare the painted overlay against a baseline PNG).

## Where this plugs into existing CI

`.github/workflows/vscode-extension-e2e.yml` already runs the
`COMPOSE_PREVIEW_E2E=1` suite on `main` and on workflow_dispatch. The new
tests live in the same suite (`describeE2E` block) so they ride along when
the e2e workflow runs. No new workflow needed.
