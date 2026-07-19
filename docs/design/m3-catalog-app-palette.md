# Re-skinning the Material 3 catalog with an app theme

The Compose Material 3 catalog (`:samples:design-catalog-m3`, published to
`preview.coo.ee/compose-m3/`) resolves its theme overrides through shared functions in
[`CatalogThemeChoices`](../../samples/design-catalog-m3-shared/src/commonMain/kotlin/com/example/designcatalogm3/shared/CatalogThemeChoices.kt)
that **both** render tiers call — the desktop `@Preview` sticker sheet (`CatalogSticker`) and the
in-browser Wasm viewer (`CatalogApp`). Because every sticker resolves the knobs through that one
place, a consumer can re-skin the **whole** catalog under its own brand theme with **no per-preview
change and nothing brand-specific hardcoded in this repo**.

A `MaterialTheme` is `(colorScheme, typography, shapes)`, so there is one knob per axis:
`theme.colors`, `theme.typography`, `theme.shapes`. Each is inert by default — the decode only fires
for its own `scheme:` / `typo:` / `shapes:` prefix, so a named/absent knob renders byte-identically.

## `theme.colors` — a serialized `ColorScheme`

Alongside the named palettes (`M3`, `Coral`, `Teal`), `theme.colors` accepts a serialized
`ColorScheme` inline on the string knob:

```
scheme:l=<role>:<AARRGGBB>,<role>:<AARRGGBB>,…;d=<role>:<AARRGGBB>,…
```

- `l=` / `d=` carry the light and dark schemes; the resolver decodes the segment for the mode being
  rendered.
- Each role is one of the 48 M3 `ColorScheme` roles — the base set **and** the fixed accent roles
  (`primaryFixed`, `onPrimaryFixed`, …) the `compose/theme` token export reads. Roles the blob omits
  keep their stock M3 tone, so a **partial** palette still renders.
- An unparseable value falls through to the stock M3 scheme — the decode never throws.

Encoder: `serializeCatalogColorScheme(light, dark)`.

### Light/dark support per theme

A theme baked or shown in a mode it doesn't define renders an auto-derived variant its author never
intended (a light-only palette force-darkened to muddy greys), so a consumer that enumerates
variants — an exporter baking per-mode PNGs, the landing's Light/Dark selector — asks
`catalogThemeModes(value)` which modes a theme actually supports and offers only those. Generating
both modes for a single-mode theme just makes 50% of the output unusable.

| Theme (`theme.colors`) | Light | Dark | How it's determined |
| --- | :---: | :---: | --- |
| `M3` (and any unknown name) | ✅ | ✅ | Declared — the stock M3 light/dark scheme. |
| `Coral` | ✅ | — | Declared — a fixed **light** brand scheme. |
| `Teal` | — | ✅ | Declared — a fixed **dark** brand scheme. |
| `scheme:…` app palette | if `l=` | if `d=` | **Inferred** from which mode segments carry usable roles — reusing the same decoder the renderer uses, so it can't disagree with what actually renders; a blob with neither mode falls back to both. |

So a meshcore palette serialized with both `l=` and `d=` segments is offered in both modes, while an
app that ships only a light palette (`scheme:l=…`) is never baked or shown dark.

## `theme.shapes` — a serialized `Shapes`

```
shapes:xs=<dp>,s=<dp>,m=<dp>,l=<dp>,xl=<dp>
```

The five M3 corner-size tokens as `RoundedCornerShape` radii in dp. Only the tokens the blob carries
are overridden (the rest stay stock M3); any other / absent value yields the stock `Shapes()`. This
re-corners the components that read the size tokens — cards (`medium`), chips (`small`), FAB
(`large`), text fields (`extraSmall`) — while components pinned to `CornerFull` (buttons) stay their
stadium shape, exactly as under a normal app `Shapes`. Encoder: `serializeCatalogShapes(…)` (it takes
the five dp values directly, since a built `CornerBasedShape` doesn't expose its size portably).

## `theme.typography` — serialized type **metrics**

```
typo:<role>=<sizeSp>/<lineHeightSp>/<letterSpacingSp>/<weight>,…
```

One entry per M3 type role, carrying the numeric scale (size / line-height / letter-spacing /
weight); `-` in a slot leaves the base value. The metrics are overlaid on the base type scale, which
already carries the **typeface** from the `theme.font` knob — because a font *file* can't travel on a
string knob, the face is resolved separately (the catalog's vendored / named families), and only the
scale rides here. Encoder: `serializeCatalogTypography(typography)`.

> For pixel-faithful type under an arbitrary app font that the catalog doesn't vendor, the
> whole-theme `PreviewWrapperProvider` route (the one the wear catalog uses) is the general answer —
> it hands the catalog the app's real `Typography` (and `FontFamily`) object rather than serializing
> the scale. The string knobs here are the rebuild-free path that works today on the CMP-desktop
> pipeline.

## Before / after

The same M3 components, same layout — only the `theme.*` knobs differ. Left is the stock theme; right
is an example app re-skin combining **all three** axes (a teal `ColorScheme`, sharper corners, and a
bolder/larger type scale), each fed through its knob and resolved by the shared functions.

| Stock theme | App re-skin (colors + shapes + typography) |
| --- | --- |
| ![Stock M3 theme](../images/m3-full-theme-stock.png) | ![App theme re-skin](../images/m3-full-theme-reskin.png) |

The colors axis on its own:

| Stock `M3` | App palette (`scheme:…`) |
| --- | --- |
| ![Stock M3 palette](../images/m3-app-palette-stock.png) | ![Teal app palette](../images/m3-app-palette-teal.png) |

Overrides resolve in-composition, so a value reaches a live render through `compose-preview serve`
(`/render/<id>.png?knob.theme.colors=scheme:…`, `&knob.theme.shapes=shapes:…`,
`&knob.theme.typography=typo:…`) or the daemon RPC. The same override seed re-skins an **already
published** bundle offline, with no source rebuild:

```
compose-preview bundle render <bundle.png> --knob theme.colors=scheme:… -o <dir>
```

stands up the bundle's own render daemon (the path `serve` uses for `/render?knob…`) and writes one
re-themed PNG per preview. Because it runs the published bundle — not the app's source — a consumer
that only has the `.png` (e.g. meshcore reusing the compose-m3 stickersheet under its own theme) can
bake the re-skinned catalog and surface it as its own tab. `--knob` is repeatable and each
`key=value` splits on the first `=`, so a serialized `scheme:…` blob keeps its own `=`/`,`/`;`
separators; the flag is inert without a `desktop`/`android` backend bundle plus the `:cli:installDist`
daemon sidecars (re-run without `--knob` for the stock, override-free render).

Encode/decode round-trips are covered by
[`CatalogColorSchemeTest`](../../samples/design-catalog-m3-shared/src/desktopTest/kotlin/com/example/designcatalogm3/shared/CatalogColorSchemeTest.kt)
and
[`CatalogShapesTypographyTest`](../../samples/design-catalog-m3-shared/src/desktopTest/kotlin/com/example/designcatalogm3/shared/CatalogShapesTypographyTest.kt).
