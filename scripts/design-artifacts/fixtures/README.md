# `fixtures/` — cross-runtime design-artifact fixtures

`parity-issues.json` is emitted in the JavaScript producer's wire format and loaded directly by
`ServeParityIssuesStoreTest`, pinning the producer and Kotlin consumer to the same schema.

`known-differences/` is the conformance suite for `compose-preview-known-differences/v1` — generated
by `build-known-difference-fixtures.mjs`, consumed by `known-differences.test.mjs` here and, as batch
05 lands, by `design-parity`'s suite and the server projector's Kotlin tests. It has [its own
README](known-differences/README.md); do not hand-edit it.

The `.rc` files below are captured Remote Compose documents for the player tests.

Small, committed `.rc` documents the browser-player tests in this directory replay. They are real
captures from the `remote-m3` catalog's render, not hand-written bytes, so a test built on one
exercises exactly the wire form the connector packs into a bundle's `ir/<id>.rc` sidecar. That
catalog is published from [yschimke/wear-m3-catalog](https://github.com/yschimke/wear-m3-catalog)
now (#4588), so re-capturing means rendering it there — see below. The committed fixtures are
unaffected: they are bytes, not a build.

| file | source preview | why it is here |
|---|---|---|
| `watch-screen-round-clip.rc` | `WatchScreenRemote` (454×454, density 2.0) | The catalog's only **size-relative** clip: `RemoteModifier.clip(RemoteCircleShape)` writes each `MODIFIER_ROUNDED_CLIP_RECT` corner as a NaN-encoded expression over the component's measured size rather than a dp literal. Reading those as plain floats produced an empty clip path and a blank canvas ([#2930](https://github.com/yschimke/compose-ai-tools/issues/2930)) — a failure that parses cleanly and warns about nothing, so only a pixel assertion catches it. Used by `rc-round-clip.test.mjs`. |

Re-capture one by rendering the catalog and copying the sidecar:

```sh
# in a yschimke/wear-m3-catalog checkout
./gradlew :remote-catalog:composePreviewRenderAll
cp remote-catalog/build/compose-previews/renders/\
WatchScreenRemote_width_227dp_height_227dp_dpi_320.rc \
  <compose-ai-tools>/scripts/design-artifacts/fixtures/watch-screen-round-clip.rc
```

Or pull it straight off the delivery branch without a build at all — the same document ships as the
sticker's IR sidecar:

```sh
git fetch https://github.com/yschimke/wear-m3-catalog.git design-artifacts/remote-m3
git show FETCH_HEAD:bundle/ir/Template%2FWatchScreen.rc > \
  scripts/design-artifacts/fixtures/watch-screen-round-clip.rc
```

Keep them small — these are checked-in binaries, and a document big enough to be interesting is
already only a couple of kilobytes.
