# Per-preview "report an issue" — asking the reporter for a title

Evidence for [#4228](https://github.com/yschimke/compose-ai-tools/issues/4228): the viewer's
per-preview report used to be one click straight to a GitHub issue whose title the server
wrote (`Preview issue: <preview> (<system>)`), so the reporter was never asked what was
actually wrong. It now opens a small panel with a **required** Summary — the same trade the
footer's "report a bug" already makes since
[#4153](https://github.com/yschimke/compose-ai-tools/pull/4153).

Captured from the committed page fixtures by the preview-harness
(`npm --prefix vscode-extension run harness:snapshot`). Every state below is a registered
`FIXTURE_STATES` entry, so later changes to this panel are diffed automatically rather than
needing a hand-taken screenshot.

| | light | dark |
| --- | --- | --- |
| before — one click, no questions asked | `viewer-report-before.light.png` | `viewer-report-before.dark.png` |
| after — the panel that click now opens | `viewer-report-open-after.light.png` | `viewer-report-open-after.dark.png` |

The closed row is unchanged: "report an issue" still reads as a link beside "source" and
"playground". What changed is what the click does — and the preview's identity is not lost,
it moved to the `| Preview |` row of the issue body's "Which preview" table.

## Where the panel is anchored, and why

The panel hangs off the **row**, not off its own toggle. A `max-width` caps how wide a panel
is and says nothing about where it starts, and this toggle's start moves: it is the
last-but-one entry in a row whose earlier entries are all optional, so every link a session
happens to have shifts it right. Two separate ways that bit:

- With the panel anchored to the toggle, the row's ~190px offset at phone width ran the panel
  from x=191 to x=519 on a 360px screen.
- A live viewer with an **executable bundle** carries four links before the toggle, which
  pushed the panel to x=420 — clipped by up to 160px between 680 and 820px. `html {
  overflow-x: clip }` means clipped, not scrollable, so the Summary field was simply
  unreachable with no scrollbar to hint at it.

Anchoring to the row makes the panel's position independent of how many links precede it, so
no future addition to this row can reintroduce the bug at some width nobody thought to test.
Verified across 320–1600px on all three fixtures that carry the affordance, with and without
the bundle link.

The focused comparison renders the same affordance with no `.cp-preview-links` around it, so
the rule is scoped to the row; there `.cp-report` stays relative, which is the only containing
block that panel has.

| state | what it locks | files |
| --- | --- | --- |
| `report-open-mobile` (412×800) | the phone layout | `viewer-report-open-mobile.*.png` |
| `report-open-bundle` (760×800) | the bundle-enabled row, at the width it used to clip at | `viewer-report-open-bundle.*.png` |
| comparison `report-open-mobile` (412×800) | the page with no provenance row, which had no coverage at all | `compare-report-open-mobile.*.png` |

The last two assert the panel's geometry as well as shooting it: a panel clipped by
`overflow-x: clip`, or one that flew 619px above its toggle, still screenshots as a
plausible-looking page.
