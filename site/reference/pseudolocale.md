---
title: Pseudolocale
parent: Reference
nav_order: 8
permalink: /reference/pseudolocale/
---

# Pseudolocale

Render any preview through the `en-XA` (accent / expansion) or
`ar-XB` (bidi / RTL) pseudolocale at runtime, without
`pseudoLocalesEnabled` or `resConfigs` build-time configuration on the
consumer. Drop `locale = "en-XA"` (or `"ar-XB"`) onto a `@Preview`,
or set `localeTag` in `renderNow.overrides`, and the renderer
pseudolocalises every `stringResource(...)` lookup on the fly.

## At a glance

| | |
|---|---|
| Trigger | `localeTag` in `{en-XA, ar-XB}` (BCP-47) — same field as any other locale override. |
| Modules | `:data-pseudolocale-core` (published) · `:data-pseudolocale-connector` |
| Render mode | default |
| Cost | low |
| Token usage | n/a — visual-only effect, no JSON payload. |
| Transport | n/a |
| Platforms | Android (Compose Multiplatform follow-up). |

## What it answers

- **Layout expansion** (`en-XA`) — does the UI still hold together when every translation is ~30 % longer? Buttons that fit `Save` but break on `[Šàʌê ··]` show up immediately.
- **RTL correctness** (`ar-XB`) — does the layout flip cleanly to right-to-left, do start/end paddings switch sides, do icons mirror? No real Arabic translations needed; the framework's `LocalLayoutDirection = Rtl` plus per-word RLO/PDF marks is enough to surface ordering bugs.
- **Hard-coded strings** — text that *doesn't* appear pseudolocalised in the render is text that didn't go through `Resources.getString*` (or the Compose `stringResource` wrapper) and won't be translated either.

## What it does NOT answer

- It does not pseudolocalise hard-coded Kotlin string literals (`Text("Hi")`) — same limitation Android Studio's pseudolocale dropdown has. Use the gap as a checklist of strings that need extracting to `strings.xml`.
- It does not pseudolocalise CMP Desktop `compose.components.resources` lookups — that resolution path doesn't go through `LocalContext.resources`. Tracked as a follow-up.
- It does not score copy expansion against a per-language budget. Pair with the `text/strings` data product's `didOverflowWidth` / `truncated` fields if you want a CI gate.

## Use cases

- Catch text overflow before it ships: render every `@Preview` at `locale = "en-XA"` in CI and diff the resulting PNGs against a baseline.
- Verify RTL layouts on a screen-by-screen basis without translating to a real RTL language first.
- Sanity-check that a new feature's strings actually go through `stringResource` — anything left unchanged in the `en-XA` render is suspect.

## How to use

### Static `@Preview`

```kotlin
@Preview(name = "accent", locale = "en-XA", widthDp = 320, heightDp = 180)
@Composable
fun MyScreenAccent() {
  MyScreen()
}

@Preview(name = "bidi", locale = "ar-XB", widthDp = 320, heightDp = 180)
@Composable
fun MyScreenBidi() {
  MyScreen()
}
```

`./gradlew :app:renderAllPreviews` produces `MyScreenAccent_accent.png`
and `MyScreenBidi_bidi.png` alongside the default render. No app-level
config required: no `pseudoLocalesEnabled = true`, no `resConfigs`,
no AAPT2 flag.

### Daemon / `renderNow.overrides`

```jsonc
{
  "method": "renderNow",
  "params": {
    "previewId": "com.example.MyScreenKt#MyScreen",
    "overrides": { "localeTag": "en-XA" }
  }
}
```

The same planner runs in the daemon path. Drop the override, render
again, and you're back to the default locale. The daemon doesn't need
to be restarted between locales.

### Sample

[`samples/android/.../PseudolocalePreviews.kt`](https://github.com/yschimke/compose-ai-tools/blob/main/samples/android/src/main/kotlin/com/example/sampleandroid/PseudolocalePreviews.kt)
ships three previews — `default`, `accent`, `bidi` — driven from the
same body. Run `./gradlew :samples:android:renderAllPreviews` and
compare the three PNGs in `samples/android/build/compose-previews/renders/`.

## How it works

The override mechanism reuses `localeTag` rather than a new field, so
it slots into Studio's locale dropdown convention. Two pieces:

1. **Qualifier rewrite.** When `localeTag` matches `en-XA` or `ar-XB`,
   the renderer emits the Robolectric resource qualifier for the
   *base* locale (`en`) so the framework still finds `values/`
   strings, plus `ldrtl` for `ar-XB` so `Configuration` reports an
   RTL layout direction. Lives in
   `RenderEngine.applyPreviewQualifiers` (daemon) and
   `RobolectricRenderTest.applyPreviewQualifiers` (plugin path).
2. **Around-composable wrap.** A
   `PreviewOverrideExtension` planner in
   [`:data-pseudolocale-connector`](https://github.com/yschimke/compose-ai-tools/tree/main/data/pseudolocale/connector)
   maps the same `localeTag` value to a `PseudolocaleOverrideExtension`
   that:
   - Wraps `LocalContext` with a `ContextWrapper` whose `getResources()`
     returns a `Resources` subclass that pseudolocalises return values
     from `getText(int)` / `getQuantityText(int, int)`.
     `androidx.compose.ui.res.stringResource` walks
     `LocalContext.current.resources.getString(id)`, which routes
     through `getText(int)` — so every `stringResource(R.string.foo)`
     callsite picks up the wrapped path automatically.
   - Provides `LocalLayoutDirection = Rtl` for `ar-XB`.

Pure transform code (`Pseudolocalizer.accent`, `Pseudolocalizer.bidi`)
lives in `:data-pseudolocale-core` with no Android or Compose
dependency, so it can be unit-tested directly and reused by other
tooling. The transform follows AAPT2's `Pseudolocalizer.cpp`:
ASCII-letter accent map, ~30 % bracket-padded expansion, placeholder
preservation (`%1$s`, `{name}`, `<b>…</b>`).

## Comparison to AGP `pseudoLocalesEnabled`

AGP can build pseudolocalised `values-en-rXA/strings.xml` resources
into the consumer's APK at compile time, then load them at runtime via
the standard locale qualifier path. This data product takes the
opposite tack: leave `strings.xml` alone, intercept the lookup. The
trade-offs:

| | This product | AGP `pseudoLocalesEnabled` |
|---|---|---|
| Consumer config | none | `buildTypes.<type>.pseudoLocalesEnabled = true` |
| APK size | unchanged (renderer-only) | grows with pseudo resources |
| Runtime cost | `Resources.getText` wrap, ~free | none (resources resolved by framework) |
| Off-by-default | yes — only triggers when `localeTag` in `{en-XA, ar-XB}` | yes — opt-in per build type |
| Works in production app | no — preview / render only | yes |

For preview rendering specifically — which is what this tool is for —
the runtime path is strictly cheaper and simpler. Production apps that
ship pseudolocalised resources for QA still want AGP.
