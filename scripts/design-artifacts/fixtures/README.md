# `fixtures/` — cross-runtime design-artifact fixtures

`parity-issues.json` is emitted in the JavaScript producer's wire format and loaded directly by
`ServeParityIssuesStoreTest`, pinning the producer and Kotlin consumer to the same schema.

The `.rc` files below are captured Remote Compose documents for the player tests.

Small, committed `.rc` documents the browser-player tests in this directory replay. They are real
captures from the `design-catalog-remote-m3` render, not hand-written bytes, so a test built on one
exercises exactly the wire form the connector packs into a bundle's `ir/<id>.rc` sidecar.

| file | source preview | why it is here |
|---|---|---|
| `watch-screen-round-clip.rc` | `WatchScreenRemote` (454×454, density 2.0) | The catalog's only **size-relative** clip: `RemoteModifier.clip(RemoteCircleShape)` writes each `MODIFIER_ROUNDED_CLIP_RECT` corner as a NaN-encoded expression over the component's measured size rather than a dp literal. Reading those as plain floats produced an empty clip path and a blank canvas ([#2930](https://github.com/yschimke/compose-ai-tools/issues/2930)) — a failure that parses cleanly and warns about nothing, so only a pixel assertion catches it. Used by `rc-round-clip.test.mjs`. |

Re-capture one by rendering the catalog and copying the sidecar:

```sh
./gradlew :samples:design-catalog-remote-m3:composePreviewRenderAll
cp samples/design-catalog-remote-m3/build/compose-previews/renders/\
WatchScreenRemote_width_227dp_height_227dp_dpi_320.rc \
  scripts/design-artifacts/fixtures/watch-screen-round-clip.rc
```

Keep them small — these are checked-in binaries, and a document big enough to be interesting is
already only a couple of kilobytes.
