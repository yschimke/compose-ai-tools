# `RcPlayerTheme` / `systemColors` — render evidence for #4058

Captured with `ImageComposeScene` through `RcComposePlayer` on the CMP desktop lane, on this branch
("after") and on `origin/main` at `5b11d4cbc` ("before") with an otherwise identical harness that
differs only in how the theme and colour hook are spelled — `RcTheme.LIGHT` / `systemColorLookup`
returning ARGB before, `RcPlayerTheme.Light` / `systemColors` returning `Color` after.

**`*-before.png` and `*-after.png` are byte-identical** for all three checked-in `.rc` fixtures
(`TitleCardRemote-640x480`, `LargeRemoteIconButton-400x400`, `watch-screen-round-clip`) — the point
of the pair. This change retypes two parameters; a moved pixel would mean it did not.

The fixtures carry no `ColorTheme` operation, so they cannot show the theme parameter *working*.
`themed-card-*.png` is a synthetic document that does: one `ColorTheme` recording
`system_primary_light` / `system_primary_dark` with Material 3 fallbacks, rendered three ways.

| File | What it shows |
|---|---|
| `themed-card-light.png` | `RcPlayerTheme.Light` — the light fallback, `#65558F` |
| `themed-card-dark.png` | `RcPlayerTheme.Dark` — the dark fallback, `#D0BCFF` |
| `themed-card-host-palette.png` | `RcPlayerTheme.Light` plus `systemColors` returning `Color(0xFF3A7BD5)` for `system_primary_light` — the host palette reaching the document through the new `Color`-typed hook |
