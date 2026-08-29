# Full-height preview cards

Chromium captures of the committed `serve-landing-grouped` page fixture in the mixed-height state
added for issue #4784. The harness constrains the FAB preview to 96 px beside the taller Card
family, reproducing the reported short-control / tall-screen row without depending on a remote
catalog.

- `before.png` — the former natural-height cards leave the short FAB tile pinned to the top.
- `after.png` — every card fills the row, the short FAB is vertically centred in its expanded image
  well, and the metadata footers align.

The after-state capture also runs browser geometry assertions for equal card heights, equal card
bottoms, and vertical centring before taking the screenshot.
