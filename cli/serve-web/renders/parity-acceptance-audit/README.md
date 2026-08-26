# Design parity: the catalog-wide acceptance audit

Before/after for the **Known differences** panel on `/{system}/parity` — the walk of a catalog's
whole known-difference document, run with no comparison at all.

| | |
| --- | --- |
| `before.png` | the dashboard as it was. The catalog publishes three acceptances and the page says nothing about any of them: one names a preview this catalog no longer has, and no comparison anywhere can report it, because no comparison scopes it in. |
| `after.png` | the same dashboard, with the panel. Three acceptances, two needing attention — a broken artifact and the orphan — and one whose tracking issue has closed while the record is still committed. |

Nothing in the panel is drawn by the fixture: every row is painted by `known-differences.js` running
the shared acceptance engine over the committed `parity/` bytes, which is the reason this surface is
shot rather than described. The three findings are what a validation-only pass can stand behind —
refusals, orphans, and the issue-index lifecycle join. It reports no *verdict*: whether an acceptance
still matches its recorded difference is a question about pixels, and it stays on the comparison page
where the pixels are.

## Reproducing

```
node shoot.mjs after.png            # the dashboard as this branch serves it
node shoot.mjs before.png --before  # the same dashboard with the panel taken back out
```

`fixture/page.html` is the real page — `ServeWeb.parityPage` output for a catalog publishing three
acceptances — beside the `parity/known-differences.json` and the two artifacts the walk fetches.
`--before` strips the band, its payload and the bundle's script tag, which is exactly the three lines
this change adds to that page.

The three records are chosen to cover what the walk can conclude on its own:

| Record | What it is |
| --- | --- |
| `m3-iconbutton-tonal-glyph` | in scope, artifacts intact — the healthy case, listed only because its issue has closed |
| `m3-fab-lowered-shadow` | its artifact directory does not exist — `refused (artifact-unreadable)` |
| `m3-checkbox-checked-ring` | names `Checkbox/Checked`, which the catalog does not serve — `orphaned-target` |
