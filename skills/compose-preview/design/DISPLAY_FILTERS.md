# Display filters

Apply post-process colour-matrix transforms to each captured PNG. One
extra PNG per enabled filter lands alongside the base capture, so a
single render covers grayscale (bedtime mode), inversion, and the
common daltonizer simulations without re-rendering.

## When to enable

- **Grayscale** (`grayscale`) — verifying state isn't carried by hue
  alone. Red error / green success colour pairs are the most common
  thing this catches; if both collapse to the same grey the UI relies
  on hue more than it should.
- **Inversion** (`invert`) — Android's "Color inversion" setting flips
  the whole display. Useful for finding hard-coded white/black
  assumptions (logos, illustrations, custom badges) and assets that
  the dark-theme path doesn't cover.
- **Colour-vision simulation** (`deuteranopia`, `protanopia`,
  `tritanopia`) — what a viewer with that cone-loss sees. Default
  pairing for most projects: `grayscale,deuteranopia` — bedtime mode
  + the most common deficiency catches the bulk of hue-only signalling
  bugs in one extra-cheap pass.

These are **simulations**, not corrections. The Android
"Color correction" accessibility setting applies a different
(error-shifted) matrix that compensates for a deficiency; that is a
separate transform that belongs alongside other a11y settings, not
here.

## Enabling

Gradle property on the direct render path (the plugin forwards it to
the renderer subprocess as the `composeai.displayfilter.filters`
sysprop):

```sh
./gradlew :samples:cmp:renderAllPreviews \
    -PcomposePreview.displayFilter.filters=grayscale,deuteranopia
```

For daemon-mode use the matching JVM flag directly:
`-Dcomposeai.displayfilter.filters=grayscale,deuteranopia`. Empty /
unset disables the feature entirely — the daemon doesn't register the
extension, so `extensions/list` won't show it. Unknown ids are dropped
with a warning. Duplicates collapse.

## Output

Per render under `build/compose-previews/data/<previewId>/`:

- `displayfilter-variants.json` — manifest enumerating every variant.
- `<base>_displayfilter_<filterId>.png` — one PNG per enabled filter.

The data product surfaces over JSON-RPC as `displayfilter/variants`
(inline transport, manifest as JSON, variant PNGs ride along as
`extras`).

## Reading the variants

Quick agent loop: render once, look at the base PNG, then look at each
variant. Two failure shapes worth flagging:

1. **Identical greys** — different states collapse to the same luma
   under `grayscale`. Fix: add an icon, weight, or text label so state
   isn't hue-only.
2. **Unreadable text under inversion** — text on photos / brand colours
   that goes from black-on-white to white-on-black-on-bright-photo.
   Fix: contrast scrim, or test if the assistive `Smart Invert` exempt
   path is genuinely needed.

## Caveats

- Purely **post-process** — no Compose state changes, so things like
  high-contrast text outlines or bold text aren't covered here. Those
  belong on the a11y side (re-render with the OS flag set).
- Daltonizer matrices are at severity 1.0 (full cone loss). Most users
  with a deficiency have partial loss; severity-controlled matrices
  could be added if needed.
