# @yschimke/remote-compose-player-cmp

The Compose Multiplatform / Wasm [Remote Compose](https://developer.android.com/jetpack/androidx/releases/compose-remote)
player, as a browser bundle. It renders a `.rc` document into a page, driven entirely by query
parameters and one `window` function — no JavaScript API to learn and no bundler step.

```html
<iframe src="/rc-player/index.html?src=/documents/watch-face.rc&theme=dark"></iframe>
```

## Install

```
npm install @yschimke/remote-compose-player-cmp
```

The package ships a `dist/` directory: `index.html`, the compiled Wasm module, the Skiko runtime,
and a fonts manifest. Serve it as static files — copy `dist/` into your public directory, or point
your server at `node_modules/@yschimke/remote-compose-player-cmp/dist`. Nothing here is meant to be
imported into an app bundle; the player runs in its own document.

Every file must be served from the same directory, and `.wasm` must be served as
`application/wasm` — the module is instantiated by streaming.

## Which player is this?

**There are two.** This one is the Compose Multiplatform renderer. The other is a TypeScript player
vendored inside `compose-preview`'s CLI, and today **it supports more operations**. Reach for this
package when you want the same renderer that runs on Android and iOS — one implementation, one set
of pixels across platforms. Reach for the TypeScript player when coverage matters more than
cross-platform parity. `RC_CMP_WASM_PLAYER.md` in the repository tracks which gates remain before
this one replaces it.

## The embed contract

Versioned separately from the release it ships in, because a host cares whether `?src=` still means
what it coded against — not which release it happens to have. `window.rcPlayerContractVersion` and
`document.documentElement.dataset.rcPlayerContract` both carry it, and this package's **major**
tracks it.

Full reference: [RC_PLAYER_EMBED.md](https://github.com/yschimke/compose-ai-tools/blob/main/docs/design/RC_PLAYER_EMBED.md).
Summary:

| parameter | meaning |
|---|---|
| `?src=` | URL of the `.rc` document. Required. |
| `?theme=light\|dark` | Force a mode. Anything else follows `prefers-color-scheme`. |
| `?fontsBase=` | Directory holding `fonts.json` and its faces. Default `./fonts/`. |
| `?namedValues=` | Host overrides for the document's named variables. |
| `?rcTrace=1` | Emit User Timing marks for a DevTools performance profile. |
| `?allowExternalImagePlaceholders=1` | Render a placeholder instead of failing on an external image. |
| `?handoffDelayMs=` | Cold-start tail before `ready`. Only lower it if you composite the result yourself. |

- `window.rcPlayerLoad(src)` swaps the document without reloading the page, keeping the Wasm module,
  the Compose runtime and the fetched fonts warm.
- `document.documentElement.dataset.rcPlayerState` is `loading`, `ready` or `error`. Wait for
  `ready` before revealing the frame; `rcPlayerError` carries the message on `error`.
- The player also `postMessage`s `cp-rc-wasm-ready` / `cp-rc-wasm-error:<message>` and structured
  host-action and debug-message events to `window.parent`, same-origin.

## Size

The bundle is around 23 MB, nearly all of it the Skiko WebAssembly runtime. That is the cost of
running the real Compose renderer in a browser rather than a reimplementation of it. The repository
enforces a budget so an unintended jump fails the build; the budget is **not** a published
guarantee, and it moves when a deliberate payload lands.

## License

Apache 2.0.
