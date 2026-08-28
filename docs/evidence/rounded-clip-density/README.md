# Rounded clip radius doubled at density ≠ 1 (wear-m3-catalog#89)

`RemoteRoundedClipShape` multiplied every corner by the display density when the
document declared `DENSITY_BEHAVIOR_DP`, on the belief that remote-core scaled
DP-mode corners only at paint time. It does not — `updateVariables` folds the
density into `mX1..mY2` before the player reads them. Measured off a real
document, a 26dp card corner arrives as `26` at density 1.0 and `52` at density
2.0, so the extra multiply doubled it.

`before.png` / `after.png` — `Card/Outlined`, `Card` and `AppCard` from
wear-m3-catalog's `remote-catalog`, rendered at density 2.0 (`dpi=320`).

The outlined card is the loud case: clipped to a 104px radius instead of 52px,
the clip cut the corners and sides off the border its content drew, leaving two
hairlines. The path handed to `drawPath` was never wrong — it measures a
complete 1168px rounded-rect contour.

The filled cards were wrong the whole time too, just quietly: over-rounded into
lozenges that read as a design choice rather than a bug.

**Why it survived so long.** `roundedRectRadiusScale` normalizes an oversized
corner back to the box, so on a stadium or a circle the doubled radius clamps
straight back to the right shape — every button on the sheet looked correct.
Only a corner genuinely smaller than half its box keeps the doubling. And at
density 1.0 the multiply is a no-op, which is the density the unit tests used.

Of 57 `remote-catalog` renders, 47 are byte-identical across the fix; the 10 that
move are the cards, the watch-screen template and the two non-stadium buttons.

Regenerate (needs an Android SDK and a wear-m3-catalog checkout):

```bash
# in compose-ai-tools
PLUGIN_VERSION=1.44.0-SNAPSHOT ./gradlew :third-party-rc-embedded-player:publishToMavenLocal
# in wear-m3-catalog, with mavenLocal() added and composePreviewAnnotations pinned to the snapshot
./gradlew :remote-catalog:composePreviewRenderAll
```
