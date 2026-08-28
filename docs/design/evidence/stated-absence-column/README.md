# The kit column on a catalog that states its absences

Before/after for a catalog that resolves **no** design reference and says why — an empty
`designRefById` alongside components declaring `noReference`.

| | |
| --- | --- |
| `before.png` / `before-note.png` | the same fixture rendered by `0559903543` (the commit before #4742) |
| `after.png` / `after-note.png` | rendered by this branch |
| `fixture.mjs` | the generator both shots came from |

#4740 taught the kit cell to publish an authored `noReference` instead of the generic wording, but
the column it renders into was gated on `designRefById?.size`. A catalog whose components all state
their absences passes an **empty** map, the column was dropped, and none of the reasons appeared —
so the feature was visible only on catalogs that did not need it. #4742 closed that; this change
finishes it.

Two differences:

1. **The column exists at all.** Before, `matches.html` is two columns and the audited absences are
   nowhere on the page. After, the leading **Design kit** column carries each authored reason, with
   the plain `no kit reference` still shown for `Shader/Linear`, which stated none — the distinction
   the field exists to make.
2. **The note describes what the column actually is** (`*-note.png`). Reusing the reference wording
   would have told a reader the column "is the published design reference BOTH implementations are
   reproducing … contributed by whichever catalog carries the `figma:` mapping" — a picture that
   does not exist on this page. It now says the column carries no reference and is here for the
   stated absences, and the subtitle counts `2 with a stated absence` rather than claiming a kit
   comparison.

An author who turns the column off with `compareWith.design: false` still gets no column: the
generator omits `designRefById` entirely in that case, and only a *present* map — empty or not —
lets a stated absence carry it.

## Reproducing

```
node docs/design/evidence/stated-absence-column/fixture.mjs /tmp/after.html

git worktree add /tmp/base 0559903543
CROSS_SYSTEM_RENDERER=/tmp/base/scripts/design-artifacts/render-cross-system-html.mjs \
  node docs/design/evidence/stated-absence-column/fixture.mjs /tmp/before.html
```

This same fixture against a renderer pinned to the baseline — the fixture is the constant, the
renderer is the variable. Chromium screenshots the `<table>` and the `<header>` at
`deviceScaleFactor: 2`.

The sibling ("Wear Compose Material 3") column shows broken images in both shots: it bakes
`raw.githubusercontent.com` URLs for a sibling branch the fixture does not have. Identical before
and after, and not what this change is about.
