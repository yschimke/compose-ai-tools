# Subtracting a capture gutter on the sheet

The measurement behind
[m3-catalog#179](https://github.com/yschimke/m3-catalog/issues/179), and what this change does
about it.

## The complaint

`@CaptureGutter` (#4445) moved a shadow's room out of the component tree and into the capture, so
the component measures what it always measured and only the canvas grows. That fixed the
*measurement*. It did not fix what a reader sees, because every consumer of a sticker sheet fits
the **whole canvas** to its column:

| render | canvas | component | drawn in a 213px column |
| --- | --- | --- | --- |
| `button-filled__ideal__default__light` | 249×126 | 249×126 | 213.0px |
| `button-tonal__ideal__default__light` | 249×126 | 249×126 | 213.0px |
| `button-outlined__ideal__default__light` | 249×126 | 249×126 | 213.0px |
| `button-elevated__ideal__default__light` | **271×150** | 249×126 | **198.1px** |

7.0% smaller, for a reason that has nothing to do with the design — the one emphasis that casts a
shadow.

## The picture

Real Chromium, the real `serve.css`, m3-catalog's own renders at 2.625 density in a 213px column.
Top band is what the sheet draws today; bottom band is the same four cards with the declared gutter
subtracted, which is what this change makes the sheet do.

![The button row today and with the gutter subtracted](sheet-row-before-after.png)

The elevated card's shadow is still there in the bottom band: the window that lines the box up with
its neighbours does **not** hide its overflow (`.cp-crop--bleed`), and the card it sits in stops
clipping too (`.cp-card:has(.cp-crop--bleed)`), so the spill reaches the grid's gap rather than
dying at the card's rounded edge. Clipping it would crop the very shadow the gutter was captured to
keep (#4445, and m3-catalog#102 before it).

Two rules the geometry follows, both so a cropped card and a plain one land on the same size:

- **The display cap is on height, not the largest edge.** A plain card image is bounded by the
  stylesheet's `max-height`, which scales an image on its height and lets width follow. Capping the
  largest edge would shrink a wide-but-short component (a 249×126 button) that no plain sibling
  shrinks — the same mismatch, one layer down.
- **That cap lives in the crop geometry, not in CSS.** A window carries an inline `aspect-ratio`,
  and constraining its height in CSS squashes the box instead of scaling it.

## How to reproduce the picture

1. Render m3-catalog's button stickers: `./gradlew :catalog:composePreviewRender
   -PcomposePreview.filter=FilledButton,TonalButton,OutlinedButtonSticker,ElevatedButtonSticker`.
2. Lay the four PNGs out in `.cp-imgwrap` cards at `--cp-card-w: 213px` with this repo's
   `serve.css`, once as plain `<img>` and once with the elevated one in a
   `<span class="cp-crop cp-crop--bleed" style="width:249px;aspect-ratio:249/126">` window whose
   image is sized `width:108.8353%;left:-4.4177%;top:-8.7302%` (249/271, −11/249, −11/126). The
   gutter's 126px box is inside the 240px cap, so the geometry is native pixels here.
3. Screenshot at `deviceScaleFactor: 2`.
