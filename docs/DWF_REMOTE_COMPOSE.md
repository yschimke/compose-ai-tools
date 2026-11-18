# `dwf-remote-compose` — Watch Face Format → Remote Compose, as a library

`ee.schimke.composeai:dwf-remote-compose` compiles Watch Face Format (WFF / Declarative Watch Face)
XML plus its `res/` tree into a Remote Compose `.rc` document. It is the translator that used to live
inside `:rc-render-jvm`, split out so a host can call it directly: the UI builder gets WFF XML and
resources from the editor on every edit and needs `.rc` bytes back for the browser's Remote Compose
player.

It is **the compiler only** — no player, no Skiko window, no daemon protocol. `:rc-render-jvm`
depends on it and keeps `DwfDaemonMain`, `DwfRenderMain`, `RcJvm*` and the typeface/URL image loaders.

Placement: layer 1 ([REPOSITORY_LAYERS.md](design/REPOSITORY_LAYERS.md)) — it opens no socket; the
server and the UI builder consume it as a published coordinate (it is in the BOM like every module
that applies `composeai.maven-publishing`).

## API

```kotlin
val result = WffRemoteCompose.compile(
  xml = editorXml,
  resources = WffResourceSource.of(mapOf("drawable/hand_hour.png" to bytes, "values/colors.xml" to xmlBytes)),
  options = WffCompileOptions(widthPx = 454, heightPx = 454, ambient = false, wffVersion = 2),
)
result.rcBytes        // ByteArray? — null when the document could not be compiled
result.namedValues    // List<NamedValueSpec> — what the document reads, with defaults
result.diagnostics    // List<WffDiagnostic>
```

* `WffResourceSource` — `Empty`, `InMemory(Map<String, ByteArray>)` (res-relative path → bytes, a leading
  `res/` tolerated), or `Directories(List<File>)`. `drawable-fr/…`, `values-fr/…`, `font/…`, `raw/…`
  resolve as they do on Android.
* `WffCompileOptions` — `widthPx`/`heightPx` (default: the `<WatchFace>` size; the scene scales to it, so
  one XML serves every device), `density` (header only: WFF geometry is pixels), `ambient`, `flavor`,
  `configuration`, `wffVersion`, `clipToWatchShape`, `placeholderMissingResources`.
* `WffCompileResult` — `rcBytes`, `namedValues`, `diagnostics`, `widthPx`/`heightPx`,
  `watchFaceWidth`/`watchFaceHeight`, `wffVersion`, `compileMillis`, `isSuccess`.
* `extractNamedValueSpecs(rcBytes, booleanConfigIds)` — the same manifest from `.rc` bytes alone.

Stable surface = package `ee.schimke.composeai.dwf`, pinned by the ABI dump
(`dwf-remote-compose/api/`; `./gradlew :dwf-remote-compose:updateKotlinAbi`). The translator internals
(`ee.schimke.composeai.rccreation.dwf`, `DwfResourceDirectoryResolver`) are public so the daemons and
the Gradle plugin's DWF task can link them, but are **not** pinned.

### Never throws for bad input

A malformed document gives `rcBytes == null` and an `ERROR` diagnostic (`PARSE_ERROR`,
`COMPILE_ERROR`) so a host keeps showing the last good `.rc` mid-edit. A drawable that cannot be found
is a `WARNING` `MISSING_RESOURCE`, and (with `placeholderMissingResources`, the default) is drawn as a
magenta crossed tile so the author sees where it belongs. A name counts as missing only if neither a
bitmap nor a vector drawable resolves.

Problems that are not fatal come back as `WARNING` diagnostics and the face still renders with a
documented fallback: `UNKNOWN_SOURCE` (a `[SOURCE]` the translator does not know evaluates to 0),
`MALFORMED_EXPRESSION` (unbalanced parenthesis, trailing tokens, stray characters),
`UNKNOWN_FUNCTION` and `UNSUPPORTED_ELEMENT` (a malformed v4 `<Reference>`, one without a `name` and a `source` or `defaultValue`).

**`<Reference>` (v4).** `<Reference name="color" source="[CONFIGURATION.theme.0]" defaultValue="#FF888888"/>` inside an element binds that element's attribute `name` to `source`; `defaultValue` is used when `source` names a configuration the face does not declare. Every Reference is also readable as `[REFERENCE.name]` in expressions (an unknown name is `UNKNOWN_SOURCE`).

**Moon phase.** `[MOON_PHASE_POSITION]` (integer 0..27), `[MOON_PHASE_TYPE]` (0 new, 1 waxing crescent, 2 first quarter, 3 waxing gibbous, 4 full, 5 waning gibbous, 6 last quarter, 7 waning crescent) and `[MOON_PHASE_TYPE_STRING]` / `[MOON_PHASE_TYPE_NAME]` derive from the face's date with the mean synodic month (accurate to about a day). `dwf.moon.phase` (float 0..1, default -1 = from the date) drives them in a preview.

`wffVersion` (1–5, default 2) is **advisory today**: the translator accepts the union of versions 1–5
and does not flag features newer than the target; an out-of-range value is `INVALID_OPTION`. It is
echoed back so hosts can thread it already. See *Moving onto `wff-core`* below.

**Ambient.** Ambient is dimmed about 15% (a `0x26000000` scene scrim) to match the real runtime, as
measured in #6586 with `dwf-diff`. On top of that, only what a face authors changes: `<Variant
mode="AMBIENT">`, and the second hand hiding unless it has an ambient variant. A `Group` or `PartDraw` `alpha` below 255 (constant or
variant-driven) composites as one offscreen layer, so overlapping children read uniform. Remote
Compose has no `saveLayer`; this uses `drawToOffscreenBitmap` at document size, and a layer nested
inside a layer falls back to per-draw alpha (the offscreen draw does not nest). The layer is cleared
every frame, and a data-driven alpha draws through it at every value rather than straight through
while opaque: one emission of the content, so no expression is first written inside a branch the
player skips.

## Named values

Seed these into the player's `namedValues` map, keyed by `NamedValueSpec.name`, to drive the preview
without recompiling. Names are the document's `USER:` variables **without** the `USER:` prefix — the
key the players take. Only variables the document actually reads are listed (a face with no
complication slots has no `dwf.complication.*`). Booleans travel as integers `0`/`1`.

**Time is not a named value.** The clock comes from the player's time source; drive time-of-day and
date through that, not through a name here.

| Name | Type | Category | Meaning |
| --- | --- | --- | --- |
| `dwf.is_ambient` | bool | AMBIENT | Always-on display. `<Variant mode="AMBIENT">` switches on it. |
| `dwf.is_24_hour` | bool | CLOCK | 24-hour (true) or 12-hour presentation. |
| `dwf.is_dst` | bool | CLOCK | Daylight Saving Time in effect. |
| `dwf.battery_percent` | float | BATTERY | 0–100. |
| `dwf.battery_charging` | bool | BATTERY | Charging. |
| `dwf.battery_is_low` | bool | BATTERY | ≤ 15 %. |
| `dwf.unread_notifications` | int | NOTIFICATIONS | Unread count. |
| `dwf.step_goal` | float | METRIC | Daily step goal. |
| `metrics.heart_rate` | float | METRIC | BPM; negative = no reading (`[HEART_RATE] != null` is false). |
| `metrics.day.steps` | int | METRIC | Steps today. |
| `metrics.day.calories` | float | METRIC | Active calories today. |
| `metrics.day.distance` | float | METRIC | Meters today. |
| `metrics.day.flights_of_stairs` | float | METRIC | Floors today. |
| `dwf.accel_angle_x`, `dwf.accel_angle_y` | float | SENSOR | Device tilt, degrees. |
| `dwf.weather.is_available` / `.is_day` | bool | WEATHER | Data available / daytime at the location. |
| `dwf.weather.condition` | int | WEATHER | Condition enum 0–15. |
| `dwf.weather.condition_name` | text | WEATHER | Localized condition text. |
| `dwf.weather.temperature`, `.temperature_high`, `.temperature_low` | float | WEATHER | Current / daily high / daily low. |
| `dwf.weather.temperature_unit` | int | WEATHER | 1 Celsius, 0 Fahrenheit. |
| `dwf.weather.chance_of_precipitation` | int | WEATHER | 0–100. |
| `dwf.weather.uv_index` | int | WEATHER | Current UV index. |
| `dwf.weather.last_updated_text` | text | WEATHER | Preformatted for `icuText(…, [WEATHER.LAST_UPDATED])`. |
| `dwf.weather.hours.<1..24>.{is_available,is_day,condition,temperature,uv_index}` | mixed | WEATHER | Hourly forecast, +N hours. |
| `dwf.weather.days.<1..7>.{is_available,condition_day,condition_night,temperature_high,temperature_low,chance_of_precipitation,chance_of_precipitation_night,uv_index}` | mixed | WEATHER | Daily forecast, +N days. |
| `dwf.config.<id>` | int / bool | CONFIGURATION | The selected value of a `<UserConfigurations>` entry as an **integer, not text**: the zero-based ordinal of the chosen `<ListOption>` / `<ColorOption>` in document order (so `dwf.config.theme=2` picks the third option; an option id such as `cyan` is not accepted), and `0`/`1` for a `<BooleanConfiguration>`. Flavors set these. |
| `dwf.color.<id>.<n>` | color | COLOR_THEME | Resolved ARGB of color *n* of `<ColorConfiguration id>`, read wherever the face writes `[CONFIGURATION.<id>.<n>]`. **This is what recolours such a face**: seeding `dwf.config.<id>` alone does not change a colour read through `[CONFIGURATION.<id>.<n>]`, so seed `dwf.color.<id>.<n>` (for each *n* the face reads) as well. The ordinal in `dwf.config.<id>` still drives `<ListConfiguration>` / `<ColorConfiguration>` option switches and a colour that is chosen per option while the alpha is not 1. |
| `dwf.complication.<slot>.type` | int | COMPLICATION | 0 EMPTY, 1 SHORT_TEXT, 2 LONG_TEXT, 3 RANGED_VALUE, 4 MONOCHROMATIC_IMAGE, 5 SMALL_IMAGE, 6 PHOTO_IMAGE, 7 GOAL_PROGRESS, 8 WEIGHTED_ELEMENTS. |
| `dwf.complication.<slot>.text`, `.title` | text | COMPLICATION | `[COMPLICATION.TEXT]` / `.TITLE`. |
| `dwf.complication.<slot>.value`, `.min`, `.max` | float | COMPLICATION | Ranged / goal value and bounds. |
| `dwf.complication.<slot>.has_{monochromatic_image,small_image,photo_image,ranged_value_colors,goal_progress_colors,weighted_elements}` | bool | COMPLICATION | Which payloads the slot supplies. |
| `dwf.complication.<slot>.{ranged_value_colors,goal_progress_colors}_interpolate` | bool | COMPLICATION | Whether the color ramp interpolates. |
| `dwf.complication.<slot>.color_count` | int | COMPLICATION | Active color stops, 1–4. |
| `dwf.complication.<slot>.color.<0..3>` | color | COMPLICATION | Ramp / weighted-stroke color stops. |
| `dwf.complication.<slot>.weight.<0..3>` | float | COMPLICATION | Weighted-element weights. |
| `dwf.complication.<slot>.{image_color,accent_color}` | color | COMPLICATION | Icon tint; badge/backdrop accent. |

Image-valued variables (`…monochromatic_image`, `…small_image`, `…photo_image`, photo configuration)
are URL-backed bitmaps, not seedable scalars, and are not in the manifest. `DwfVariables` is the
catalogue; `WffRemoteComposeTest` fails if a `dwf.*` / `metrics.*` name appears that
`describeNamedValue` does not document.

The names a given face declares (from the Wear OS samples): SimpleAnalog `dwf.is_ambient`,
`dwf.config.themeColor`; Flavors adds `metrics.heart_rate`, `dwf.complication.0.*`; Complications declares
slots 0–4; Weather declares the `dwf.weather.*` family and `dwf.is_24_hour`.

### Rendering with overrides from the command line

`DwfRenderMain` renders one face with named-value overrides, with no Kotlin test needed:

```
java -cp <rc-render-jvm classpath> ee.schimke.composeai.rcjvm.DwfRenderMainKt \
  --watchface res/raw/watchface.xml --resource-dir res --png out.png \
  --set dwf.is_ambient=true --set metrics.heart_rate=120 \
  --set dwf.complication.1.text=Hi --set dwf.config.theme=2 \
  --time 15:08:00 --date 2026-11-18
```

`--set` takes any name from the manifest above; the value is typed by the face's own declaration
(`true`/`false`/`1`/`0` for a bool, a number for an int or float, `#AARRGGBB` for a colour, anything
for text). A name the face does not declare is an error that lists the ones it does. `--time` and
`--date` set the clock (default `10:10:30` on 2026-11-18). `--fakes-json` (or a `<face>.fakes.json`
beside the XML) seeds defaults. Translator warnings (unknown data source, malformed expression,
unsupported `<Reference>`) are printed to stderr as `warning CODE: message`; the same entries are
the `UNKNOWN_SOURCE`, `MALFORMED_EXPRESSION`, `UNKNOWN_FUNCTION` and `UNSUPPORTED_ELEMENT`
diagnostics of `WffRemoteCompose.compile`.

## Latency

`WffRemoteComposeTest › compile latency` prints it (reported, not gated; goal well under 100 ms warm).
SimpleAnalog with in-memory resources, after JIT warm-up, in a cloud sandbox: **median
6 ms, p90 ~11 ms, max ~19 ms**.

## Moving onto `wff-core` (not done here)

`ee.schimke.watchface:wff-core` (yschimke/watchface-tools) is intended to own the WFF model. The seams:

* **`DwfParser` + `DwfModel`** are the only things that read XML. A later change swaps `DwfParser.parse`
  for a `wff-core` parse, with a mapping to `DwfDocument` (or `DwfDocument` replaced by the core model).
  `WffRemoteCompose.compile` is the one caller and its signature does not change.
* **`wffVersion`** becomes enforced: `wff-core`'s per-version feature table turns the advisory
  `WFF_VERSION` info diagnostic into per-feature warnings/errors.
* The translator back end (`DwfExpressionCompiler`, `DwfCanvasRenderer`) keeps consuming the model;
  `XmlMiniDom` would go.
