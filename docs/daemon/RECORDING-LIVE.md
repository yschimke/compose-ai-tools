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

## Surfaces

- **Daemon** (this change) — `recording/stop` returns `capturedScript`. Wire
  contract: [`RecordingStopResult.capturedScript`](../../daemon/core/src/main/kotlin/ee/schimke/composeai/daemon/protocol/Messages.kt).
- **VS Code** (follow-up) — a Record toggle on a LIVE card drives a live
  recording, then offers "save script" / "generate test" from `capturedScript`.
- **MCP / web** (follow-up) — a live-interaction tool so an agent can blaze one
  input at a time (observe `compose/semantics` → act by handle → repeat) and get
  the captured script + generated test at stop.
