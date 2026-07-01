# Local render accelerator (offload live previews to your own machine)

A shared preview host (`preview.coo.ee`) is a **tiny box**: it serves the
catalog UI, baked PNGs, and data-only tiers, but it deliberately refuses
server-side re-render of Android/Compose source — the desktop-only public image
has no Android toolchain, and building a catalog's Gradle is code execution
(RCE), so `--allow-render-trusted` and `--revisions` stay **off** there (see
[`../public-preview-server.md`](../public-preview-server.md)).

This design lets a user who is already running `compose-preview serve` on their
own machine **offload live previews to it** — the hosted page stays the
shareable UI, but every live re-render (device/orientation/theme overrides,
streamed animation frames, interactive input) runs on the user's box, where the
source and toolchain already live. The tiny server does zero render CPU while an
accelerator is attached.

The model is [Perfetto's local
accelerator](https://perfetto.dev/docs/visualization/large-traces): its hosted
UI (`ui.perfetto.dev`) normally parses traces in an in-browser WebAssembly
`trace_processor`, but offloads to a native `trace_processor server http` on
`127.0.0.1:9001` when one is present. The UI probes a fixed loopback port,
CORS-allowlists exactly its own origin, and prompts the user before switching.
We copy that shape — with **one addition**, because what we offload is not data
but *"build and run this module,"* which is the very RCE lever the public box
withholds. So the accelerator gates each new target behind an **out-of-band
human accept**, not just an origin allowlist.

## Goals

- **Offload the heavy render** (Robolectric / Skiko via the daemon) from the
  shared host to a `compose-preview serve` running on the user's own machine.
- **Reuse the existing protocol.** The accelerator *is* `compose-preview serve`;
  the browser already speaks `WS /ws/{id}` + `ServeStreamProtocol` to it. No new
  wire format, no new render path.
- **Never let a web page silently run code on the accelerator.** Building a
  module / checking out a ref requires an explicit accept on a channel the
  requesting page cannot touch.
- **Graceful fallback.** With no accelerator (or on decline / drop), the page
  behaves exactly as today: baked PNGs and whatever the host renders.

## Non-goals

- **Remote (cross-machine) offload.** This design is loopback-only: the browser
  and the accelerator are on the same machine, so `http://127.0.0.1` works. A
  reverse-tunnel variant (share a link to a colleague; browser on a phone) is
  sketched in [Future work](#future-work) but out of scope here.
- **Replacing the trust model.** The accelerator keeps the existing fail-closed
  floor (origin allowlist, own-repo, `--revisions-allow`); the out-of-band
  accept is *added on top*, not a substitute.

## Why this maps almost 1:1 onto `serve`

The lucky part: the accelerator already exists. The exact protocol the browser
speaks is implemented on both ends today.

- The viewer client builds its live connection as
  `ws(s)://{location.host}{base}/ws/{id}` —
  [`cli/.../serve/ServeWeb.kt:634`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeWeb.kt).
- Server side: `WS /ws/{id}`, `GET /api/previews`, `GET /render/{id}.png`,
  ungated machine-readable `GET /version` (carries `serveSchema`) —
  [`ServeHttpServer.kt`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeHttpServer.kt),
  wire format in
  [`ServeStreamProtocol.kt`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeStreamProtocol.kt),
  render path
  [`ServeRenderHost.kt`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeRenderHost.kt)
  → `RenderSession` → Robolectric / Skiko.

Today `location.host` hard-pins every render to whichever origin served the page
(the tiny host). The offload is simply: let the page **swap the render base**
from `location.host` to `127.0.0.1:<accelerator-port>` once a compatible,
consenting accelerator is found.

## The two paths

The plain "probe and swap" path is safe *only* for tiers that execute no code
(baked PNG, Remote Compose, Protolayout/Lottie, the CMP-Wasm sandbox). For those
there is nothing to consent to — they can offload on discovery alone.

Anything that **runs Kotlin/Gradle** (Compose Android via Robolectric, CMP
desktop via Skiko, a `--revisions` checkout) must go through negotiation. That
is the primary flow below; the plain-swap path is its no-code-execution
fallback.

## Primary flow: negotiate + out-of-band accept

The negotiation turns the untrusted web origin into a **proposer** and the
trusted local operator into the **disposer**, over a separate channel.

### 1. Discover

On load the page fetches `http://127.0.0.1:8723/version` (fixed, well-known
port — the current `serve` auto-picks the next free port on conflict, so the
accelerator preset must **pin** the port; the page probes a small range as
fallback). The response advertises:

```json
{ "schema": "compose-preview-serve/version/v1", "version": "0.16.5",
  "serveSchema": "compose-preview-serve/v1",
  "accelerator": true, "consent": "out-of-band",
  "repo": "yschimke/compose-ai-tools", "refsAllowed": ["main"] }
```

It advertises what it *could* run (its checkout + allowed refs), **not** a list
of live sessions. Nothing is built yet. The page checks `serveSchema`
compatibility before offering to switch.

### 2. Propose

The page `POST`s a new endpoint `/accelerator/sessions` naming the target:

```json
{ "origin": "https://preview.coo.ee", "repo": "yschimke/compose-ai-tools",
  "ref": "main", "module": ":app:their-module",
  "previewId": "forms__button", "nonce": "…" }
```

The server validates against the **fail-closed floor** — origin allow-listed,
`repo` == its own checkout, `ref` ∈ `--revisions-allow` — then creates a
**pending grant** with a short-lived `requestId` and a **pairing code**, and
returns without building:

```json
{ "requestId": "…", "status": "pending", "code": "4821-9930" }
```

Pending requests are cheap (just a code), so unsolicited requests can't DoS the
operator into an expensive build.

### 3. Accept out of band — with number matching

The accelerator prints to **its own terminal** (the trusted channel the page
can't reach):

```
preview.coo.ee wants to render :app:their-module @ main
Match this code shown in your browser:  4821-9930
Approve? [y/N]
```

The browser shows the same `4821-9930`. This number-matching (the `gh auth`
device-flow / WebAuthn pattern) is the robustness move: a blind page that merely
**guessed** the loopback port can send a request but can't display the code the
operator sees, so a mismatch is rejected. Plain `y/N` already helps; the
number-match is the version to ship.

### 4. Await

The browser polls `GET /accelerator/sessions/{requestId}` (or holds a WS) until
`approved` (with a `sessionId`), `denied`, or `expired`. The expensive Gradle
build + daemon spin-up happens **now, post-consent**.

### 5. Render

The browser opens the normal `WS /ws/{id}` against the approved `sessionId` and
streams frames exactly as today. Overrides / slider drags on that session need
**no re-prompt** — only a *different* module or ref starts a fresh negotiation.
The grant is least-privilege: bound to `(origin, repo, ref, module)`.

### 6. Revoke / expiry

Grants idle-expire and are revocable from the terminal (Ctrl-C, or a `revoke`
line). The trusted side stays in control throughout.

### Sequence

```
Browser (preview.coo.ee page)        Accelerator (compose-preview serve, 127.0.0.1:8723)   Operator (terminal)
        |                                     |                                              |
        |-- GET /version -------------------->|                                              |
        |<-- accelerator:true, consent -------|                                              |
        |-- POST /accelerator/sessions ------>| validate floor (origin/repo/ref)             |
        |   {module,ref,nonce}                | create pending grant + code 4821-9930        |
        |<-- 202 pending, code:4821-9930 -----|                                              |
        | show code 4821-9930                 |-- print "…code 4821-9930  Approve?" -------->|
        |-- GET .../{requestId} (poll) ------>|                                    <-- y ----|
        |                                     | build module @ ref, open session             |
        |<-- approved, sessionId -------------|                                              |
        |-- WS /ws/{sessionId} -------------->| stream frames (render on this machine)        |
        |<== frames ==========================|                                              |
```

## Security properties

| Threat | Mitigation |
|---|---|
| Rogue website probes `127.0.0.1:8723` | CORS `Access-Control-Allow-Origin` allowlist (never `*`); `Origin` validated on the WS upgrade (403 otherwise). Blind probe also can't complete number-matching. |
| Chrome Private Network Access preflight | Accelerator answers `OPTIONS` with `Access-Control-Allow-Private-Network: true`. |
| DNS-rebinding to loopback | Validate `Origin`/`Host` on every request against the allowlist. |
| **Compromised / XSS'd allow-listed origin** (or a malicious catalog `livePreview` deep-link) silently triggers a build | **Out-of-band accept** — acceptance happens in the operator's terminal, a channel the requester can't forge. Origin allowlist alone would not stop this. |
| Unsolicited requests DoS the operator into building | Build starts **only after** accept; pending grants are cheap; rate-limit proposals. |
| Over-broad grant | Grant is bound to `(origin, repo, ref, module)`; a different target re-prompts. Existing `--revisions-allow` + own-repo floor still applies even to an approved request. |

Two independent, fail-closed gates: the **origin allowlist floor** stops rogue
sites; the **out-of-band accept** stops even an allow-listed-but-compromised
origin from auto-running Gradle. This is what makes it safe to do on the user's
machine what the public box refuses to do.

## Consent scope (the one knob)

Prompting on *every* new target is safest but nags. Default: **remember approved
`(origin, target)` tuples for the accelerator process lifetime** — only a
genuinely new tuple re-prompts; overrides on an approved session never do.
Escape hatches at both ends:

- `--accelerator-trust-origin https://preview.coo.ee` — pre-approve an origin
  for any target still within the allowlist floor (zero-prompt, startup-time
  opt-in for flow over friction).
- **Headless / CI accelerators** have no TTY, so the accept channel is
  pluggable: **TTY number-match (default)** | a printed local approve-URL | a
  pre-shared `--accelerator-token` (for automation; explicitly the least-safe
  option).

## Implementation sketch

Small, because the protocol already exists on both ends.

1. **`compose-preview accelerator` subcommand** — a thin preset over `serve`
   that **pins** `--host 127.0.0.1 --port 8723`, sets the default
   `--allow-origin https://preview.coo.ee` (+ `http://localhost:*` for dev), and
   wires the negotiation gate to `--allow-render-trusted` / `--revisions` so
   they become *per-target, post-consent* rather than launch-time all-or-nothing.
   (`ServeCommand.kt`.)
2. **CORS + PNA + WS-origin validation** on `ServeHttpServer.kt`: `--allow-origin`
   flag; emit `Access-Control-Allow-Origin` for allow-listed origins only; answer
   the `OPTIONS` preflight incl. `Access-Control-Allow-Private-Network: true`;
   validate `Origin` on the `/ws/{id}` upgrade.
3. **`/version` advertisement**: add `accelerator`, `consent`, `repo`,
   `refsAllowed` fields (the compat-check + discovery surface).
4. **Negotiation endpoints**: `POST /accelerator/sessions` (propose → pending +
   code), `GET /accelerator/sessions/{requestId}` (await), plus the terminal
   number-match prompt on the trusted side. Grants keyed by
   `(origin, repo, ref, module)`, idle-expiring, revocable.
5. **Frontend probe + rewire** in the viewer JS emitted by `ServeWeb.kt`: on
   load, `fetch('http://127.0.0.1:8723/version')` with a short timeout + schema
   check; if compatible, run the negotiation and, on approval, compute the
   live-render base from the accelerator origin instead of `location.host` at
   [`ServeWeb.kt:634`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeWeb.kt)
   (and the `/render/{id}.png` / `/api/previews` calls). Remember the choice in
   `localStorage`. Baked PNGs from the host remain the instant fallback if the
   accelerator drops.

No new render backend, no new wire format — CORS/consent plumbing plus a probe.

## Future work

- **Remote / shared offload.** For browser and accelerator on *different*
  machines, loopback can't reach. The accelerator would dial *out* and register
  a reverse tunnel through the host, which then relays frames — more moving
  parts, an auth/pairing story, and some relay load returns to the host. Ship
  loopback first; it covers the actual ask ("don't load the tiny server while I
  iterate on my own machine").
- **mDNS/service discovery** instead of a fixed-port probe, if multiple
  accelerators or non-default ports become common.
</content>
</invoke>
