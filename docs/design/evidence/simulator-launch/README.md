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
