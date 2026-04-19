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
