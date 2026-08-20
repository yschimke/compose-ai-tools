# wear-os-samples (WearTilesKotlin) — Compose previews

Auto-rendered by the integration matrix from [`android/wear-os-samples@main`](https://github.com/android/wear-os-samples/tree/main). Updated on every push to `main`.

## CI notes

- Tiles-focused cell: at least one `kind: TILE` preview must
  render, guarding the `TilePreviewComposable` →
  `TileRenderer.inflateAsync` path.
- Pins an older Compose BOM than our renderer, which previously
  surfaced a transitive-dependency gap (activity-compose 1.13 →
  missing `androidx.navigationevent.R` resources crashed
  Robolectric at Activity bootstrap). Kept rendering so the
  regression can't return silently.


### Workarounds applied by the integration harness

- Source: [`android/wear-os-samples@main`](https://github.com/android/wear-os-samples/tree/main)
- No source or build-script workarounds — the project renders against the locally-built plugin snapshot as-is.

> [!WARNING]
> 1 preview(s) failed to render in the latest update (1 with no prior baseline to retain). See **Render Failures** below.

## Render Failures

The render task completed but no PNG was produced for these previews. Entries with a prior baseline keep their previous image; the rest are absent from the gallery until a successful render lands.

| Preview | Module | Function | Source | Baseline |
|---------|--------|----------|--------|----------|
| `app/com.example.wear.tiles.golden.AlarmKt.alarmPreview_Large Round 0.94f` | app | `alarmPreview` | `src/debug/java/com/example/wear/tiles/golden/Alarm.kt` | none |

## app

| Preview | Image |
|---------|-------|
| `alarmPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/alarmPreview_Large_Round_1_00f-5c5ff4ec.png" width="150" /> |
| `alarmPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/alarmPreview_Large_Round_1_24f-0e00bbe4.png" width="150" /> |
| `alarmPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/alarmPreview_Small_Round_0_94f-3219b540.png" width="150" /> |
| `alarmPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/alarmPreview_Small_Round_1_00f-389a1e70.png" width="150" /> |
| `alarmPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/alarmPreview_Small_Round_1_24f-0dd3ac7a.png" width="150" /> |
| `calendar1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar1Preview_Large_Round_0_94f-41cad8e6.png" width="150" /> |
| `calendar1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar1Preview_Large_Round_1_00f-20a09034.png" width="150" /> |
| `calendar1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar1Preview_Large_Round_1_24f-ee0fb6e4.png" width="150" /> |
| `calendar1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar1Preview_Small_Round_0_94f-578ce3fe.png" width="150" /> |
| `calendar1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar1Preview_Small_Round_1_00f-449e5267.png" width="150" /> |
| `calendar1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar1Preview_Small_Round_1_24f-832fa416.png" width="150" /> |
| `calendar2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar2Preview_Large_Round_0_94f-fd4193b2.png" width="150" /> |
| `calendar2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar2Preview_Large_Round_1_00f-9aa6676d.png" width="150" /> |
| `calendar2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar2Preview_Large_Round_1_24f-4ab2553f.png" width="150" /> |
| `calendar2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar2Preview_Small_Round_0_94f-a09b1d70.png" width="150" /> |
| `calendar2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar2Preview_Small_Round_1_00f-c25fae7d.png" width="150" /> |
| `calendar2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar2Preview_Small_Round_1_24f-60472ead.png" width="150" /> |
| `goalPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/goalPreview_Large_Round_0_94f-b905c81c.png" width="150" /> |
| `goalPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/goalPreview_Large_Round_1_00f-7bdd6010.png" width="150" /> |
| `goalPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/goalPreview_Large_Round_1_24f-88afb512.png" width="150" /> |
| `goalPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/goalPreview_Small_Round_0_94f-0d33bd4f.png" width="150" /> |
| `goalPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/goalPreview_Small_Round_1_00f-1182706e.png" width="150" /> |
| `goalPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/goalPreview_Small_Round_1_24f-889007e3.png" width="150" /> |
| `alarm` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/alarm_Large_Round-fe43b361.png" width="150" /> |
| `alarm` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/alarm_Small_Round-b23e2539.png" width="150" /> |
| `calendar1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar1_Large_Round-8b06161c.png" width="150" /> |
| `calendar1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar1_Small_Round-4a5b8357.png" width="150" /> |
| `calendar2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar2_Large_Round-7b71dd6f.png" width="150" /> |
| `calendar2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar2_Small_Round-4d689c79.png" width="150" /> |
| `contacts2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/contacts2_Large_Round-3be4f2a2.png" width="150" /> |
| `contacts2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/contacts2_Small_Round-a7d0b92b.png" width="150" /> |
| `contacts5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/contacts5_Large_Round-13725b76.png" width="150" /> |
| `contacts5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/contacts5_Small_Round-e16a8fdb.png" width="150" /> |
| `contacts6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/contacts6_Large_Round-78136a93.png" width="150" /> |
| `contacts6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/contacts6_Small_Round-d561b824.png" width="150" /> |
| `goal` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/goal_Large_Round-bfc63161.png" width="150" /> |
| `goal` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/goal_Small_Round-8e35219a.png" width="150" /> |
| `hike` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/hike_Large_Round-eb2d8996.png" width="150" /> |
| `hike` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/hike_Small_Round-beed5b92.png" width="150" /> |
| `media` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/media_Large_Round-f71f0027.png" width="150" /> |
| `media` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/media_Small_Round-2e83941d.png" width="150" /> |
| `mindfulness` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mindfulness_Large_Round-f58376f9.png" width="150" /> |
| `mindfulness` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mindfulness_Small_Round-8d697e3f.png" width="150" /> |
| `news` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/news_Large_Round-0ad811c9.png" width="150" /> |
| `news` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/news_Small_Round-fa561661.png" width="150" /> |
| `run` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/run_Large_Round-55937af3.png" width="150" /> |
| `run` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/run_Small_Round-19e0ffff.png" width="150" /> |
| `ski` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/ski_Large_Round-a2d3d3ab.png" width="150" /> |
| `ski` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/ski_Small_Round-4c8a4bd5.png" width="150" /> |
| `timer1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer1_Large_Round-3db0a0da.png" width="150" /> |
| `timer1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer1_Small_Round-4ff7424b.png" width="150" /> |
| `timer2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer2_Large_Round-be6227d9.png" width="150" /> |
| `timer2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer2_Small_Round-2bfa7e29.png" width="150" /> |
| `workout` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workout_Large_Round-8bf97cab.png" width="150" /> |
| `workout` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workout_Small_Round-766402d7.png" width="150" /> |
| `hikePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/hikePreview_Large_Round_0_94f-c6432c77.png" width="150" /> |
| `hikePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/hikePreview_Large_Round_1_00f-d8e80823.png" width="150" /> |
| `hikePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/hikePreview_Large_Round_1_24f-95d47c7c.png" width="150" /> |
| `hikePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/hikePreview_Small_Round_0_94f-cdd715a8.png" width="150" /> |
| `hikePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/hikePreview_Small_Round_1_00f-89af9e33.png" width="150" /> |
| `hikePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/hikePreview_Small_Round_1_24f-8d7350cd.png" width="150" /> |
| `mediaPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mediaPreview_Large_Round_0_94f-4792ebc1.png" width="150" /> |
| `mediaPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mediaPreview_Large_Round_1_00f-21598ab1.png" width="150" /> |
| `mediaPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mediaPreview_Large_Round_1_24f-972a7fcc.png" width="150" /> |
| `mediaPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mediaPreview_Small_Round_0_94f-b959626f.png" width="150" /> |
| `mediaPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mediaPreview_Small_Round_1_00f-04747467.png" width="150" /> |
| `mediaPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mediaPreview_Small_Round_1_24f-be2a772d.png" width="150" /> |
| `mindfulnessPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mindfulnessPreview_Large_Round_0_94f-f3b74d4a.png" width="150" /> |
| `mindfulnessPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mindfulnessPreview_Large_Round_1_00f-81b81dac.png" width="150" /> |
| `mindfulnessPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mindfulnessPreview_Large_Round_1_24f-9cc3d83a.png" width="150" /> |
| `mindfulnessPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mindfulnessPreview_Small_Round_0_94f-ccf12d26.png" width="150" /> |
| `mindfulnessPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mindfulnessPreview_Small_Round_1_00f-ca30b47f.png" width="150" /> |
| `mindfulnessPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mindfulnessPreview_Small_Round_1_24f-06dc9764.png" width="150" /> |
| `newsPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/newsPreview_Large_Round_0_94f-27276569.png" width="150" /> |
| `newsPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/newsPreview_Large_Round_1_00f-5db70f2d.png" width="150" /> |
| `newsPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/newsPreview_Large_Round_1_24f-ff99a545.png" width="150" /> |
| `newsPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/newsPreview_Small_Round_0_94f-0c853673.png" width="150" /> |
| `newsPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/newsPreview_Small_Round_1_00f-e7baf2fa.png" width="150" /> |
| `newsPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/newsPreview_Small_Round_1_24f-cc0cf1b0.png" width="150" /> |
| `skiPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/skiPreview_Large_Round_0_94f-8913b30b.png" width="150" /> |
| `skiPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/skiPreview_Large_Round_1_00f-e19ca2e0.png" width="150" /> |
| `skiPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/skiPreview_Large_Round_1_24f-e8fc1211.png" width="150" /> |
| `skiPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/skiPreview_Small_Round_0_94f-5eefa90b.png" width="150" /> |
| `skiPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/skiPreview_Small_Round_1_00f-5fa1b735.png" width="150" /> |
| `skiPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/skiPreview_Small_Round_1_24f-147f0800.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview1_Large_Round_0_94f-d2fda872.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview1_Large_Round_1_00f-20ada1d3.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview1_Large_Round_1_24f-4e293fa3.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview1_Small_Round_0_94f-dcb80719.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview1_Small_Round_1_00f-2c5bd278.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview1_Small_Round_1_24f-3fa98d11.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview2_Large_Round_0_94f-79ede690.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview2_Large_Round_1_00f-420072e8.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview2_Large_Round_1_24f-1e74f606.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview2_Small_Round_0_94f-9cb8fdf9.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview2_Small_Round_1_00f-faeb6dfd.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview2_Small_Round_1_24f-04b9c075.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview3_Large_Round_0_94f-de563135.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview3_Large_Round_1_00f-438757d0.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview3_Large_Round_1_24f-ce7c4dce.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview3_Small_Round_0_94f-67c5da10.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview3_Small_Round_1_00f-2ee3a926.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview3_Small_Round_1_24f-e4582519.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview4_Large_Round_0_94f-99d23f14.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview4_Large_Round_1_00f-cc036b54.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview4_Large_Round_1_24f-7e189728.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview4_Small_Round_0_94f-da88014c.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview4_Small_Round_1_00f-606e939e.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview4_Small_Round_1_24f-a6033314.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview5_Large_Round_0_94f-ecef9a29.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview5_Large_Round_1_00f-cba4b077.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview5_Large_Round_1_24f-256fdb29.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview5_Small_Round_0_94f-eabafbbd.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview5_Small_Round_1_00f-4feebb4a.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview5_Small_Round_1_24f-a52e59e1.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview6_Large_Round_0_94f-f4420939.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview6_Large_Round_1_00f-cb4d80e2.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview6_Large_Round_1_24f-ec0acb62.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview6_Small_Round_0_94f-7323e67b.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview6_Small_Round_1_00f-1a8baf0b.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview6_Small_Round_1_24f-5093a4cc.png" width="150" /> |
| `timer1LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer1LayoutPreview_Large_Round_0_94f-49a0850d.png" width="150" /> |
| `timer1LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer1LayoutPreview_Large_Round_1_00f-cca89f4d.png" width="150" /> |
| `timer1LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer1LayoutPreview_Large_Round_1_24f-2d8f6790.png" width="150" /> |
| `timer1LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer1LayoutPreview_Small_Round_0_94f-44793c2c.png" width="150" /> |
| `timer1LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer1LayoutPreview_Small_Round_1_00f-900a0d93.png" width="150" /> |
| `timer1LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer1LayoutPreview_Small_Round_1_24f-a001f600.png" width="150" /> |
| `timer2LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer2LayoutPreview_Large_Round_0_94f-6d467878.png" width="150" /> |
| `timer2LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer2LayoutPreview_Large_Round_1_00f-8d955192.png" width="150" /> |
| `timer2LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer2LayoutPreview_Large_Round_1_24f-9f4fbb8a.png" width="150" /> |
| `timer2LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer2LayoutPreview_Small_Round_0_94f-23d2ac55.png" width="150" /> |
| `timer2LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer2LayoutPreview_Small_Round_1_00f-ae73ce5b.png" width="150" /> |
| `timer2LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer2LayoutPreview_Small_Round_1_24f-a21adce0.png" width="150" /> |
| `weatherPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/weatherPreview_Large_Round_0_94f-2eba0e99.png" width="150" /> |
| `weatherPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/weatherPreview_Large_Round_1_00f-f5b758a4.png" width="150" /> |
| `weatherPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/weatherPreview_Large_Round_1_24f-f8ed6116.png" width="150" /> |
| `weatherPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/weatherPreview_Small_Round_0_94f-4914aa0e.png" width="150" /> |
| `weatherPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/weatherPreview_Small_Round_1_00f-b78746c9.png" width="150" /> |
| `weatherPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/weatherPreview_Small_Round_1_24f-d5a6bb5e.png" width="150" /> |
| `workoutLayout1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout1Preview_Large_Round_0_94f-d2724080.png" width="150" /> |
| `workoutLayout1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout1Preview_Large_Round_1_00f-e5be1a92.png" width="150" /> |
| `workoutLayout1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout1Preview_Large_Round_1_24f-2b167bb6.png" width="150" /> |
| `workoutLayout1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout1Preview_Small_Round_0_94f-94a14d6a.png" width="150" /> |
| `workoutLayout1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout1Preview_Small_Round_1_00f-c4c513f7.png" width="150" /> |
| `workoutLayout1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout1Preview_Small_Round_1_24f-537e5103.png" width="150" /> |
| `workoutLayout2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout2Preview_Large_Round_0_94f-11b3f62b.png" width="150" /> |
| `workoutLayout2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout2Preview_Large_Round_1_00f-18c92401.png" width="150" /> |
| `workoutLayout2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout2Preview_Large_Round_1_24f-b0d50762.png" width="150" /> |
| `workoutLayout2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout2Preview_Small_Round_0_94f-25382129.png" width="150" /> |
| `workoutLayout2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout2Preview_Small_Round_1_00f-28c74aee.png" width="150" /> |
| `workoutLayout2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout2Preview_Small_Round_1_24f-fbaa129a.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview1_Large_Round_0_94f-e0d8a005.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview1_Large_Round_1_00f-d639a905.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview1_Large_Round_1_24f-c676aee6.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview1_Small_Round_0_94f-40b2cc18.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview1_Small_Round_1_00f-dd8fc100.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview1_Small_Round_1_24f-9819de42.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview2_Large_Round_0_94f-1ff6d9a9.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview2_Large_Round_1_00f-0c5a1cf0.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview2_Large_Round_1_24f-ada54a77.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview2_Small_Round_0_94f-2642035a.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview2_Small_Round_1_00f-322bc43d.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview2_Small_Round_1_24f-bce37d63.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview3_Large_Round_0_94f-53e01b22.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview3_Large_Round_1_00f-c0234fbf.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview3_Large_Round_1_24f-b7837dc9.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview3_Small_Round_0_94f-2a4974b6.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview3_Small_Round_1_00f-f4170d80.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview3_Small_Round_1_24f-382c4ff0.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview4_Large_Round_0_94f-5ef150b4.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview4_Large_Round_1_00f-c306901c.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview4_Large_Round_1_24f-d6c7788e.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview4_Small_Round_0_94f-f2d40d3c.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview4_Small_Round_1_00f-9f099119.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview4_Small_Round_1_24f-095c3d03.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview5_Large_Round_0_94f-ce9ada8e.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview5_Large_Round_1_00f-dace93b9.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview5_Large_Round_1_24f-9c92cbb8.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview5_Small_Round_0_94f-3c1fc129.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview5_Small_Round_1_00f-debe4b04.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview5_Small_Round_1_24f-26022609.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview6_Large_Round_0_94f-8db47f90.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview6_Large_Round_1_00f-f637bbef.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview6_Large_Round_1_24f-6e3f7b30.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview6_Small_Round_0_94f-65ed058d.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview6_Small_Round_1_00f-8408e5d1.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/socialPreview6_Small_Round_1_24f-3aef15e9.png" width="150" /> |

