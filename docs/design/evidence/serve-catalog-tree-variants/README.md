# The catalog tree grows two levels, and reaches section-less catalogs

Captured from the committed page fixtures with

```sh
npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot \
  -g "serve-landing-grouped|serve-landing-tree-depth"
```

Full pages, light theme, 1024×720.

| File | What it shows |
| --- | --- |
| `before-outline.png` | a **section-less** catalog on `main`: a wall of cards under family headings, with no navigation at all — `buildSections` returns empty for a catalog that declares only `group`, so the tree never rendered |
| `after-outline.png` | the same catalog with the outline tree beside it: its synthesized families are the top level, each opening onto its components |
| `after-collapsed.png` | the full-depth fixture at rest — the first group open on its components, everything else collapsed |
| `after-variants.png` | a component opened onto its primary-axis variants (Default / RTL / Locale ar-XB / Font 2.0×). Theme is absent by design: it is a secondary axis the card swaps in place |

The `before` pair was captured against `origin/main` with `serve-landing-grouped` temporarily added
to `STYLED_FIXTURES` — it was captured bare until this change, so nothing about how a section-less
catalog is navigated could move a baseline. It is a styled fixture from here on, alongside the new
`serve-landing-tree-depth`, whose `component-open` state pins the two deepest levels.
