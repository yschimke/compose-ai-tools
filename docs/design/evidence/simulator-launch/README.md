# `compose-preview launch` — an APK in Robolectric's simulator

Evidence for [`docs/SIMULATOR.md`](../../../SIMULATOR.md): `samples/android`'s debug APK
(`:samples:android:assembleDebug`), converted from dex by `ApkClassConverter` and run in
Robolectric 4.17's simulator on JDK 25, in this repository's cloud sandbox.

| The window (Xvfb) | After `tap` → the app's `startActivity` | After `qualifiers` `+land` |
| --- | --- | --- |
| ![window](window.png) | ![now playing](now-playing.png) | ![landscape](landscape.png) |

- **window** — `compose-preview launch app.apk` under `xvfb-run`, captured with `import -window
  root` after 25 s. The Swing window draws the 540×960 display (the `w360dp-h640dp-hdpi`
  default) one pixel to one pixel; there is no window manager, hence no frame.
- **now playing** — `--control stdio`: `{"cmd":"tap","text":"Open Now Playing"}` taps the Compose
  button found through the semantics hierarchy; the app's own `startActivity` is drained by
  `Navigator` and `NowPlayingActivity` launched onto the back stack; then `screenshot`.
- **landscape** — `{"cmd":"qualifiers","value":"+land"}` recreates `MainActivity` at 960×540.

## Watching the agent

With `--control stdio` and a display, the window stays up and draws what the agent sends —
captured from the Xvfb screen (not the agent's screenshots, which never contain the overlay):

| `tap "Open Now Playing"` (ring at the tap, on the screen it opened) | `swipe` |
| --- | --- |
| ![agent tap](agent-tap.png) | ![agent swipe](agent-swipe.png) |

Re-collect: build the APK, `./gradlew :cli:installDist`, then drive
`cli/build/install/compose-preview/bin/compose-preview launch <apk> --java <jdk21+> --control
stdio` with the commands above.

## Real apps

Debug APKs built from each project's `main`, run headless (`--control stdio --headless`) on JDK 21;
screenshots are the control channel's `screenshot`.

**Now in Android** (`app-demo-debug.apk`), after a 10 s wait, then `tap "Interests"`, `tap
"Saved"`, `tap` the Settings action. The topics come from the demo flavor's sync, run by
WorkManager once the network reads as connected:

![Now in Android: For you, Interests, Saved, Settings](nowinandroid-tour.png)

**Confetti** (`androidApp-debug.apk`, `--sdk 36`), its onboarding after startup. Past `Skip` the
conference list stays empty here: this sandbox's network refuses `confetti-app.dev`.

![Confetti onboarding](confetti-onboarding.png)

**Home Assistant RemoteCompose, Cadence, MeshCore** (debug APKs, `--sdk 36`): Home Assistant's
connect screen, then its offline demo after `tap "Offline demo · sample data on this phone"`;
Cadence's library, its activity built by Metro's `AppComponentFactory`; MeshCore's device list.

![Home Assistant, Home Assistant demo, Cadence, MeshCore](more-apps.png)

**Sunflower, Muzei, MeshCore's Bluetooth flow** (debug APKs): Sunflower's plant list from the
Room data its seed worker loads (the photos come from upload.wikimedia.org, which this sandbox's
network refuses); Muzei's intro, its nine content providers installed; MeshCore's BLE tab asking
for the permission, then scanning after `tap "Grant permission"` is granted.

![Sunflower, Muzei, MeshCore before and after the Bluetooth grant](sunflower-muzei-meshcore-ble.png)

## Display, fold and device controls

Now in Android's demo debug APK, headless and under Xvfb, driven over `--control stdio` with the
display commands (`device`, `rotate`, `posture`, `resize`, `qualifiers`, `fontScale`); screenshots
are the control channel's `screenshot` unless noted.

**Fold postures** — `device foldable`, `tap "Interests"`, `rotate landscape` (841×673dp), then
`posture`. Flat is what `main` shows at that size, since no fold reached the app before: a
navigation rail and the list-detail pair. In `tabletop` (a horizontal half-opened fold)
`NavigationSuiteScaffold` moves to a bottom bar, as Material's adaptive library does for
`isTabletop`; in `book` (vertical, half-opened, so separating) the detail pane starts at the
hinge; with `hingeDp: 40` it starts past the hinge band.

![Flat, tabletop, book and a 40dp hinge on the foldable in landscape](fold-postures.png)

**Device presets and rotation** — `device phone` (411×891dp: bottom bar), `device foldable` (673×841dp
open: a rail, one pane), the phone after `rotate` (891×411dp), `device tablet` (1280×800dp) and
`device desktop` (1920×1080dp at mdpi): list-detail with a rail.

![Phone, foldable, rotated phone, tablet and desktop](device-presets.png)

**Configuration at run time** — `qualifiers +night` and `fontScale 1.5`, each recreating the
activity as a device does.

![Phone, night mode, font scale 1.5](runtime-config.png)

**The window follows** — the Xvfb screen (`import -window root`, scaled), after launch, after the
foldable in landscape, and after `device desktop`: the Swing window is resized to the display each
time.

![The Swing window at three display sizes](window-follows.png)

## Wear OS, Glimmer and Android XR

`launch` detects the form factor from the APK (`docs/SIMULATOR.md` → Form factors). All headless
(`--control stdio --headless`) on JDK 21; screenshots are the control channel's `screenshot`.

**Home Assistant's Wear app** (`:wear:assembleDebug` of homeassistant-remotecompose), before and
after: the same APK on the phone default (`--form-factor phone`, what `launch` used for every APK
before), then detected as Wear on the round 454×454 watch display, cropped round as a round
`@Preview` is; then after `{"cmd":"rotary","delta":-1,"count":6}` and a 2 s `wait`, the crown has
scrolled its `ScalingLazyColumn` to Settings.

| Before: phone default | After: round watch | After `rotary` |
| --- | --- | --- |
| ![Home Assistant Wear on the phone default](wear-ha-before-phone.png) | ![Home Assistant Wear, round](wear-ha-round.png) | ![Home Assistant Wear after rotary](wear-ha-rotary.png) |

**`samples/wear`** (`:samples:wear:assembleDebug`), then `{"cmd":"rotary","delta":-1,"count":6}`:
the crown scrolls its `TransformingLazyColumn` to the `EdgeButton`. **MeshCore's Wear app** starts
with no radio, as it would with no phone paired.

| `samples/wear` | after `rotary` | MeshCore Wear |
| --- | --- | --- |
| ![samples/wear](wear-sample.png) | ![samples/wear after rotary](wear-sample-rotary.png) | ![MeshCore Wear](wear-meshcore.png) |

**Glimmer and XR.** No Glimmer or XR app lives in this repository (`samples/xr-glimmer` is a
library), so these come from two throwaway probe apps, not committed: a Glimmer activity
(`TitleChip`, `Card`, two `ListItem`s) declared `requiredDisplayCategory="xr_projected"`, on the
960×720 glasses display; and an `androidx.xr.compose` 1.0.0-beta02 app in Home Space, its `Orbiter`
rendered inline, after one `tap "Click"`. The Glimmer probe sets no theme, hence the action bar.

![Glimmer probe](glimmer-probe.png)

![XR probe in Home Space](xr-probe-home-space.png)

## More than one display

A probe APK (plain Views, built outside the repository) that listens for displays, shows an
`android.app.Presentation` on each presentation display, and launches a second activity onto one
with `ActivityOptions.setLaunchDisplayId`. Headless, with `launch --display 960x540,name=TV`,
driven over `--control stdio`; each image is the control channel's `screenshot` with `display` (or `"all"`), unless noted.

**A presentation on the TV** — the app's `DisplayListener` and `getDisplays(
DISPLAY_CATEGORY_PRESENTATION)` find the display, and its `Presentation` draws there at the
display's 960×540. Two `tap {"display":1}` reached the presentation's button; the phone's picture
does not contain it (before this change, Robolectric drew every window into the one screen).

![The phone, and the presentation on display 1](multi-display-presentation.png)

**Activities on a display** — the app's own `setLaunchDisplayId` puts `SecondActivity` on the TV
while `MainActivity` stays resumed on the phone; `SecondActivity` reports the TV's 960×540dp. After
`displays change` to 640×360dp at xhdpi it is re-created there at 1280×720 pixels. (Its "Open
another here" starts a third activity with no options, which stays on the TV.)

![MainActivity on the phone, SecondActivity on the TV, and after the TV changes density](multi-display-activities.png)

**The windows** — under Xvfb (`import -window root`, cropped and scaled): the phone, then a window
per secondary display (titled `<name> — display <id>`; no window manager, hence no frames): the TV
with `SecondActivity`, and a 480×360 virtual "Projector" added with `displays add`, showing its
presentation.

![The phone and two display windows under Xvfb](multi-display-window.png)

**A paired phone and watch** (`launch phone.apk --pair wear.apk`). Two throwaway probe apps, one
button per Wear interaction API (the full table is in
[`../../SIMULATOR_DATALAYER.md`](../../SIMULATOR_DATALAYER.md)): `RemoteActivityHelper` opening a
`VIEW` intent on the other device, each way, and the probes' results.

| Phone → watch | Watch → phone |
| --- | --- |
| ![Opened on the watch](pair-remote-phone-to-watch.png) | ![Opened on the phone](pair-remote-watch-to-phone.png) |

| Phone probe | Watch probe |
| --- | --- |
| ![Phone probe](pair-probe-phone.png) | ![Watch probe](pair-probe-watch.png) |

Home Assistant's real phone and Wear apps, paired: the watch before, and after the phone's
"Offline demo" tap synced 14 data items to it.

| Watch before | Watch after the phone's demo tap |
| --- | --- |
| ![Home Assistant watch before](pair-ha-watch-before.png) | ![Home Assistant watch in demo mode](pair-ha-watch-demo.png) |
