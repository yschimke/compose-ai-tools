# Android XML resource previews

`@Preview` composables are the headline use case, but Android apps also ship
XML drawables and mipmaps that change between releases — vector logos,
adaptive launcher icons, animated splash transitions. The plugin renders
those alongside your composables, with no extra config.

`:<module>:discoverAndroidResources` walks every `res/drawable*/` and
`res/mipmap*/` directory in your variant's source set, classifies each XML
by root tag, and emits `build/compose-previews/resources.json` next to the
existing `previews.json`. `:<module>:renderAndroidResources` then renders
each capture to PNG / GIF under `build/compose-previews/renders/resources/`.

Modules with no matching XML resources self-no-op (a single empty
`resources.json` write), so consumers don't pay for what they don't use.

## Supported resource types

| Resource kind | Output | Variant axes |
|---|---|---|
| `<vector>` | PNG at intrinsic size × density | `densities`; explicit qualifier dirs (`drawable-night/`, `drawable-ldrtl/`, …) automatically picked up |
| `<adaptive-icon>` (mipmap-anydpi-v26) | PNG per (shape × style), demonstrating both App Search (full colour) and Home (shape-clipped, optionally 2-tone themed) launcher surfaces | `densities` × `shapes` (`CIRCLE`, `SQUIRCLE`, `ROUNDED_SQUARE`, `SQUARE`) × `styles` (`FULL_COLOR`, `THEMED_LIGHT`, `THEMED_DARK`, `LEGACY`) |
| `<animated-vector>` | GIF | `densities` |

Out of scope today: `<animation-list>`, `<shape>`, `<selector>`,
`<layer-list>`, `<level-list>`, `<ripple>`, raster `<bitmap>`. Adding a new
type is a localised plugin change.

## Tuning

```kotlin
composePreview {
    resourcePreviews {
        // Defaults shown — every knob is optional.
        enabled = true                              // set false to skip task registration
        densities = listOf("xhdpi")                 // implicit density fan-out
        shapes = listOf(                            // adaptive-icon mask set
            AdaptiveShape.CIRCLE,
            AdaptiveShape.SQUIRCLE,                 // Pixel / Material You default
            AdaptiveShape.ROUNDED_SQUARE,
            AdaptiveShape.SQUARE,
        )
        styles = listOf(                            // adaptive-icon style set
            AdaptiveStyle.FULL_COLOR,               // App Search appearance
            AdaptiveStyle.THEMED_LIGHT,             // Home, "Themed icons" on, light theme
            AdaptiveStyle.THEMED_DARK,              // Home, "Themed icons" on, dark theme
            AdaptiveStyle.LEGACY,                   // pre-O fallback
        )
    }
}
```

`THEMED_LIGHT` / `THEMED_DARK` need a `<monochrome>` element on the
`<adaptive-icon>` (Android 13+). Captures for icons missing the monochrome
layer are skipped at render time with a warning rather than failing the
build.

## AndroidManifest.xml link-don't-re-render

Manifest references — `android:icon`, `android:roundIcon`, `android:logo`,
`android:banner` on `<application>` / `<activity>` / `<activity-alias>` /
`<service>` / `<receiver>` / `<provider>` — are recorded in
`resources.json#manifestReferences` as an *index* into the rendered files,
not as separate captures. Tooling (CodeLens, doc generators, the VS Code
resource grid) links the manifest line to the same PNG that resource
discovery already rendered — no double rendering.

The contributor-facing data model is in `gradle-plugin/.../PreviewData.kt`
(`ResourceManifest`, `ResourcePreview`, `ResourceCapture`, `ResourceVariant`).
