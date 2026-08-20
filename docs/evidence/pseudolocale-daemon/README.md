# Pseudolocales apply on the live daemon lane (#4371)

`samples/android`'s `PseudoSampleDefault` — three `stringResource(...)` texts —
rendered through the **daemon** with a `localeTag` override, the lane the preview
server's `?localeTag=…` uses. Not the Gradle `composePreviewRenderAll` path,
which resolves the pseudolocale from `@Preview(locale = …)` itself and was never
affected.

| | before | after |
|---|---|---|
| `en-XA` (accent) | `accent-before.png` | `accent-after.png` |
| `ar-XB` (bidi) | `bidi-before.png` | `bidi-after.png` |

Before, all three cells of the matrix — `en`, `en-XA`, `ar-XB` — came back with
the **same sha256** (`2a85b330`): plain English, left-to-right, no expansion
brackets. Nothing errored, so the override read as "pseudolocales don't work"
rather than as a failure. After, the accent cell renders `[Ĥêḷḷö, ŵöŕḷđ ···]` with
its ~30 % expansion padding and the bidi cell mirrors right-to-left with the
per-word RLO/PDF marks (`44411298` / `048f2581`, both `changed`).

Regenerate (the `before` column needs this PR's commit reverted):

```bash
./gradlew :cli:installDist
cli/build/install/compose-preview/bin/compose-preview render-matrix \
  --module :samples:android --preview PseudoSampleDefault \
  --locale en,en-XA,ar-XB --cells-dir=/tmp/pseudolocale-evidence
```
