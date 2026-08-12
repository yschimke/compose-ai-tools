# rc-text-linebreak-style

The `AppCardRemote-640x480.rc` fixture rendered through the embedded CMP/JVM player
(`RcJvmFigmaSvgExportTest`, `rc.jvm.svg.report`), before and after the text-style fix.

- `before.png` — with the unconditional `style = TextStyle(lineBreak = …, hyphens = …)` the
  extended-CoreText work (#3667) put on every `RcPlayerText`. The document sets neither property,
  but pinning `LineBreak.Simple` where the style previously left it unspecified remeasured the
  card's text: it grew 216px → 219px tall and its rounded clip moved from `y=132` to `y=131`.
- `after.png` — the same document with the style merged onto the ambient one and each property
  overridden only when the document sets it. The card is back to `y=132`, `height=216`.

The drift is a few pixels, which is exactly why it needs a committed pair: it reads as a rendering
nit and is in fact the whole document being measured under different line-breaking rules.
