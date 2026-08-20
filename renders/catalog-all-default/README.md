# A catalog opens on All

Issue #4306. A sectioned catalog landed on its **first section** with the rest of
itself hidden: `preview.coo.ee/wear-m3-catalog/` said "9 previews" in its header
and put two on the screen, and the only way to see the catalog was to click each
branch in turn. The filter beside the tree already spanned every section — but
only once you had typed into it, which is not a state you can browse in.

The tree now leads with an **All** row and lands on it.

| file | what it is |
| --- | --- |
| `before-first-section.png` | before: `Themes` selected, 2 of 9 previews on screen, the rest a click away each |
| `after-all.png` | after: `All 9` selected, every panel showing, the whole inventory one scroll |
| `after-section-open.png` | picking `Components` still narrows to it — All is a row you come back to |
| `after-filtered.png` | filtering `device` from All spans the catalog: matches in Components *and* Screens, under their own headings |
| `after-mobile.png` | the phone layout, where the tree is a scrolling strip — All leads it there too |

Three things follow from All showing several sections at once, and each lives in
the piece of the page that owns it:

- **the grid** — a card is in the current tab whatever section holds it
  (`current === "all"` in the filter script's `tabOk`);
- **the tree** — every section expands, because a tree standing beside a grid
  showing everything has to be the outline of everything;
- **the headings** — `cp-js` hides the per-section `<h2>` because the selected
  row names the one section on screen, which stops being true the moment several
  are. `cp-multi-section` on `<html>` brings them back under All *and* under a
  live search, which had the same problem and never showed it.

`all` is a reserved section slug, so a catalog that really does name a section
"All" gets `all-2` rather than colliding over `#cp-tab-all` / `?tab=all`. A
catalog with one section gets no row at all — that section already is the whole
catalog.

Captured from `serve-landing-sections` (meshcore-mobile), the fixture that
renders the navigation tree, so the states above are re-shot and diffed on every
PR without anyone remembering to.

```
./gradlew :cli:test              # green
cd vscode-extension
npm run harness:snapshot         # 183 passed
```
