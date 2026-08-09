# `remote-m3` themes applied at replay

Rendered from the **published** `design-artifacts/remote-m3` documents — the ones whose module
bytecode `bundle pack` dropped — by seeding named colour values onto them. No recomposition, no
per-theme capture:

```
/remote-m3/render/button-filled__ideal__default__compact.png?rcPlayer=cmp-jvm
    &rc.WearM3.primary=color:%23FF6F61&rc.WearM3.secondary=color:%23FFB4A9
```

Each file is `<sticker>-<theme>.png`. The seeds are exactly `remoteCatalogThemeColors(<theme>)` from
`:samples:design-catalog-remote-m3`, so what these show is what the Theme select will show once the
server expands `?themeProvider=` into the same seeds.

Only components drawing `primary` / `secondary` move under these palettes — a sticker painted from
`background` / `surfaceContainer` / `onSurface` (the watch-screen template, say) is untouched,
because those roles carry no override. That is the palettes being faithful to the Wear sibling's
`wearColorScheme`, which edits the same two families.
