# The Wasm player's embed contract

What a page that embeds the Compose Multiplatform Remote Compose player may rely on, and how that
set of promises is versioned. Companion to [RC_CMP_WASM_PLAYER.md](RC_CMP_WASM_PLAYER.md), which
covers the player itself, and to [RC_PLAYER_SWIFT.md](RC_PLAYER_SWIFT.md), which does the same job
for iOS.

Implements #4067. Before it, this contract existed — `Main.kt` has always defined it — but only in
the source, which is a poor place for something two other repositories drive.

## Distribution

`@yschimke/remote-compose-player-cmp` on npm. The bundle is a set of static files served as their
own document, not something imported into an application bundle:

```html
<iframe src="/rc-player/index.html?src=/documents/watch-face.rc&theme=dark"></iframe>
```

`:rc-player-wasm:rcPlayerNpmPackage` stages the package directory; `release.yml` runs `npm publish`
from it. **No Node enters the Gradle build** — a `Sync` task is all the staging needs, which keeps
the coupling the CLI's vendored JS player was specifically arranged to avoid.

The GitHub Release also carries the raw `wasmDist` as an asset, for a consumer who wants the files
without npm.

## Which player should an external user reach for?

There are two, and the honest answer is not "the newest one".

| | this bundle | the vendored TypeScript player |
|---|---|---|
| renderer | Compose Multiplatform, the same one Android and iOS run | a separate browser implementation |
| operation coverage | narrower today | **wider today** |
| pixels | identical to the Android/iOS players by construction | close, but a second implementation |
| size | ~23 MB, nearly all Skiko | small |

Reach for **this** when cross-platform parity is the point — one renderer, one set of pixels
everywhere. Reach for the **TypeScript** player when coverage matters more, or when 23 MB is
disqualifying. `RC_CMP_WASM_PLAYER.md` tracks the gates that remain before this one replaces it;
until then the vendored player stays, and this document exists so the answer does not depend on
which page someone found first.

## Versioning

The contract carries **its own integer version**, separate from the repo's release version:

- `window.rcPlayerContractVersion`
- `document.documentElement.dataset.rcPlayerContract`

The bundle ships on every release, but a host cares whether `?src=` and `window.rcPlayerLoad` still
mean what it coded against — not which release it happens to have. The npm package's **major**
tracks this number.

**Bump it** for anything a host could observe breaking: a query parameter's meaning, a
`data-rc-player-state` value, a `postMessage` payload's shape, `window.rcPlayerLoad`'s behaviour.
**Do not bump it** for additions — a new parameter or a new message type is feature-detected.

Current version: **1**.

## Query parameters

| parameter | meaning |
|---|---|
| `src` | URL of the `.rc` document. **Required**; absent is an `error` state, not an empty render. |
| `theme` | `light` or `dark` forces a mode. Any other value, or none, follows the browser's `prefers-color-scheme`. |
| `fontsBase` | Directory holding `fonts.json` and its faces. Default `./fonts/`. Rejected unless relative or `http(s):` — a page-supplied parameter must not become an arbitrary scheme. |
| `namedValues` | Host overrides for the document's named variables, as a URL-encoded **JSON array** of `{"kind", "name", "value"}` objects — see below. Kinds: `string`, `float`, `dp`, `int`, `bool`, `color`, `long`. Names are prefixed `USER:` internally. |
| `rcTrace` | `1` emits User Timing marks, so the player's spans land in a DevTools performance profile under the same names the desktop player writes to Perfetto. Off by default: `performance`'s entry buffer is finite and shared with the embedding page. |
| `allowExternalImagePlaceholders` | `1` renders a placeholder instead of refusing a document that names an external image. |
| `handoffDelayMs` | Cold-start tail before `ready`, clamped to 0–10 000, default 1 500. **Only lower it if you composite the result yourself** — the default guards a human seeing a blank frame, and the failure it guards against cannot be reproduced under CDP capture. |

### `namedValues` in detail

The value is a JSON array, URL-encoded as any query parameter is. Each element names one variable:

```js
const namedValues = [
  { kind: "string", name: "label", value: "Ready" },
  { kind: "color", name: "stopColor", value: "#FF8800" },
  { kind: "float", name: "progress", value: 0.75 },
];
const src = `player.html?src=doc.rc&namedValues=${encodeURIComponent(JSON.stringify(namedValues))}`;
```

`value` is stringified, so a number or boolean may be passed unquoted. `color` accepts
`#RRGGBB`/`#AARRGGBB` with or without the leading `#`.

**`bool` is `"true"` or nothing.** It is the one kind that does not validate: the parser tests for
the exact string `true` and maps *everything else* — `"1"`, `"TRUE"`, `"yes"`, a typo — to `false`.
So a malformed boolean does not fall back to the document's own value, it actively overrides the
variable to false. Send `true`/`false` and nothing else.

**Otherwise, a malformed array is silently ignored rather than reported.** The parser returns no
overrides at all on any JSON error, and an element whose `kind` is unrecognized or whose `value`
does not parse as its kind is dropped on its own — the document still renders, with its own
defaults. Nothing appears in `data-rc-player-error`, because failing to *style* a document is not
failing to *load* one. A host that cannot tolerate a silently-defaulted (or, for `bool`, silently
falsified) override should validate before building the URL.

(The NUL-and-`\u0001`-delimited rows in `Main.kt`'s `flattenNamedValuesFromLocation` are an internal
shape used to hand the parsed array across the JS/Wasm boundary. They are not the wire format, and
passing them as `namedValues` yields no overrides at all.)

`theme` and `namedValues` belong to the *page* and are **not** re-read on a document swap. A host
that needs different ones navigates.

## The `window` surface

**`window.rcPlayerLoad(src)`** shows another document in the player that is already running, instead
of navigating the page again. A navigation is the honest way to load the *first* document and a poor
way to load the next one: it discards the instantiated Wasm module, the Compose runtime and the
fetched fonts, then rebuilds all three to draw a document that is usually a few dozen operations
long. Requests are last-one-wins.

The marker returns to `loading` **synchronously** inside that call, so a host waiting for `ready`
cannot read the outgoing render's marker and screenshot the document it just replaced.

**`window.rcPlayerContractVersion`** — the integer above.

## The readiness marker

`document.documentElement.dataset.rcPlayerState`:

| value | meaning |
|---|---|
| `loading` | a document is being fetched or decoded |
| `ready` | the document has rendered and the surface has been presented |
| `error` | the load failed; `dataset.rcPlayerError` carries the message |

Wait for `ready` before revealing the frame. `ready` is deliberately later than "the composition
ran": Compose schedules Skiko's raster work after composition, so the player waits three browser
frames plus `handoffDelayMs` before setting it. Chromium can acknowledge frames before the Skiko
surface reaches the compositor.

## `postMessage`

All same-origin, to `window.parent`:

| message | when |
|---|---|
| `'cp-rc-wasm-ready'` | alongside the `ready` marker |
| `'cp-rc-wasm-error:<message>'` | alongside the `error` marker |
| `{type: 'cp-rc-debug-message', message, value, flags}` | the document executed `DebugMessage` |
| `{type: 'cp-rc-host-action', actionId}` | a host action fired |
| host-metadata and host-named-action variants | as documented in `Main.kt` |

Host actions also accumulate on `dataset.rcPlayerActionTrace`, comma-separated, for a driver that
polls rather than listens.

## Size

~23 MB, nearly all of it the Skiko WebAssembly runtime — the cost of running the real Compose
renderer in a browser rather than a reimplementation of it.

`wasmPlayerDist` enforces a byte budget. **That budget is a ratchet, not a published guarantee**, and
this document says so deliberately rather than letting the number be inherited as a promise: it
exists to make an unintended jump fail the build, and it moves when a deliberate payload lands (it
already has, twice — a Compose Multiplatform bump and a vendored font family). A consumer should
treat the size as "tens of megabytes", not as a number to assert against.
