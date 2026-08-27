# Remote Compose snapshot → released alphas — before/after

`*-before.png` is rendered against the androidx-main snapshot the catalog
pinned (`1.0.0-SNAPSHOT` on build `16155060` for `compose-remote`,
`wear-compose-remote` and `glance-wear`); `*-after.png` against the released
Google Maven coordinates this change moves to — `compose-remote 1.0.0-alpha18`,
`wear-compose-remote 1.0.0-alpha10`, `glance-wear 1.0.0-alpha17`. Same
previews, same renderer, same command —
`:samples:remotecompose:composePreviewRenderAll` and
`:samples:design-catalog-remote-m3:composePreviewRenderAll` — with only the
dependency line moved (`-Pcomposeai.remoteCompose=snapshot` renders the before
lane).

**63 of the 67 rendered PNGs across the two samples are byte-identical.** The
four that moved are all circular progress indicators, plus the three
indeterminate `.gif` animations of the same components:

| file | preview | pixels changed |
| --- | --- | --- |
| `rc-circular-progress-standard-*.png` | `:samples:remotecompose` `RemoteAnimatedCircularProgressIndicatorStandardPreview` | 2.10% |
| `rc-circular-progress-embedded-*.png` | `:samples:remotecompose` `RemoteAnimatedCircularProgressIndicatorEmbeddedPreview` | 2.10% |
| `remote-m3-circular-progress-*.png` | remote-m3 `CircularProgressRemote` | 1.74% |
| `remote-m3-disabled-circular-progress-*.png` | remote-m3 `DisabledCircularProgressRemote` | 1.73% |

The drift is one upstream change, not four, and it is precisely the one
[`renders/androidx-snapshot-16130474/`](../androidx-snapshot-16130474/) recorded
in the other direction: the indicator arc's heavier stroke and wider
active/track gap are still snapshot-only, so moving back to released
coordinates draws the ring thinner again with a smaller break between
segments. Geometry, colours and progress values are unchanged. Nothing else in
either sample moved — buttons, shapes, shaders, text and layout previews are
all byte-identical across the two lanes.

Expect these four to move once more, back to the heavier stroke, when the
released line catches up with the snapshot.

Difference is the fraction of pixels differing at all between the two PNGs.
