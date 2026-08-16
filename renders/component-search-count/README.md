# Component search count label

Visual evidence for the component command palette's tree-count handling.

| file | what it shows |
| --- | --- |
| `before.png` | The previous extraction joined each component name to its `34`-render tree badge. |
| `after.png` | The command palette keeps the component name and omits the presentational count badge. |

Captured at 1320 × 780 from the committed
`serve-landing-tree-depth.html` Playwright fixture with its component rows
renamed to mirror the reported Icon Button catalog. The before capture applies
the previous joined label to the rendered command rows; the after capture is
the output of the updated `keyboard-navigation.js` bundle. The source tree at
left intentionally retains its count badges in both captures—the fix is scoped
to command-palette label extraction.
