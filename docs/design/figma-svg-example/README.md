# `compose/figma-svg` — example output

An illustrative example of the layered, editable SVG the `compose/figma-svg`
data product emits (renderer: `FigmaLayeredSvg` in `:data-layoutinspector-core`).

[`profile-card.svg`](profile-card.svg) is the exact document shape the exporter
produces for a small Material card — a rounded, bordered surface containing a
circular avatar, a bold title, a subtitle, a filled button, an outlined button,
and a rounded badge. [`profile-card.png`](profile-card.png) is that SVG
rasterised (so the vector can be viewed as pixels here):

![Rendered compose/figma-svg example](profile-card.png)

What to notice — and why it imports cleanly into Figma:

- **Every composable is a named `<g id="…">` group**, nested exactly as the
  composables nest (`Surface` › `Avatar` / `Title` / `PrimaryButton` › `ButtonLabel`
  …), so a Figma SVG import lands each component/screen as a named layer.
- **Container tokens become real vector shapes**: `background` → a filled `<rect>`
  (or a rounded-corner `<path>` when corners aren't uniform), `border` → a stroke,
  the resolved corner radius → Figma's editable corner radius, `CircleShape` →
  a max-radius rounded rect (the avatar).
- **Text is editable `<text>`**, not outlines, carrying the captured
  family / size / weight / colour — so a designer edits the string in place.
- **Named theme colours ride along** as a `<title>` + `data-token` on the layer
  (`Surface · surface`, `Avatar · primary`, …), to pair with the sibling
  `figma-variables.json` when binding fills to variables.

The live artifact is produced per-render by both daemon backends; the desktop
`RenderEngineTest` asserts a real render drops `compose-figma.svg` alongside the
wireframe.
