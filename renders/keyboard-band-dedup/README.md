# Soft-keyboard band de-duplication (#5165) — before/after

`SoftKeyboardBand` moved out of `:data-keyboard-connector` into the new plain-JVM
`:data-keyboard-band`, so the Android band is now compiled in a different module
against Compose Multiplatform's `androidx.compose.*` artifacts rather than the
connector's `compose-bom-compat` ones. That is a real change to the band's compile
inputs, so it is worth pixels rather than an argument.

`*-before.png` is `origin/main` (2ee93ec), `*-after.png` this branch. Same preview,
same renderer, same command, in a worktree of each commit:

```
./gradlew :samples:android:composePreviewRender --rerun \
  -PcomposePreview.filter=SoftKeyboardIdlePreview
```

The two PNGs are **byte-identical** (md5 `a0103cc5fe12f9a96f608f9d9d145608`) — the
band draws exactly as it did, keycaps, palette, insets and all.

`SoftKeyboardIdlePreview` (`:samples:android`) is the surface that shows the band at
rest; the desktop connector has no static preview that raises the band (the CMP
`KeyboardDemoPreview` only shows it under a live-mode `KEY_*` dispatch), so the
desktop side is covered by the connector build and the daemon harness instead.
