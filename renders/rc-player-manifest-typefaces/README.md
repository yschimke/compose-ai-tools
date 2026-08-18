# Manifest typefaces resolve off Wasm — render evidence for #4061

Rendered through `RcComposePlayer` on the **CMP desktop lane**, against the repo's real catalog
manifest (`samples/cmp-wasm-catalog/src/wasmJsMain/resources/fonts/fonts.json`) loaded by the new
shared `RcManifestTypefaceLoader`.

The pairing is not before/after — the point of #4061 is that *before*, on any target but Wasm, the
left column was unreachable. Each row is the same document rendered with the host's manifest and
with `RcTypefaceLoader.Empty`, which is what a desktop or iOS host silently got.

| Document | with the manifest | without (what non-Wasm hosts got) |
|---|---|---|
| names **no** family (every CoreText in the remote-m3 catalog) | ![](no-family-manifest.png) | ![](no-family-fallback.png) |
| names `google:Roboto Flex` | ![](google-prefixed-manifest.png) | ![](google-prefixed-fallback.png) |

The right column is Compose's built-in face. It is perfectly good text, which is exactly why this
went unnoticed: nothing fails, the document just renders in the wrong typeface.
`RcManifestTypefaceRenderTest` asserts the difference by ink width rather than by a resolved object,
for the same reason.
