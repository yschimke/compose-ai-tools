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

The closed row is unchanged: "report an issue" still reads as a link beside "source" and
"playground". What changed is what the click does — and the preview's identity is not lost,
it moved to the `| Preview |` row of the issue body's "Which preview" table.
