# Record-live capture (the Trailblaze bridge)

Live recording mode (`recording/start { live: true }` + `recording/input` +
`recording/stop`) drives a held scene in real time and writes one frame per
tick, producing a GIF/APNG/MP4. Issue #2047 adds the missing half: the live
session now **captures the inputs it dispatched as a coordinate-free script**
and returns it from `recording/stop`. That timeline replays verbatim as a
`recording/script` and feeds [`RecordingTestGenerator`](../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/RecordingTestGenerator.kt)
to emit a runnable Compose UI test — so an exploratory live session becomes
durable regression coverage. This is the "blaze live, capture as you go" loop
Block's Trailblaze records into `.trail.yaml`, done in-process against a
`@Preview` with no device.

## What changed

Live mode already routed every `recording/input` through the same
script-handler registry the scripted path uses (the typed `RecordingInputParams`
→ synthetic `RecordingScriptEvent`). It just **discarded** the result —
"only the scripted stop result carries `scriptEvents`." Now the desktop session
accumulates each dispatched event into `RecordingStopResult.capturedScript`:

- `RecordingInputParams.target` (new) — an agent driving a live recording by
  semantic handle (`ref` / `testTag` / `role`+`text`, mirroring
  `interactive/input`) posts the target on the wire; it is recorded verbatim.
- For panel clicks that carry only **pixel coordinates**, the tick loop resolves
  the pixel back to the node it hit via
  [`SemanticsTargets.nodeAt`](../../data/layoutinspector/core/src/main/kotlin/ee/schimke/composeai/data/layoutinspector/SemanticsTargets.kt)
  against the held scene's live semantics tree and records *that* handle —
  dropping the pixels — so even a mouse click becomes a layout-resilient,
  coordinate-free step.
- A click that lands on no targetable node (canvas / custom-drawn surface) keeps
  its raw pixels, so the timeline still reflects what happened.

## Coordinate-free resolution

`SemanticsTargets.nodeAt(root, x, y)` returns the strongest **stable** handle
for the node under a point, mirroring `RecordingTestGenerator`'s finder
preference so the captured handle round-trips into a clean selector:

1. `testTag` → `onNodeWithTag(...)`
2. visible text / label → `onNodeWithText(...)` / `onNodeWithContentDescription(...)`
3. `ref` → replayable handle for an interactive node (clickable / has a role)
   that carries no human-readable handle.

Among overlapping nodes the **smallest-area** one wins — the deepest/topmost
hit, matching what a real pointer lands on rather than an enclosing container.
Pure structural containers (a ref but nothing targetable) are never hit targets.

## Backends

| Backend | `capturedScript` |
|---------|------------------|
| Desktop | ✅ coordinate-free, via the held `ImageComposeScene`'s `composeSemanticsRoot()` |
| Android | ⬜ default empty for now (Android live recording is a follow-up; interactive needs `sandboxCount ≥ 2`) |

## From timeline to test

`recording/generateTest` turns a captured timeline into a runnable Compose UI
test without a client porting [`RecordingTestGenerator`](../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/RecordingTestGenerator.kt)
to its own language. The client sends `{ previewId, events }`; the daemon
resolves the composable's real function name from its preview catalog
(`previewIndex.byId` — load-bearing for named/variant previews, whose synthetic
id is not the function name), derives the class/method names, and returns the
generated source. Identifier overrides (`className`, `methodName`,
`composableInvocation`, `packageName`) win over the derived defaults. Wire
contract: [`RecordingGenerateTestParams` / `RecordingGenerateTestResult`](../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/protocol/Messages.kt).

## Surfaces

- **Daemon** — `recording/stop` returns `capturedScript`; `recording/generateTest`
  turns it into a test. Wire contract:
  [`RecordingStopResult.capturedScript`](../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/protocol/Messages.kt).
- **VS Code** — the panel's Record toggle (`focusToolbar` REC button) already
  drives a parallel live recording while a card is LIVE; on stop, when the
  session captured a coordinate-free timeline, the extension offers **Generate
  test** and opens the generated source in an untitled Kotlin editor for review
  (`handleSetRecording` → `generateRecordingTest` in `extension.ts`). No new
  webview chrome — the affordance is a native notification + editor.
- **MCP / web** (follow-up) — a live-interaction tool so an agent can blaze one
  input at a time (observe `compose/semantics` → act by handle → repeat) and get
  the captured script + generated test at stop.
