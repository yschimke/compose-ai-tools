# Play Store graphics (Wear)

Gradle Play Publisher uploads every image here on
`:clients:wear:publishBundle` (filename order).

| Directory | Required? | Format | Dimensions | Notes |
|---|---|---|---|---|
| `icon/` | yes | PNG, 32-bit, no transparency | exactly **512 × 512** | Hi-res Play listing icon. |
| `feature-graphic/` | yes | PNG / JPEG, no transparency | exactly **1024 × 500** | Listing banner. |
| `wear-screenshots/` | yes (≥ 1, ≤ 8) | PNG / JPEG | 384–3840 px per side | Watch screenshots (square works well for round displays). |

## How these screenshots are produced

The watch screenshots are the app's `@Preview` chrome rendered by compose-preview:

```sh
./gradlew :clients:wear:composePreviewRenderAll
cp clients/wear/build/compose-previews/renders/*.png \
   clients/wear/src/main/play/listings/en-US/graphics/wear-screenshots/
```

`icon/` and `feature-graphic/` are committed placeholders — replace with final
art before any public (non-internal) release.
