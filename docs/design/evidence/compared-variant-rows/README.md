# Compared-variant rows on the cross-system page

Before/after for the three #4721 post-merge review findings, all on the variant rows
`comparableEntries` flattens into `matches.html`.

| | |
| --- | --- |
| `before.png` | the same fixture rendered by `origin/main` at `ab03de26d7` |
| `after.png` | rendered by this branch |
| `fixture.mjs` | the generator both shots came from — a catalog carrying one `select`-only variant, two variants sharing a state, and one stating `noReference` |

Three differences, one per finding:

1. **`Home/List · HomeListViewPreview` → `Home/List · size=smallRound`, and the render
   changes with it.** The `select` axis was dropped on the way into the manifest, so
   `variantImageOf` had nothing to discriminate on: every default-state image qualified and
   the width sort returned the widest. Before shows the blue `large 240` render under the
   small-round variant; after shows the green `small 192` one it actually names.
2. **`Button/Filled · pressed` twice → `pressed, size=small` and `pressed, size=large`.**
   Two variants sharing a state and differing by props read identically. The thumbnails
   already differed — which is what made the duplicate label a reading problem rather than
   a pairing one.
3. **`no kit reference` → `no kit reference — the kit exports no Text=No cell`.** The
   authored rationale reached the row and stopped there; the generic cell published a
   deliberate absence and an unaudited gap as the same thing.

## Reproducing

```
node docs/design/evidence/compared-variant-rows/fixture.mjs /tmp/after.html
```

Renders through this checkout's own `render-cross-system-html.mjs` by default. The before shot runs
**this same fixture** against a renderer pinned to the baseline commit — the fixture is the constant,
the renderer is the variable:

```
git worktree add /tmp/base ab03de26d7
CROSS_SYSTEM_RENDERER=/tmp/base/scripts/design-artifacts/render-cross-system-html.mjs   node docs/design/evidence/compared-variant-rows/fixture.mjs /tmp/before.html
```

Not by running the copy of the fixture that lands in that worktree: the fixture has itself been
fixed since (it used to carry an absolute import), so the older copy reproduces nothing on any other
checkout, and once this one reaches `main` the worktree's copy would render the *fixed* renderer and
produce no before shot at all. Chromium screenshots the
`<table>` element at `deviceScaleFactor: 2`.

The sibling ("Wear Compose Material 3") column shows broken images in both shots: it bakes
`raw.githubusercontent.com` URLs for a sibling branch, and the fixture has no branch to bake
from. It is identical before and after, and is not what these findings are about.
