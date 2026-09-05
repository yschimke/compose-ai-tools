# Wear LONG scroll seams — horologist `SectionedList*` previews

horologist's `sample` module, `@WearPreviewDevices @ScrollingPreview(modes = [LONG])` on the four
`SectionedList*ScreenPreview` functions — a Wear Material `ScalingLazyColumn` of section headers and
chips under an `AppScaffold` whose `TimeText` is **pinned** (drawn over the top of the viewport while
the list scrolls under it).

Rendered with `./gradlew :sample:composePreviewRender --rerun -PcomposePreview.filter='SectionedList*'`
against the plugin published to mavenLocal; PNGs read from
`sample/build/compose-previews/data/render-scroll-long/`.

| file | plugin | what it shows |
| --- | --- | --- |
| `before-expandable-small-round.png` | `main` before this change | `10:10` stamped over the `Tomorrow` header, which repeats |
| `before-expandable-large-round.png` | `main` before this change | `Book holidays` repeated with `10:10` between; the last viewport painted twice |
| `before-stateful-small-round.png` | `main` before this change | `Recommendations` repeated with `10:10` through it |
| `before-stateless-small-round.png` | `main` before this change | same seam; every following item shifted |
| `after-*.png` | this change | the same four previews, every seam verified |

**Prevalence:** 4 of the 6 `SectionedList*` LONG renders were broken (the two `Devices - Large Round`
media-style screens happened to land on a seam the old matcher got right). Every failure had the same
signature: the matcher picked a shift at the far end of its window whose overlap was a dozen all-black
rows, scored it near 0, and painted the next slice from its top row — `TimeText` and all — sixty rows
too high.

**Detection:** the renderer now scores every seam (`data/scroll/core`'s `ScrollSeam`) and every
stride (`data/scroll/android`'s `ScrollStep`); anything unverified is written to
`<png>.warnings.json` as `unverifiedScrollSeams` / `unlandedScrollSteps`. On the `before` slices the
new matcher reports every seam verified; the old choice would have been reported as `low_signal`
(0 informative rows in the overlap).

Regenerate the `before` side by rendering horologist against a plugin from before this change; the
`after` side with the plugin from this branch (`./gradlew publishToMavenLocal`, then point
horologist's `composeAiTools` version at the snapshot).
