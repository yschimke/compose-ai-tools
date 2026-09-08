# Remote Compose capture density — font scale evidence

Rendered from `:samples:remotecompose` with the `accessibility` permutation, which
draws every preview a second time at `fontScale = 2` (`RenderPreviewPermutations`):

```
./gradlew :samples:remotecompose:composePreviewRenderAll --rerun \
  -PcomposePreview.permutations=accessibility [-PcomposePreview.rcDensity=host]
```

Each pair is one preview against **its own** `_fontscale-2x` variant, so the
oracle is self-contained: the two images differ iff that preview responds to
font scale.

| Pair | `.rc` document | pixels |
| --- | --- | --- |
| `RemoteButtonWithBorderPreview` baseline | identical (1943 B) | **identical** |
| `RemoteButtonWithBorderPreview` `rcDensity=host` | identical (2407 B) | **identical** |
| `Control-NonRemote` baseline | no document | **differ** |

## What it shows

The baseline reproduces the reported defect: a Remote Compose sticker renders
byte-identically at 1x and 2x. The control — an ordinary Compose preview in the
same module, same run, same Robolectric lane — does scale, so the harness is
applying the font scale and the defect is specific to the Remote capture path.

Under `rcDensity=host` the captured document changes as intended: it grows by the
expression graphs that replaced the folded constants (1943 -> 2407 bytes) and
becomes byte-identical across font scales, because the scale is no longer stored
in it at all. That is what "deferred to the player's `FONT_SIZE`" means, and it is
the property the replay lane needs.

The rasterised pixels, however, are still identical. The capture half is verified;
a link between the document's variable reference and the drawn glyphs is not, and
is not closed by the change these renders accompany.
