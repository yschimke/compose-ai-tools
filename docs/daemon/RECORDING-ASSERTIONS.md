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
| `assert.a11y`       | the held scene has no ATF findings at the threshold | an ATF error (or warning, at `warnings`) is present |

`assert.a11y` carries no `target` — it checks the **whole held scene**. Its severity threshold rides
the existing `inputText` field (`errors`, the default, or `warnings`); `errors` fails only on ATF
errors, `warnings` fails on errors *and* warnings (`INFO` never fails). See
[Accessibility assertions](#accessibility-assertions-asserta11y) below.

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

| Backend | `assert.visible` / `assert.notVisible` | `assert.textEquals` | `assert.a11y` |
|---------|----------------------------------------|---------------------|---------------|
| Desktop | ✅ (resolved against the live unmerged semantics tree) | ✅ | ❌ — desktop a11y is overlay-only (no ATF findings); not advertised |
| Android | ✅ (resolved against the probe-semantics snapshot, by `testTag` only) | ❌ — `record_preview` rejects it | ✅ (ATF against the held View hierarchy) |

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

### Accessibility assertions (`assert.a11y`)

`assert.a11y` (issue #1966) gates a recording on accessibility findings — the Espresso/ATF "fail the
test on a11y violations" model, applied to the held scene. It runs Android's Accessibility Test
Framework (`AccessibilityChecker.check`) against the **same View hierarchy being recorded** at the
event's `tMs`, so the check is coupled to the rendered scene rather than to a separate data fetch.

```json
[
  { "tMs": 0,   "kind": "preview.reload" },
  { "tMs": 100, "kind": "assert.a11y" },
  { "tMs": 100, "kind": "assert.a11y", "inputText": "warnings" }
]
```

- **Threshold** (`inputText`): `errors` (default) fails the recording on any ATF *error*; `warnings`
  fails on errors *and* warnings. `INFO` findings never fail. The failure evidence reports the
  error/warning counts and the first few breaching rules (e.g. `TouchTargetSizeCheck: …`).
- **Android-only.** ATF needs a real View hierarchy; the desktop backend's a11y support is
  overlay-only (it produces no findings), so desktop advertises no `assert.a11y` descriptor and
  `record_preview` rejects the event there. On a backend that genuinely can't run the check, the
  event is recorded as `FAILED` ("accessibility capture is unavailable…") rather than passing
  silently — the user asked for the check, so an un-runnable check is a failure, not a no-op.
- **No new fetch path.** Capture rides a dedicated `captureA11yFindings` interactive-bridge command
  (sandbox-side ATF run → findings serialized across the classloader boundary), mirroring
  `captureProbeSemantics`. The verdict (`evaluateA11yAssertion`) is pure and lives in `:daemon:core`,
  so it's unit-tested without a Robolectric scene.

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
- **Accessibility assertions** — **Shipped (Android)** as `assert.a11y`, see
  [above](#accessibility-assertions-asserta11y). Runs ATF against the held View hierarchy and fails
  the recording on findings at the chosen threshold. Desktop has no ATF backend, so it stays
  Android-only for now.
- **Pixel / golden assertions** — Robo and screenshot tests diff against a baseline. The preview
  pipeline already has the visual-diff bot + `baselines.json`; an `assert.pixels` event diffing the
  current frame against a committed PNG would fold golden-image checks into the same script.

## Code map

- Status + evidence: `RecordingScriptEventStatus.FAILED`, `failedEvidence(...)`
  (`daemon/core/.../RecordingScriptHandlerRegistry.kt`).
- Event kinds + descriptors: `RecordingScriptDataExtensions.ASSERT_VISIBLE_EVENT` /
  `ASSERT_NOT_VISIBLE_EVENT` / `ASSERT_TEXT_EQUALS_EVENT` / `ASSERT_A11Y_EVENT`; `assertionDescriptor`
  (desktop, all three text/visibility), `assertionVisibilityDescriptor` (Android, visibility only),
  and `assertionA11yDescriptor` (Android, a11y) (`data/render/core/.../DataExtensionPlan.kt`).
- Verdict logic (pure, unit-tested, shared by both backends): `evaluateVisibilityAssertion` /
  `evaluateTextEqualsAssertion` / `resolvedNodeText` / `evaluateA11yAssertion` + `A11yAssertThreshold`
  (`daemon/core/.../RecordingAssertions.kt`).
- Handler wiring: `DesktopRecordingSession.assertVisibilityHandler` /
  `assertTextEqualsHandler` (advertised by `DesktopHost`); `AndroidRecordingSession`'s
  `assertVisibilityHandler` resolving against `captureProbeSemantics()` and `assertA11yHandler`
  resolving against `captureA11yFindings()` — the latter bridged by the `CaptureA11yFindings`
  interactive command running `AccessibilityChecker.check` sandbox-side in `RobolectricHost`
  (advertised by `RobolectricHost`).
- CLI gate: `RecordPreviewCommand` — fails any non-`APPLIED` `assert.*` evidence, prints each, exits 2.
