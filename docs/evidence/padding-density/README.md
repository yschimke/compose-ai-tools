# Padding applied twice at density ≠ 1 (wear-m3-catalog#90)

`PaddingModifier` read the edges through `getLeft()`..`getBottom()`, which return the
operation's `…Value` fields. `updateVariables` writes those by scaling the source
by the display density — and the source is already in pixels, so the resolved
field is density-squared. Dividing it by density once left **double** the inset
the component asked for.

Measured on `RemoteCompactButton`'s 8dp inset, one edge, off a real document:

| density | `mTop` (source) | `mTopValue` (resolved) | inset used | wanted |
| --- | --- | --- | --- | --- |
| 1.0 | 8 | 8 | 8dp ✓ | 8dp |
| 2.0 | 16 | **32** | **16dp** ✗ | 8dp |

At density 1.0 the extra multiply is the identity, which is where the tests ran.

`before.png` / `after.png` — `Button/Compact`, `Button/Filled` and `AppCard` from
wear-m3-catalog's `remote-catalog` at density 2.0 (`dpi=320`).

The compact button is the loud case. It is a 48dp touch target with that inset
around a 32dp pill, so a doubled inset leaves `96 − 32 − 32 = 32px` where 64px
was meant:

| density | before | after |
| --- | --- | --- |
| 1.0 | 32dp ✓ | 32dp |
| 2.0 | 16dp | 32dp |
| 3.0 | **nothing renders** (`144 − 72 − 72 = 0`) | 32dp |

The blank render at density 3.0 falling out of the arithmetic, and recovering
with the fix, is what makes this the whole story rather than part of it.

The filled button was over-wide for the same reason and is unchanged in height;
the cards' content was inset twice over, so their text reflows.

Of 57 `remote-catalog` renders, 38 are byte-identical across the fix and 20 move
— every component that carries padding.

Regenerate (needs an Android SDK and a wear-m3-catalog checkout):

```bash
# in compose-ai-tools
PLUGIN_VERSION=1.44.0-SNAPSHOT ./gradlew :third-party-rc-embedded-player:publishToMavenLocal
# in wear-m3-catalog, with mavenLocal() added and composePreviewAnnotations pinned to the snapshot
./gradlew :remote-catalog:composePreviewRenderAll
```
