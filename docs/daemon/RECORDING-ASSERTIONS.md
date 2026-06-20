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
`uia.inputText`), so it adds no new wire field either. It compares against the resolved node's own
text, or — for a tag-on-the-container shape like `Button(Modifier.testTag("submit")) { Text("Submit") }`
— the **merged text of its descendants**, mirroring Compose's merged semantics so the check sees what
the user sees.

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

| Backend | `assert.visible` / `assert.notVisible` | `assert.textEquals` |
|---------|----------------------------------------|---------------------|
| Desktop | ✅ (resolved against the live unmerged semantics tree) | ✅ |
| Android | ✅ (resolved against the probe-semantics snapshot, by `testTag` only) | ❌ — `record_preview` rejects it |

Desktop advertises `RecordingScriptDataExtensions.assertionDescriptor` (all three) and resolves via
`state.scene.composeSemanticsRoot()`. Android (issue #1964) advertises the narrower
`assertionVisibilityDescriptor` and resolves visibility against the already-bridged
`captureProbeSemantics()` snapshot — the same path the `recording.probe` event uses, so no new
sandbox command is needed. The pure verdict logic (`evaluateVisibilityAssertion`) lives in
`:daemon:core` so both backends share one implementation.

**Android limitations (today):** assertions resolve by **`testTag` only**. The probe snapshot is a
flat node list, so a `role`+`text` target can't be matched reliably — a `Button { Text("Add") }`
emits the `role` on the button and the `text` on a separate child, so checking both on one node
matches nothing and would make `assert.notVisible` *wrongly pass* while the control is on screen.
`ref` (no refs in the snapshot) and `role`+`text` both fail with a clear message rather than risk a
false pass; `assert.textEquals` isn't advertised at all. Enriching the snapshot to support
`role`+`text` is a follow-up.

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
- Event kinds + descriptors: `RecordingScriptDataExtensions.ASSERT_VISIBLE_EVENT` /
  `ASSERT_NOT_VISIBLE_EVENT` / `ASSERT_TEXT_EQUALS_EVENT`; `assertionDescriptor` (desktop, all three)
  and `assertionVisibilityDescriptor` (Android, visibility only)
  (`data/render/core/.../DataExtensionPlan.kt`).
- Verdict logic (pure, unit-tested, shared by both backends): `evaluateVisibilityAssertion` /
  `evaluateTextEqualsAssertion` / `resolvedNodeText` (`daemon/core/.../RecordingAssertions.kt`).
- Handler wiring: `DesktopRecordingSession.assertVisibilityHandler` /
  `assertTextEqualsHandler` (advertised by `DesktopHost`); `AndroidRecordingSession`'s
  `assertVisibilityHandler` resolving against `captureProbeSemantics()` (advertised by
  `RobolectricHost`).
- CLI gate: `RecordPreviewCommand` — fails any non-`APPLIED` `assert.*` evidence, prints each, exits 2.
