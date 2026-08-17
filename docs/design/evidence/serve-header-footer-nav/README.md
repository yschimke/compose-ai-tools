# The header drops "Catalogs" and the repo link; GitHub moves to the footer

Before/after crops of the `serve` site chrome, captured from the committed page fixtures
(`vscode-extension/preview-harness/fixtures/pages/serve-landing-public.html`) served statically and
shot with Playwright (light theme, 2× DPR; the dark shots are the same markup).

| Pair | Shot | What changed |
| --- | --- | --- |
| `catalog-header-{before,after}.png` | 1000px header bar | "Catalogs" is gone — it linked `/`, which the brand beside it already does — and so is the "GitHub" repo link. Catalog/Dev, Status and Settings are untouched |
| `catalog-footer-{before,after}.png` | the same page's footer | the repo link lands here: the row's first entry now reads **GitHub** rather than "source", which is the label the per-preview link to a preview's *source file* uses |
| `mobile-menu-{before,after}.png` | 390px, `⋮` open | at phone width the whole nav is that menu, so this is where the two removed entries are most visible: four items become two |

The "before" pages are `git show HEAD:…` copies of the same fixtures, so both columns are production
`ServeWeb` output rather than hand-written markup.
