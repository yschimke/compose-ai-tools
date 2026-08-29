# Preview server Wasm frontend

An isolated Compose/Wasm prototype over the preview server's existing public client contracts. It
does not share source or build output with `cli/serve-web`.

Build the static app and the CLI:

```shell
./gradlew :cli:serve-wasm:wasmFrontendDist :cli:installDist
```

Start a normal local preview server and add this distribution to its existing Wasm asset lane:

```shell
cli/build/install/compose-preview/bin/compose-preview browse \
  --wasm-dir preview-ui=cli/serve-wasm/build/wasmDist
```

The command prints the server URL and token. Open the normal UI as usual, then open the prototype
side by side at:

```text
http://127.0.0.1:<port>/wasm/preview-ui/?token=<token>
```

For a named catalog, append `&session=<catalog-id>`. The app preserves those parameters in its API,
snapshot, legacy-viewer, and WebSocket requests. Use `?token=...&session=...&preview=<id>` to deep
link directly into a preview. Add `&live=1` to connect its live stream immediately (handy for a
saved development URL or browser smoke test).

Implemented in the prototype:

- catalog loading, filtering, responsive preview cards, and direct preview deep links;
- baked PNG previews through `/render/{id}.png`;
- persistent live preview frames through `/ws/{id}` with connection/error state;
- light/dark, font-scale, locale, and transparent-background render overrides;
- pointer taps forwarded into live compositions; and
- links back to the existing viewer for advanced inspection surfaces.
