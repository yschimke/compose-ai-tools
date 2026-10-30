---
title: Motion captures
parent: Reference
nav_order: 17
permalink: /reference/motion/
---

# Motion captures

A short, deterministic recording of a component in motion, published
beside its ordinary still. Two annotations produce one: `@AnimatedPreview`
records motion the component runs *by itself*, and `@InteractionPreview`
records motion a *user* provokes by scripting a real pointer gesture
against the live composition.

## At a glance

| | |
|---|---|
| Kinds | `render/motion/apng`, `render/motion/gif` |
| Schema version | n/a (image-only) |
| Modules | `:data-motion-core` (pure JVM — APNG encoder, interaction-script expansion, frame-delay rationals) |
| Render mode | default |
| Cost | high (one full-size frame per interval across the window) |
| Token usage | Image-only — the payload is a `path`. See [token usage](https://github.com/yschimke/compose-ai-tools/blob/main/docs/TOKEN_USAGE.md). |
| Transport | path (APNG / GIF) |
| Platforms | Android · Desktop · shared |

## What it answers

- What does the component look like *between* its states — the spring
  the indicator rides, the shape a container morphs into under a
  finger, the fade of a state layer?
- Does the component actually respond to a real press, through its own
  `Modifier.clickable` / `toggleable` wiring?
- Does the motion survive a theme swap, a density change, an RTL
  locale?

A PNG of a navigation bar shows where the indicator ended up; it says
nothing about the spring that carried it there, which is exactly what
Material 3 Expressive changed. A lot of what a modern design system
*is* lives in the motion, so the interaction is the documentation.

## What it does NOT answer

- Motion capture is **renderer-side only** — it produces image
  artifacts, not a JSON payload, so it has no `kind` on the daemon's
  `initialize.capabilities.dataProducts` list and never round-trips
  through `data/fetch` or `data/subscribe`.
- It does not measure motion *performance*. Every frame is sampled on a
  paused clock, so the file says what the animation looks like, not what
  it costs — for that, instrument
  [`compose/recomposition`](../recomposition) over the same window.
- It is not a video recorder. The window is bounded (10 s for an
  interaction, 5 s for an animation) because every frame is a full-size
  image in the output.

## Both backends produce these

`@InteractionPreview` used to be implemented in the desktop renderer
only; the Robolectric lane produced no capture, failed confusingly, and
took the component's ordinary still down with it
([#4215](https://github.com/yschimke/compose-ai-tools/issues/4215)).
Both backends honour it now, and they share the parts that must agree:

- **The script expansion.** The recording window is *derived* — lead-in,
  plus one press and one settle window per target — by
  `InteractionScript` in `:data-motion-core`. Two backends deriving it
  separately would disagree silently, and a recording cut short
  mid-gesture is a plausible-looking file rather than an error.
- **The container.** One `ApngEncoder`, so a component's capture cannot
  differ in container bytes from its sibling's depending on which
  backend built it.

What is genuinely per-backend is how the pointer is injected and how
platform animations are advanced. On Robolectric the capture advances
the **main looper** alongside Compose's `mainClock` on every frame,
because Material's ripple is a platform `RippleDrawable` and would
otherwise stay frozen at frame 0 for the whole recording.

## Targeting: indices into the preview's clickable nodes

`@InteractionPreview(targets = [...])` addresses the composable's
clickable nodes in **layout order** — index 2 of a five-destination
navigation bar is its third destination. Both renderers resolve the
indices against the live semantics tree, **once**, against the
composition at rest, then dispatch to each node's centre.

- Resolving once is deliberate: a component that reflows as it responds
  would move the node the *next* index refers to, so a script written
  against the resting layout would silently start hitting different
  things partway through the recording.
- Indices rather than pixel coordinates (which every density and
  breakpoint would invalidate) and rather than label text (which every
  one of this repo's locales would invalidate).
- A target index that resolves to nothing **fails the capture loudly**.
  A recording of nothing happening is indistinguishable from a component
  that doesn't respond, and answering that question is the artifact's
  whole job.

There is no `Toggle` gesture: `targets = [0, 0]` taps one switch twice,
which is what a toggle already says.

## Framing

A capture is framed like the still it sits beside — cropped to the
composable's measured box on a wrapped axis, so a catalog can show one
in place of the other without the card resizing under the reader. The
measurement is the **maximum** across the recording, not the resting
one: a component that expands mid-gesture grows the canvas rather than
being clipped at the frame edge. Nothing is ever resampled.

## Why APNG by default

`@InteractionPreview` defaults to APNG; `@AnimatedPreview` keeps GIF for
backwards compatibility. Two properties decide it, and both are things
GIF *cannot* do rather than things it does worse:

- **Exact frame timing.** A GIF frame delay is an integer count of
  1/100 s, so 60 fps (16.67 ms) is not representable. An APNG delay is a
  rational `num/den`, so `1/60` is exact.
- **Full colour.** GIF's 256-entry palette bands precisely what these
  captures exist to show — state-layer fades, ripple gradients, and the
  anti-aliased edge of a shape mid-morph.

Ask for `format = MotionFormat.Gif` when reach in old tooling matters
more.

The output's extension always follows the format the capture is
written in: `@AnimatedPreview(format = MotionFormat.Apng)` writes
`<id>.apng` on the desktop (Compose Multiplatform) backend. The Android
backend does not encode `@AnimatedPreview` as APNG yet, so there an
APNG request is written as GIF to `<id>.gif` and discovery warns that
it was downgraded; `@InteractionPreview` honours `format` on both.

## Payload shape

Image-only artifacts. Output paths under
`build/compose-previews/renders/<id>.apng` (or `.gif`). A motion
capture never shares a slot with a still: a failure writes
`<output>.error.json` beside the motion output, and the preview's own
PNG is left intact.

## Enabling

Annotate the preview:

```kotlin
@CatalogComponent(id = "Switch/On", caption = "…")
@InteractionPreview(
  targets = [0, 0],
  caption = "Toggle off and back on — the thumb rides the theme's spatial spec.",
)
@Composable
fun SwitchOn() = Sticker("switch-on")
```

See
[`skills/compose-preview/references/capture-modes.md`](https://github.com/yschimke/skills/blob/main/skills/compose-preview/references/capture-modes.md)
for the full set of capture-mode annotations.

### Publishing it in a design catalog

A design catalog collects motion **per component**, off the component's own
`@Preview` — so the pairing above needs no extra wiring. Two things push the
recording onto a function of its own, and there it needs claiming or it
publishes nowhere:

- the component carries `@OverrideVariant` cells. The motion annotation rides
  every cell and the animated path ignores their knobs, so one recording
  publishes N byte-identical times, once per variant name;
- the recording needs a pinned canvas (`widthDp` + `heightDp` — every frame of a
  GIF must share one size) while the component's sticker wraps and is cropped.

Name the separate function from the component:

```kotlin
@Preview(widthDp = 200, heightDp = 120) annotation class MotionCanvas

@MotionCanvas @AnimatedPreview @Composable fun SwitchTransitionMotion() = Sticker { … }

@CatalogComponent(id = "Toggles/Switch", motionPreview = "SwitchTransitionMotion")
@Composable
fun SwitchButtonSticker() = Sticker { … }
```

A `catalog.spec.json` component's `motionPreview` does the same and wins over the
annotation. The named function needs no `@CatalogComponent` of its own — it adds
no card and no design-kit node, it only supplies the bytes. An authored recording
that **no** component claims renders and is then dropped at the join; the export
warns when it finds one.

## Recording a timeline the preview drives itself

`@AnimatedPreview` and `@InteractionPreview` stop at 5 s and 10 s. Some recordings tell a longer
story made of several changes, each driven by the host: a door opening, then dusk falling for
three simulated hours, then the door closing. Put that story in the preview and record it with
`compose-preview record`. You don't need to step frames by hand.

1. **The preview owns the timeline.** Drive state from a `LaunchedEffect` with `delay()`. A
   recording runs on the virtual frame clock, so every `delay` lands exactly where you put it,
   on every run:

   ```kotlin
   @Preview(widthDp = 360, heightDp = 300) // pin both: every frame shares one canvas
   @Composable
   fun GarageAtDusk() {
     var open by remember { mutableStateOf(false) }
     var elevation by remember { mutableStateOf(14f) }
     LaunchedEffect(Unit) {
       delay(1_000); open = true
       delay(2_500)
       for (step in sunset) { elevation = step; delay(45) } // three hours in four seconds
       delay(800); open = false
     }
     CardHost(snapshot = states(open, elevation))
   }
   ```

2. **Drive the host's inputs, not its outputs.** When the composable under test is a host,
   change what the host reads, and let it derive the rest. For example, a Remote Compose player
   fed named values computed from a data snapshot: change the snapshot. If you hand-build the
   values the host would push, you record your copy of the host rather than the host itself.

3. **The script only sets the length.** With no input to inject, two probes are enough:

   ```json
   [
     { "tMs": 0, "kind": "recording.probe", "label": "start" },
     { "tMs": 11000, "kind": "recording.probe", "label": "end" }
   ]
   ```

   ```shell
   compose-preview record --preview GarageAtDusk --script length.json --out garage.apng
   ```

4. **Choose the size, and the format for where it goes.** The cost is the number of frames times
   their size. An 11 s recording at the default 30 fps and full scale is 331 frames; for a 360 dp
   card that comes to a 40 MB APNG. `--fps 12 --scale 0.6` makes the same story 133 frames and
   9 MB.

   APNG is the default here, lossless and in full colour. For a chat app or an issue comment,
   ask for MP4 with `--out garage.mp4`, which needs `ffmpeg` on `PATH`. It plays inline in
   Slack, and is about 200 KB for the same recording.

   Encoding waits a minute plus two seconds for every million pixels across the frames, up to
   half an hour. A long recording at full scale therefore still finishes, just not quickly.

## Companion products

- [Scroll captures](../scroll) — `render/scroll/gif` for motion driven by scrolling rather than by a pointer or the component itself.
- [Focus](../focus) — `@FocusedPreview(gif = true)` for a keyboard-traversal walk.
- [History diff](../history) — `history/diff/regions` against a baseline to catch motion regressions frame-region by frame-region.
