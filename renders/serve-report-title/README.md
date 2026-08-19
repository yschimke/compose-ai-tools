# Per-preview "report an issue" — asking the reporter for a title

Evidence for [#4228](https://github.com/yschimke/compose-ai-tools/issues/4228): the viewer's
per-preview report used to be one click straight to a GitHub issue whose title the server
wrote (`Preview issue: <preview> (<system>)`), so the reporter was never asked what was
actually wrong. It now opens a small panel with a **required** Summary — the same trade the
footer's "report a bug" already makes since
[#4153](https://github.com/yschimke/compose-ai-tools/pull/4153).

Captured from the committed `serve-viewer` page fixture by the preview-harness
(`npm --prefix vscode-extension run harness:snapshot`). The `report-open` state is a
registered `FIXTURE_STATES` entry, so every later change to this panel is diffed
automatically rather than needing a hand-taken screenshot.

| | light | dark |
| --- | --- | --- |
| before — one click, no questions asked | `viewer-report-before.light.png` | `viewer-report-before.dark.png` |
| after — the panel that click now opens | `viewer-report-open-after.light.png` | `viewer-report-open-after.dark.png` |

## On a phone

The panel's first cut floated off the right of the screen at every width up to ~636px: its
toggle is the third entry in the provenance row, that row does not wrap until far below phone
width, and a `max-width` caps how wide a panel is while saying nothing about where it starts.
Measured on this fixture at 360px, it ran from x=191 to x=519 and took the document's
scrollWidth with it. The panel is now handed to the row, which spans the full content width,
so it is anchored at both ends — the same remedy `.cp-catalog-theme` already uses for the
landing's Theme menu.

`viewer-report-open-mobile.light.png` / `viewer-report-open-mobile.dark.png`, captured at
412x800 by the `report-open-mobile` harness state.

The closed row is unchanged: "report an issue" still reads as a link beside "source" and
"playground". What changed is what the click does — and the preview's identity is not lost,
it moved to the `| Preview |` row of the issue body's "Which preview" table.
