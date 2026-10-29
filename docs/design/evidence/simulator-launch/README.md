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
