# Recording assertions

The scripted-recording surface (`compose-preview record`, MCP `record_preview`) can now carry
**assertions** that turn a recording into a check: a recording that scripts a tap and then asserts
the resulting state either passes or fails, and the `record` command exits non-zero when an
assertion isn't met. This is the Maestro / Espresso model — drive the UI, then assert — applied to
held `@Preview` scenes instead of an emulator.

## What shipped

Two assertion script events, evaluated against the held scene's **live semantics tree** at the
event's `tMs`:

| Kind                | Passes when…                          | Fails when…                         |
|---------------------|---------------------------------------|-------------------------------------|
| `assert.visible`    | the `target` matches ≥ 1 node         | the target matches no node          |
| `assert.notVisible` | the `target` matches no node          | the target matches ≥ 1 node         |
| `assert.textEquals` | the `target` resolves and its text == the expected string | the text differs, or the target matches 0 / >1 nodes |

The `target` is the existing `SemanticsInputTarget` (`ref` / `testTag` / `role`+`text`) — the same
handle `input.click` and the rest of the recording vocabulary already resolve, so assertions and
input share one resolver (`SemanticsTargets.resolve`). No new target shape, no new finder.
`assert.textEquals` carries its expected string in the **existing `inputText` field** (reused from
`uia.inputText`), so it adds no new wire field either.

A failed assertion produces `RecordingScriptEvidence` with the new
`RecordingScriptEventStatus.FAILED` status (distinct from `UNSUPPORTED` — the daemon *did* evaluate
the event, the UI just didn't meet the condition). A failed `assert.visible` also carries the
candidate node list, so the agent sees what *is* on screen without re-rendering — the same
affordance a missed `input.click` target gives.

### In a script

```json
[
  { "tMs": 0,    "kind": "input.click",     "target": { "testTag": "submit" } },
  { "tMs": 300,  "kind": "assert.visible",  "target": { "text": "Thanks!" } },
  { "tMs": 300,  "kind": "assert.notVisible","target": { "text": "Submit" } }
]
```

```
compose-preview record --preview MyForm --script form.json --out form.gif
# writes form.gif, then exits 2 if either assertion failed (with the unmet condition printed)
```

The recording is **always written**, even on failure — the captured frames are the evidence for
*why* the assertion failed. The non-zero exit (code `2`) is what lets CI or an agent treat a
recording as a gating check.

### Backend support

Desktop today. The desktop host advertises the `assertion` data extension
(`RecordingScriptDataExtensions.assertionDescriptor`) and wires the handlers in
`DesktopRecordingSession`. Android is a follow-up: its probe-semantics snapshot doesn't yet carry
the refs the resolver needs, so `RobolectricHost` omits the descriptor and the MCP layer rejects
`assert.*` for Android daemons up front rather than silently no-op'ing.

## What we borrowed (and what we deliberately didn't)

Comparison with the emulator-driven UI-test tools this is modelled on:

| Concept (source)                         | Status here | Notes |
|------------------------------------------|-------------|-------|
| Assert visible / not-visible (Maestro `assertVisible`, Espresso `matches(isDisplayed())`) | **Shipped** | Reuses the semantics-target resolver. |
| Driving by stable handle, not coordinates (Maestro `id:`/text, Espresso `withTag`) | Already present | `SemanticsInputTarget` predates this PR; assertions reuse it. |
| Deterministic virtual clock (vs. emulator wall-clock + idling resources) | Already present | Recordings tick on `frameIndex * 1e9 / fps`; no flakiness, no `IdlingResource` needed — the scene can't run ahead of the script. |
| Same artifact for human review + machine gate (Maestro recordings, Robo crawl reports) | **Shipped** | One GIF/APNG/MP4 doubles as the visual record and, via assertions, the pass/fail signal. |
| Assert exact text / value (Maestro `assertTrue`, Espresso `withText`) | **Shipped** | `assert.textEquals` compares the resolved node's `text` to the expected string in the existing `inputText` field. |
| Crawl / auto-explore (Robo, Monkey) | Roadmap, narrow | A preview is a single held scene, not an app graph — "crawl" reduces to "fan a tap over every clickable semantics node and snapshot," which the probe machinery could drive. Useful as a smoke check, not a crawl. |

### Other emulator-test techniques worth borrowing

Things these tools apply on emulators that *could* map onto held preview scenes, with how they'd
land (each is a separate follow-up, not in this PR):

- **State / network / clock injection** — Maestro's `setLocation`, Espresso's `IdlingResource` and
  test doubles. Previews already expose `PreviewOverrides` (locale, font scale, theme, ambient,
  permissions, …); the same `DataExtension` seam could inject a fake clock or a canned network
  response so an assertion checks a *loaded* state rather than a spinner. Fits the existing override
  model cleanly.
- **Retry / flake quarantine** — emulator suites re-run flaky tests. Here it's mostly moot: the
  virtual clock makes a scripted recording deterministic frame-for-frame, so a flaky assertion is a
  real bug, not a timing race. Worth a note in docs rather than a retry knob.
- **Accessibility assertions** — Espresso/ATF fail a test on a11y violations. The daemon already
  produces `a11y/atf` findings; an `assert.a11y` event (or a `--fail-on a11y` flag on `record`)
  would let a scripted walk gate on them — pairs naturally with the TalkBack spec (#1955's sibling).
- **Pixel / golden assertions** — Robo and screenshot tests diff against a baseline. The preview
  pipeline already has the visual-diff bot + `baselines.json`; an `assert.pixels` event diffing the
  current frame against a committed PNG would fold golden-image checks into the same script.

## Code map

- Status + evidence: `RecordingScriptEventStatus.FAILED`, `failedEvidence(...)`
  (`daemon/core/.../RecordingScriptHandlerRegistry.kt`).
- Event kinds + descriptor: `RecordingScriptDataExtensions.ASSERT_VISIBLE_EVENT` /
  `ASSERT_NOT_VISIBLE_EVENT` / `assertionDescriptor`
  (`data/render/core/.../DataExtensionPlan.kt`).
- Verdict logic (pure, unit-tested): `evaluateVisibilityAssertion` / `evaluateTextEqualsAssertion`
  (`daemon/desktop/.../RecordingAssertions.kt`).
- Handler wiring: `DesktopRecordingSession.assertVisibilityHandler`; advertised by
  `DesktopHost.recordingScriptEventDescriptors`.
- CLI gate: `RecordPreviewCommand` — scans returned evidence for `FAILED`, prints each, exits 2.
