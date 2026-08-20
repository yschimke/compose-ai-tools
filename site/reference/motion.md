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

## Companion products

- [Scroll captures](../scroll) — `render/scroll/gif` for motion driven by scrolling rather than by a pointer or the component itself.
- [Focus](../focus) — `@FocusedPreview(gif = true)` for a keyboard-traversal walk.
- [History diff](../history) — `history/diff/regions` against a baseline to catch motion regressions frame-region by frame-region.
