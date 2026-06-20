# Mobile + Wear session-viewer clients

Tap a link → an app opens → it connects to a live `compose-preview serve`
session and presents the rendered preview **as if it were a complete app**,
painting pushed frames and forwarding your taps / keys / rotary turns back into
the running composition.

This is the client half of the streaming-interactive surface. The server half
is `compose-preview serve` (in `:cli`) — its streamed-frame WebSocket lane
(`WS /ws/{previewId}`, the tier-2 spike in
[PR #1989](https://github.com/yschimke/compose-ai-tools/pull/1989)) is what the
clients connect to. See [daemon/STREAMING.md](../daemon/STREAMING.md) for the
native daemon streaming protocol the serve lane mirrors.

## Modules

| Module | What it is |
|---|---|
| **`:clients:core`** | Pure-JVM engine. No Android, no Compose — so it's unit-tested headlessly. Holds the wire protocol, the connection state machine, the session-link parser, and the mDNS discovery contract. |
| **`:clients:mobile`** | Android phone/tablet app. Compose **Material 3**. |
| **`:clients:wear`** | Wear OS app. **Wear Compose Material 3**, with rotary-bezel input. |

The apps are thin shells: a `ComponentActivity`, a Compose canvas that paints
frames, and touch/rotary handling. All the protocol logic lives in `:clients:core`.

## The engine (`:clients:core`)

```
SessionLink ──connect──▶ SessionClient ──▶ StreamTransport (Ktor/OkHttp WebSocket)
                              │
        state: StateFlow<SessionState>   ◀── frames / errors
        frame: StateFlow<StreamFrame?>   (newest-wins, seq-deduped)
                              │
        send(InputEvent) ────▶ forwarded as `input` messages
```

- **`SessionClient`** drives one session: dial the link's WebSocket, expose the
  connection `state` and the latest `frame` as `StateFlow`s the UI paints, and
  forward `send(InputEvent)` / `setOverrides` / `requestFrame` back. Transport is
  injected (`StreamTransport.Factory`), so it's tested against a fake socket and
  runs over Ktor on device.
- **`StreamMessages`** is the client mirror of the serve lane's
  `ServeStreamProtocol`: it decodes `frame` / `error` and encodes
  `setOverrides` / `requestFrame` — plus an additive `input` message (pointer /
  key) whose fields match the daemon's `InteractiveInputParams` `@SerialName`s,
  so the serve lane can route it straight to `interactive/input` when input
  support lands server-side.
- **`KtorStreamTransport`** is "OkHttp via Ktor" — the Ktor WebSocket client over
  the OkHttp engine, one stack for the JVM, the phone, and the watch.

## Session links

A tapped link resolves to a `SessionLink` (host + port + token + target). Several
shapes parse into the same model:

| Shape | Example |
|---|---|
| Custom scheme, running preview | `composeai://session?host=H&port=7341&token=T&preview=com.x.Foo` |
| Custom scheme, **bundle** | `composeai://session?host=H&port=7341&token=T&bundle=<url>[&preview=id]` |
| Serve viewer URL (pasteable) | `http://H:7341/p/com.x.Foo?token=T` |
| Raw WebSocket URL | `ws://H:7341/ws/com.x.Foo?token=T` |
| Host-less bundle (open on **my** server) | `composeai://open?bundle=<url>&token=T` |

The token is the only gate on the served endpoints, so it always rides the link
(never mDNS — see below).

### Two kinds of target

- **`SessionTarget.Preview`** — a preview id on a module the server is *already*
  running. Connects to `/ws/{previewId}`.
- **`SessionTarget.Bundle`** — a [portable preview bundle](../portable-bundles.md)
  the server should **fetch and start a session over** before streaming. Connects
  to the (forward-looking) `/ws/bundle?src=<url>` entrypoint. Because a bundle is
  self-contained (manifest + classpath + baked frames), a link can point at one
  sitting on any reachable host and "a server somewhere" spins it up on demand —
  the client never needs the project checked out. A host-less
  `composeai://open?bundle=…` link is paired with the app's configured server via
  `SessionLink.forBundle(...)`.

## Discovery (mDNS / DNS-SD)

`compose-preview serve --lan` advertises itself on the local network as
`_composeai._tcp` (`ServeMdnsAdvertiser`, jmdns). The apps browse for it with
Android's `NsdManager` (`NsdSessionDiscovery`) and list nearby servers on the
connect screen, so you can pick one without typing a URL.

The advertisement carries the module label, preview ids, and a TLS flag in TXT
records — **but never the token**. A broadcast token would defeat the gate, so a
discovered server is still opened with a token you supply (the shared link / QR).
The service type + TXT keys are the shared contract in
`DiscoveredSession` (`:clients:core`), mirrored by `ServeMdnsAdvertiser`
(`:cli`).

## Input forwarding

Touches on the frame are mapped from view-local pixels to the frame's
image-natural pixel space (`InputEvent.scalePointer`, accounting for
letterbox-fit) — the same coordinate contract the daemon's renderer and the VS
Code panel use ([daemon/INTERACTIVE.md](../daemon/INTERACTIVE.md) §6–7). A tap is
a `click`; a drag is `pointerDown`/`pointerMove`/`pointerUp`; the watch's rotary
bezel is a `rotaryScroll`.

## Status

The clients are complete against the serve **frame** lane (connect, paint,
dedup, reconnect) today. Two pieces depend on follow-on server work, and are
built to slot in without a client change:

1. **Pointer/key input on the serve lane.** The clients already send `input`
   messages in the daemon's shape; the serve lane accepts `setOverrides` /
   `requestFrame` today and gains `input` in the documented follow-on to
   PR #1989.
2. **The `/ws/bundle?src=…` entrypoint.** The bundle target is modelled and
   addressable client-side; the server route that fetches + starts a bundle is
   the matching follow-on.
