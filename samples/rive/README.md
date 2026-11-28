# Rive test app

Three `RiveAnimationView`s, one per part of Rive's native runtime the simulator's host build of
`librive-android` ([`host-natives/rive`](../../host-natives/rive)) has to get right:

| File | Exercises |
| --- | --- |
| `off_road_car_blog.riv` | vector paths, gradients and a looping animation |
| `image_db_cats.riv` | embedded images, decoded through `android.graphics` |
| `font_db_station_sign.riv` | an embedded font, shaped with HarfBuzz |

A fourth row draws `image_db_cats.riv` with Rive's Compose API (`rememberRiveWorkerOrNull` and
`Rive`), which needs OpenGL ES. The host build renders it with the host's EGL (Mesa's llvmpipe will
do) and presents each frame to the row's `TextureView` (`SimulatorShadowEGL14`); the row logs
`PASS compose: the GPU renderer drew …`. On a host without EGL the worker must fail with an error
the app can handle, as on a device without OpenGL ES, and the row logs `PASS compose: no GPU, …`.

In the simulator, as on a device or emulator without Rive's GPU renderer, every `RiveAnimationView`
falls back to Rive's Canvas renderer, which draws into the view's `TextureView`
(`SimulatorShadowTextureView`).
The app reads each view's frame back until all have drawn, and logs the result under the
`RiveSample` tag:

```
I/RiveSample: PASS off_road_car_blog: 540x275, 51.1% of pixels drawn
I/RiveSample: DONE 4/4 passed
```

Run it in the simulator, headless:

```
./gradlew :samples:rive:assembleDebug :cli:installDist
python3 .github/scripts/simulator_smoke.py --cli cli/build/install/compose-preview/bin/compose-preview \
  --apk samples/rive/build/outputs/apk/debug/rive-debug.apk --check rive
```

The check keeps a screenshot (`build/simulator-smoke/rive.png`). Until a `host-natives-rive-*`
release is in `host-natives/index.json`, point `COMPOSE_PREVIEW_HOST_NATIVES_INDEX` at an index
with a build of your own (`host-natives/build.sh rive`); without one, `Rive.init` fails with
`UnsatisfiedLinkError: no rive-android`, as it did before.

The `.riv` files are rive-android's own samples (MIT, Copyright Rive), from
[rive-android 11.17.0](https://github.com/rive-app/rive-android/tree/11.17.0/app/src/main/res/raw).
