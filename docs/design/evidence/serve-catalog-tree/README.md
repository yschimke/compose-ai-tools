# Catalog navigation: a tree, not a row of tabs

Before/after of the sectioned catalog landing, captured from the committed page fixture
(`vscode-extension/preview-harness/fixtures/pages/serve-landing-sections.html`) with

```sh
npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot -g serve-landing-sections
```

Full pages, light theme, 1024×720 — the dark shots are the same markup under the M3 dark roles.

The `before` pair was captured against `origin/main` with the fixture temporarily added to
`STYLED_FIXTURES`, because until this change it was captured **bare**: the tab bar, the group
headings and the card grid were all shot without the production stylesheet, so nothing about how the
navigation was painted could move a baseline. Adding `serve-landing-sections` to `STYLED_FIXTURES`
is part of this change, which is why an honest "before" had to be produced by hand exactly once.

| File | What it shows |
| --- | --- |
| `before.png`, `before.dark.png` | the section tab bar: three tabs over one panel, the section's groups reachable only by scrolling it |
| `after.png`, `after.dark.png` | the tree beside the grid: sections as rows, the selected one open on its groups, the scroll-spy marking the group on screen |
| `after-section-open.png` | another branch opened — Components, its Device and Contacts groups listed, the panel beside it swapped |
| `after-filtered.png` | a search for "device": every branch holding a match opens, the group rows the filter emptied are gone with their clusters |

`after-section-open` and `after-filtered` are also committed as `FIXTURE_STATES` in
`pages-snapshot.spec.mjs`, so every future PR diffs them without anyone remembering to.
