# Wear Tiles

Tiles are the *glanceable* Wear surface — the horizontal swipe-layer next to
the watch face. They are **not** Compose. A tile is a `protolayout` tree
(proto-backed layout primitives) built on a background thread, serialised,
and rendered by the system. The preview pipeline treats them as a separate
kind of preview: different annotation, different entry-point signature,
different renderer.

## What's different from Compose previews

- **Not a composable.** A tile preview is a top-level function returning
  `TilePreviewData`, optionally taking a `Context`:
  ```kotlin
  fun HelloTilePreview(context: Context): TilePreviewData = …
  ```
  It does not use `@Composable`. Discovery recognises this shape by the
  return type, not by a target marker.
- **Different `@Preview`.** The annotation lives under
  `androidx.wear.tiles.tooling.preview`, not
  `androidx.compose.ui.tooling.preview`. Importing the wrong one silently
  produces zero tile previews.
- **No Compose runtime at play.** The renderer skips `ComposeTestRule` /
  `captureRoboImage` for tiles and uses `TileRenderer` to turn the
  protolayout into a view, which is then captured. Nothing about Compose
  dispatchers, `LaunchedEffect`, or `LocalInspectionMode` applies here.
- **Timeline-based output.** A tile emits a `Timeline` of entries. For
  previews we always pick the single-entry helper so the captured PNG
  corresponds to a stable snapshot rather than whichever entry happens to
  be active when the timer fires.

## Dependencies

| Purpose | Artifact |
| :--- | :--- |
| **Tile core primitives** (`TileBuilders`, `RequestBuilders`, `ResourceBuilders`) | `androidx.wear.tiles:tiles` |
| **Tile preview tooling** (`@Preview`, `TilePreviewData`, `TilePreviewHelper`) | `androidx.wear.tiles:tiles-tooling-preview` |
| **Protolayout layout/modifier types** | `androidx.wear.protolayout:protolayout` |
| **Dynamic expressions** (state, arithmetic, formatters) | `androidx.wear.protolayout:protolayout-expression` |
| **Material 3 components for protolayout** (`materialScope`, `primaryLayout`, `titleCard`, `textEdgeButton`) | `androidx.wear.protolayout:protolayout-material3` |
| **Preview device metadata** (`WearDevices.SMALL_ROUND`, `LARGE_ROUND`, `SQUARE`) | `androidx.wear:wear-tooling-preview` |

The Gradle plugin injects `androidx.wear.tiles:tiles-renderer` into the
renderer's classpath on demand — consumer apps do *not* need to list
`tiles-renderer` in their own dependencies. Keep the list above; leave
rendering infrastructure to the plugin.

## Authoring a tile preview

```kotlin
import android.content.Context
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.material3.titleCard
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.tooling.preview.Preview
import androidx.wear.tiles.tooling.preview.TilePreviewData
import androidx.wear.tiles.tooling.preview.TilePreviewHelper
import androidx.wear.tooling.preview.devices.WearDevices

@Preview(device = WearDevices.LARGE_ROUND)
fun StepsTilePreview(context: Context): TilePreviewData =
    TilePreviewData { request ->
        TilePreviewHelper.singleTimelineEntryTileBuilder(
            materialScope(context, request.deviceConfiguration) {
                primaryLayout(
                    titleSlot = { text("Steps".layoutString) },
                    mainSlot = {
                        titleCard(
                            onClick = clickable(),
                            title = { text("6,482".layoutString) },
                            content = { text("Goal 10,000".layoutString) },
                            modifier = LayoutModifier.contentDescription("Steps today"),
                        )
                    },
                    bottomSlot = {
                        textEdgeButton(
                            onClick = clickable(),
                            labelContent = { text("Details".layoutString) },
                            modifier = LayoutModifier.contentDescription("Show step details"),
                        )
                    },
                )
            },
        ).build()
    }
```

Things to notice:

- **`TilePreviewData { request -> … }`** — the lambda receives a
  `TileRequest`; extract `request.deviceConfiguration` to pass into
  `materialScope` so sizes and colours match the target device.
- **`TilePreviewHelper.singleTimelineEntryTileBuilder(...)`** — wraps a
  single layout into a `TileBuilders.Tile` with a trivial timeline. Call
  `.build()` at the end.
- **`materialScope(context, deviceConfiguration) { … }`** — opens the
  Material 3 scope. Components inside are the protolayout analogues of
  Compose widgets, not Compose composables.
- **`primaryLayout(titleSlot = …, mainSlot = …, bottomSlot = …)`** — the
  Material 3 scaffold for tiles. Slot-based, similar in intent to
  `ScreenScaffold` on the Compose side.
- **`.layoutString`** — the extension that lifts a Kotlin `String` into a
  `protolayout` `LayoutString`. Use it everywhere text is set.
- **`LayoutModifier.clickable()` / `.contentDescription(…)`** — modifiers
  apply to protolayout elements, not to Compose modifiers. Different
  import path, different type.

## Multi-preview

The same multi-preview pattern used for Compose works here — any annotation
class stacked with multiple `@Preview` entries fans out when applied:

```kotlin
@Preview(device = WearDevices.SMALL_ROUND, name = "Small Round")
@Preview(device = WearDevices.LARGE_ROUND, name = "Large Round")
internal annotation class MultiRoundTilesPreviews
```

Apply `@MultiRoundTilesPreviews` to a tile preview function and discovery
expands it to both devices. The plugin's discovery walks the annotation
class looking for `@Preview` (with cycle detection), so nested meta-preview
annotations work identically to the Compose side.

## Reference sample

Working examples live in
[`samples/wear/src/main/kotlin/com/example/samplewear/TilePreviews.kt`](../../../samples/wear/src/main/kotlin/com/example/samplewear/TilePreviews.kt):

- `HelloTilePreview` — minimal `titleCard` inside `primaryLayout`,
  exercising `materialScope` + `.layoutString`.
- `StepsTilePreview` — adds a `textEdgeButton` in `bottomSlot`, proving the
  M3 scaffold slots render independently.
- `MultiRoundTilesPreviews` — multi-preview fan-out across small and large
  round devices.

`./gradlew :samples:wear:renderAllPreviews` produces the tile PNGs alongside
the Compose ones; the preview manifest tags tile entries with
`kind = TILE` so tooling can treat them differently if needed.
