# Remote Compose

Remote Compose is a Compose dialect that compiles your `@Composable` tree into
a portable **RemoteDocument** byte stream — a serialisable description of the
UI that a remote player (a watch face, tile, widget, or another surface that
can't host a full Compose runtime) replays at display time. Same `@Composable`
authoring model, but the output is a document, not an Android view tree.

Two things that matter for anyone writing previews against it:

- **Different applier.** Remote Compose uses its own applier that emits into a
  RemoteDocument capture buffer, not into Android's layout/draw tree. A plain
  `Text("hello")` from `androidx.compose.material` won't work inside a Remote
  Compose tree — you need the remote-aware equivalents (`RemoteText`,
  `RemoteBox`, `RemoteButton`, …). Mixing the two inside a single capture
  produces a document with gaps.
- **Different target marker.** Remote-aware composables are annotated with
  `@RemoteComposable` (a `@ComposableTargetMarker`) instead of the usual
  `@UiComposable`. The Compose compiler uses target markers to flag "wrong
  dialect" usage at compile time, so the compiler — not runtime — tells you
  when you reach for a UI composable inside a remote subtree.

## Library layout

The group is `androidx.compose.remote` (plus the Wear add-on
`androidx.wear.compose.remote`). For previews the interesting pieces are:

| Purpose | Artifact |
| :--- | :--- |
| **Authoring primitives** (`RemoteBox`, `RemoteModifier`, state like `RemoteString`/`RemoteColor`, the `.rs`/`.rdp`/`.rb`/`.rf` conversion helpers, `HostAction`) | `androidx.compose.remote:remote-creation-compose` |
| **Core creation runtime** (capture buffer, profiles) | `androidx.compose.remote:remote-creation` |
| **Preview tooling** (`RemotePreview`, `RemotePreviewWrapper`) | `androidx.compose.remote:remote-tooling-preview` |
| **Wear Material 3 remote components** (`RemoteButton`, `RemoteText`, `RemoteButtonDefaults`, `buttonSizeModifier`, …) | `androidx.wear.compose.remote:remote-material3` |

`remote-material3` builds on top of `remote-creation-compose` — it gives you
Material 3 components that emit remote layout/draw commands, so you get M3
styling inside a RemoteDocument without reinventing button colours, shapes,
and typography. Same role `compose-material3` plays for a normal app.

### Dependency wiring

`remote-tooling-preview`'s POM declares its creation deps at **`runtime`**
scope, so a project that only lists it on `implementation` won't see the
creation types on its compile classpath. Add them explicitly:

```kotlin
dependencies {
    implementation("androidx.compose.remote:remote-tooling-preview:…")
    implementation("androidx.compose.remote:remote-creation:…")
    implementation("androidx.compose.remote:remote-creation-compose:…")
    implementation("androidx.wear.compose.remote:remote-material3:…")
}
```

Most Remote Compose APIs are `@RestrictTo(LIBRARY_GROUP)`. You'll get lint
failures (`RestrictedApi`) and IDE red squigglies without either
`@file:Suppress("RestrictedApiAndroidX")` at the top of each file *and* a
`lint { disable += "RestrictedApi" }` block in the module's `build.gradle.kts`.

## Previewing: two shapes, same output

### 1. `RemotePreview` wrapper inside a `@Preview` composable

```kotlin
@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@Composable
fun RemoteButtonEnabledPreview() {
    RemotePreview(profile = RcPlatformProfiles.ANDROIDX) {
        Container { RemoteButtonEnabled() }
    }
}
```

`RemotePreview` captures the inner `@RemoteComposable` tree into a
RemoteDocument, then plays it back into the regular Compose preview surface.
This is the shape used in `wear/compose/remote/remote-material3/samples` in
AOSP and works on any Android Studio that can render `@Preview` — no
dependency on the newer `PreviewWrapper` annotation.

### 2. `@PreviewWrapper(RemotePreviewWrapper::class)`

```kotlin
@Preview(showBackground = true, widthDp = 200, heightDp = 200)
@PreviewWrapper(RemotePreviewWrapper::class)
@Composable
fun RemoteButtonWithBorderPreview() {
    Container { RemoteButtonWithBorder() }
}
```

Tooling applies `RemotePreviewWrapper.Wrap { … }` around the function body, so
the preview function itself only contains remote content. `PreviewWrapper`
landed in `androidx.compose.ui:ui-tooling-preview` 1.11.0-beta+ — older
Compose releases don't ship the annotation class, so this shape is newer than
approach 1.

`RemotePreviewWrapper` itself lives in `remote-tooling-preview`:

```kotlin
class RemotePreviewWrapper : PreviewWrapperProvider {
    @Composable
    override fun Wrap(content: @Composable () -> Unit) {
        RemotePreview(profile = RcPlatformProfiles.ANDROIDX, content = content)
    }
}
```

> [!NOTE]
> If your `remote-tooling-preview` alpha predates the published
> `RemotePreviewWrapper` class, drop a local copy into the sample module —
> signature is identical, swap to the upstream one once it ships.

Both approaches render the same pixels — pick the one that suits your
preview density. Approach 1 scales well when previews need different profiles
or framing per case; approach 2 keeps many same-shaped previews terse.

## Component previews, not device previews

Remote Compose components don't care about device chrome (bezel, status bar,
system time) — they're rendered by a host surface that owns those details.
Size previews by the component's own footprint, not by a device:

```kotlin
@Preview(showBackground = true, widthDp = 200, heightDp = 200)
```

Skip `@WearPreviewDevices` / `@PreviewScreenSizes` here. A 200×200 canvas
frames a single `RemoteButton` cleanly; stretch when you add components that
need more room, or split into multiple previews rather than packing a whole
screen.

Give the remote tree a centered container so the component isn't jammed into
the top-left corner of the canvas:

```kotlin
@Composable
@RemoteComposable
fun Container(content: @Composable @RemoteComposable () -> Unit) {
    RemoteBox(
        modifier = RemoteModifier.fillMaxSize(),
        contentAlignment = RemoteAlignment.Center,
        content = content,
    )
}
```

## Reference sample

A working end-to-end example lives in [`sample-remotecompose/`](../../../sample-remotecompose/):

- `RemoteComponents.kt` — three `@RemoteComposable` button variants + the
  `Container` helper.
- `Previews.kt` — both preview shapes side by side.
- `RemotePreviewWrapper.kt` — local copy of the wrapper until the upstream
  alpha catches up.
- `build.gradle.kts` — explicit dependency wiring, `lint { disable +=
  "RestrictedApi" }`, and a note on why this module doesn't use the Compose
  BOM (Remote Compose alphas pull in a newer Compose runtime than the BOM
  currently pins).

`./gradlew :sample-remotecompose:renderAllPreviews` produces PNGs for both
shapes, so you can see that the capture-and-replay path works end-to-end in
the plugin's renderer.

## Building live-updating widgets

Most Remote Compose consumers are widget-like: a Glance widget, a tile,
or a watch surface that refreshes its state without re-encoding the
document on every change. Two state models matter:

### Host-driven state (named bindings)

The host pushes a fresh value into a named binding after an HA / API /
WebSocket update. The document identifies the binding by name and
reacts without any re-recording.

```kotlin
// Widget-side authoring
val isOn: RemoteBoolean = createNamedRemoteBoolean(
    name = "light.kitchen.on",
    initialValue = false,
    domain = Domain.User,
)
val label: RemoteString = createNamedRemoteString("light.kitchen.label", "—")

RemoteText(
    text = label,
    color = isOn.select(onColor, offColor),
    // ...
)
```

```kotlin
// Host-side update (your app)
player.write("light.kitchen.on", true)
player.write("light.kitchen.label", "On")
```

**Gotcha.** On the authoring side, `RemoteBoolean.constantValueOrNull`
returns `null` for named bindings — there's no compile-time constant to
seed the initial visual from. If your layout needs to *branch* on the
initial value (e.g. "render the knob on the right when the widget is
authored for a seeded-true light") you have to carry that `Boolean` on
the host side separately and pass it into the composable as a plain
Kotlin `Boolean`, producing a literal `0f.rf` / `1f.rf` in the document.

### In-document state (optimistic click)

For click-flip-now-ack-later UX, the player keeps the state in-document
and flips it on click without a host round-trip:

```kotlin
val localIsOn: MutableRemoteBoolean = rememberMutableRemoteBoolean(initial)
val toggle: Action = ValueChange(localIsOn, localIsOn.not())
val hostCall: Action = HostAction("light.toggle", entityId)

RemoteBox(
    modifier = RemoteModifier
        .clip(RemoteRoundedCornerShape(11.rdp))
        .background(localIsOn.select(onColor, offColor))
        .clickable(toggle, hostCall), // both run on tap
)
```

The `ValueChange` fires synchronously in the player; the `HostAction`
is dispatched back to your app so it can call the real service. If the
host call fails, write back to the same binding by name to roll back.
This is the pattern in the `ClickableDemo` / `SwitchDemo` in the
androidx-main `compose/remote/integration-tests/demos`.

### Animation

Wrap a `RemoteFloat` target in `animateRemoteFloat` and the player
tweens at playback time — no per-frame wake-up on the host:

```kotlin
val target: RemoteFloat = localIsOn.select(1f.rf, 0f.rf)
val progress: RemoteFloat = animateRemoteFloat(rf = target, duration = 0.18f)
// drive position / alpha / color lerp from `progress`
```

## alpha08 pitfalls

All of these bit us building a Lovelace-card widget library against
`remote-creation-compose:1.0.0-alpha08`. Check whether your current
alpha still has each before designing around it.

### V2 applier: don't mix Remote and UI composables

alpha08 dropped the UI-applier fallback. Use `RemotePreview` /
`RememberRemoteDocumentInline` / `captureSingleRemoteDocumentV2` to
open a recording scope, and keep its body pure `@RemoteComposable`.
Mixing in a `Text(…)` from `compose.material` inside the recording
scope produces a broken document. Between scopes (e.g. a regular
`Column` holding multiple independent `RemotePreview` hosts) is fine —
that's how you model "one widget per card" dashboards.

### FlowLayout requires the experimental profile

`RemoteFlowRow` and `RemoteFlowColumn` emit op code 240, which isn't
in the baseline ANDROIDX profile. Build a shared profile once:

```kotlin
val androidXExperimental: Profile = Profile.create(
    Profile.OperationsSetType.Predefined,
    /* operations = */ null,
    Profile.PROFILE_ANDROIDX or Profile.PROFILE_EXPERIMENTAL,
)

RemotePreview(profile = androidXExperimental) { /* uses RemoteFlowRow */ }
```

### `RemoteDp(RemoteFloat)` is `internal`

You can't build a dp value that tracks a computed `RemoteFloat`, which
rules out `Modifier.offset(x = someProgress.toDp())` for sliding a knob
between two positions. Workaround: two weight-driven spacers on either
side of the fixed-size element:

```kotlin
RemoteRow {
    RemoteBox(modifier = RemoteModifier.weight(progress).fillMaxHeight())
    RemoteBox(modifier = RemoteModifier.size(KnobSize).clip(RemoteCircleShape)…)
    RemoteBox(modifier = RemoteModifier.weight(1f.rf - progress).fillMaxHeight())
}
```

But see the next item before you ship this.

### `select`-derived `RemoteFloat` breaks `.clip()` + inner content

Tracked upstream as b/504893436. When a `RemoteFloat` derived from
`RemoteBoolean.select(1f.rf, 0f.rf)` (or from `animateRemoteFloat`)
feeds into either:

- `lerp(...)` inside `Modifier.background(<RemoteColor>)`, or
- `RemoteRowScope.weight(<RemoteFloat>)`,

…the surrounding `RemoteBox` drops its rounded clip and the inner
layout fails to render. Only the computed background paints, in an
unclipped rectangle. Literal `0f.rf` / `0.5f.rf` / `1f.rf` work fine in
the same code.

Until the fix lands, either:

1. Pick the float on the host side from a Kotlin `Boolean` and pass
   `0f.rf` or `1f.rf` — you lose in-document click flip for the
   animated visual and have to re-encode after each host update, but
   the render is correct.
2. Avoid `weight`/`lerp`-with-dynamic-float visuals; use layouts
   where all dynamic values are static `RemoteColor` via `select`,
   which is unaffected.

### `.clip(RemoteCircleShape)` on small squares

A `RemoteBox.size(32.rdp).clip(RemoteCircleShape)` renders as a
rounded rectangle with ~8px corner radius, not a full circle. Happens
for both `RemoteCircleShape` and `RemoteRoundedCornerShape(percent = 50)`.
Workaround options when you care: draw the disc yourself via a
`RemoteCanvas` background, or use `remote-material3`'s
`drawShapedBackground` helper.

### Modifier chain order when mixing click + clip + background

On the outer shape of a clickable pill, keep the visual modifiers
first and `.clickable` last:

```kotlin
modifier
    .size(36.rdp, 22.rdp)
    .clip(RemoteRoundedCornerShape(11.rdp))
    .background(trackColor)
    .clickable(toggle, hostAction)   // <- last
```

Prepending `.clickable` ahead of `.clip`/`.background` in alpha08 can
swallow the clip + child content (shape renders as a rectangle with no
interior widgets). Probably related to how the clickable wrapper is
emitted into the document.

### `Row` with weighted children overruns `.size(...)`

`RemoteRow` with any `weight(…)` child claims all available width and
ignores a `Modifier.size(W, H)` on the row itself — the weight
behaviour treats the row as fillMax. When you need a fixed-size
weight-driven layout (e.g. the inside of a toggle switch), wrap in an
outer `RemoteBox` whose `.size(...)` is the hard constraint, and have
the inner row `.fillMaxSize()` inside it.

```kotlin
RemoteBox(modifier = RemoteModifier.size(TrackWidth, TrackHeight)
    .clip(...).background(...)) {
    RemoteRow(modifier = RemoteModifier.fillMaxWidth().fillMaxHeight()) {
        // weighted children are now bounded by the Box's size
    }
}
```

## Architecture: one document per card

For dashboard-shaped UIs (a list of independent cards that each update
on their own), resist the urge to author one big `.rc` for the whole
surface. Each widget instance owning its own document gives you:

- independent state updates (one card's binding change doesn't invalidate
  another's player),
- independent hosting (install one card as a Glance widget without
  dragging the whole dashboard into scope),
- smaller documents (easier to stay under `remote-player` memory budgets
  on Wear / embedded surfaces).

Model this in previews too. A "dashboard" preview is a regular
`Column` of `RemotePreview` hosts — *not* one `RemotePreview` wrapping a
`RemoteVerticalStack`:

```kotlin
@Preview(widthDp = 381, heightDp = 411)
@Composable
fun Dashboard() {
    Column(Modifier.fillMaxWidth()) {
        PlayerSlot(heightDp = 43)  { RenderCard(tileA) }
        PlayerSlot(heightDp = 43)  { RenderCard(tileB) }
        PlayerSlot(heightDp = 169) { RenderCard(entities) }
        PlayerSlot(heightDp = 149) { RenderCard(glance) }
    }
}

@Composable
private fun PlayerSlot(heightDp: Int, content: @Composable @RemoteComposable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(heightDp.dp)) {
        RemotePreview(profile = androidXExperimental, content = content)
    }
}
```

`RemoteDocPreview` (what `RemotePreview` delegates to) sizes the player
from `LocalWindowInfo.current.containerSize`, so each slot must have a
bounded size — a wrap-content parent won't work.

## Pixel-parity canvas sizing

When you're comparing RC renders to a reference screenshot, pin the
preview canvas in dp such that the RC PNG has the same pixel
dimensions as the reference PNG:

```
widthDp = round(referenceCapturePx.width / previewRenderDensity)
```

The `compose-preview` plugin renders at density **2.625**. If you
capture HA / dashboard references via Puppeteer at
`deviceScaleFactor = 2` (Retina), a 1000×444 px reference is 381×169 dp
at our render density. Lock that in a top-of-file comment so future
changes don't drift the canvas "for safety margin" and desync the
comparison.

When your converter emits content smaller than the reference card's
natural size, do not shrink the canvas to hide the gap — the
transparent padding is a signal to grow the widget, not the canvas.
