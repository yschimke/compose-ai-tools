# The sample app's UI-builder design

`home-screen.uibuilder.json` is a UI-builder design authored to match `HomeScreen` in
[`../src/main/kotlin/com/example/sampleandroid/MainActivity.kt`](../src/main/kotlin/com/example/sampleandroid/MainActivity.kt).

It exists to answer one question daily: **does a design authored against a real screen still look
like that screen?** The [`Device Capture Parity`](../../../.github/workflows/device-capture-parity.yml)
workflow renders both and records how far apart they are.

## The three files, and which is the source of truth

| File | Role |
| --- | --- |
| `home-screen.uibuilder.json` | **The design.** The source of truth for what was authored. |
| [`HomeScreenDesignPreview.kt`](../src/main/kotlin/com/example/sampleandroid/HomeScreenDesignPreview.kt) | The design as Compose — its committed projection, and what actually renders. |
| [`DeviceCaptureParityTest.kt`](../src/test/kotlin/com/example/sampleandroid/DeviceCaptureParityTest.kt) | Scores the design render against the app capture. |

The design document is written in the builder's operation format
(`compose-ui-builder-operations/v1-candidate`) — a `createDesign` followed by one `insertNode` per
node, exactly what the editor and the MCP adapter emit. Every `componentId`, property name, allowed
value, modifier and slot in it is from `m3-catalog`'s builder capability catalog, so it is a design
the builder would accept rather than a JSON file shaped like one.

## The transcription seam

`HomeScreenDesignPreview.kt` is **transcribed from the design by hand, not generated from it**, and
that is the one place a reviewer has to check by eye.

The generator that would produce it — `CapabilityComposeCodeExporter` — lives in
`compose-preview-server`, which is layer 2. This repository is layer 1 and
[may not depend upward](../../../docs/design/REPOSITORY_LAYERS.md), so the export cannot run here.
What keeps the seam honest is that the comparison is on **pixels**: a transcription that drifted
from the design would have to drift in a way that still renders identically to the app, which is a
much harder mistake to make silently than a mistyped property.

Replacing the transcription with the exporter's real output — run in compose-preview-server and
committed here with a regeneration command — is the obvious follow-up.

## Changing the app

If you change `HomeScreen`, the next daily run will report a difference. That is the lane working:
re-author the design to match, update the transcription, and the score returns to its floor.

## What this is not

Not a device capture. The Activity is launched under Robolectric — real lifecycle, real
`setContent`, real semantics tree, but no window offsets, no multi-resume, no `PixelCopy` timing.
Those are Tier 2 in [`DEVICE_CAPTURE.md`](../../../docs/design/DEVICE_CAPTURE.md), and swapping this
lane's producer for an on-device one changes neither the design nor the comparison.
