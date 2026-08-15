# Design pages: a branch of the navigation tree, not a chip in the header

Before/after of the section-less catalog landing — the shape most published design systems are in,
m3-catalog included — captured from the committed page fixture
(`vscode-extension/preview-harness/fixtures/pages/serve-landing-grouped.html`) with

```sh
npx playwright test -c preview-harness/playwright.config.mjs pages-snapshot -g serve-landing-grouped
```

Full pages, 1024×720, both themes.

The `before` pair was captured against `origin/main` with `pageCount = 2` temporarily passed to the
`landingGrouped` fixture: until this change no committed golden carried a tree *and* published
pages at once (the only fixture with pages, `serve-landing-public`, has no tree), so an honest
"before" of the surface being replaced had to be produced by hand exactly once. The `after` pair is
the ordinary output of the fixture, which now passes `designPages` — so every future PR diffs this
branch without anyone remembering to.

| File | What it shows |
| --- | --- |
| `before.png`, `before.dark.png` | the `2 pages` action chip in the header row, beside `download all (.zip)` — the count, and nothing else |
| `after.png`, `after.dark.png` | a `Pages` branch at the foot of the tree, each page listed by name, the header row back to its actions |
| `compare.png`, `compare.dark.png` | the two cropped to the header and the navigation column, side by side |
