# `RcTypefaceLoader` — render evidence for #4060

Captured with `ImageComposeScene` through `RcComposePlayer` on the CMP desktop lane, against the
repo's vendored Roboto Flex (`samples/cmp-wasm-catalog/.../fonts/RobotoFlex.ttf`) — the same face
the browser lane's host manifest supplies. "before" is this PR's base branch, where the parameter is
`fontFamilies: Map<String, RcFontFaces>`; "after" is the same harness with
`typefaces = RcBundledTypefaceLoader(faces)`.

**Every pair is byte-identical.** The failure mode this change could introduce is a *fallback face* —
it passes tests and looks wrong — so a pixel comparison against a real variable font is the check
that matters, not a compile.

| File | What it shows |
|---|---|
| `named-wdth25-*` | `google:Roboto Flex` at `wdth 25` — the prefix strip, and the axis instance reaching the font engine |
| `named-wdth151-*` | the same run at `wdth 151`; visibly wider, so the axes are not being dropped |
| `default-alias-*` | a run naming **no** family, which asks for the literal `"default"` key |
| `unresolvable-*` | a name the host cannot supply, which falls through to Compose's built-in face |
