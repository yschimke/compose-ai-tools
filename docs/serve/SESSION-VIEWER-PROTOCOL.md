# Session-viewer wire contract (`composeai-stream/1`)

This is the **versioned, published interface** the mobile / Wear session-viewer
clients build against. The clients live in a separate repo —
[`yschimke/compose-preview-client`](https://github.com/yschimke/compose-preview-client)
(split out in [#2533](https://github.com/yschimke/compose-ai-tools/issues/2533))
— and depend on `compose-preview serve` **only through this contract**, never
through a code dependency. That makes this document the source of truth for three
things: the streamed-frame WebSocket protocol, the tappable session-link format,
and the mDNS discovery contract.

The server side lives in `:cli`:
[`ServeStreamProtocol`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeStreamProtocol.kt)
(frames), [`ServeUrls`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeUrls.kt)
(link shapes), and
[`ServeMdnsAdvertiser`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeMdnsAdvertiser.kt)
(discovery). See [daemon/STREAMING.md](../daemon/STREAMING.md) for the native
daemon streaming protocol this serve lane mirrors.

## Versioning

The wire version is **`composeai-stream/1`**, advertised in the mDNS `proto` TXT
record (below) so a discoverer can ignore a server it can't talk to. Bump the
version and update this doc together with any breaking change to the frame
protocol, the link format, or the TXT keys. Additive, backward-compatible fields
(a new optional message field, a new TXT key) do **not** bump the major.

Robustness rule (both sides): **unknown message types and unknown fields are
ignored, never fatal** — a client decodes an unrecognised server message to a
benign "unknown" value and keeps the lane open, and the server ignores unknown
client messages. This is what lets the version stay at 1 across additive growth.

## 1. Streamed-frame WebSocket lane

Endpoint: `ws(s)://host:port/ws/{previewId}?token=…` — a preview the server is
already running. An equivalent path form `ws(s)://host:port/{system}/ws/{name}`
exists, where the `{system}` segment *is* the session. The `token` is the only
gate on the served endpoints, so it always rides the URL. All messages are UTF-8
JSON text frames.

> **Forward-looking — not yet served.** The bundle entrypoint
> `ws(s)://host:port/ws/bundle?src=<url>&token=…[&preview=<id>]` — where the server
> would fetch and start a portable [bundle](../portable-bundles.md) before
> streaming — is **modeled client-side but has no server route yet**. The server
> currently registers only `/ws/{name}` (+ the `/{system}/ws/{name}` form), so a
> `bundle` link resolves `bundle` as a preview id and is rejected. Treat bundle
> session targets (`SessionTarget.Bundle`, `composeai://…&bundle=…`,
> `composeai://open?bundle=…`) as pending until this route lands; a preview target
> on an already-running module is the supported path today.

### Server → client

| `type` | Fields | Meaning |
|---|---|---|
| `frame` | `seq` (int, monotonic), `codec` (string, `"png"`), `widthPx` (int), `heightPx` (int), `dataBase64` (base64 of the image bytes) | One rendered frame. Newest-`seq`-wins; a client dedupes/repaints. |
| `error` | `message` (string) | Non-fatal render/notice. The lane stays open. |

A client MUST treat any other `type` (or malformed text) as a benign unknown and
keep the connection open.

### Client → server

| `type` | Fields | Meaning |
|---|---|---|
| `setOverrides` | `overrides` (object of string→string) | Replace the display overrides and re-render. |
| `requestFrame` | — | Re-render and push a frame at the current overrides. |
| `switch` | `previewId` (string), `overrides` (object, optional) | Move the connection to another preview on the same module without reconnecting. |
| `input` | `kind` + optional `pixelX`/`pixelY`/`pointerId`/`scrollDeltaY`/`keyCode`, all **top-level** (see below) | Forward one pointer/key/rotary event. |

`setOverrides`, `requestFrame`, and `switch` are handled by the serve lane today.
`input` is dispatched into a **live (daemon-streamed)** composition; the
snapshot-render fallback lane ignores it (it can't accept input). `kind` matches
the daemon's `InteractiveInputKind`.

#### `input` message

The input fields sit **at the top level of the message object** — not nested under
an `input` key. This is the exact shape `ServeStreamProtocol.parseClient` reads:

```json
{ "type": "input",
  "kind": "click",
  "pixelX": 120, "pixelY": 240,
  "pointerId": 0,
  "scrollDeltaY": 1.0,
  "keyCode": "Enter" }
```

- `kind` (required) — one of `click`, `pointerDown`, `pointerMove`, `pointerUp`,
  `rotaryScroll`, `keyDown`, `keyUp`. These spellings match the daemon's
  `InteractiveInputKind` `@SerialName`s exactly.
- `pixelX` / `pixelY` — image-**natural** pixel coordinates (not view-local). The
  client maps a touch from its letterbox-fit canvas back into frame pixels before
  sending (uniform scale, centred), so the coordinate lands on the element the
  user touched regardless of display scaling. Present for pointer kinds.
- `pointerId` — per-pointer id for multi-touch; defaults to `0`, ignored for
  non-pointer kinds.
- `scrollDeltaY` — wheel/rotary delta for `rotaryScroll`; positive = scroll-down.
- `keyCode` — key identifier for `keyDown` / `keyUp` (e.g. `"Enter"`, `"A"`).

## 2. Session-link format

A tapped link resolves to a connectable target `(host, port, token, target,
secure)`. Several shapes parse into the same model:

| Shape | Example |
|---|---|
| Custom scheme, running preview | `composeai://session?host=H&port=7341&token=T&preview=com.x.Foo` |
| Custom scheme, **bundle** | `composeai://session?host=H&port=7341&token=T&bundle=<url>[&preview=id]` |
| Serve viewer URL (pasteable) | `http(s)://H:7341/p/com.x.Foo?token=T` |
| Raw WebSocket URL | `ws(s)://H:7341/ws/com.x.Foo?token=T` |
| Host-less bundle (open on **my** server) | `composeai://open?bundle=<url>&token=T[&preview=id]` |

- Scheme: **`composeai`**. Default port: **`7341`** (plaintext) / `443` (TLS).
- `secure=true` (custom scheme) or an `https`/`wss` scheme ⇒ TLS.
- Two target kinds: a **preview id** on a module the server already runs
  (`/ws/{previewId}`), or a **bundle** `src` URL the server fetches and starts
  (`/ws/bundle?src=…`). A host-less `composeai://open?bundle=…` names a bundle but
  not a server; the app pairs it with its configured default server.
- The `token` always rides the link (never mDNS — see below).

## 3. mDNS / DNS-SD discovery

`compose-preview serve --lan` advertises itself; the apps browse with Android's
`NsdManager` and list nearby servers on the connect screen.

- **Service type:** `_composeai._tcp.` (both sides register/browse this).
- **TXT records:**

  | Key | Value | Meaning |
  |---|---|---|
  | `module` | e.g. `:samples:android` | Gradle module being served (display only). |
  | `previews` | comma-separated ids | Advertised preview ids (best-effort; may be truncated). |
  | `secure` | `"true"` | Server speaks TLS (wss/https). Absent/other ⇒ plaintext. |
  | `proto` | `composeai-stream/1` | Wire-protocol marker; a discoverer ignores servers it can't talk to. |

- The advertisement carries the module label, preview ids, a TLS flag, and the
  protocol marker — but **never the token**. A broadcast token would defeat the
  gate, so a discovered server is still opened with a token the user supplies (the
  shared link / QR).
