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
| `assert.pixels`     | the recorded frame at this `tMs` matches the baseline PNG within tolerance | the frame drifts beyond tolerance, or the baseline / frame is missing |

`assert.pixels` carries no `target` either — it pins the **rendered frame** against a committed
baseline PNG whose path rides the existing `inputText` field. See
[Pixel assertions](#pixel-assertions-assertpixels) below.

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

| Backend | `assert.visible` / `assert.notVisible` | `assert.textEquals` | `assert.a11y` | `assert.pixels` |
|---------|----------------------------------------|---------------------|---------------|-----------------|
| Desktop | ✅ (resolved against the live unmerged semantics tree) | ✅ | ❌ — desktop a11y is overlay-only (no ATF findings); not advertised | ✅ (diffs the frame it wrote against the baseline) |
| Android | ✅ (resolved against the probe-semantics snapshot: `testTag` / `role`+`text` / `text`) | ✅ | ✅ (ATF against the held View hierarchy) | ✅ (diffs the frame it wrote against the baseline) |

Desktop advertises `RecordingScriptDataExtensions.assertionDescriptor` (all four) and resolves via
`state.scene.composeSemanticsRoot()`. Android (issues #1964, #2519) advertises
`assertionAndroidDescriptor` (visible / notVisible / textEquals / pixels) and resolves targets
against the already-bridged `captureProbeSemantics()` snapshot — the same path the `recording.probe`
event uses, so no new sandbox command is needed. The pure verdict logic
(`evaluateVisibilityAssertion` / `evaluateTextEqualsAssertion` / `pixelAssertVerdict`) lives in
`:daemon:core` so both backends share one implementation.

**Android targets (issue #2519):** the probe snapshot is a flat node list, but each retained node
now carries its **merged descendant text** (`RecordingProbeNode.mergedText`), so `testTag`,
`role`+`text`, and `text` alone all resolve. A `Button { Text("Add") }` emits the `role` on the
button and the `text` on a child; the button node carries the child's merged text, so a
`role`+`text` (or `assert.textEquals`) target matches the control the user sees rather than failing
closed. The pure resolver `resolveProbeTarget` (in `:daemon:core`) mirrors the desktop resolver's
precedence (testTag → role+text → text) and reads a node's *effective* text (own text, else merged
descendant text). The one shape it can't resolve is a `ref` target — no refs survive into the flat
snapshot — which fails with a clear message rather than risk a false `assert.notVisible` pass.

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

### Pixel assertions (`assert.pixels`)

`assert.pixels` (issue #1967) gates a recording on a **golden image** — the Robo / screenshot-test
"diff against a baseline" model, applied to a recorded frame. It diffs the frame the recording wrote
at the event's `tMs` against a committed baseline PNG, failing when the diff exceeds tolerance.

```json
[
  { "tMs": 0,    "kind": "input.click",   "target": { "testTag": "submit" } },
  { "tMs": 500,  "kind": "assert.pixels", "inputText": "submitted.png" }
]
```

```
compose-preview record --preview MyForm --script form.json --out form.gif --baseline-dir baselines/
# diffs frame@500ms against baselines/submitted.png; exits 2 if it drifts beyond tolerance
```

- **Reuses `PixelDiff`.** The comparison is the same `PixelDiff` comparator (per-pixel +
  aggregate-fraction + absolute-cap tolerance) the preview-review pipeline uses — no new comparator.
  Tolerance is `PixelDiffTolerance.DEFAULT` today; a per-event override is a follow-up. A baseline
  whose dimensions differ from the frame fails as a dimension mismatch.
- **Baseline path rides `inputText`** (no new wire field, like `assert.textEquals`). The CLI's
  `--baseline-dir` makes relative paths absolute before the script is posted, so resolution is
  independent of the daemon's working directory; the daemon reads the PNG off the shared local
  filesystem.
- **Snapshotted at the event's position.** An `assert.pixels` event freezes the frame **at its own
  point in the timeline** — rendered at the frame bucket's instant but *before* any later events that
  share the same bucket are dispatched — so the golden check observes the UI as of the assertion, not
  after a same-bucket input. Absent later same-bucket events the snapshot is byte-identical to the
  on-disk frame a baseline is captured from. Only the diff itself is deferred to a post-playback pass
  (to keep evidence in timeline order); on failure it writes `actual.png` / `expected.png` /
  `diff.png` next to the encoded output so the drift is inspectable without re-running.
- **Fail-closed.** A missing baseline or a dimension mismatch is `FAILED`, never a silent pass — the
  script asked to pin pixels.
- **Both backends (issue #2519).** Desktop and Android both write a `frame-NNNNN.png` per frame and
  diff the frame at the event's `tMs` against the baseline. The pure verdict (`pixelAssertVerdict`)
  lives in `:daemon:core` (relocated there from `:daemon:desktop` when Android gained the feature),
  reusing the `PixelDiff` comparator (also in `:daemon:core`). Desktop snapshots the frame *before*
  any later same-bucket event; Android renders one frame per bucket *after* dispatching every
  same-bucket event, so on Android the golden check observes the on-disk frame written for that
  `tMs` (a same-bucket input dispatched before the assert is therefore reflected in it). Both write
  `actual/expected/diff.png` next to the encoded output on failure.

## What we borrowed (and what we deliberately didn't)

Comparison with the emulator-driven UI-test tools this is modelled on:

| Concept (source)                         | Status here | Notes |
|------------------------------------------|-------------|-------|
| Assert visible / not-visible (Maestro `assertVisible`, Espresso `matches(isDisplayed())`) | **Shipped** | Reuses the semantics-target resolver. |
| Driving by stable handle, not coordinates (Maestro `id:`/text, Espresso `withTag`) | Already present | `SemanticsInputTarget` predates this PR; assertions reuse it. |
| Deterministic virtual clock (vs. emulator wall-clock + idling resources) | Already present | Recordings tick on `frameIndex * 1e9 / fps`; no flakiness, no `IdlingResource` needed — the scene can't run ahead of the script. |
| Same artifact for human review + machine gate (Maestro recordings, Robo crawl reports) | **Shipped** | One GIF/APNG/MP4 doubles as the visual record and, via assertions, the pass/fail signal. |
| Assert exact text / value (Maestro `assertTrue`, Espresso `withText`) | **Shipped** | `assert.textEquals` compares the resolved node's `text` to the expected string in the existing `inputText` field. |
| Golden-image / screenshot diff (Robo, Paparazzi/Roborazzi) | **Shipped (desktop)** | `assert.pixels` diffs the recorded frame against a committed baseline PNG via `PixelDiff`. |
| Crawl / auto-explore (Robo, Monkey) | Roadmap, narrow | A preview is a single held scene, not an app graph — "crawl" reduces to "fan a tap over every clickable semantics node and snapshot," which the probe machinery could drive. Useful as a smoke check, not a crawl. |

### Other emulator-test techniques worth borrowing

Things these tools apply on emulators that *could* map onto held preview scenes, with how they'd
land (each is a separate follow-up, not in this PR):

- **Fake clock** — **Shipped** as the `clockEpochMillis` override (issue #1968). Pins the preview's
  wall clock to a fixed instant so time-dependent UI — relative timestamps ("2m ago"), countdowns
  ("expires in…") — renders deterministically instead of drifting every run, so an assertion checks a
  stable frame. It rides the existing `DataExtension` seam (no renderer branch): the
  `:data-preview-overrides-connector` planner provides a fixed clock through the `LocalClock`
  composition local (`:data-preview-overrides-runtime`), and it's **opt-in** — since Compose has no
  built-in wall-clock local, consumer UI reads `LocalClock.current.nowEpochMillis()` instead of
  `System.currentTimeMillis()`, the same model as `previewOverride*` / `PreviewSlot`. Drive it with
  `compose-preview record --overrides clockEpochMillis=<epoch-ms>`. Both backends honour it.
- **Canned state / network injection** — Maestro's `setLocation`, Espresso's `IdlingResource` and
  test doubles. A canned "loaded vs. loading vs. error" seam so an assertion checks *content* rather
  than a spinner would fit the same override model as the fake clock above — a natural follow-up.
- **Retry / flake quarantine** — emulator suites re-run flaky tests. Here it's mostly moot: the
  virtual clock makes a scripted recording deterministic frame-for-frame, so a flaky assertion is a
  real bug, not a timing race. Worth a note in docs rather than a retry knob.
- **Accessibility assertions** — **Shipped (Android)** as `assert.a11y`, see
  [above](#accessibility-assertions-asserta11y). Runs ATF against the held View hierarchy and fails
  the recording on findings at the chosen threshold. Desktop has no ATF backend, so it stays
  Android-only for now.
- **Pixel / golden assertions** — **Shipped (both backends)** as `assert.pixels`, see
  [above](#pixel-assertions-assertpixels). Diffs the recorded frame against a committed baseline PNG
  via `PixelDiff`; Android landed alongside the text/target enrichment in issue #2519.

## Code map

- Status + evidence: `RecordingScriptEventStatus.FAILED`, `failedEvidence(...)`
  (`daemon/core/.../RecordingScriptHandlerRegistry.kt`).
- Event kinds + descriptors: `RecordingScriptDataExtensions.ASSERT_VISIBLE_EVENT` /
  `ASSERT_NOT_VISIBLE_EVENT` / `ASSERT_TEXT_EQUALS_EVENT` / `ASSERT_A11Y_EVENT` / `ASSERT_PIXELS_EVENT`;
  `assertionDescriptor` (desktop — visibility, text, **and pixels**), `assertionAndroidDescriptor`
  (Android — visibility, text, **and pixels**, resolved against the flat probe snapshot), and
  `assertionA11yDescriptor` (Android, a11y) (`data/render/core/.../DataExtensionPlan.kt`).
- Verdict logic (pure, unit-tested, all in `:daemon:core`): `evaluateVisibilityAssertion` /
  `evaluateTextEqualsAssertion` / `resolvedNodeText` / `evaluateA11yAssertion` + `A11yAssertThreshold`
  (`daemon/core/.../RecordingAssertions.kt`); `resolveProbeTarget` / `effectiveText` (the Android
  flat-snapshot resolver, `daemon/core/.../RecordingProbeTargets.kt`); `pixelAssertVerdict`
  (golden-image, reuses `PixelDiff`, `daemon/core/.../PixelAssertions.kt`).
- Probe-snapshot enrichment: `RecordingProbeNode.mergedText` (`daemon/core/.../protocol/Messages.kt`)
  populated by `ComposeSemanticsNode.toProbeNodes()`
  (`data/layoutinspector/connector/.../ComposeSemanticsDataProduct.kt`).
- Handler wiring: `DesktopRecordingSession.assertVisibilityHandler` /
  `assertTextEqualsHandler` (advertised by `DesktopHost`); `AndroidRecordingSession`'s
  `assertVisibilityHandler` / `assertTextEqualsHandler` resolving against `captureProbeSemantics()`
  via `resolveProbeTarget`, and `assertA11yHandler` resolving against `captureA11yFindings()` — the
  latter bridged by the `CaptureA11yFindings` interactive command running `AccessibilityChecker.check`
  sandbox-side in `RobolectricHost` (advertised by `RobolectricHost`).
- Pixel-assert wiring: `DesktopRecordingSession.evaluatePixelAssert` (snapshots the frame at the
  event's position) and `AndroidRecordingSession.evaluatePixelAssert` (diffs the on-disk
  `frame-NNNNN.png` written for the event's `tMs`); both a post-playback diff against the baseline,
  writing `actual/expected/diff.png` on failure.
- CLI gate: `RecordPreviewCommand` — `--baseline-dir` resolves `assert.pixels` baseline paths; fails
  any non-`APPLIED` `assert.*` evidence, prints each, exits 2.
