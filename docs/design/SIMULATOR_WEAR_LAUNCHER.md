# Handover: a Wear OS system UI for the simulator

**Status:** built, except as noted below; [`docs/SIMULATOR.md` → Wear system UI](../SIMULATOR.md#wear-system-ui)
documents what exists. Still open: complications on the demo face, `TileUpdateRequester` pushes,
widget containers other than the large one, the paired phone's bridged notifications and launcher
entries, and verification on Home Assistant Wear and MeshCore Wear (their APKs were not in the
sandbox; a probe app outside the repo covered every surface). The brief as written follows.

**Goal:** `compose-preview launch` gets the Wear OS surfaces outside the app:
- the notifications stream;
- ongoing activities;
- the Tiles and Wear Widgets carousel;
- an app launcher;
- a demo watch face.

The point is to test a Wear app's system-facing surfaces through the control channel, as an agent would on an emulator. The watch face is the least important: it is a backdrop for the other surfaces, not a watch-face renderer.

Read first:
- [`AGENTS.md`](../../AGENTS.md): the CI-enforced rules (commits, branch names, formatter, PR evidence).
- [`docs/SIMULATOR.md`](../SIMULATOR.md), in particular Form factors → Wear, the command table, and "What does not, or not yet".

## What exists today

The pieces you build on:

| Piece | Where | Use it for |
| --- | --- | --- |
| Round watch display, `round` / `watch` qualifiers, round screenshots, `rotary` | `FormFactor`, `DeviceDisplay`, `Session.rotary` (#6271) | Every surface here draws on that display |
| Per-display back stacks, activity starts, services, binds, broadcasts | `Navigator` | Starting the launcher, binding tile and widget services, firing `PendingIntent`s |
| The app's own `AppComponentFactory` | `AppComponents.factory(app)` | Instantiating the app's services and receivers as the app would |
| Binds by action, including manifest services resolved before `onCreate` | `Navigator.registerBindableServices`, `SimulatorShadowContextImpl` | Binding `TileService` / widget providers by their intent action |
| The notification list | `Notifications` (reads `ShadowNotificationManager`) | The data model for the stream |
| Per-display pictures, input routing, window ordering | `Displays`, `SimulatorShadowUiAutomation` (#6274) | Getting a system-UI window drawn and tapped without the app's windows covering it |
| One-line command dispatch | `DeviceDisplay.command` / `DeviceState.command` pattern in `Session.run` | Keeping `Session` edits to a line or two |
| A paired phone over the Data Layer | `--pair`, `DataLayerHub` (#6269) | Bridged phone notifications, later |
| Tile and Wear Widget **previews** | `samples/wear` (`TileImagePreviews.kt`), `samples/wear-widget`, `runtimes/wear-preview` (`CapturingWearWidgetPreview`, `WearWidgetPreviewPlayer`, `EmbeddedWearWidgetPlayer`) | How a tile layout and a Wear Widget's Remote Compose document are already turned into pixels in this repo |

## The load-bearing constraint: no Compose in the system UI

The app's Compose comes from the converted APK on the sandbox classpath. A second Compose on that classpath, for a launcher written in Compose, would clash: one class loader, two versions of `androidx.compose.*`. Robolectric loads `:simulator-host` into the same sandbox.

So everything the simulator draws itself is **plain `android.view` / `Canvas`**:
- the launcher;
- the notification cards;
- the carousel chrome;
- the watch face.

The app's own content (a tile layout, a widget document, a notification's `RemoteViews`) is drawn by renderers. Whose copy of each renderer to load is the design question for each surface, covered below.

## Surfaces, in priority order

### 1. Home and navigation model

Add a `--launcher` flag (`--home`?), off by default so today's behaviour is unchanged. With it, a `WearHome` system activity sits at the bottom of the default display's stack:
- it is a class in `:simulator-host`;
- `Robolectric.buildActivity` takes any class, so it needs no manifest entry;
- the app is launched over it.

On a watch, navigation works like this, and each move needs a control command and a gesture:

| Gesture on a watch | Goes to | Command |
| --- | --- | --- |
| Back swipe or `KEYCODE_BACK` from the app's root | the watch face | `home` / `key BACK` |
| `KEYCODE_STEM_PRIMARY` (side button) | the app launcher list | `key STEM_PRIMARY` / `launcher` |
| Swipe up on the watch face | the notifications stream | `notifications open` |
| Swipe left / right on the watch face | the tiles and widgets carousel | `tiles next` / `tiles prev` / `tiles show {index\|component}` |
| Swipe down on the watch face | quick settings (stub: battery, airplane, Wi-Fi, from `DeviceState` / `Device`) | `quickSettings` |

Leaving the app runs `onPause` / `onStop`, and returning runs `onRestart`, via `Navigator`'s controllers. `activities` / `info` report which surface is showing (`"surface": "watchface" | "launcher" | "notifications" | "tiles" | "app"`).

`hierarchy` / `find` / `tap` must see system-UI views. Give them stable resource ids such as `sim:id/notification_card` and carry the item's identity, e.g. notification key or tile component, in content descriptions. That way an agent can `tap {resourceId: "sim:id/notification_action", index: 0}`.

### 2. Notifications stream (highest value)

**Model.** The model is `ShadowNotificationManager`'s active notifications, plus posts and cancels as they happen. Hook `notify` / `cancel`, or poll in `Session.cycle`, as `Logcat.poll` does. Stream them as `notification` events: `posted` / `updated` / `removed`, with the key, channel, category, and `ongoing`.

**Rendering.** Wear system UI does not inflate `RemoteViews`; it draws a card from `Notification` extras. Do the same in Views:
- small icon, app name, `EXTRA_TITLE`, `EXTRA_TEXT`, `EXTRA_BIG_TEXT`;
- `MessagingStyle` messages;
- large icon / `BigPicture`;
- the actions, including `WearableExtender` actions, which replace the phone actions when present;
- `setContentIntent`.

Long text expands on tap, as on a watch. Group summaries collapse their children.

**Commands:**
- `notifications` (exists; extend it with keys and the extras above);
- `notifications open` / `close`;
- `notification tap {key}`: sends the content `PendingIntent`;
- `notification action {key, index}`;
- `notification reply {key, index, text}`: fills the action's `RemoteInput` results with `RemoteInput.addResultsToIntent`, then sends;
- `notification dismiss {key}`: sends `deleteIntent` and cancels unless the notification is ongoing;
- `notification snooze` if cheap.

Sending a `PendingIntent` has to reach the app:
- for an activity, through `Navigator`, so it lands on the stack;
- for a broadcast, through `Navigator.broadcast`, so receivers run;
- for a service, through `startService`.

Check what `ShadowPendingIntent.send` already does before building your own.

**Also cover:**
- channels and importance (an `IMPORTANCE_NONE` / blocked channel does not show);
- `POST_NOTIFICATIONS` denied (the `permissions` command, #6265), which shows nothing and is reported;
- heads-up for high-importance posts: a peek card on the watch face for a few seconds;
- `setTimeoutAfter`.

**Verify on:**
- Home Assistant Wear (`/home/user/apks/homeassistant-wear-debug.apk`);
- MeshCore Wear;
- a probe app of our own that posts every style, including an inline reply and a `WearableExtender` action. Keep the probe outside the repo, as earlier probes were.

### 3. Ongoing activities

`androidx.wear.ongoing.OngoingActivity` is library code inside the app. It writes its status, icons and touch intent into the notification it decorates (extras plus `LocusId`). Read it back with the library's own `OngoingActivity.recoverOngoingActivity(context, notification)`, called reflectively through the app's class loader so the version matches.

Then render it:
- as the chip at the bottom of the watch face;
- as a row at the top of the launcher list.

Each shows the static icon, the `Status` text and timers (evaluate `Status.getText(context, now)` against the simulator clock), and the touch intent on tap.

Commands: `ongoing` lists them, and `ongoing tap {index}` taps one.

Verify with a foreground-service probe, a workout-style timer. Check whether any downloaded Wear app uses it (`grep -r OngoingActivity` over the converted classes jar).

### 4. Tiles and Wear Widgets carousel

**Discovering tiles.** List the app's tiles from its manifest:
- the services with action `androidx.wear.tiles.action.BIND_TILE_PROVIDER`;
- `com.google.android.wearable.tile.PREVIEW` metadata for the picker image.

**Discovering widgets.** List the app's Wear Widgets from:
- the service declarations `androidx.glance.wear` generates;
- their `res/xml/*widget_provider*.xml` metadata, with `label`, `description`, `container`, `preferredType` and `previewImage`. Home Assistant Wear ships `wear_slot_widget_provider_small` / `_large`, and MeshCore Wear ships `wear_status_widget_provider`.

Read `samples/wear-widget` (`ActivitySummaryWidget.kt`, `RemoteImageWidget.kt`) and `runtimes/wear-preview` for the exact `GlanceWearWidget` / `WearWidgetDocument` API this repo already drives.

The carousel holds the tiles and widgets the agent added:
- `tiles add {component}` / `tiles remove` / `tiles list`, including available-but-not-added;
- `launch --tile <component>` starts with one added.

**Tiles.** Bind the `TileService` by action through `Navigator` and speak its AIDL, which comes from the app's own `tiles` library:
- `onTileRequest`, `onTileResourcesRequest`;
- `onTileAddEvent`, `onTileEnterEvent` / `onTileLeaveEvent`, `onTileRemoveEvent`;
- the `TileUpdateRequester.requestUpdate` path, so a requested update refreshes the tile.

Render the returned `TileData` with the **protolayout renderer**: `androidx.wear.protolayout:protolayout-renderer` / `androidx.wear.tiles:tiles-renderer`. Apps do not ship it, so the simulator must supply it, and that is this surface's design risk. The renderer and the app share `protolayout-proto` classes in the one class loader, and the app's version wins.

Options, cheapest first:
1. **Put a pinned renderer on the simulator classpath.** It works when the app's protolayout is close enough. Detect the app's protolayout version from the jar and report a mismatch.
2. **Load the renderer and its own protolayout in a child-first class loader inside the sandbox.** Hand it the tile as serialized proto bytes (`TileData` carries bytes), so no app class crosses the boundary.

The preview renderer already draws tiles with `tiles-renderer` (see `samples/wear` and `docs/RENDERER_COMPATIBILITY.md` in compose-preview-daemon). Reuse its version choice.

Clickables (`LoadAction`, `LaunchAction`) must work:
- a `LoadAction` sends `onTileRequest` with the clicked id and state;
- a `LaunchAction` starts the activity via `Navigator`.

**Widgets.** A Wear Widget is a Remote Compose document. The preview path plays it with the vendored Compose `RcPlayer` (`:third-party-rc-embedded-player`), which is Compose and so has the same class-loader problem. Prefer the View-based androidx `RemoteComposePlayer`, if the app's classpath carries `androidx.compose.remote` player classes, or a child-first loader as for tiles.

Wire the widget's actions back to the app:
- its host actions;
- its named values (`ConnectorNamedValues` shows the shape).

**Commands:**
- `tiles` (list / add / remove / show / next / prev);
- `tile tap {x, y}` or a selector inside the tile;
- `tile refresh`.

A `screenshot` while a tile is showing is the tile on the round display.

**Verify on:**
- Home Assistant Wear and MeshCore Wear (widgets);
- `samples/wear` once it declares a `TileService`. Add one to the sample if it has none; it is ours.

`wear-os-samples` `WearTilesKotlin` is a good third-party case, but it needs the user's approval to clone and build.

### 5. App launcher

The launcher is a scrolling list of launchable activities: `ACTION_MAIN` + `CATEGORY_LAUNCHER` from the app's manifest, and from the paired APK's manifest too when `--pair` is on. It uses the round list style:
- the label and icon come from `PackageManager` (`loadLabel` / `loadIcon` work in the sandbox);
- `rotary` scrolls it;
- tapping starts the activity through `Navigator`.

### 6. Demo watch face (lowest priority)

A fixed analog or digital face drawn with `Canvas`, which shows:
- the simulator clock (`SystemClock` / Robolectric's scheduler, so `wait` advances it);
- the ongoing-activity chip;
- a notification indicator;
- ambient dimming when the `ambient` command is sent.

Real watch faces (Watch Face Format, `WatchFaceService`) are out of scope, and so are complications. A later step could bind the app's `ComplicationDataSourceService` and show its data in one slot on the demo face. Note that as a follow-up, not part of this brief.

## Shared plumbing

- **New classes, small `Session` edits.** Add `WearSystemUi` (home, surfaces, gesture routing), `NotificationStream`, `OngoingActivities`, `TileHost` and `WidgetHost`. Each owns its commands through a `command(name, c)` method, wired by one dispatch line in `Session`, as #6270 did.
- **Gestures.** Swipes from the window edges, and `key STEM_PRIMARY`, need routing to the system UI instead of the app when a system surface is showing, or when they start at an edge on the watch face.
- **Overlay.** `AgentOverlay` draws taps on system surfaces too.
- **Docs.** In `docs/SIMULATOR.md`, add a "Wear system UI" section and the new rows in the command table. Remove the matching items from the Wear limits bullet.
- **MCP.** Add the new commands to compose-preview-mcp's `simulator_command` enum and schema, as a separate PR; compose-preview-mcp#119 is the pattern.

## Evidence the PR needs

Evidence goes under `docs/design/evidence/simulator-launch/`, embedded per `AGENTS.md`. It should show, on real apps:
- the stream with Home Assistant's or MeshCore's notifications;
- an inline reply reaching the app;
- the carousel showing a widget from each Wear app;
- a tile, from a sample or probe;
- an ongoing-activity chip;
- the launcher list.

Each image needs before and after, or the step that changed it.

## Practicalities learned so far

- **Java 21.** The simulator needs it: `COMPOSE_PREVIEW_SIMULATOR_JAVA=/usr/lib/jvm/java-21-openjdk-amd64` on the shared sandbox.
- **The shared lock.** Wrap every run in `flock /tmp/claude-simulator.lock`, because other agents share the machine. Never let a child that outlives the run inherit the lock's file descriptor. `xvfb-run` under `flock` left an orphaned Xvfb holding it for an hour; close fd 3 (`exec 3>&-`) before starting long-lived children.
- **Stdin.** Keep stdin open until the `"event":"ready"` line. Closing it shuts the simulator down.
- **APK conversion is cached by the simulator-host jar.** Any `:simulator-host` change re-converts each APK on its next run, which takes minutes.
- **No third-party builds without asking.** Cloning and building third-party repositories needs the user's explicit approval, per repository.
