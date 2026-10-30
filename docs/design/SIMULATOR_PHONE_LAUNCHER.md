# Handover: a phone launcher with app widgets for the simulator

**Status:** surfaces 1–4 built (`launch --launcher`, `--widget`); the shade (5) is not. What
runs, the commands and the limits are in [`docs/SIMULATOR.md` → Home screen and
widgets](../SIMULATOR.md#home-screen-and-widgets), the evidence in
[`evidence/simulator-launch/`](evidence/simulator-launch/README.md#home-screen-and-widgets).
Still open from this brief: the evidence from Muzei, Home Assistant and MeshCore (no APK and no
route to one from the sandbox that built it; `samples/android` carries a widget of each kind
instead), the MCP schema (compose-preview-mcp, a separate PR), and the shade. Two departures: a
widget is named `widgetId` and a shortcut `shortcutId` in commands, since `id` is the command's
own; and a pin request brings the home screen forward rather than drawing its sheet over the app.

This was the brief for the agent that built it.

**Goal:** `compose-preview launch` gets a home screen. The app's icon sits on it, the app can be left and returned to, and **the app's own widgets** are placed, sized, updated and tapped there as a device launcher would do it. That makes widgets something an agent can test end to end: a classic `RemoteViews` `AppWidgetProvider`, a collection widget, and a Glance widget.

Read first:
- [`AGENTS.md`](../../AGENTS.md): the CI-enforced rules (commits, branch names, formatter, PR evidence).
- [`docs/SIMULATOR.md`](../SIMULATOR.md): the architecture, the command table, and "What does not, or not yet".
- The sibling brief [`SIMULATOR_WEAR_LAUNCHER.md`](SIMULATOR_WEAR_LAUNCHER.md). It shares the home and navigation model, the no-Compose constraint and the practicalities; read its "load-bearing constraint" and "Practicalities" sections.

## What exists today

| Piece | Where | Use it for |
| --- | --- | --- |
| Back stacks per display, activity starts, broadcasts to manifest and runtime receivers, services, binds | `Navigator` | Sending `APPWIDGET_*` broadcasts, starting configure activities, binding `RemoteViewsService` |
| The app's own `AppComponentFactory` | `AppComponents.factory(app)` | Instantiating the app's `AppWidgetProvider` as the app would |
| Display presets, resize, rotation, folds | `DeviceDisplay` (#6270) | The grid follows the display. A resize or fold re-flows the home screen and sends `OPTIONS_CHANGED` to widgets whose size changed |
| Per-display pictures and input routing | `Displays`, `SimulatorShadowUiAutomation` (#6274) | Drawing the home window under the app's windows and routing taps to it |
| `RemoteViews` inflation and widget sizing in **previews** | `runtimes/appwidget` (`AppWidgetContent`, the `LauncherWidgetMetadata` / `LauncherWidgetSize` / `LauncherResizeAxes` protocol types), `runtimes/glance` (`GlanceAppWidgetContent`), `samples/android` (`AppWidgetPreviews.kt`, `WeatherAppWidgetReceiver.kt`) | How this repo already turns provider metadata into cell sizes and a `RemoteViews` into pixels. Reuse its size math rather than inventing a second one |
| Notifications list | `Notifications` | A pull-down shade, later. See the Wear brief's stream for the model |

As in the Wear brief, everything the simulator draws itself (the grid, icons, drawer, picker, resize frame) is **plain `android.view`**: no Compose in `:simulator-host`. A widget's `RemoteViews` is inflated by the platform's own `RemoteViews.apply`, which is in the Android jar, so widgets need no extra renderer. That is why phone widgets are the cheaper half of this pair.

## Surfaces, in priority order

### 1. Home and navigation

**Flag.** Add a `--launcher` flag, the same flag as the Wear brief, off by default. With it, a `PhoneHome` system activity sits at the bottom of the default display's stack. It is a class in `:simulator-host`, built with `Robolectric.buildActivity`, and needs no manifest entry.

**Navigation:**
- `home` / `key HOME` stops the app and shows home.
- Tapping the app's icon starts its launcher activity, or brings its task forward with `onRestart` / `onResume` if it is running.
- Back from the app's root returns home.
- `recents` and switching tasks are optional; there is only ever the one app (two with `--pair`).

**The grid:**
- A grid of cells sized from the display, 4×5 or 5×5 at phone widths, more on `tablet` / `desktop` presets.
- The app's launchable activities appear as icons, using the label and icon from `PackageManager`; `<activity-alias>` launchers count.
- An app drawer lists them all.

**App shortcuts.** A long press on an icon shows the app shortcuts:
- static ones from `android.app.shortcuts` metadata;
- dynamic and pinned ones from `ShortcutManager`. Check what `ShadowShortcutManager` keeps.

Tapping a shortcut starts its intent. `requestPinShortcut` adds an icon to home and reports an event.

**Hierarchy and info.** `hierarchy`, `find` and `tap` see home views with stable ids (`sim:id/icon`, `sim:id/widget`, `sim:id/widget_resize`, …). `info` reports `"surface": "home" | "drawer" | "app"`.

### 2. App widgets: placement and lifecycle (the core)

**Discovery.** Find the app's widget providers: manifest `<receiver>`s with an `android.appwidget.action.APPWIDGET_UPDATE` filter and `android.appwidget.provider` metadata. Parse each `<appwidget-provider>` XML through the app's resources into an `AppWidgetProviderInfo`, covering:
- `minWidth` / `minHeight`, `targetCellWidth` / `targetCellHeight`;
- `minResizeWidth` / `minResizeHeight` and `maxResizeWidth` / `maxResizeHeight`;
- `resizeMode`, `widgetCategory` and `widgetFeatures`;
- `configure`, `previewLayout` / `previewImage`, `description`, `initialLayout`, `updatePeriodMillis`.

Downloaded APKs that have providers:
- Muzei (`appwidget_info`);
- Home Assistant (`terrazzo_widget_info`);
- MeshCore (`widget_battery_info`);
- Cadence (`podcast_sync_widget`).

**Hosting.** Place widgets the way a launcher does, with Robolectric's `ShadowAppWidgetManager` as the system side.

1. **Bind.** `AppWidgetHost.allocateAppWidgetId`, then `bindAppWidgetIdIfAllowed`. Robolectric's shadow binds without the permission dialog. Report the bind as an event anyway.
2. **Configure.** If the provider has `configure`, start that activity with `EXTRA_APPWIDGET_ID` for a result. `RESULT_CANCELED` removes the widget, as on a device. The `WIDGET_FEATURE_CONFIGURATION_OPTIONAL` / `RECONFIGURABLE` features change this.
3. **Send the broadcasts.** `APPWIDGET_ENABLED` (first instance), `APPWIDGET_UPDATE`, `APPWIDGET_OPTIONS_CHANGED` with the size bundle, `APPWIDGET_DELETED` / `APPWIDGET_DISABLED` on removal. Send them through `Navigator.broadcast` to the app's receiver. Glance's `GlanceAppWidgetReceiver` is an `AppWidgetProvider`, so it takes the same path.
4. **Draw.** Take what the app passes to `AppWidgetManager.updateAppWidget` / `partiallyUpdateAppWidget`, either through the shadow's record or a seam on the manager. Inflate it into an `AppWidgetHostView` in the home grid. Redraw on every update; that is the point of testing widgets.
5. **Size options.** The size bundle must match what the launcher shows: `OPTION_APPWIDGET_MIN_WIDTH` / `MAX_WIDTH` / `MIN_HEIGHT` / `MAX_HEIGHT` and, on API 31+, `OPTION_APPWIDGET_SIZES`. Responsive `RemoteViews(Map<SizeF, RemoteViews>)` then picks the right layout. Take the cell-to-dp math from `runtimes/appwidget` so previews and the simulator agree.

**Commands:**
- `widgets`: providers, as available, plus placed widgets with id, provider, cell span, size in dp, last update time and the update count.
- `widget add {provider, cellX?, cellY?, spanX?, spanY?}`: runs configure if needed.
- `widget remove {id}`.
- `widget resize {id, spanX, spanY}`: within the provider's limits. Re-sends options, and the widget relayouts.
- `widget update {id?}`: sends `APPWIDGET_UPDATE`, as an `updatePeriodMillis` tick or a launcher refresh would.
- `launch --widget <provider>`: starts with one placed.

`requestPinAppWidget` from the app shows a confirm sheet that the agent accepts or rejects with `widget pin accept|reject`. It then delivers the success callback `PendingIntent`.

### 3. Widget interaction

**Clicks.** `setOnClickPendingIntent` / `setOnCheckedChangeResponse` (compound buttons, API 31+) and fill-in intents must fire when the view is tapped:
- an activity intent goes onto the stack via `Navigator`;
- a broadcast goes to receivers;
- a service intent starts the service.

Glance's actions are this underneath: an `ActionCallback` runs through Glance's own broadcast receiver and `WorkManager`. The work runs because #6254 kept WorkManager initialised (processName). Confirm a Glance `actionRunCallback` round-trips.

**Collections.** For `setRemoteAdapter` with a `RemoteViewsService`, bind the service through `Navigator`, drive its `RemoteViewsFactory` (`onCreate`, `onDataSetChanged`, `getCount`, `getViewAt`), and populate the list. `notifyAppWidgetViewDataChanged` refreshes it. Newer apps use `RemoteCollectionItems` with no service; that just inflates.

**Updates from the app.** Any `updateAppWidget` call redraws the widget and emits a `widget` event (`{id, provider, layout, reason}`). Such calls come from a worker, a broadcast, or the app's own UI (Glance `update()`). An agent can then check "tapping X in the app updates the widget".

### 4. Widget picker and previews

The picker lists the providers, showing each provider's:
- `description`;
- preview: `previewLayout` inflated, else `previewImage`, else, on API 35+, generated previews set by `AppWidgetManager.setWidgetPreview`.

It places a widget on a long press, as a launcher does. This is the visual half of `widget add`, and it lets an agent screenshot the picker for evidence.

### 5. Shade (optional, shared with the Wear brief)

A pull-down notification shade that inflates each notification's `contentView` / `bigContentView` `RemoteViews`, as the preview renderer in `runtimes/notification` does. It supports tap, action, inline reply and dismiss, with the same commands as the Wear stream. Build it only if the Wear stream's model lands first and can be shared.

## Shared plumbing

- **New classes, small `Session` edits.** Add `PhoneHome`, `WidgetHost` (bind, broadcasts, `RemoteViews` updates, sizes), `WidgetPicker` and `Shortcuts`. Each owns its commands through a `command(name, c)` method, wired by one dispatch line in `Session`.
- **Multiple displays.** Home belongs to the default display only. A launch with `--display` gives the secondary displays no launcher.
- **Docs.** In `docs/SIMULATOR.md`, add a "Home screen and widgets" section and the command rows, and update the limits.
- **MCP.** Add the new commands to compose-preview-mcp's `simulator_command` enum and schema, as a separate PR; compose-preview-mcp#119 is the pattern.

## Evidence the PR needs

Evidence goes under `docs/design/evidence/simulator-launch/`, embedded per `AGENTS.md`, from real apps. Each image needs before and after, or the step that changed it.
- Home with the app's icon, and the app reopened from it.
- Muzei's widget placed, before and after an update, and after a resize.
- Home Assistant's or MeshCore's widget after configure, and after a tap inside it that reaches the app.
- A collection widget scrolled.
- A Glance widget's `actionRunCallback` updating it. `samples/android`'s widget receiver works if no downloaded app uses Glance.
- The picker.

## Practicalities

See the Wear brief's "Practicalities learned so far": Java 21, the shared `flock` lock and fd inheritance, stdin until ready, re-conversion after `:simulator-host` changes, and asking before building third-party apps.

Specific to widgets:
- Muzei's widget renders the current artwork, which comes from a network source. Offline or blocked fetches leave it on its placeholder. That is still a valid placement test; say so in the evidence.
- Glance widgets compose inside the app's process with the app's own Compose and Glance. They need no host renderer, only the `RemoteViews` they emit.
