# Remote Compose on androidx.dev snapshots — before/after

`*-before.png` is rendered against the Google Maven alphas the catalog pinned
(`compose-remote 1.0.0-alpha17` / `wear-compose-remote 1.0.0-alpha09`);
`*-after.png` against the androidx-main snapshot build `16113093`
(`1.0.0-SNAPSHOT` for both, plus `androidx.glance.wear`). Same preview, same
renderer, same command — `:samples:remotecompose:composePreviewRenderAll` and
`:samples:design-catalog-remote-m3:composePreviewRenderAll` — with only the
dependency line moved.

| file | preview | pixels changed |
| --- | --- | --- |
| `bordered-button-*.png` | `:samples:remotecompose` `RemoteButtonWithBorderPreview` | 6.2% |
| `button-group-*.png` | remote-m3 `ButtonGroupRemote` | 3.7% |
| `disabled-button-*.png` | remote-m3 `DisabledRemoteButton` | 3.2% |
| `enabled-button-*.png` | `:samples:remotecompose` `RemoteButtonEnabledPreview` | 1.5% |

The bordered button is the substantive one: on the alphas the border drew only
where the button's own fill didn't cover it, so the green ring survived as four
slivers at the edges. On the snapshot it draws as a complete ring around the
pill. The other three are shape-default drift — the button silhouette is fuller
and rounder, closer to a true pill — not a defect either way.

33 of the 65 rendered previews across the two samples changed; these four are
the largest movers. Difference is the fraction of pixels differing at all
between the two PNGs.
