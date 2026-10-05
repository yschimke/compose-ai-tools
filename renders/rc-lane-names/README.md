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
node --test @design-parity/export-driver/render-rc-compare-html.test.mjs   # 26 pass
cd cli/serve-web && npm run verify                                     # 301 passing
```

## Second rename: the three `RC · …` lanes

The note above left `RC · JS player`, `RC · cmp-jvm player` and `RC · cmp-wasm player` as a separate
call. They are renamed now, to the vocabulary the selector chips use (`RcPlayerBackend.label`),
because the `cmp-jvm` name was hiding what drew the column:

| lane id (frozen) | was | now |
| --- | --- | --- |
| `js` | `RC · JS player` | `Camaelon JS` |
| `cmp-jvm` | `RC · cmp-jvm player` | `rc-player JVM` |
| `cmp-wasm` | `RC · cmp-wasm player` | `rc-player Wasm` |

Until rc-players 2.0.0 the `cmp-jvm` column was **not** the CMP player: it was a desktop-JVM cut of the
AndroidX embedded `RcPlayer` (`:third-party-rc-embedded-player-jvm`). That module is gone, and the
column now runs `RcCmpRenderHarness` (`rc-player-compose`), so `cmp-jvm` and `cmp-wasm` are the same
player on two platforms and the names are true. The live `?rcPlayer=cmp-jvm` chip moved with it, to
the `:rc-render-jvm` worker. Only `cmp-android` is still an AndroidX player under a `cmp-` id.

Ids and the staged `rc-embedded-jvm/` directory names are untouched: they key assets already
published in catalogs and the `?ref=` parameter.
