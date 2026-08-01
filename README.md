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

## app

| Preview | Image |
|---------|-------|
| `alarmPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/alarmPreview_Large_Round_0_94f.png" width="150" /> |
| `alarmPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/alarmPreview_Large_Round_1_00f.png" width="150" /> |
| `alarmPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/alarmPreview_Large_Round_1_24f.png" width="150" /> |
| `alarmPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/alarmPreview_Small_Round_0_94f.png" width="150" /> |
| `alarmPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/alarmPreview_Small_Round_1_00f.png" width="150" /> |
| `alarmPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/alarmPreview_Small_Round_1_24f.png" width="150" /> |
| `calendar1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar1Preview_Large_Round_0_94f.png" width="150" /> |
| `calendar1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar1Preview_Large_Round_1_00f.png" width="150" /> |
| `calendar1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar1Preview_Large_Round_1_24f.png" width="150" /> |
| `calendar1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar1Preview_Small_Round_0_94f.png" width="150" /> |
| `calendar1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar1Preview_Small_Round_1_00f.png" width="150" /> |
| `calendar1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar1Preview_Small_Round_1_24f.png" width="150" /> |
| `calendar2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar2Preview_Large_Round_0_94f.png" width="150" /> |
| `calendar2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar2Preview_Large_Round_1_00f.png" width="150" /> |
| `calendar2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar2Preview_Large_Round_1_24f.png" width="150" /> |
| `calendar2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar2Preview_Small_Round_0_94f.png" width="150" /> |
| `calendar2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar2Preview_Small_Round_1_00f.png" width="150" /> |
| `calendar2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar2Preview_Small_Round_1_24f.png" width="150" /> |
| `goalPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/goalPreview_Large_Round_0_94f.png" width="150" /> |
| `goalPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/goalPreview_Large_Round_1_00f.png" width="150" /> |
| `goalPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/goalPreview_Large_Round_1_24f.png" width="150" /> |
| `goalPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/goalPreview_Small_Round_0_94f.png" width="150" /> |
| `goalPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/goalPreview_Small_Round_1_00f.png" width="150" /> |
| `goalPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/goalPreview_Small_Round_1_24f.png" width="150" /> |
| `alarm` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/alarm_Large_Round.png" width="150" /> |
| `alarm` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/alarm_Small_Round.png" width="150" /> |
| `calendar1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar1_Large_Round.png" width="150" /> |
| `calendar1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar1_Small_Round.png" width="150" /> |
| `calendar2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar2_Large_Round.png" width="150" /> |
| `calendar2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/calendar2_Small_Round.png" width="150" /> |
| `contacts2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/contacts2_Large_Round.png" width="150" /> |
| `contacts2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/contacts2_Small_Round.png" width="150" /> |
| `contacts5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/contacts5_Large_Round.png" width="150" /> |
| `contacts5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/contacts5_Small_Round.png" width="150" /> |
| `contacts6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/contacts6_Large_Round.png" width="150" /> |
| `contacts6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/contacts6_Small_Round.png" width="150" /> |
| `goal` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/goal_Large_Round.png" width="150" /> |
| `goal` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/goal_Small_Round.png" width="150" /> |
| `hike` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/hike_Large_Round.png" width="150" /> |
| `hike` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/hike_Small_Round.png" width="150" /> |
| `media` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/media_Large_Round.png" width="150" /> |
| `media` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/media_Small_Round.png" width="150" /> |
| `mindfulness` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mindfulness_Large_Round.png" width="150" /> |
| `mindfulness` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mindfulness_Small_Round.png" width="150" /> |
| `news` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/news_Large_Round.png" width="150" /> |
| `news` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/news_Small_Round.png" width="150" /> |
| `run` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/run_Large_Round.png" width="150" /> |
| `run` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/run_Small_Round.png" width="150" /> |
| `ski` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/ski_Large_Round.png" width="150" /> |
| `ski` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/ski_Small_Round.png" width="150" /> |
| `timer1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer1_Large_Round.png" width="150" /> |
| `timer1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer1_Small_Round.png" width="150" /> |
| `timer2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer2_Large_Round.png" width="150" /> |
| `timer2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer2_Small_Round.png" width="150" /> |
| `workout` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workout_Large_Round.png" width="150" /> |
| `workout` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workout_Small_Round.png" width="150" /> |
| `hikePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/hikePreview_Large_Round_0_94f.png" width="150" /> |
| `hikePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/hikePreview_Large_Round_1_00f.png" width="150" /> |
| `hikePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/hikePreview_Large_Round_1_24f.png" width="150" /> |
| `hikePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/hikePreview_Small_Round_0_94f.png" width="150" /> |
| `hikePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/hikePreview_Small_Round_1_00f.png" width="150" /> |
| `hikePreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/hikePreview_Small_Round_1_24f.png" width="150" /> |
| `mediaPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mediaPreview_Large_Round_0_94f.png" width="150" /> |
| `mediaPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mediaPreview_Large_Round_1_00f.png" width="150" /> |
| `mediaPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mediaPreview_Large_Round_1_24f.png" width="150" /> |
| `mediaPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mediaPreview_Small_Round_0_94f.png" width="150" /> |
| `mediaPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mediaPreview_Small_Round_1_00f.png" width="150" /> |
| `mediaPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mediaPreview_Small_Round_1_24f.png" width="150" /> |
| `mindfulnessPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mindfulnessPreview_Large_Round_0_94f.png" width="150" /> |
| `mindfulnessPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mindfulnessPreview_Large_Round_1_00f.png" width="150" /> |
| `mindfulnessPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mindfulnessPreview_Large_Round_1_24f.png" width="150" /> |
| `mindfulnessPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mindfulnessPreview_Small_Round_0_94f.png" width="150" /> |
| `mindfulnessPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mindfulnessPreview_Small_Round_1_00f.png" width="150" /> |
| `mindfulnessPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/mindfulnessPreview_Small_Round_1_24f.png" width="150" /> |
| `newsPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/newsPreview_Large_Round_0_94f.png" width="150" /> |
| `newsPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/newsPreview_Large_Round_1_00f.png" width="150" /> |
| `newsPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/newsPreview_Large_Round_1_24f.png" width="150" /> |
| `newsPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/newsPreview_Small_Round_0_94f.png" width="150" /> |
| `newsPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/newsPreview_Small_Round_1_00f.png" width="150" /> |
| `newsPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/newsPreview_Small_Round_1_24f.png" width="150" /> |
| `skiPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/skiPreview_Large_Round_0_94f.png" width="150" /> |
| `skiPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/skiPreview_Large_Round_1_00f.png" width="150" /> |
| `skiPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/skiPreview_Large_Round_1_24f.png" width="150" /> |
| `skiPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/skiPreview_Small_Round_0_94f.png" width="150" /> |
| `skiPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/skiPreview_Small_Round_1_00f.png" width="150" /> |
| `skiPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/skiPreview_Small_Round_1_24f.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview1_Large_Round_0_94f.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview1_Large_Round_1_00f.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview1_Large_Round_1_24f.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview1_Small_Round_0_94f.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview1_Small_Round_1_00f.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview1_Small_Round_1_24f.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview2_Large_Round_0_94f.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview2_Large_Round_1_00f.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview2_Large_Round_1_24f.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview2_Small_Round_0_94f.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview2_Small_Round_1_00f.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview2_Small_Round_1_24f.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview3_Large_Round_0_94f.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview3_Large_Round_1_00f.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview3_Large_Round_1_24f.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview3_Small_Round_0_94f.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview3_Small_Round_1_00f.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview3_Small_Round_1_24f.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview4_Large_Round_0_94f.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview4_Large_Round_1_00f.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview4_Large_Round_1_24f.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview4_Small_Round_0_94f.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview4_Small_Round_1_00f.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview4_Small_Round_1_24f.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview5_Large_Round_0_94f.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview5_Large_Round_1_00f.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview5_Large_Round_1_24f.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview5_Small_Round_0_94f.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview5_Small_Round_1_00f.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview5_Small_Round_1_24f.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview6_Large_Round_0_94f.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview6_Large_Round_1_00f.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview6_Large_Round_1_24f.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview6_Small_Round_0_94f.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview6_Small_Round_1_00f.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/SocialKt.socialPreview6_Small_Round_1_24f.png" width="150" /> |
| `timer1LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer1LayoutPreview_Large_Round_0_94f.png" width="150" /> |
| `timer1LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer1LayoutPreview_Large_Round_1_00f.png" width="150" /> |
| `timer1LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer1LayoutPreview_Large_Round_1_24f.png" width="150" /> |
| `timer1LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer1LayoutPreview_Small_Round_0_94f.png" width="150" /> |
| `timer1LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer1LayoutPreview_Small_Round_1_00f.png" width="150" /> |
| `timer1LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer1LayoutPreview_Small_Round_1_24f.png" width="150" /> |
| `timer2LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer2LayoutPreview_Large_Round_0_94f.png" width="150" /> |
| `timer2LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer2LayoutPreview_Large_Round_1_00f.png" width="150" /> |
| `timer2LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer2LayoutPreview_Large_Round_1_24f.png" width="150" /> |
| `timer2LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer2LayoutPreview_Small_Round_0_94f.png" width="150" /> |
| `timer2LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer2LayoutPreview_Small_Round_1_00f.png" width="150" /> |
| `timer2LayoutPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/timer2LayoutPreview_Small_Round_1_24f.png" width="150" /> |
| `weatherPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/weatherPreview_Large_Round_0_94f.png" width="150" /> |
| `weatherPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/weatherPreview_Large_Round_1_00f.png" width="150" /> |
| `weatherPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/weatherPreview_Large_Round_1_24f.png" width="150" /> |
| `weatherPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/weatherPreview_Small_Round_0_94f.png" width="150" /> |
| `weatherPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/weatherPreview_Small_Round_1_00f.png" width="150" /> |
| `weatherPreview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/weatherPreview_Small_Round_1_24f.png" width="150" /> |
| `workoutLayout1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout1Preview_Large_Round_0_94f.png" width="150" /> |
| `workoutLayout1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout1Preview_Large_Round_1_00f.png" width="150" /> |
| `workoutLayout1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout1Preview_Large_Round_1_24f.png" width="150" /> |
| `workoutLayout1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout1Preview_Small_Round_0_94f.png" width="150" /> |
| `workoutLayout1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout1Preview_Small_Round_1_00f.png" width="150" /> |
| `workoutLayout1Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout1Preview_Small_Round_1_24f.png" width="150" /> |
| `workoutLayout2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout2Preview_Large_Round_0_94f.png" width="150" /> |
| `workoutLayout2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout2Preview_Large_Round_1_00f.png" width="150" /> |
| `workoutLayout2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout2Preview_Large_Round_1_24f.png" width="150" /> |
| `workoutLayout2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout2Preview_Small_Round_0_94f.png" width="150" /> |
| `workoutLayout2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout2Preview_Small_Round_1_00f.png" width="150" /> |
| `workoutLayout2Preview` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/workoutLayout2Preview_Small_Round_1_24f.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview1_Large_Round_0_94f.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview1_Large_Round_1_00f.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview1_Large_Round_1_24f.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview1_Small_Round_0_94f.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview1_Small_Round_1_00f.png" width="150" /> |
| `socialPreview1` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview1_Small_Round_1_24f.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview2_Large_Round_0_94f.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview2_Large_Round_1_00f.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview2_Large_Round_1_24f.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview2_Small_Round_0_94f.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview2_Small_Round_1_00f.png" width="150" /> |
| `socialPreview2` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview2_Small_Round_1_24f.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview3_Large_Round_0_94f.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview3_Large_Round_1_00f.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview3_Large_Round_1_24f.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview3_Small_Round_0_94f.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview3_Small_Round_1_00f.png" width="150" /> |
| `socialPreview3` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview3_Small_Round_1_24f.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview4_Large_Round_0_94f.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview4_Large_Round_1_00f.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview4_Large_Round_1_24f.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview4_Small_Round_0_94f.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview4_Small_Round_1_00f.png" width="150" /> |
| `socialPreview4` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview4_Small_Round_1_24f.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview5_Large_Round_0_94f.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview5_Large_Round_1_00f.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview5_Large_Round_1_24f.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview5_Small_Round_0_94f.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview5_Small_Round_1_00f.png" width="150" /> |
| `socialPreview5` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview5_Small_Round_1_24f.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview6_Large_Round_0_94f.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview6_Large_Round_1_00f.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview6_Large_Round_1_24f.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview6_Small_Round_0_94f.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview6_Small_Round_1_00f.png" width="150" /> |
| `socialPreview6` | <img src="https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/integration/wear-tiles/renders/app/LayoutKt.socialPreview6_Small_Round_1_24f.png" width="150" /> |

