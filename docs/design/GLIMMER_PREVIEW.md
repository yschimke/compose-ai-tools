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
│   ─ EnvironmentBackdrop enum   │
│   ─ GlimmerSurface             │
└────────────────────────────────┘

                                    ┌──────────────────────────────────────┐
   PreviewOverrides.glimmerInput ───▶  :data-glimmer-input-connector       │
                                    │   ─ GlimmerInputOverrideExtension    │
                                    │     (AroundComposable: focus ring,   │
                                    │      swipe arrow, voice bubble)      │
                                    │   ─ GlimmerInputDataProductRegistry  │
                                    └──────────────────────────────────────┘

                                    ┌──────────────────────────────────────┐
   PreviewOverrides.glimmerEnv ─────▶  :data-glimmer-environment-connector │
                                    │   ─ post-render compositor           │
                                    │     (PNG + alpha → PNG over env)     │
                                    │   ─ canned environment assets        │
                                    └──────────────────────────────────────┘
```

The runtime AAR is sister to `:splash-preview-runtime` / `:notification-preview-runtime` — pure Compose Foundation under the hood, no compile dep on `:renderer-android`, `compileOnly(libs.compose.bom.compat)` so emitted bytecode rides whatever Compose the consumer carries. Glimmer itself is `compileOnly` for the same reason: the consumer's own `androidx.xr.glimmer:glimmer` version wins at runtime.

### Module: `:glimmer-preview-runtime`

```
glimmer-preview-runtime/
├── build.gradle.kts
└── src/main/kotlin/ee/schimke/composeai/preview/glimmer/
    ├── GlimmerPreview.kt        ← @GlimmerPreview meta-annotation
    ├── EnvironmentBackdrop.kt   ← enum + composite hint metadata
    └── GlimmerSurface.kt        ← GlimmerTheme + Color.Black bg helper
```

`@GlimmerPreview`:

```kotlin
@Preview(
  name = "Glimmer · Light",
  device = "spec:width=640px,height=480px,dpi=240",   // Studio's AI-Glasses preset
  showBackground = false,                              // additive display → transparent
  backgroundColor = 0L,                                // 0x00000000 (transparent, not black)
  fontScale = 1f,
)
@Repeatable
annotation class GlimmerPreview(
  /** Environment backdrop the post-render compositor pastes behind the captured frame. */
  val environment: EnvironmentBackdrop = EnvironmentBackdrop.Dark,
  /** Whether the focus-ring / touchpad / voice overlay should paint over this capture. */
  val showInput: Boolean = false,
)
```

Discovery picks `@GlimmerPreview` up as a multi-preview meta-annotation (the plugin's `ClassGraph` walk already handles `@Repeatable` chains with cycle detection — see [DiscoverPreviewsTask](../../gradle-plugin/src/main/kotlin/ee/schimke/composeai/plugin/DiscoverPreviewsTask.kt)). The `environment` and `showInput` fields are surfaced through a sidecar `data/glimmer/preview` JSON entry alongside the captured PNG so the two connectors below can read them at render time without us having to grow `PreviewParams` for Glimmer-specific knobs. Sister to how `@ScrollingPreview` plumbs scroll mode into `Capture.scroll` — feature wiring lives in its own data product, not the core schema.

`GlimmerSurface`:

```kotlin
@Composable
fun GlimmerSurface(
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  // GlimmerTheme is the authoritative root; Color.Black is the SKILL.md-mandated
  // background that renders as 100% transparent on additive displays.
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

1. **Make transparency the default that `@GlimmerPreview` cannot accidentally undo.** The meta-annotation sets `showBackground = false, backgroundColor = 0L` explicitly; even if the author stacks another `@Preview(showBackground = true)` underneath, the Glimmer entry stays transparent (multi-preview entries are independent — they don't merge).
2. **VS Code panel: checkerboard backdrop indicator.** When a capture has alpha-zero pixels covering more than say 30% of the frame, the panel paints a faint 8-px grey/white checkerboard *behind* the PNG (CSS layer, not baked into the file). Helps the user see at a glance that the capture is genuinely transparent rather than just black. Lands in `vscode-extension/src/webview/previewCard.ts` alongside existing display-filter chips.
3. **CLI `compose-preview show` flag: `--composite-onto <path>` / `--composite-onto-checker`.** For agents that don't have the VS Code panel, a one-shot post-process that pastes the alpha PNG onto a checkerboard or a user-supplied background image. Shares its implementation with the environment-connector compositor below.

None of these changes the renderer — the PNG keeps its alpha plane. They're all consumer-side overlays on top.

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
┌──────────────────────────────┐
│  environment compositor       │
│   1. load env asset (PNG/JPG) │
│   2. resize to capture bounds │
│   3. alpha-over: env + capture│
│   4. (optional) bloom pass    │
└──────────────────────────────┘
        │
        ▼
data product `glimmer/environment` (image/png) — written alongside the
original capture, never replacing it
```

Two output files per matching capture: `Foo_Preview.png` (the original, transparent) and `Foo_Preview.glimmerEnvironment.png` (the composited one). The original is what regression diffs and pixel tests compare against; the composited one is for human review and agent screenshots.

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
@GlimmerPreview(environment = EnvironmentBackdrop.Light)
@GlimmerPreview(environment = EnvironmentBackdrop.Dark)
@GlimmerPreview(environment = EnvironmentBackdrop.Busy)
@GlimmerPreview(environment = EnvironmentBackdrop.VeniceCanalCats)
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

@GlimmerPreview(showInput = true)        // focus ring + gesture arrow paint
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

A separate test in `:samples:android` re-uses the existing `TransparentBackgroundPreviewPixelTest` harness to assert that the *un-composited* `NowPlayingCard_…_Light.png` capture is still alpha-zero in its outer pixels — the environment compositor outputs a sibling file, it doesn't mutate the source.

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
