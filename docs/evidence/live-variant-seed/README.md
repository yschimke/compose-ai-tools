# A live-browsed `@OverrideVariant` renders its own state (yschimke/wear-m3-catalog#33)

The `split` variant of a labelled switch row, rendered through the **held**
(`interactive/start` → stream) lane the viewer's *Live (stream)* toggle opens,
with no `knob.*` in the request — an ordinary browse.

`before.png` — one container. The variant's baked `split = true` seed never
reached the held composition, so it composed its primary's state. The frame was
byte-identical to the primary's (same SHA over every pixel), which is why the
report reads "in Live mode split switch becomes the normal one" and not as an
error: nothing failed, the picture was just of a different component.

`after.png` — two containers with a gap, i.e. the two tap targets the variant is.
The seed the daemon resolved from `previews.json` is now the floor the
per-render override bag lands on, so a browse with no knobs still composes the
variant.

The frames are `RedFixturePreviews.SplittableSwitchRow`, a desktop stand-in for
`wear-m3-catalog`'s `SwitchButton`: its two states differ structurally, so a lost
seed shows up as the wrong component rather than as a recoloured one.

Regenerate (`after.png`; `before.png` needs the fix in
`DesktopHost.applyOverrides` reverted):

```bash
HELD_VARIANT_DEMO_DIR=/tmp/live-variant-evidence \
  ./gradlew :daemon:desktop:test --tests '*OverrideIntegrationTest.heldSessionSeedsTheSplitVariantTheIssueReported*'
```

It writes `primary.png` and `variant.png`; `variant.png` is the frame above.
