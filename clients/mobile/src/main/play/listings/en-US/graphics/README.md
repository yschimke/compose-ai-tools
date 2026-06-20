# Play Store graphics

Gradle Play Publisher (GPP) auto-uploads every image in these directories on
`:clients:mobile:publishBundle` (in filename order). Drop files straight in.

| Directory | Required? | Format | Dimensions | Notes |
|---|---|---|---|---|
| `icon/` | yes | PNG, 32-bit, no transparency | exactly **512 × 512** | Hi-res Play listing icon (not the launcher icon). |
| `feature-graphic/` | yes | PNG / JPEG, no transparency | exactly **1024 × 500** | Banner at the top of the listing. |
| `phone-screenshots/` | yes (≥ 2, ≤ 8) | PNG / JPEG | 320–3840 px per side, max side ≤ 2× min side | Phone listing screenshots. |
| `seven-inch-screenshots/` | optional | PNG / JPEG | 320–3840 px per side | 7″ tablets. |
| `ten-inch-screenshots/` | optional | PNG / JPEG | 320–3840 px per side | 10″ tablets. |

## How these screenshots are produced

The phone screenshots here are the app's own `@Preview` chrome, rendered by the
compose-preview pipeline this repo ships — so they regenerate for free:

```sh
./gradlew :clients:mobile:composePreviewRenderAll
cp clients/mobile/build/compose-previews/renders/*.png \
   clients/mobile/src/main/play/listings/en-US/graphics/phone-screenshots/
```

The renders are 945 × 1890 (a clean 1:2, within Play's ratio bounds). The live
frame canvas can't be captured this way (it needs a running session) — grab
those from a device/emulator running the release build if you want them in the
set. `icon/` and `feature-graphic/` are brand assets, committed as placeholders;
replace them with final art before a public (non-internal) release.
