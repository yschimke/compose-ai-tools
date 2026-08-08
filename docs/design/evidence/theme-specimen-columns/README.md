# Theme specimen sheets stop truncating

Both `@ThemeCatalog` and `@WearThemeCatalog` sheets laid their rows out in a single `Column` on a
fixed canvas, so every row past the bottom edge was simply not drawn. Nothing reported it — the PNG
just ended, and in both cases it ended part-way through the *last* section, which is the one a
reader is least likely to notice is missing.

At the old 400 x 800 canvas (16dp padding → 768dp of content), with each swatch / shape row at 40dp
plus 4dp padding and a 4dp gap = 48dp:

| sheet | content | what rendered |
| --- | --- | --- |
| Wear (21 colours + 6 type) | 21 x 48 = 1008dp of colour alone | 16 swatches, **no type at all** |
| Mobile (11 colours + 4 type + 5 shapes) | ~954dp | **1 of 5 shape rows** |

The rows now pack into as many columns as the canvas needs (`CatalogSpecimenSheet`), and the
synthetic theme preview gets a 640 x 900 canvas instead of falling back to the 400 x 800 sandbox.

## Wear — `wearthemecatalog__KotlinConf`

The type scale is what this sheet could never show. After, the KotlinConf pairing is legible
directly in its own specimen: JetBrains Mono on `displaySmall` / `titleLarge` / `titleMedium`, Inter
on `bodyLarge` / `bodyMedium` / `labelSmall`.

| before | after |
| --- | --- |
| ![before](wear-kotlinconf-before.png) | ![after](wear-kotlinconf-after.png) |

## Mobile — `themecatalog__Brand_Light`

The shape scale returns — all five tokens, not just `extraSmall`.

| before | after |
| --- | --- |
| ![before](mobile-brand-light-before.png) | ![after](mobile-brand-light-after.png) |
