# Naming the Remote Compose lanes after their players

Committed evidence for renaming two lanes on the compare page.

| file | what it is |
| --- | --- |
| `lanes-before.light.png` | `main`: `baked PNG` and `RC · embedded player` |
| `lanes-after.light.png` | `AndroidX Java` and `AndroidX Embedded` |

Both renames say **whose player drew the pixels** rather than how the pixels got
here:

- **`baked PNG` → `AndroidX Java`.** It is AndroidX's `RemoteComposePlayer` — an
  Android `View` painting to a framework `Canvas` — rendered offline under
  Robolectric/Skiko. "Baked PNG" described the file format it arrives in, which
  is the one thing a reader comparing it against four other players does not
  care about; every lane on the page is a PNG.
- **`RC · embedded player` → `AndroidX Embedded`.** It is AndroidX's `RcPlayer`,
  a pure-Compose interpreter of the same document. This repo vendors a copy as
  `:third-party-rc-embedded-player` and updates it, but that is a fact about this
  repo's build, not about whose player it is.

## What did NOT change

The lane **ids** are untouched — `baked` and `embedded` on the wire. They key the
staged assets on the delivery branch (`rc-compare/baked/0.png`) and the `?ref=`
parameter, so renaming them would strand every published catalog and break every
bookmarked comparison. Only what a reader sees moved.

The three remaining lanes keep their `RC · …` style, so the row now mixes two
conventions. That was the change asked for; renaming `RC · JS player`,
`RC · cmp-jvm player` and `RC · cmp-wasm player` to match is a separate call.

## Both sides, or neither

`ServeRcCompare.LANES` (the `serve` page) and `render-rc-compare-html.mjs` (the
published static `rc-compare.html`) carry the same lane table and are documented
as mirrors of each other. Renaming one and not the other would leave the two
pages disagreeing about what the same column is called, so both moved together —
along with the prose on each that named the lane inline.

```
./gradlew :cli:test --tests '*ServeWeb*' --tests '*RcCompare*'
node --test scripts/design-artifacts/render-rc-compare-html.test.mjs   # 26 pass
cd cli/serve-web && npm run verify                                     # 301 passing
```
