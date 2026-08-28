# `rc-player/bundle.js` — vendored Remote Compose browser player (build output)

`bundle.js` is the **built** IIFE bundle (global name `RC`) of the TypeScript
Remote Compose player whose source is vendored under
[`third_party/remote-compose-player/`](../../../../../third_party/remote-compose-player).
It is served over `GET /rc-player/bundle.js` by `compose-preview serve` so the
viewer can render a preview's captured Remote Compose document
(`GET /render/<id>.rc`) client-side, in a `<canvas>`, without a Robolectric
daemon — the browser counterpart of the daemon render.

It is a **generated artifact** checked in on purpose: the `:cli` module builds on
a pure-JVM toolchain (no Node/npm in its Gradle build or CI lane), so the bundle
is produced once from the vendored source and committed as a resource rather than
rebuilt during the CLI build. See `third_party/remote-compose-player/PROVENANCE.md`
for the upstream origin and licence (Apache-2.0,
`camaelon/remotecompose-experiments`, commit `d8b07da2`).

## Regenerating

After bumping the vendored source (a new upstream commit under
`third_party/remote-compose-player/`), rebuild the bundle from that source and
copy it back over this file:

```bash
(cd third_party/remote-compose-player && npm ci)
./third_party/remote-compose-player/node_modules/.bin/esbuild \
  third_party/remote-compose-player/src/web/main.ts \
  --bundle --format=iife --target=es2020 \
  --global-name=RC --external:canvas \
  --outfile=cli/serve/src/main/resources/rc-player/bundle.js
```

Run it **from the repository root**, as written. esbuild labels each bundled
module with its path *relative to the working directory*, so the same source
built from inside `third_party/remote-compose-player/` differs from this file in
~280 comment lines and 4.7 kB while being otherwise identical — a diff that
buries whatever actually changed. From the root the rebuild is byte-for-byte
reproducible: with no source edit, `cmp` against the committed file passes.

`--external:canvas` keeps the Node-only `canvas` dependency out of the browser
build. Keep this file and the vendored source in lockstep — a stale bundle would
render an older document format than the connector packs.
