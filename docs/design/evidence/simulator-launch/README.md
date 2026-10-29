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
