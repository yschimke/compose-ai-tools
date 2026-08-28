# App previews & tours — real activities, intents, scripted navigation

`@Preview` renders show isolated composables. App previews and tours put the *app itself* in front
of agents and reviewers: the real activities the manifest declares, launched for real (full
lifecycle, their own `setContent`, their theme), navigated between via real intents, and captured
as PNGs by the same pipeline as every other preview.

Two synthetic preview kinds implement this (same discovery pattern as `LOTTIE` / `CATALOG` — no
`@Preview`, no consumer composable):

- **`kind=ACTIVITY`** — one preview per enabled `<activity>` in the module's merged
  `AndroidManifest.xml`. The launcher activity's capture is the app's **hero image**
  (`renders/activity__MainActivity.png`); every other activity renders best-effort (`optional`
  captures — real screens often need intent extras discovery can't guess, so a missing PNG plus an
  `.error.json` sidecar never fails the render gate).
- **`kind=APP_TOUR`** — one preview per committed tour script in `compose-previews/tours/` (module
  root). A tour launches a start activity and executes scripted steps — click a target, fire an
  intent (e.g. a deep link), press back — capturing one PNG per step:
  `renders/apptour__<name>_step00_launch.png`, `_step01_<label>.png`, …

Alongside the synthetic previews, `previews.json` gains a top-level `activities` array — the app's
real entry points with their intent-filters (launcher, deep-link schemes/hosts, exported) — so an
agent can see **which screens exist and the ways to launch between them** without parsing the
manifest, and can author tour specs from it.

## Tour spec format

`<module>/compose-previews/tours/<name>.json`:

```json
{
  "name": "getting-started",
  "description": "Home → Now Playing, back, then deep-link.",
  "start": { "activityClassName": "com.example.MainActivity" },
  "steps": [
    { "label": "open now playing", "click": { "text": "Open Now Playing" } },
    { "label": "back home", "back": true },
    {
      "label": "deep link",
      "intent": { "action": "android.intent.action.VIEW", "data": "sample://nowplaying" }
    }
  ]
}
```

- `start` is optional — it defaults to the manifest's launcher activity. An explicit start can name
  an `activityClassName`, an implicit `action`/`data`/`categories`, and string `extras`.
- Step 0 (`launch`) is synthesized: every tour captures its start state before the first authored
  step.
- Each authored step performs at most one action, settles, then captures the currently-resumed
  activity:
  - `click` — `text` / `contentDescription` / `tag` match through the Compose semantics tree and
    fire the node's real `OnClick` action; `viewId` / `text` fall back to classic-View traversal +
    `performClick`.
  - `intent` — constructed and resolved through the real, manifest-backed `PackageManager`, so a
    deep link only works where an intent-filter actually matches.
  - `back: true` — destroys the top activity and resumes the previous one.

## How rendering works

Discovery (`AppTourDiscovery`, driven from `PreviewDiscovery` when the build supplies a merged
manifest) emits the synthetic previews; the Android renderer's `AppTourRenderer` executes them —
dispatched at the top of `RobolectricRenderTestBase.renderPreview`, *before* the
`PreviewRenderStrategy` machinery, because there is no composition to host: the activity owns its
content.

Inside the Robolectric sandbox, a tour session:

1. wraps the run in `createEmptyComposeRule()` — registering Compose roots from *any* activity
   launched inside it (the documented multi-activity pattern) and giving the same paused-
   `mainClock` determinism as the composable render path;
2. launches activities through Robolectric's `ActivityController` (`buildActivity(...).setup()`);
3. follows navigation: Robolectric records `startActivity` calls instead of launching them, so
   after each step the session drains `ShadowApplication.getNextStartedActivity()` and launches
   each recorded intent itself (pausing/stopping the previous activity) — including chained
   redirects, capped defensively;
4. emulates the back stack with controller lifecycle transitions (`back` = destroy top,
   restart/resume previous);
5. captures the resumed activity's `window.decorView` through `captureRoboImage` — the same
   hardware-capture path (`robolectric.pixelCopyRenderMode=hardware`) as every other preview.

## The Application an activity renders against

App-level previews run from **their own test class in their own package** —
`ee.schimke.composeai.apptour.AppTourRobolectricRenderTest`, alongside
`ee.schimke.composeai.renderer.RobolectricRenderTest` — and the split exists for exactly one
reason: Robolectric resolves the Application per test *class* (from the `robolectric.properties`
files merged down that class's package hierarchy), never per test method.

The composable lane pins `application=android.app.Application` so an isolated composable never runs
the consumer's `Application.onCreate()` — DI graphs, `BridgingManager`, Firebase and WorkManager
routinely fail inside the sandbox, and a composable has no business needing them. That is the wrong
default here, because **an Activity *is* the app**. Launched against the stub, a Hilt activity fails
on contact:

```
java.lang.IllegalStateException: Hilt Activity must be attached to an @HiltAndroidApp Application.
Did you forget to specify your Application's class name in your manifest's <application />'s
android:name attribute?
```

so does a Koin one (`KoinApplication has not been started`), and so does one whose
`AppComponentFactory` constructs it through DI (`Couldn't call constructor`). Before the lanes were
split that was 39 of 45 activity-tour renders across the catalog fleet — no app using app-level DI
could tour at all.

So the app-tour lane's generated properties file omits `application=` entirely and lets Robolectric
fall back to the manifest-declared class. The consumer's `Application.onCreate()` failing under
Robolectric is now the failure mode, and it is **contained**: it fails this class, not the module's
composable previews.

Set `composePreview.appTourUseConsumerApplication = false` to put activities back on the stub —
worth reaching for when the app's `onCreate()` cannot survive the sandbox *and* its activities
render without one. `composePreview.useConsumerApplication = true` still moves **both** lanes onto
the manifest Application.

This lane is never sharded: a module has at most a handful of app-level previews, and the cost that
matters is the Application init the class pays once per sandbox — which sharding would multiply
rather than divide.

## Limitations

- **Standalone Gradle path only.** The daemon (`PreviewIndex` parses `kind` as a string, so
  manifests with app previews load fine) does not yet render these kinds interactively; a daemon
  `RenderEngine` branch + VS Code/MCP affordances (launch-with-intent options on the card) are the
  natural follow-up.
- Compose clicks fire the semantics `OnClick` action directly (deterministic under the paused
  clock) rather than synthesizing motion events — press ripples/tooltips tied to real pointer input
  won't appear mid-tour.
- Fragments/`NavHost` navigation *within* one activity works out of the box (it's just composition
  state); only `startActivity`-based navigation needs the intent-following machinery above.

Sample: `samples/android` — `MainActivity` (launcher, hero) → `NowPlayingActivity` (button click +
`sample://nowplaying` deep link), toured by `compose-previews/tours/getting-started.json`.
