# skiko encode rebind — before / after

Evidence for binding `Image.encodeToData` late (compose-ai-tools#4190). skiko 0.150.0 added a
parameter to that method, so the renderer's compiled call site stopped linking against any consumer
on Compose Multiplatform 1.12.0-beta01+; the encode now goes through the synthetic `$default` bridge
with a mask that asks skiko for its own defaults for every parameter but the format.

Rendered from `samples/cmp` with `./gradlew :samples:cmp:composePreviewRender`, on `origin/main`
(`*.before.png`) and with the change applied (`*.after.png`), against the skiko this repo currently
resolves — **0.144.6**, the two-parameter form. The point of the pair is that nothing moved: the
mask reproduces exactly the defaults the direct call used to pass, so the pixels are unchanged on
the old skiko while the new one now links at all.

| capture | before | after |
| --- | --- | --- |
| `AppPreview` | `AppPreview.before.png` | `AppPreview.after.png` |
| `ShaderJuliaPreview` — an AGSL shader, so the encode carries real per-pixel variety rather than flat fill | `ShaderJuliaPreview_Shader_Gallery_Julia_Set.before.png` | `ShaderJuliaPreview_Shader_Gallery_Julia_Set.after.png` |

Both pairs are byte-identical (same SHA-256), as were all 62 captures the two runs have in common.
