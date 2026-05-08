---
title: Display filters
parent: Reference
nav_order: 16
permalink: /reference/displayfilter/
---

# Display filters

Apply post-process colour-matrix transforms to each captured PNG —
grayscale (Android's "bedtime mode"), classic colour inversion, and
colour-blindness simulations (deuteranopia, protanopia, tritanopia).
Each enabled filter produces an additional PNG sibling alongside the
base capture, so an agent can verify a UI still communicates without
hue, holds up under inversion, or remains readable for colour-vision
deficiencies.

## At a glance

| | |
|---|---|
| Kinds | `displayfilter/variants` |
| Schema version | 1 |
| Modules | `:data-displayfilter-core` (published) · `:data-displayfilter-connector` |
| Render mode | default — purely post-capture, no Compose changes |
| Cost | very low — one matrix multiply per pixel per enabled filter on the captured `BufferedImage` |
| Token usage | ~10 tok inline (manifest is tiny — `{filter, path}` per variant); +~1.5 k per filtered PNG read. |
| Transport | inline (JSON manifest) · variant PNGs ride as `extras` |
| Platforms | Android, Desktop |
| Status | Wired across both render paths — the daemon (VS Code, MCP, CLI daemon mode) and the Gradle-plugin direct path (`:samples:cmp:renderAllPreviews`) both emit variants and the manifest after each capture. |

## What it answers

- **Hue-only signalling** — does this UI still distinguish error/success/warning when the display is grayscale? Run with `grayscale` and look at the variant PNG: if a red error pill collapses to the same grey as a green success pill, hue is carrying signal that contrast/iconography should.
- **Inversion robustness** — does the UI hold up when the user has Android's "Color inversion" accessibility setting on? `invert` reveals hard-coded white/black assumptions and assets that don't react to dark theme.
- **Colour-vision deficiency** — what does this design look like to a viewer with the most common forms of colour blindness? `deuteranopia` (~6% of males), `protanopia`, and `tritanopia` simulate cone-loss using the Machado / Oliveira / Fernandes 2009 LMS-cone-loss matrices at severity 1.0.

## What it does NOT answer

- It is **simulation**, not **correction**. Android's accessibility "Color correction" setting applies an error-shifted matrix that *compensates* for the deficiency; that's a separate transform that belongs in the a11y bag.
- It does not perform contrast checks — for WCAG-style audits use [`a11y/atf`](./a11y).
- It does not understand image *content* (e.g. embedded photos), so smart-invert-style preservation of media is not modelled.

## Use cases

- PR review: render any UI PR with `grayscale` enabled and skim the variant column — hue-only state is the most common reviewer-blind regression.
- Accessibility audit: sweep the design system once with `deuteranopia` to find palette pairs that collapse for the most common colour-vision deficiency.
- Bedtime / digital-wellbeing parity: confirm the app still looks deliberate when Android's bedtime grayscale schedule kicks in, not just "all icons gone grey".

## Payload shape

`DisplayFilterArtifact`, `DisplayFilterArtifacts` — defined in
[`:data-displayfilter-core`](https://github.com/yschimke/compose-ai-tools/tree/main/data/displayfilter/core).
The manifest written to disk:

```jsonc
// build/compose-previews/data/<previewId>/displayfilter-variants.json
{
  "variants": [
    {
      "filter": "grayscale",
      "path": "<absolute>/build/compose-previews/data/<previewId>/render_displayfilter_grayscale.png",
      "mediaType": "image/png"
    },
    {
      "filter": "invert",
      "path": "<absolute>/.../render_displayfilter_invert.png",
      "mediaType": "image/png"
    }
  ]
}
```

`data/fetch` against `displayfilter/variants` returns the same JSON
inline plus one `DataProductExtra` per variant pointing at the variant
PNG, so a panel that subscribed to the manifest gets every PNG path
without a follow-up fetch.

## Filter catalogue

| id | Matrix source | What it simulates |
|---|---|---|
| `grayscale` | `setSaturation(0)` with Rec.709 luma weights (R · 0.2126 + G · 0.7152 + B · 0.0722) — same weights `android.graphics.ColorMatrix` uses | Android Digital Wellbeing's bedtime-mode grayscale |
| `invert` | RGB channel negation, alpha preserved | Android "Color inversion" accessibility setting (classic, not Smart Invert) |
| `deuteranopia` | Machado / Oliveira / Fernandes 2009, severity 1.0 — M-cone (green) loss | Most common colour-vision deficiency (~6% of males) |
| `protanopia` | Machado 2009, severity 1.0 — L-cone (red) loss | Red-blind |
| `tritanopia` | Machado 2009, severity 1.0 — S-cone (blue) loss | Rare blue/yellow deficiency |

## Enabling

A comma-separated list of filter ids enables the feature. Empty / unset
disables it entirely (no manifest, no variant PNGs, no extension
registration).

**Gradle plugin / direct render path.** Pass the Gradle property
(matches the rest of the `composePreview.*` flag namespace):

```sh
./gradlew :samples:cmp:renderAllPreviews \
    -PcomposePreview.displayFilter.filters=grayscale,deuteranopia
```

The plugin forwards the value to the spawned renderer JVMs as
`-Dcomposeai.displayfilter.filters=...`, where `RobolectricRenderTest`
(Android) and `DesktopRendererMain` (CMP Desktop) read it after each
PNG capture and run `DisplayFilterDataProducer.writeArtifacts(...)`.

**Daemon path.** Same sysprop, set on the daemon JVM directly:
`-Dcomposeai.displayfilter.filters=...`.

Unknown filter ids are dropped with a warning so a typo doesn't strand
the rest. Duplicates collapse — `grayscale,invert,grayscale` runs each
filter once.

## Companion products

- [Accessibility](./a11y) — `a11y/atf` for ATF audit, `a11y/overlay` for annotated PNG. Pair `grayscale` with `a11y/atf` contrast findings to triage the same palette from two angles.
- [Theme](./theme) — `compose/theme` to see *which* tokens drove the render before applying a filter to the result.
