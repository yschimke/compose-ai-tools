# The Pages pane is a tree, and its sections are searchable

Each design page now carries its **major sections** — the sheet's Figma
`COMPONENT_SET` groupings, which is what a reader means by its headings.

| file | what it is |
| --- | --- |
| `pages-tree.png` | `Shape` open with `Corner radius` and `Shape scale` under it; `Typography` beside it as a leaf, because it has no sets |
| `section-match.png` | typing `corner` — a word in neither page's title — keeps the Shape page, opens it, and shows only the section that matched |

That second shot is the point of putting sections in the sidebar rather than
leaving them on the page: `corner` is the name of a grouping on the Shape sheet,
so before this the only way to find it was to open pages until you saw it.

A page kept **only** because a section matched is opened, and shows just the
matching sections — a hit you have to expand a twisty to see is a hit the filter
did not really surface. A page matching on its own name keeps its whole list.

## Where a section row lands

`/{system}/pages/{page}#cp-node-{node}`. Every node hotspot on the page view now
carries that `id`, built from the design-tool node id rather than being it: `1:23`
is legal in an HTML `id` but not in a CSS selector or a URL fragment without
escaping, and it is third-party manifest text either way.

Native fragment scroll for now. Zooming and selecting the node on arrival — via
`design-page.js`, reusing the zoom work from #3904 — is the agreed follow-up.

## Bounds

Grouping nodes only. A definition sheet carries hundreds of concrete components
and listing those would rebuild the wall of rows this navigation exists to
avoid. Unnamed sets are dropped (a row with no label is a row you cannot choose)
and the list is capped at `MAX_PAGE_SECTIONS = 24`, since nothing in a
third-party manifest bounds how many sets a page declares.

```
./gradlew :cli:test              # green
cd vscode-extension
npm run harness:snapshot         # 159 passed
```
