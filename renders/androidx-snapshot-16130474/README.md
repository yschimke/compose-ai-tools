# androidx.dev snapshot 16113093 → 16130474 — before/after

`*-before.png` is rendered against androidx-main post-submit build `16113093`
(the build `androidxSnapshotBuildId` pinned previously); `*-after.png` against
build `16130474`. Same previews, same renderer, same command —
`:samples:remotecompose:composePreviewRenderAll` and
`:samples:design-catalog-remote-m3:composePreviewRenderAll` — with only the
build id moved.

60 of the 65 rendered previews across the two samples are byte-identical. The
five that moved are all circular progress indicators:

| file | preview | pixels changed |
| --- | --- | --- |
| `remote-m3-circular-progress-*.png` | remote-m3 `CircularProgressRemote` | 2.24% |
| `remote-m3-disabled-circular-progress-*.png` | remote-m3 `DisabledCircularProgressRemote` | 2.23% |
| `rc-circular-progress-standard-*.png` | `:samples:remotecompose` `RemoteAnimatedCircularProgressIndicatorStandardPreview` | 2.10% |
| `rc-circular-progress-embedded-*.png` | `:samples:remotecompose` `RemoteAnimatedCircularProgressIndicatorEmbeddedPreview` | 2.10% |
| `remote-m3-indeterminate-circular-progress-*.png` | remote-m3 `IndeterminateCircularProgressRemote` | 0.58% |

The drift is one upstream change, not five: the indicator arc is drawn with a
thicker stroke and a wider gap between the active segment and the track, so the
ring reads heavier and the break between segments is more pronounced. It is a
styling move, not a defect — the geometry, colours and progress values are
unchanged, and the indeterminate preview (whose active segment is short) moves
least because less of its ring is drawn.

Nothing else in either sample changed: buttons, shapes, shaders, text and layout
previews are all byte-identical across the two builds. Difference is the
fraction of pixels differing at all between the two PNGs.
