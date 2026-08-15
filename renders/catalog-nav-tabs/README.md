# Components and Pages as two searchable panes

The catalog sidebar's design pages used to be a branch at the **foot of the
component tree** — below every family, component and variant. On m3-catalog that
is past ~120 rows of the inventory you were not looking for, and the two lists
were competing for one column while answering different questions: *which
component* versus *which page of the design file*.

They are peers now, behind a segmented switch, and the one filter below the
switch serves whichever is showing.

| file | what it is |
| --- | --- |
| `components-pane.png` | the resting state: `Components 14 | Pages 2`, Components selected, the tree below |
| `pages-pane.png` | the Pages pane — the pages as named rows, the box relabelled `Filter pages…`, and `All pages` as the way through to the index |
| `pages-filtered.png` | typing `shape` narrows the pages to one **and leaves the grid whole** |

That last one is the part that is not layout. The pages were the only list in
this column the filter could not reach. Now the box follows the pane it is
pointed at:

- **Components** — a grid query, exactly as before; the pages are untouched.
- **Pages** — a page query, and the grid is *released* rather than left filtered
  by a query that was never about it.

Getting that wrong is not subtle: it answers `shape` with "No previews match
your filter" under a sidebar that has just found the Shape page. An earlier
revision of this change did exactly that, which is why the behaviour is pinned
by `contract · the sidebar filter follows the pane it is pointed at` rather than
by the screenshots alone.

Only a catalog with **both** lists gets the switch — one tab switches nothing,
and emitting it everywhere would move every committed golden for no reader
benefit. `serve-landing-grouped` is the only fixture that changes.

```
./gradlew :cli:test              # green
cd vscode-extension
npm run harness:snapshot         # 159 passed
```
