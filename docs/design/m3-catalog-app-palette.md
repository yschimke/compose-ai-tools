# Re-skinning the Material 3 catalog with an app palette

The Compose Material 3 catalog (`:samples:design-catalog-m3`, published to
`preview.coo.ee/compose-m3/`) resolves its `theme.colors` override through a single shared
function — [`catalogColorScheme`](../../samples/design-catalog-m3-shared/src/commonMain/kotlin/com/example/designcatalogm3/shared/CatalogThemeChoices.kt)
— that both render tiers call: the desktop `@Preview` sticker sheet (`CatalogSticker`) and the
in-browser Wasm viewer (`CatalogApp`). Because every sticker resolves the knob through that one
function, a consumer can re-skin the **whole** catalog under its own brand palette with **no
per-preview change and no brand colour hardcoded in this repo**.

## The `scheme:` value

Alongside the named palettes (`M3`, `Coral`, `Teal`), `theme.colors` accepts a **serialized
`ColorScheme`** carried inline on the existing string knob:

```
scheme:l=<role>:<AARRGGBB>,<role>:<AARRGGBB>,…;d=<role>:<AARRGGBB>,…
```

- `l=` / `d=` carry the light and dark schemes; the resolver decodes the segment for the mode being
  rendered.
- Each role is one of the 36 M3 `ColorScheme` roles (`primary`, `onPrimary`, `surface`, …). Roles
  the blob omits keep their stock M3 tone, so a **partial** palette still renders (only the supplied
  roles change).
- An unparseable value falls through to the stock M3 scheme — the decode never throws.

A consumer produces the value with `serializeCatalogColorScheme(light, dark)` on its own two
`ColorScheme`s; there is no dependency on this module's palette names.

Overrides are resolved in-composition, so the value reaches a live render through
`compose-preview serve` (`/render/<id>.png?knob.theme.colors=scheme:…`) or the daemon RPC. Baking
the re-themed PNGs into a published bundle is a follow-up (a `--knob` seam on `bundle pack`), which
lets an app surface the re-skinned catalog as its own tab.

## Before / after

The same M3 components, same layout — only the palette on `theme.colors` differs. Left is stock
`M3`; right is an example teal app palette fed through the `scheme:` value, decoded by
`catalogColorScheme`.

| Stock `M3` | App palette (`scheme:…`) |
| --- | --- |
| ![Stock M3 palette](../images/m3-app-palette-stock.png) | ![Teal app palette](../images/m3-app-palette-teal.png) |

Round-trip coverage for the encode/decode lives in
[`CatalogColorSchemeTest`](../../samples/design-catalog-m3-shared/src/desktopTest/kotlin/com/example/designcatalogm3/shared/CatalogColorSchemeTest.kt).
