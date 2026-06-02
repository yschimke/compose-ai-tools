# Preview support for Jetpack Compose Glimmer (Android XR display glasses)

Design for rendering Glimmer (`androidx.xr.glimmer:glimmer`) composables through this repo's `@Preview` pipeline. Glimmer is the Compose UI toolkit Google ships for display AI Glasses; the headline constraint is that the displays are **additive** — pure black renders as 100% transparent (light is added to the wearer's field of view, not subtracted), so previews have to preserve alpha all the way through to the captured PNG.

## Reference material

- **Official skill / sample.** [`android/skills` → `xr/display-glasses-with-jetpack-compose-glimmer`](https://github.com/android/skills/tree/main/xr/display-glasses-with-jetpack-compose-glimmer) — Google's canonical "how to build a Glimmer projected activity" skill, with per-component `references/*-source.md` and `references/*-samples-source.md` files for `Card`, `Button`, `Icon`, `ListItem`, `Stack`, `Surface`, `Text`, `TitleChip`. Treat the SKILL.md tokens table (colours, shapes, typography axes, depth levels) as the authoritative source for tooling defaults.
- **Studio preview behaviour.** [Preview your Jetpack Compose Glimmer UI with composable previews](https://developer.android.com/develop/xr/jetpack-xr-sdk/jetpack-compose-glimmer/preview) — Studio Canary's "AI Glasses" device in the preview-configuration picker plus an **environment chip** with **Light / Dark / Busy** background presets to approximate viewing conditions.
- **Library coordinates / release notes.** [`androidx.xr.glimmer`](https://developer.android.com/jetpack/androidx/releases/xr-glimmer) for the toolkit, [`androidx.xr.glimmer:glimmer-google-fonts`](https://developer.android.com/develop/xr/jetpack-xr-sdk/jetpack-compose-glimmer/whats-included) for `createGoogleSansFlexTypography()`.
- **Toolchain floor.** Mobile project must target `compileSdk = 37` or higher (per the official skill's prerequisites). This is one notch above the current Robolectric ceiling — see [Robolectric SDK gating](#robolectric-sdk-gating) below.

## Scope

In:

1. A `:glimmer-preview-runtime` module — a tiny JVM-friendly helper that wraps a `@Preview` so the captured PNG looks like what Studio's Glimmer preview pane shows, including the additive-display approximation.
2. A `@GlimmerPreview` multi-preview meta-annotation that flips the defaults that matter (transparent background, canvas size, density, font scale, inspection on / off) and fans across the **Light / Dark / Busy** environment presets from Studio.
3. A `:data-glimmer-input-connector` data extension — XR input affordances (focus ring, 1-D touchpad swipe arrow, voice transcript bubble) as a planner-driven `AroundComposableExtension`, shaped like `:data-touch-overlay-connector`.
4. A `:data-glimmer-environment-connector` data extension — composites the alpha-preserved capture onto a background image (Light / Dark / Busy by default; a small bundle of "world view" presets shipped alongside).

Out:

- Real-device capture / emulator integration. Use the Android Studio AI Glasses emulator for that — this work targets the offline-render pipeline only.
- 3-D scene XR (`androidx.xr.compose`'s `Subspace` / `SpatialPanel`). A separate toolkit with a separate rendering model; tracked elsewhere.
- Wear OS or phone-projection rendering — different display models, different stacks.

## Architecture

Four pieces, three modules. The runtime is the only piece a consumer pulls into their app; the two connectors plug into the daemon's existing override / data-product machinery.

```
┌────────────────────────────────┐
│  consumer module (Android lib) │
│   ─ @GlimmerPreview …          │  ← multi-preview meta-annotation
│   ─ GlimmerSurface { … }       │  ← optional helper wrapping GlimmerTheme
└──────────────┬─────────────────┘
               │ depends on
┌──────────────▼─────────────────┐
│  :glimmer-preview-runtime      │  (AAR, compileOnly Compose, compileOnly Glimmer)
│   ─ @GlimmerPreview            │
│   ─ @GlimmerPreviewLight/      │
│     Dark / Busy / Venice…      │  ← one per env so discovery gives them
│   ─ @GlimmerPreviewInput       │     distinct PreviewInfo.ids
│   ─ GlimmerSurface             │
└────────────────────────────────┘

                                    ┌──────────────────────────────────────┐
   PreviewInfo.name ── "· Input" ──▶  :data-glimmer-input-connector       │
                                    │   ─ GlimmerInputOverrideExtension    │
                                    │     (AroundComposable: focus ring,   │
                                    │      swipe arrow, voice bubble)      │
                                    │   ─ GlimmerInputDataProductRegistry  │
                                    └──────────────────────────────────────┘

                                    ┌──────────────────────────────────────┐
   PreviewInfo.name ── "· Light"  ─▶  :data-glimmer-environment-connector │
                  ── "· Dark"  ──┘  │   ─ post-render ADD-blend            │
                  ── "· Busy"  ──┐  │     compositor (env + capture)       │
                  ── "· Venice" ─┘  │   ─ canned environment assets        │
                                    └──────────────────────────────────────┘
```

The runtime AAR is sister to `:splash-preview-runtime` / `:notification-preview-runtime` — pure Compose Foundation under the hood, no compile dep on `:renderer-android`, `compileOnly(libs.compose.bom.compat)` so emitted bytecode rides whatever Compose the consumer carries. Glimmer itself is `compileOnly` for the same reason: the consumer's own `androidx.xr.glimmer:glimmer` version wins at runtime.

### Module: `:glimmer-preview-runtime`

```
glimmer-preview-runtime/
├── build.gradle.kts
└── src/main/kotlin/ee/schimke/composeai/preview/glimmer/
    ├── GlimmerPreview.kt         ← @GlimmerPreview baseline annotation
    ├── GlimmerEnvironmentPreviews.kt
    │                             ← @GlimmerPreviewLight / Dark / Busy /
    │                               VeniceCanalCats / Input meta-annotations
    └── GlimmerSurface.kt         ← GlimmerTheme + Color.Black bg helper
```

`@GlimmerPreview` and per-environment variants:

```kotlin
// Baseline annotation — one transparent / additive capture per stacked instance.
// Authors who only want the raw capture (no env compositing) use this directly.
@Preview(
  name = "Glimmer",
  device = "spec:width=960,height=720,dpi=160",       // AI-Glasses preset, calibrated below
  showBackground = true,                              // see "Capture encoding" below
  backgroundColor = 0xFF000000L,                      // opaque black = additive-zero baseline
  fontScale = 1f,
)
annotation class GlimmerPreview

// One annotation per Studio-parity environment. Each carries a distinct `name`
// so discovery generates a distinct PreviewInfo.id and the four captures don't
// collapse into one. The connector reads the env intent off the name suffix
// (`Glimmer · Light` → `light`) — no schema changes to `PreviewParams`.
@Preview(name = "Glimmer · Light", device = "spec:width=960,height=720,dpi=160",
         showBackground = true, backgroundColor = 0xFF000000L)
annotation class GlimmerPreviewLight

@Preview(name = "Glimmer · Dark", …)        annotation class GlimmerPreviewDark
@Preview(name = "Glimmer · Busy", …)        annotation class GlimmerPreviewBusy
@Preview(name = "Glimmer · VeniceCanalCats", …)
annotation class GlimmerPreviewVeniceCanalCats

// Input-overlay variant (focus ring + gesture arrow + voice bubble paint).
@Preview(name = "Glimmer · Input", …)       annotation class GlimmerPreviewInput
```

**Why a family of annotations instead of one `@GlimmerPreview(environment = …)`?** Discovery in this repo (`DiscoverPreviewsTask` + `ClassGraph`) keys captures on the *inner* `@Preview`'s `name`, which means a single `@Repeatable annotation class GlimmerPreview(val environment: Environment)` would collapse all instances to one capture — multiple `@GlimmerPreview(Light) @GlimmerPreview(Dark)` would reduce to a single `Glimmer` entry, with no way to recover the parameter value at render time. The per-env family side-steps this by giving each variant its own `name`. **Trade-off:** authors who want a new env have to add a new annotation rather than passing a value; we accept that for the four bundled environments and document an "extensible discovery" path (let the plugin synthesise one `@Preview` per `@Repeatable` instance, surfacing the annotation params alongside the name) as a phase-N enhancement.

The environment intent travels via the `name` suffix into the connector's planner — no Glimmer-specific schema in `PreviewParams`. The plugin doesn't grow Glimmer awareness; the per-env names happen to be parseable, and the env compositor is the only thing that parses them.

### Capture encoding: additive-RGB vs alpha-RGBA

The captured PNG can encode "this pixel is empty on an additive display" two ways:

- **Encoding A (alpha-RGBA).** Don't paint a background. Captured PNG has `alpha = 0` outside the drawn Glimmer light, RGB matches Glimmer's drawn colours. Compositing onto an environment uses standard `OVER` blend.
- **Encoding B (additive-RGB).** Paint `Color.Black` as the SKILL.md mandates. Captured PNG is fully opaque, RGB = `(0,0,0)` outside drawn light. Compositing onto an environment uses `ADD` blend: `result = env + capture` (per-channel, clamped to 255).

**We pick Encoding B.** Three reasons:

1. **Matches on-device reality.** Real additive displays add light to the wearer's field of view; there is no "punch-through" channel. `ADD` blend reproduces what the wearer sees; `OVER` blend reproduces what a phone-emulator screenshot would look like.
2. **SKILL.md compliance.** The official skill says "**Mandatory:** Set pure black background (`Modifier.background(Color.Black)`) on root Projected Activity container." If `GlimmerSurface` painted transparent instead, every Glimmer composable would see a different `LocalContentColor` resolution path than it does on-device, and surface-tint heuristics inside Glimmer's components would compute against the wrong base.
3. **Diff stability.** Encoding A's "empty" pixels are RGB-undefined (whatever the renderer fills with). Two backends could write different RGBs in transparent pixels and still pass `alpha = 0` checks. Encoding B's "empty" pixels are exactly `(0,0,0)` — pixel-equal across renderers and survives lossy encoders without ambiguity.

Cost: the sample's "outer pixels are empty" test becomes `assert RGB == (0,0,0)` instead of `assert alpha == 0`. Same one-liner, just on a different channel.

Codex flagged ([review thread](https://github.com/yschimke/compose-ai-tools/pull/1560#discussion_r3317608516)) that an early draft had `showBackground = false` + `backgroundColor = 0` on `@GlimmerPreview` while *also* keeping `Color.Black` in `GlimmerSurface` — that combination would have produced an opaque-black PNG and tripped the existing transparent-corner test in `:samples:android`. The fix above commits to Encoding B end-to-end: `@GlimmerPreview` declares `showBackground = true, backgroundColor = 0xFF000000L` so the capture intent is unambiguous.

`GlimmerSurface`:

```kotlin
@Composable
fun GlimmerSurface(
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  // GlimmerTheme is the authoritative root; Color.Black is the SKILL.md-mandated
  // additive-zero base that renders as transparent on-device and as
  // RGB(0,0,0) in our additive-RGB captures (Encoding B above).
  Box(modifier.fillMaxSize().background(Color.Black)) {
    GlimmerTheme(
      typography = createGoogleSansFlexTypography(),
      content = content,
    )
  }
}
```

The helper is opt-in. Authors can compose `GlimmerTheme` themselves; this is just the shortest path for one-off previews. It deliberately doesn't read `isSystemInDarkTheme()` — Glimmer doesn't have a light/dark dichotomy in the Material sense (everything sits on the additive display's transparent base).

### Transparent backgrounds (general, beyond Glimmer)

Alpha-preserving captures are already a thing — `samples/android/.../TransparentBackgroundPreviewPixelTest` asserts that `@Preview` with no `showBackground` / `backgroundColor` writes an RGBA PNG with alpha=0 outside the drawn region. That's the **Roborazzi parity contract**: `RoborazziComposeOptions.background(showBackground = false, backgroundColor = 0)` and our default capture path agree.

What Glimmer needs on top:

1. **Transparent siblings, even when the preview declares a background.** Today the renderer captures exactly what `showBackground` / `backgroundColor` ask for: opt into a background and the alpha channel is gone. The Glimmer environment compositor wants the choice — opaque additive-RGB (Encoding B) for `ADD`-blending onto the env, *and* the same composition rendered with `showBackground = false, backgroundColor = 0` (Encoding A) for `OVER`-blending into checkerboards, custom backdrops, or the panel UI. The renderer grows a per-capture `transparentSibling: Boolean` option (already plumbable through the existing `Capture` schema as a non-default field). When set, every render writes two files: `Foo.png` honouring the declared background and `Foo.transparent.png` with the background flag stripped. The compositions are independent — same `Capture.advanceTimeMillis`, same `Capture.scroll`, same paused-clock advance — but the second pass forces `showBackground = false, backgroundColor = 0` at the `RuntimeEnvironment` / `CompositionLocalProvider` seam so the captured tree never paints a background. Per-env Glimmer annotations enable it implicitly (the env compositor needs both encodings); consumers without Glimmer can opt in via `composePreview { transparentSiblings = true }` in their Gradle DSL, or per-preview with a future `@CaptureTransparent` annotation. Cost is one extra render pass per opted-in capture — measurable but bounded; the discovery-side dedupe already knows how to track sibling outputs (same pattern as `<id>_PARAM_<idx>.<ext>`).
2. **Make transparency the un-overridable default of the *baseline* `@GlimmerPreview` annotation.** The baseline annotation (not the per-env family) sets `showBackground = false, backgroundColor = 0L` explicitly; even if the author stacks another `@Preview(showBackground = true)` underneath, the Glimmer entry stays transparent (multi-preview entries are independent — they don't merge). The per-env family uses Encoding B and relies on (1) for the transparent companion.
3. **VS Code panel: checkerboard backdrop indicator.** When a capture has alpha-zero pixels covering more than say 30% of the frame, the panel paints a faint 8-px grey/white checkerboard *behind* the PNG (CSS layer, not baked into the file). Helps the user see at a glance that the capture is genuinely transparent rather than just black. Lands in `vscode-extension/src/webview/previewCard.ts` alongside existing display-filter chips. Works on the `.transparent.png` sibling automatically once (1) ships.
4. **CLI `compose-preview show` flag: `--composite-onto <path>` / `--composite-onto-checker`.** For agents that don't have the VS Code panel, a one-shot post-process that pastes the alpha PNG onto a checkerboard or a user-supplied background image. Shares its implementation with the environment-connector compositor below.

(1) is the key user-facing change. The rest are layered on top — once the renderer can produce both encodings on demand, the panel, the CLI, the env compositor, and any future consumer (a11y overlay, wallpaper, screenshot tests) all get to pick which one they want without each having to roll their own background-strip pass.

### Data extension: `:data-glimmer-input-connector`

Shape mirrors `:data-touch-overlay-connector`. Three overlays composed onto the same `AroundComposable` layer, gated by the override fields:

- **Focus ring.** Reads the current `LocalGlimmerFocus.value` (or the equivalent — exact API confirmed against `:references/focus-source.md` from the official skill). Paints a 1-px outline rounded to Glimmer's standard `RoundedCornerShape(36.dp)` around the focused element so the captured PNG shows where the next tap/swipe would land. Drives a `focusable = true` knob on `@GlimmerPreview`.
- **Touchpad gesture arrow.** Glimmer input is **1-dimensional** (one finger, axis is contextual). The overlay paints a short arrow + label (`▲ swipe up`, `▶ swipe right`, `· tap`, `↺ back`) at the bottom-centre of the frame for one-shot captures. For live-recording sessions (where this connector layers on top of `:data-touch-overlay-connector`), the touch-overlay's existing pointer pulses already cover the actual finger position; the arrow becomes a *gesture label* drawn above them.
- **Voice transcript bubble.** A small mic icon + a one- or two-line `"Hey Google, …"` transcript bubble pinned to the top edge. Drives a hidden `LocalGlimmerVoiceInput` CompositionLocal so the inner composition can react if the author wires it up; absent that, it's a visual-only annotation on the PNG.

Wire-format (additive in `daemon/core/.../protocol/Messages.kt`):

```kotlin
@Serializable
data class GlimmerInputOverride(
  val focusedSemanticsId: Int? = null,
  val gesture: GlimmerGesture? = null,        // Tap, SwipeUp, SwipeDown, SwipeLeft, SwipeRight, Back
  val voiceTranscript: String? = null,
)

@Serializable
data class PreviewOverrides(
  // …existing fields…
  val glimmerInput: GlimmerInputOverride? = null,
  val glimmerEnvironment: GlimmerEnvironmentOverride? = null,
)
```

Per the project's "no special-casing in core" rule in [AGENTS.md](../AGENTS.md) → *Important constraints*, the renderer / daemon / `JsonRpcServer.encodeRenderPayload` don't grow new branches. The connector module registers a `GlimmerInputPreviewOverrideExtension : DataExtension<PreviewOverrides>` in `DaemonMain` exactly like `WallpaperPreviewOverrideExtension`, and the rest is just wiring through the existing override pipeline.

CLI surface: `compose-preview render --glimmer-input gesture=swipeUp,voice="play next track"` synthesises the override JSON. Same args-synthesiser shape as profile files use today.

### Data extension: `:data-glimmer-environment-connector`

A **post-render** compositor — runs after `RenderResult` lands, not as an `AroundComposable`. Rationale: keeping the environment image out of the actual `@Preview` composition means the inner UI's `MeasureScope` / colour sampling / contrast tests see exactly what they would on a real additive display (a transparent background) and the env image doesn't accidentally leak into anything the user composes against `LocalContentColor`.

Pipeline:

```
captured PNG (RGBA, alpha=0 outside drawn light)
        │
        ▼
┌──────────────────────────────────────┐
│  environment compositor              │
│   1. load env asset (PNG/JPG)        │
│   2. resize to capture bounds        │
│   3. ADD-blend: env + capture (per   │
│      channel, clamp to 255)          │
│   4. (optional) bloom pass           │
└──────────────────────────────────────┘
        │
        ▼
data product `glimmer/environment` (image/png) — written alongside the
original capture, never replacing it
```

`ADD` blend, not `OVER`. Per the [encoding section](#capture-encoding-additive-rgb-vs-alpha-rgba) above, the capture is opaque additive-RGB — black pixels carry "add zero" intent, light pixels carry "add this colour" intent. `OVER` would paint the black pixels on top of the environment and obliterate it everywhere the Glimmer UI didn't draw; `ADD` is the operation a real additive display performs.

Two output files per matching capture: `Foo_GlimmerLight.png` (the original additive-RGB capture, opaque) and `Foo_GlimmerLight.glimmerEnvironment.png` (the composited one). The original is what regression diffs and pixel tests compare against (RGB-stable across renderers); the composited one is for human review and agent screenshots.

Bloom pass is optional and off by default — additive displays bloom on a real device because the light source is finite, but the simulated version makes diffs noisier than they need to be. Tracked separately; ship without it first.

#### Environment assets

```
data/glimmer-environment/assets/src/main/resources/glimmer-environments/
├── light.png              ← Studio "Light" parity: bright outdoor sky
├── dark.png               ← Studio "Dark" parity: nighttime city street
├── busy.png               ← Studio "Busy" parity: market crowd
├── kitchen-counter.png    ← bonus: utility scene (cooking apps, timers)
├── forest-trail.png       ← bonus: navigation / hiking apps
└── venice-canal-cats.png  ← bonus + delight: a Venice canal with cat
                            gondoliers. Cats wear striped shirts. Yes,
                            actually. Source-of-truth illustration is
                            small (≤200 KB), checked in as a PNG, only
                            loaded when `environment = "venice-canal-cats"`
                            is selected. See the
                            [art license note](#asset-licensing) below.
```

The first three are the floor — they have to be there because the official Studio docs name them by spec, and an author iterating on contrast needs the same chip set we'd give them in Studio. The bonus scenes (and any community-contributed `contrib/glimmer-environments/*`) are pure illustration that the compositing is real: the same `@GlimmerPreview` fans across all of them with no source-code change.

The compositor accepts a `file://` URI for `environment` too, so an app author can ship their own "this is what our checkout flow looks like in a moving car" asset alongside their tests without us having to centralise the registry.

### Sample: `samples/android/.../GlimmerPreviews.kt`

One sample file in the existing `:samples:android` module — Glimmer doesn't warrant a new sample module since its only consumer-visible surface is `@GlimmerPreview`. The file demonstrates:

```kotlin
@GlimmerPreviewLight
@GlimmerPreviewDark
@GlimmerPreviewBusy
@GlimmerPreviewVeniceCanalCats
@Composable
fun NowPlayingCard() {
  GlimmerSurface {
    Card(onClick = {}) {
      ListItem(
        title = { Text("Lake of Fire") },
        supportingContent = { Text("Nirvana — MTV Unplugged in New York") },
        leadingContent = { Icon(Icons.Outlined.MusicNote, contentDescription = null) },
      )
    }
  }
}

@GlimmerPreviewInput                     // focus ring + gesture arrow paint
@Composable
fun FocusableMenu() {
  GlimmerSurface {
    VerticalList(verticalArrangement = Arrangement.spacedBy(20.dp)) {
      items(menuItems) { ListItem(title = { Text(it.label) }) }
    }
  }
}
```

A four-way Venice / Light / Dark / Busy fan demonstrates the additive-display approximation directly — same code, same `GlimmerSurface`, four different rendered scenes, the contrast shifts visibly between the four. (Reviewing a preview-diff PR that bumps Glimmer's `surface` colour token, you see the gondolier cats at the same time as you see the unreadable text in Busy. Good.)

A new `GlimmerCaptureAdditivePixelTest` in `:samples:android` asserts that the *un-composited* `NowPlayingCard_Glimmer · Light.png` capture is opaque (`alpha == 0xFF` in every pixel) and that the four corners read RGB `(0, 0, 0)` (additive-zero). This is the Encoding-B mirror of `TransparentBackgroundPreviewPixelTest` — same shape, different channel. The environment compositor outputs a sibling file, it doesn't mutate the source.

### Calibrating to Studio's Glimmer model

Studio's preview pane exists to check two *quantitative* rules; we pin both so our previews aren't merely "additive-looking" but numerically aligned to Studio (`:samples:xr-glimmer`'s `GlimmerContrast` + `GlimmerContrastTest`):

- **Angular sizing.** Glimmer measures UI in visual angle, not pixels: the [type guidance](https://developer.android.com/design/ui/ai-glasses/guides/styles/type) pins the display at **30 pixels-per-degree** with a minimum readable text size of **0.6° = 18px**, which the [official skill](https://github.com/android/skills/tree/main/xr/display-glasses-with-jetpack-compose-glimmer) restates as **18sp**. The identity `18sp == 18px == 0.6°` only holds at **density 1.0**, so `AI_GLASSES_DEVICE_SPEC` is `spec:width=960,height=720,dpi=160` (density 1.0; a 960×720-px / 32°×24° canvas at 30 PPD), *not* a phone-style `dpi=240`. At the old `dpi=240` (density 1.5) every `.sp`/`.dp` Glimmer component rendered 1.5× larger in angle than Studio shows, so legibility read optimistically.
- **Contrast.** The skill mandates *"at least a 70% tone difference between foreground and background using the HCT color space"*; HCT tone == CIELAB L\*, so the bar is `ΔL* ≥ 70`. `GlimmerContrastTest` reproduces the additive composite (`BlendMode.Plus` of white text / `#262626` surface over each backdrop) straight from the source env images and measures the white-text-vs-panel tone gap. Calibrated result: **additive-zero ≈ 85** (the only surface that clears 70), **Dark ≈ 64**, **Busy ≈ 42**, **VeniceCanalCats ≈ 37** — i.e. *no* real backdrop clears Studio's bar, and Busy/Venice are decisively unreadable. That turns the informal "unreadable text in Busy. Good." above into a regression gate: brighten the `surface` token, swap a backdrop, or break the blend and the relevant bound trips.

## Toolchain notes

### Robolectric SDK gating

Glimmer needs `compileSdk = 37`. Our renderer bundles Robolectric 4.16.1, which goes up to SDK 36 and additionally requires JDK 21+ for SDK 36. SDK 37 is not in 4.16.1; consumers wanting `@GlimmerPreview` to actually render will have to wait for a Robolectric bump (tracked in `:renderer-android`'s upgrade cadence per [RENDERER_COMPATIBILITY.md](../RENDERER_COMPATIBILITY.md)) **or** restrict the Glimmer surface their `@Preview`s touch to API-36-resolvable subsets. Worth surfacing as a `compose-preview doctor` finding:

> `glimmer.compileSdk-too-low` — module declares `androidx.xr.glimmer:glimmer` but Robolectric tops out at SDK 36; rendering will fail at composition time. Bump the renderer or remove the dep.

### CMP Desktop

Glimmer is Android-only — no Compose Multiplatform target. The Desktop renderer (`DesktopRendererMain`) shouldn't crash on a `@GlimmerPreview`-discovered entry; the discovery step records the annotation as a standard `@Preview` plus a sidecar, and Desktop will try to render it. Two options:

1. **Skip with a render-error.** `RenderErrorKind.SkippedUnsupportedBackend` (new variant) lets CMP authors put `@GlimmerPreview` on shared-module composables for parity without the build failing on Desktop.
2. **Best-effort.** Glimmer's pure-Compose primitives mostly resolve on Desktop (no Android framework), the additive-display wrapper is just `Box(background = Color.Black)`, the env composite is offline post-processing. We can probably render most Glimmer previews on Desktop; the missing pieces (`GoogleSansFlexTypography` font fetch, glasses-specific accessibility services) degrade gracefully.

Prefer option 2 unless we hit a concrete blocker — keeps the CMP shared-module story symmetric with Material 3.

### Asset licensing

The Light / Dark / Busy environment PNGs need an explicit licence trail. Either:

- Commission three CC0 scenes from a single illustrator, ship inline. Total cost is small; gives us one set of named credits.
- Use stock photos under a permissive licence with attribution in `NOTICE`. Faster but the attribution drift is annoying.

The Venice-canal-cats scene and other "delight" presets we own outright (commission or hand-roll). Don't pull from random web sources — if a release-please tag goes out, the assets ship in the Maven artifact and a licence violation in there is a long-tail support headache.

## Phases

Roughly the order we'd land this:

1. **`:glimmer-preview-runtime` skeleton** — `@GlimmerPreview` annotation, `GlimmerSurface` helper, build script mirroring `:splash-preview-runtime`. Discoverable, renders against transparent background, no overlays / environment yet.
2. **Sample previews + golden PNGs** — the `GlimmerPreviews.kt` file above checked into `:samples:android`, with pixel assertions on alpha preservation. CI proves the pipeline survives an end-to-end Glimmer capture.
3. **`:data-glimmer-environment-connector` (Light/Dark/Busy only)** — the three Studio-parity presets, the post-render compositor, the second-output-file pattern. Wired into `DaemonMain` + a CLI flag `--glimmer-environment <id>`. This is the easier of the two extensions to land first because it doesn't touch the composition pipeline.
4. **`:data-glimmer-input-connector`** — focus ring + gesture arrow + voice bubble overlays. The `LocalGlimmerFocus` integration is the part that needs the most experimentation against the actual API surface; defer until 1–3 are stable.
5. **VS Code checkerboard + CLI `--composite-onto-checker`** — the general transparent-background affordance; lands independent of 1–4, doesn't depend on Glimmer being present.
6. **Bonus environment scenes (Venice gondolier cats, kitchen counter, forest trail)** — once 3 is shipping. Pure data, no protocol churn. Loaded lazily so consumers who never opt in don't pay the asset weight.
7. **`compose-preview doctor` finding for SDK-37 gating** — closes the loop once enough consumers hit the Robolectric ceiling.

Each phase is independently shippable; nothing later than phase 1 has to land before we can put `@GlimmerPreview` in front of an early adopter and see if the ergonomics hold up.
