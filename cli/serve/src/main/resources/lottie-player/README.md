# `lottie-player/bundle.js` — vendored Lottie browser player (upstream build output)

`bundle.js` is the **unmodified** `build/player/lottie.min.js` from the
[`lottie-web`](https://github.com/airbnb/lottie-web) npm package. It is served
over `GET /doc-player/lottie/bundle.js` by `compose-preview serve` so an
uploaded Lottie document (`POST /docs` → `GET /d/<id>`) plays back in the
viewer's browser — the Lottie counterpart of the vendored Remote Compose player
in [`../rc-player/`](../rc-player).

Playback happens **client-side**; the server only stores and hands back bytes,
so hosting an anonymous upload never runs anything on the host.

| | |
|---|---|
| Upstream | `lottie-web` (airbnb/lottie-web) |
| Version | 5.13.0 |
| File | `build/player/lottie.min.js` (full player: SVG / canvas / HTML renderers, expressions) |
| Licence | MIT — see [LICENSE.md](LICENSE.md), © 2015 Bodymovin |
| SHA-256 | `2eb762973aec914d981f426123040bfac9d26217239605e225ddc7cee17618ac` |

Checked in on purpose, like the RC player: the `:cli` module builds on a
pure-JVM toolchain (no Node/npm in its Gradle build or CI lane), so the player
is vendored as a resource rather than fetched at build time — and a served host
must work with no CDN reachable.

## Updating

```bash
npm pack lottie-web@<version>
tar xzf lottie-web-<version>.tgz package/build/player/lottie.min.js package/LICENSE.md
cp package/build/player/lottie.min.js cli/serve/src/main/resources/lottie-player/bundle.js
cp package/LICENSE.md                 cli/serve/src/main/resources/lottie-player/LICENSE.md
sha256sum cli/serve/src/main/resources/lottie-player/bundle.js   # refresh the row above
```

Keep the version, licence and digest rows above in step with the file — they are
the provenance record for this binary-ish artefact.
