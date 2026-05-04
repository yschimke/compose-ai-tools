# Agent PR audits

Use this reference when reviewing a Compose app PR with compose-ai-tools and a
focused audit is needed beyond the basic before/after screenshot diff. Keep
comments evidence-based: cite the preview, locale/device when relevant, and the
data product or screenshot that supports the finding.

The examples below are exercised by
[`../scripts/run-agent-audit-samples.py`](../scripts/run-agent-audit-samples.py):
the script writes temporary Kotlin/resource fixtures, runs the documented
CLI/MCP commands, and asserts the expected data-product output shapes.

### Accessibility audit

Use this when a PR changes tappable controls, icon-only actions, semantics,
contrast, or screen state. Run ATF first, then inspect the annotated PNG path
from the JSON output.

Example problem:

```kotlin
@Preview(showBackground = true)
@Composable
fun SaveToolbarPreview() {
  IconButton(onClick = {}) {
    Icon(Icons.Default.Save, contentDescription = null)
  }
}
```

Command:

```sh
compose-preview a11y --filter SaveToolbar --json --fail-on warnings
```

Issue-identifying output:

```json
{
  "previewId": "com.example.SaveToolbarPreview",
  "a11yFindings": [
    {
      "level": "WARNING",
      "type": "SpeakableTextPresentCheck",
      "message": "This item may not have a label readable by screen readers.",
      "viewDescription": "IconButton"
    }
  ],
  "a11yAnnotatedPath": "build/compose-previews/renders/com.example.SaveToolbarPreview.a11y.png"
}
```

Action: ask for a meaningful `contentDescription` or a parent semantics label,
then rerun the same command and cite the cleared finding or remaining warning.

Check:

- No new ATF warnings or errors on the changed preview.
- Visible text is readable and consistent with the semantics.
- Labels, roles, actions, and state descriptions make sense for assistive
  technology.
- Touch target warnings are real regressions, not hidden decorative nodes.

### Localisation audit

Use this when copy, string resources, date/number formatting, or row layout
changed. Test at least one longer locale and one RTL locale for user-facing
screens.

Example problem:

```kotlin
// res/values/strings.xml
// <string name="done">Done</string>
//
// res/values-de/strings.xml
// <string name="done">Vollstaendig und unwiderruflich abgeschlossen</string>

@Preview(name = "English", showBackground = true)
@Composable
fun DoneButtonPreview() {
  Button(onClick = {}, modifier = Modifier.width(96.dp)) {
    Text(stringResource(R.string.done), maxLines = 1)
  }
}
```

Commands:

```json
{
  "tool": "render_preview",
  "arguments": {
    "uri": "compose-preview://workspace/_app/com.example.DoneButtonPreview",
    "overrides": { "localeTag": "de-DE" }
  }
}
```

```json
{
  "tool": "get_preview_data",
  "arguments": {
    "uri": "compose-preview://workspace/_app/com.example.DoneButtonPreview",
    "kind": "text/strings"
  }
}
```

```json
{
  "tool": "get_preview_data",
  "arguments": {
    "uri": "compose-preview://workspace/_app/com.example.DoneButtonPreview",
    "kind": "resources/used"
  }
}
```

Issue-identifying output:

```json
{
  "kind": "text/strings",
  "payload": {
    "texts": [
      {
        "text": "Vollstaendig und unwiderruflich abgeschlossen",
        "textSource": "layout",
        "nodeId": "7",
        "boundsInScreen": "16,10,96,34",
        "localeTag": "de-DE",
        "fontScale": 1.0
      }
    ]
  }
}
```

```json
{
  "kind": "resources/used",
  "payload": {
    "references": [
      {
        "resourceType": "string",
        "resourceName": "done",
        "packageName": "com.example",
        "resolvedValue": "Vollstaendig und unwiderruflich abgeschlossen",
        "resolvedFile": "/workspace/app/src/main/res/values-de/strings.xml",
        "consumers": []
      }
    ]
  }
}
```

Action: cite both products: `resources/used` proves the German resource was
selected, and `text/strings` proves the German text is what was laid out in
the 96 dp button. Open the rendered PNG to confirm visible truncation; explicit
`truncated` / `clipBounds` fields are not exposed yet (tracked in
compose-ai-tools#705). Ask for flexible width, wrapping, shorter localized
copy, or an alternate compact layout. Then rerender with the same `localeTag`
override.

Check:

- Representative locales render the expected translated copy.
- Missing translations and accidental fallbacks are named concretely.
- Locale-specific resources are selected where expected.
- Long translated strings remain visible and do not overlap adjacent content.

### Wear and round-device audit

Use this when Wear UI, round-device previews, edge buttons, or scrolling lists
changed. Fetch the device clip metadata and compare it with the rendered PNG.

Example problem:

```kotlin
@WearPreviewLargeRound
@Composable
fun AlertRoundPreview() {
  Box(Modifier.fillMaxSize()) {
    Text(
      "High priority alert",
      modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp),
    )
  }
}
```

Command:

```json
{
  "tool": "get_preview_data",
  "arguments": {
    "uri": "compose-preview://workspace/_wear/com.example.AlertRoundPreview",
    "kind": "render/deviceClip"
  }
}
```

Issue-identifying output:

```json
{
  "kind": "render/deviceClip",
  "payload": {
    "clip": {
      "shape": "circle",
      "centerXDp": 113.5,
      "centerYDp": 113.5,
      "radiusDp": 113.5
    }
  }
}
```

Action: cite `render/deviceClip` to prove this preview is captured under a
round mask, then inspect the PNG or a11y overlay for actual clipping. The data
product does not yet report safe bounds or content intersections (tracked in
compose-ai-tools#704). If content is clipped, ask for `ScreenScaffold`, list
`contentPadding`, or a lower top alignment. Then rerun the fetch and inspect
the PNG to confirm the text is no longer hidden by the round edge.

Check:

- TransformingLazyColumn (TLC) content is not clipped at the top or bottom in
  the initial captured state.
- Text bounds and controls stay inside the useful circular area.
- Edge buttons are fully visible, tappable, and not hidden by system chrome.
- Scrollable content does not rely on a first or last item that is only
  partially visible.

### Text overflow and readability audit

Use this when a PR changes text, badges, counters, font sizes, density, or
container widths. Prefer a font-scale preview variant when available.

Example problem:

```kotlin
@Preview(name = "fontScale 1.3", fontScale = 1.3f, showBackground = true)
@Composable
fun AccountLimitPreview() {
  Row(Modifier.width(180.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    Text("Available balance")
    Text("$12,450.00")
  }
}
```

Command:

```json
{
  "tool": "get_preview_data",
  "arguments": {
    "uri": "compose-preview://workspace/_app/com.example.AccountLimitPreview",
    "kind": "text/strings"
  }
}
```

Issue-identifying output:

```json
{
  "kind": "text/strings",
  "payload": {
    "texts": [
      {
        "text": "Available balance",
        "textSource": "layout",
        "nodeId": "4",
        "boundsInScreen": "0,8,146,32",
        "localeTag": "en-US",
        "fontScale": 1.3
      },
      {
        "text": "$12,450.00",
        "textSource": "layout",
        "nodeId": "5",
        "boundsInScreen": "92,8,180,32",
        "localeTag": "en-US",
        "fontScale": 1.3
      }
    ]
  }
}
```

Action: ask for wrapping, a vertical layout at larger font scales, constrained
number formatting, or `TextOverflow.Ellipsis` only when truncation is accepted
by product. `text/strings` does not yet report overlap or truncation directly
(tracked in compose-ai-tools#705), so confirm with the same data product and
the rendered PNG.

Check:

- Long labels, numbers, and dynamic strings remain legible.
- Text does not overlap icons, controls, or neighboring text.
- Important content is not only present in semantics while visually clipped.
- Font fallback or weight changes are intentional.

### Resource and theme provenance audit

Use this when a PR changes resources, themes, dynamic colors, typography,
icons, drawables, or preview overrides. Fetch provenance and verify the
visible preview matches the intended token/resource.

Example problem:

```kotlin
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
fun WarningBannerDarkPreview() {
  Text(
    "Payment failed",
    color = Color(0xFFB00020),
    modifier = Modifier.background(MaterialTheme.colorScheme.background),
  )
}
```

Command:

```json
{
  "tool": "get_preview_data",
  "arguments": {
    "uri": "compose-preview://workspace/_app/com.example.WarningBannerDarkPreview",
    "kind": "resources/used"
  }
}
```

Issue-identifying output:

```json
{
  "kind": "resources/used",
  "payload": {
    "references": [
      {
        "resourceType": "color",
        "resourceName": "warning_red",
        "packageName": "com.example",
        "resolvedValue": "#FFB00020",
        "resolvedFile": "/workspace/app/src/main/res/values/colors.xml",
        "consumers": []
      },
      {
        "resourceType": "string",
        "resourceName": "payment_failed",
        "packageName": "com.example",
        "resolvedValue": "Payment failed",
        "resolvedFile": "/workspace/app/src/main/res/values/strings.xml",
        "consumers": []
      }
    ]
  }
}
```

Action: cite `references[]` to prove which resources resolved. If an expected
theme token or resource is absent, ask for the design-system token
(`MaterialTheme.colorScheme.error`, `onError`, etc.) or a named resource
instead of a literal. If the visual diff is intentionally unchanged, the PR
should explain why the provenance changed.

Check:

- Colors, typography, dimensions, strings, images, and fonts come from the
  expected resources or theme tokens.
- Dark mode, dynamic color, and preview-local overrides do not accidentally
  hide content.
- Changed resources map to visible preview changes, or the absence of visual
  change is explained.

### Visual regression and changed-region audit

Use this as the default PR audit once previews have been rendered on base and
head. Start from `changed: true` entries, then inspect only the named PNGs and
diffs.

Example problem:

```kotlin
@Preview(showBackground = true)
@Composable
fun ProfileHeaderPreview() {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Avatar()
    Text("Jordan Lee", modifier = Modifier.padding(start = 4.dp))
  }
}
```

Command:

```sh
compose-preview show --filter ProfileHeader --json --changed-only
```

Issue-identifying output:

```json
{
  "previews": [
    {
      "id": "com.example.ProfileHeaderPreview",
      "changed": true,
      "pngPath": "build/compose-previews/renders/com.example.ProfileHeaderPreview.png",
      "diffPath": "build/compose-previews/diffs/com.example.ProfileHeaderPreview.diff.png",
      "changedPixels": 18420
    }
  ],
  "counts": { "changed": 1, "unchanged": 0, "missing": 0 }
}
```

Action: open `diffPath` first. If pixels changed outside the intended header
area, ask for the layout cause or fix. If the change is expected, cite the
preview id and diff path in the PR review summary.

Check:

- Changed regions match the intended UI area.
- Unexpected changed pixels are explained by animation, rendering variance, or
  a real regression.
- New or removed previews are called out separately from changed previews.

### Runtime and recomposition audit

Sample command: `get_preview_data uri=<preview-uri> kind=compose/recomposition`

Check:

- A preview does not become unexpectedly slow or noisy to render.
- Recomposition changes are tied to the relevant component.
- Trace output is treated as triage evidence unless the PR explicitly targets
  runtime behavior.

Use the recomposition data extension directly. In delta mode it observes the
held interactive composition, resets counters on each flush, and returns the
scopes that recomposed after the last input. A useful bad example is a parent
passing `count` into static children that do not use it, so one click can make
the header, value, and footer all recompose.

```kotlin
@Preview
@Composable
fun BadCounterPreview() {
  var count by remember { mutableIntStateOf(0) }

  Column(Modifier.clickable { count++ }.padding(16.dp)) {
    BadCounterHeader(count)
    Text("Count: $count")
    BadCounterFooter(count)
  }
}

@Composable
private fun BadCounterHeader(count: Int) {
  Text("Checkout")
}

@Composable
private fun BadCounterFooter(count: Int) {
  Text("Tap anywhere to increment")
}
```

Agent flow:

```text
list_data_products workspaceId=<workspace>
get_preview_data uri=<preview-uri> kind=compose/recomposition \
  params='{"mode":"delta","frameStreamId":"<stream-id>"}'
```

The payload to inspect has this shape:

```json
{
  "mode": "delta",
  "sinceFrameStreamId": "agent-run-1",
  "inputSeq": 1,
  "nodes": [
    { "nodeId": "header-scope", "count": 1 },
    { "nodeId": "counter-value-scope", "count": 1 },
    { "nodeId": "footer-scope", "count": 1 }
  ]
}
```

This is a bad signal for the example above because one click changed three
scopes. The counter text is expected to recompose; the header and footer are
not, because they display static copy. The agent should infer that only the
counter value needed `count`, narrow the child parameters, rerun the
interaction, and confirm the next delta only reports the counter-related
scope. A follow-up flush without another input should return `nodes: []`,
which confirms the extension reset the delta counters.

Use DejaVu-style reasoning for this audit: a recomposition count is evidence,
not the finding by itself. Matt McKenna's DejaVu library formalizes this as
test assertions with `createRecompositionTrackingRule()`,
`assertRecompositions(...)`, and `assertStable()`, plus causality diagnostics
from snapshot-state writes and dirty parameter slots. compose-preview does not
yet expose DejaVu's `testTag`/source/dirty-bit causality, so name the scoped
payload evidence and the state/parameter path you found in code.

### State restoration and lifecycle audit

> **Capability split.** `recording.probe` and `lifecycle.event` (Android-only — drives
> `ActivityScenario.moveToState(...)` against the held rule's activity) are wired and
> `record_preview` accepts them. `state.save` / `state.restore` and `preview.reload` are still
> advertised as `supported = false` roadmap and rejected up front. The lifecycle event accepts
> `pause` / `resume` / `stop` in v1 — `destroy` is rejected because moving the scenario to
> DESTROYED would break subsequent renders.

First check the daemon's advertised extension events:

```json
{ "tool": "list_data_products", "arguments": { "workspaceId": "<workspace>" } }
```

Inspect `dataExtensions[].recordingScriptEvents[]`: only entries with `supported: true` may
appear in a `record_preview` script. If the audit needs an event that's still
`supported: false`, name the gap in the review and stop — this is a tooling roadmap item, not a
PR-level finding.

Then drive the click flow plus a pause-resume cycle to verify state survives an Android
lifecycle round-trip:

```json
{
  "tool": "record_preview",
  "arguments": {
    "uri": "compose-preview://workspace/_app/com.example.StatefulPreview",
    "events": [
      { "tMs": 0,   "kind": "click", "pixelX": 120, "pixelY": 40 },
      { "tMs": 200, "kind": "lifecycle.event", "lifecycleEvent": "pause" },
      { "tMs": 200, "kind": "lifecycle.event", "lifecycleEvent": "resume" },
      { "tMs": 200, "kind": "recording.probe", "label": "after-resume" }
    ]
  }
}
```

Events with the same `tMs` are one script step — the `pause` + `resume` + verification probe at
`tMs: 200` happen before the frame for that tick is captured, so the rendered frame at 200ms
already reflects the post-resume composition. Do not add fake delays around the lifecycle
events; that tests timing luck instead of state-survival semantics.

Check:

- `list_data_products` advertises `recording.probe` and `lifecycle.event` with `supported: true`
  on the daemon under test.
- The recording metadata's `scriptEvents` for both lifecycle events report `status: "applied"`
  (each handler emits a message naming the transition).
- Input events that should change state produce changed frames before the lifecycle round-trip
  AND survive the round-trip (`changedFromPrevious: false` on the post-resume frame would mean
  the click state was lost across pause-resume).
- Once `state.save` / `state.restore` ship as `supported: true`, extend this audit with named
  checkpoint pairs for explicit save/restore verification.

### Accessibility-driven interaction audit

> **Capability split.** Twelve `a11y.action.*` ids are wired end-to-end on the Android backend —
> `click`, `longClick`, `focus`, `expand`, `collapse`, `dismiss`, `scrollForward`,
> `scrollBackward`, `scrollUp`, `scrollDown`, `scrollLeft`, `scrollRight`. Each resolves the
> target node by content description and invokes the matching `SemanticsActions` constant —
> same path `AccessibilityNodeInfo.performAction(...)` walks via TalkBack. The remaining
> seven (`clearFocus`, `accessibilityFocus`, `clearAccessibilityFocus`, `select`,
> `clearSelection`, `nextAtGranularity`, `previousAtGranularity`) appear in
> `list_data_products` as `supported = false` roadmap entries and are rejected up front by
> `record_preview` — they don't have a clean Compose `SemanticsActions` equivalent today.

Use this when a PR changes a screen reader–facing flow: clickable nodes, `Modifier.semantics`
handlers, scrollable containers consumed by a screen reader, or anything that should be reachable
without a pointer event. The intent is to drive the preview the way assistive technology does
rather than by pixel coordinates.

Driving sequence:

```json
{
  "tool": "record_preview",
  "arguments": {
    "uri": "compose-preview://workspace/_app/com.example.SettingsRowPreview",
    "events": [
      { "tMs": 0,   "kind": "a11y.action.click", "nodeContentDescription": "Mute notifications" },
      { "tMs": 250, "kind": "recording.probe",   "label": "after-a11y-click" }
    ]
  }
}
```

`nodeContentDescription` is matched exactly against the node's `SemanticsProperties.ContentDescription`
(useUnmergedTree = true) — fetch a fresh `a11y/hierarchy` to look up the canonical strings.
When multiple nodes share the same description, the smallest by area wins. If no node matches,
`scriptEvents[*].status = "unsupported"` with a specific reason.

Check:

- `list_data_products` advertises `a11y.action.click` with `supported: true` on the daemon under
  test (Android with the a11y preview extension enabled).
- `recording.probe` evidence at the verification tick reports `applied`.
- The post-click frame's `a11y/hierarchy` reflects the expected state mutation (selected,
  expanded, focused).
- The screen-reader-driven sequence produces the same UI changes a pointer-driven sequence
  would, modulo intentional differences (e.g. focus-only flows that bypass the click).
- Roadmap actions (`a11y.action.longClick`, etc.) come back as MCP-level rejections rather than
  daemon evidence — that's a tooling gap, not an app regression.

### Failure triage audit

Use this when a preview fails to render or a daemon/CI report says a data
product is unavailable. Fetch the failure payload before guessing from the
Gradle log.

Example problem:

```kotlin
@Preview
@Composable
fun DetailPreview() {
  DetailScreen(id = checkNotNull(null))
}
```

Command:

```json
{
  "tool": "get_preview_data",
  "arguments": {
    "uri": "compose-preview://workspace/_app/com.example.DetailPreview",
    "kind": "test/failure"
  }
}
```

Issue-identifying output:

```json
{
  "kind": "test/failure",
  "payload": {
    "status": "failed",
    "phase": "unknown",
    "error": {
      "type": "IllegalStateException",
      "message": "Required value was null.",
      "topFrame": "DetailPreview.kt:42",
      "stackTrace": [
        "com.example.DetailPreviewKt.DetailPreview(DetailPreview.kt:42)"
      ]
    },
    "partialScreenshot": null,
    "partialScreenshotAvailable": false,
    "pendingEffects": [],
    "runningAnimations": [],
    "frameClockState": { "status": "unknown" },
    "lastSnapshotSummary": {
      "stateObjects": null,
      "valuesCaptured": false,
      "redaction": "state values are not captured in schema v1"
    }
  }
}
```

Action: ask for deterministic preview state or fake data at
`payload.error.topFrame`. If the payload says `DataProductUnknown`, switch to
`list_data_products` and verify the producer is enabled before filing a
renderer bug.

Check:

- The failure is reported with the preview id, target, and shortest useful
  error.
- Unavailable data products are distinguished from failing previews.
- Follow-up issues are raised for infrastructure or schema gaps that are too
  complex to fix inside the reviewed PR.
