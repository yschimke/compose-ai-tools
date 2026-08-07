# Playground — a Kotlin/Compose editor over the preview server

Status: **design proposal** (2026-07). Product analysis + architecture for a
hosted "edit Compose, get a live interactive preview" surface, built on the
seams `compose-preview serve` already ships. This document scopes what exists,
what's missing, the REST + handoff contract, the isolation requirements, and a
phased plan. Phases 1–3 (CMP, Android, Remote Compose) and the Phase-4
per-session sandbox ([§6](#6-isolation--the-actual-hard-part)) are built; the
playground remains token-gated by default, and serves under `--public` only on a
sandbox that has passed the startup containment probe. The [§8](#8-open-questions--resolved)
questions are resolved.

> **Thesis.** A Compose playground is mostly the *existing* serve viewer plus an
> editor pane plus an ephemeral, per-session module. The three things that would
> normally be the hard parts — **compiling Kotlin without Gradle**, **rendering a
> preview headlessly**, and **streaming a live, clickable composition** — already
> exist and run in production (`BtaCompileSession`, the desktop/Robolectric
> daemons, the serve `input` WebSocket protocol). The genuinely new work is
> **(1)** a two-stage *compile-then-permalink* flow that keeps a stock one-shot
> editor frontend usable, **(2)** an expiring **preview-token** capability that
> hands a compiled snippet off to a live session, and **(3)** the isolation a
> host needs before it can run a stranger's code. Everything else is wiring seams
> that already exist.

---

## 1. Product analysis

### 1.1 The user story

Someone — a docs author, an agent, a person kicking the tyres on Material 3 —
wants to:

1. Write a Compose snippet in the browser, against **our** component catalogs
   (`compose-m3`, `wear-m3`, …) so `import`s resolve to the real library.
2. Compile it and see errors inline, fast.
3. See it **render**, and then **interact** with it — click a button, watch state
   change, scroll a list — not just look at a static screenshot.
4. Share a **link** to that running preview.

…across three render modes:

| Mode | What it is |
|---|---|
| **Compose (CMP)** | Compose Multiplatform, rendered by the desktop (Skiko) daemon. |
| **Compose (Android)** | Jetpack Compose, rendered by the Robolectric daemon. |
| **Remote Compose** | The snippet emits a `RemoteDocument` byte stream; the browser plays it. Android-authored today, CMP-authored later. |

### 1.2 Prior art

| Tool | What it is | Why it isn't the fit |
|---|---|---|
| **play.kotlinlang.org** (`kotlin-playground` + `kotlin-compiler-server`) | The canonical hosted Kotlin editor. Apache-2.0, both halves. `compose-wasm` target renders Compose **in the browser** via Kotlin/Wasm. | JVM/Wasm only. No Compose **Android** (no Robolectric), no **Remote Compose**, no server-driven **live render** of a JVM composition. Its model is one-shot `POST code → get result`; it has no streamed, clickable session. |
| **Compose Playground / KMP web samples** | Prebuilt CMP-Wasm apps embedded in a page. | Fixed apps; they don't compile *new* snippets. This repo already ships one (`:samples:cmp-wasm-catalog`, served at `/wasm/<system>/`). |
| **This repo's `serve` host** | Trusted catalogs, live daemon-backed sessions, an `input` protocol, `--accept-docs` document permalinks. | Everything except the **editor** and the **compile-arbitrary-source** entry point. That's the gap this doc closes. |

**Verdict.** `kotlin-playground`'s Wasm path already covers "Compose in the
browser, no server." What it structurally cannot do is render the Android
runtime, or a JVM composition on a real Compose renderer, or a Remote Compose
document — which is exactly the ground this repo's daemons already own. So the
build is *not* "reimplement play.kotlinlang.org"; it's "put an editor in front of
the renderer we already run."

### 1.3 Why not just point `kotlin-playground`'s frontend at us (`data-server`)

`kotlin-playground` supports a `data-server` attribute to redirect compilation at
a custom backend — that's a first-class, documented feature, and its backend
contract is small and known (see [§4](#4-the-rest-contract-stock-frontend-compatibility)).
But its whole UI model is **one request in, one result out**: `POST` the code,
render `{text | jsCode | testResults}` inline. Ours is **open a session, stream
frames, send input** — a different transport, not a new result type. Bolting a
persistent clickable canvas into a framework built around one-shot POSTs fights
the framework the whole way.

The **two-stage** decomposition below dissolves that conflict, and in doing so
shrinks the frontend change from *rewrite the transport* to *surface one field in
the response*. That is what makes reusing a stock editor viable again.

---

## 2. Architecture: two stages, one compile

The insight is to **not** make the editor host the interactive preview. Split the
work where the models already split:

```
┌──────────────────────────────────────────────────────────────────────┐
│ STAGE 1 — Playground (one-shot, the editor's native model)            │
│                                                                        │
│  editor ──POST source──▶ /api/{v}/compiler/run                         │
│                            │  BtaCompileSession.compile (Compose plugin)│
│                            │  one headless render (first frame)         │
│                            ▼                                            │
│  { diagnostics, image: data-URI PNG, previewToken: "pg_…" }           │
│                                                                        │
│  editor shows errors + the still frame + an "Open live preview →" link │
└───────────────────────────────────────────┬────────────────────────────┘
                                             │ user clicks (deliberate)
                                             ▼
┌──────────────────────────────────────────────────────────────────────┐
│ STAGE 2 — Preview (the serve viewer we already ship)                  │
│                                                                        │
│  GET /pg/<token>  ── redeem ──▶ stand up / resume a live daemon session│
│      viewer + WS  ◀── frames ──   (desktop or Robolectric)             │
│      input events ──▶ dispatched into the composition (existing proto) │
└──────────────────────────────────────────────────────────────────────┘
```

Two properties fall out of this split:

- **The compile that produces the Stage-1 result is the same compile that would
  bootstrap the Stage-2 session.** So the permalink is nearly free: the `run`
  response just carries a `previewToken`. A stock `kotlin-playground` frontend
  ignores the unknown field; a one-line frontend addition turns it into a button.
  (Zero-fork fallback: put the URL in the `text` output field — ugly, but works
  with the unmodified component.)
- **Session creation is a *redeemed* act, not a per-keystroke one.** Most compiles
  never get a click, so Stage 1 does **not** stand up a streaming session — it
  returns a token. Redeeming `/pg/<token>` is the deliberate, rate-limitable,
  TTL'd step. This is the same capability model `--accept-docs` already uses for
  documents (see [§5](#5-the-preview-token-capability)).

### 2.1 What already exists (and where)

| Capability | Where | Status |
|---|---|---|
| Compile Kotlin, no Gradle, with the Compose plugin + `sourceInformation` | `daemon/core/.../bta/BtaCompileSession.kt`, driven by `JsonRpcServer.compileSources` | **Shipped.** Incremental, content-hashed classpath snapshots, structured diagnostics (`DiagnosticCollector`). |
| Compile arbitrary absolute source paths against a supplied classpath | `compileSources` takes `sources: List<Path>` with no source-set containment | **Shipped.** A snippet written to a temp file is a valid input today. |
| Headless render → PNG (both backends) | `renderer-desktop` (Skiko), `renderer-android` (Robolectric) | **Shipped.** |
| Live, daemon-backed streaming session | `cli/.../serve/ServeLiveSession.kt`, `ServeStreamSession.kt`, per-preview pool | **Shipped.** `compose-m3` (desktop) and `wear-m3` (Android) run live on `preview.coo.ee`. |
| Interactive `input` protocol (click / pointer / rotary / key) | `docs/serve/SESSION-VIEWER-PROTOCOL.md` §`input`, `ServeStreamProtocol.parseClient` | **Shipped.** Dispatched into the live composition; snapshot lane ignores it. |
| Expiring capability permalink (id = 128-bit `SecureRandom`, TTL, `private,no-store`) | `cli/.../serve/ServeDocStore.kt` | **Shipped.** The literal template for the preview-token store. |
| Remote Compose document → browser playback | `--accept-docs`, `ServeDocFormats`, vendored `RcdPlayer` (`cli/src/main/resources/rc-player/`) | **Shipped.** RC-in-browser needs no server round-trip per interaction. |
| Live-seat admission budget (desktop = 1 permit, Android = 2) | `cli/.../serve/LiveSeatLimiter.kt` | **Shipped**, but sized for *trusted catalogs* — see [§6](#6-isolation-the-actual-hard-part). |

### 2.2 What is new

1. **A `PlaygroundSession`** — an ephemeral, per-token module: a temp source dir,
   a resolved compile classpath (borrowed from a chosen catalog's live bundle),
   an output dir, and a daemon handle. Conceptually a bundle-backed session
   (`ServeBundleDaemon`) whose classes come from a just-compiled snippet instead
   of a fetched `.bundle`.
2. **The REST shim** — enough of the `kotlin-compiler-server` contract that a
   stock editor frontend talks to us ([§4](#4-the-rest-contract-stock-frontend-compatibility)).
3. **The preview-token store + `/pg/<token>` redeem route** ([§5](#5-the-preview-token-capability)).
4. **Isolation** — the part that gates going public ([§6](#6-isolation-the-actual-hard-part)).

---

## 3. The three modes → three permalink targets

The modes differ only in Stage 2 — what the token redeems to:

| Mode | Compile → | Stage-2 target | Per-interaction cost | Seat cost |
|---|---|---|---|---|
| **Compose (CMP)** | desktop daemon, `ImageComposeScene` | **live streaming session** | server re-render per event | 1 permit |
| **Compose (Android)** | Robolectric daemon | **live streaming session** | server re-render per event | 2 permits |
| **Remote Compose** | compile+run → `.rc` document | **document permalink** (`/d/<id>`) | **none** — played client-side | **0** (no daemon) |

**Remote Compose is the architectural standout, and should ship to any
public audience first.** An `.rc` document carries its own state machine
(`data/remotecompose/connector`: `RemoteOverridableState`,
`RemoteComposeController`), so once it reaches the browser the server is out of
the loop — no live seat, no re-render, no round-trip. The two JVM modes each pin
a live daemon for the session's lifetime; RC hands over bytes and lets go. Its
serving side is already public-safe (the vendored player runs in the *viewer's*
browser); only *producing* the document runs snippet code on the server, and that
is the same isolation problem all three share.

**On "CMP Remote Compose in future":** separate *authoring* from *playback*. The
browser `RcdPlayer` is already platform-neutral — it plays a byte stream and does
not care that the document was authored through Android APIs. So RC-in-browser
works for a playground **now**; what is Android-bound is the *authoring* side of
`data/remotecompose`. The playground's RC mode does not have to wait on
CMP-authored Remote Compose — it can offer Android authoring today and pick up
CMP authoring when the connector does. (There is also an AndroidX
Compose-embedded player, `:third-party-rc-embedded-player`, used for CI fidelity
diffs; the browser player is the one the playground uses.)

---

## 4. The REST contract (stock-frontend compatibility)

To let an unmodified `kotlin-playground` component talk to us, we implement the
subset of the `kotlin-compiler-server` contract its frontend actually calls. The
frontend (`src/config.js`) builds **versioned** paths — `/api/{version}/compiler/…`
— even though the upstream controller is mounted at unversioned `/api/compiler/…`;
we mount the shim at the versioned path the frontend emits.

| Path (as emitted by the frontend) | Method | We answer it with |
|---|---|---|
| `/api/{version}/compiler/run` | POST | BTA compile → diagnostics + first-frame PNG (`data:` URI) + `previewToken`. |
| `/api/{version}/compiler/highlight` | POST | BTA diagnostics reshaped to the highlight schema (we compile anyway). |
| `/versions` | GET | The single Kotlin/Compose version this host offers. |
| `/api/{version}/compiler/complete` | POST | **Deferred.** BTA compiles; it does not complete. Returns `[]` in v1. Real completion needs a Kotlin analysis backend — out of scope until there's demand. |

Request body is `{ args, files: [{ name, text, publicId }], confType }`; the
`confType` selects the mode ([§3](#3-the-three-modes--three-permalink-targets)).

The response carries the diagnostics in **both** shapes so neither client is
second-class. Our own frontend reads a flat `diagnostics` list (`PlaygroundDiagnostic`);
a stock `kotlin-playground` frontend reads `errors` — and that field is **not** a
renamed flat list, it's upstream's **map keyed by file name** whose entries nest
their position under `interval: { start, end }`, with an uppercase `severity`. So
`errors` is a genuine *projection* of the same diagnostics (`PlaygroundErrorsWire`),
not an alias — a straight `diagnostics` → `errors` rename would look compatible
while rendering nothing in the stock editor. On top of that the response adds two
fields the upstream frontend ignores and ours surfaces: `image` (first-frame
`data:` PNG) and `previewToken`.

> **On reusing the frontend at all.** Implementing this contract is what keeps a
> stock editor an option; it is not a commitment to ship theirs. `kotlin-playground`
> is itself a CodeMirror wrapper, and a bespoke editor (CodeMirror/Monaco + our
> own response shape, no contract to track) may win once completion and
> mode-specific UI matter. The contract is cheap enough to keep the door open;
> the decision of *which* editor ships is deferred and does not block Stage 1.

### 4.1 The editor — **CodeMirror 5, ours, vendored**

That deferred decision is now made: we ship **our own page with CodeMirror 5**,
not the stock `kotlin-playground` component. The REST contract above stays
implemented, so a stock frontend remains possible — but it is not what serves.

**CodeMirror 5 rather than 6** for a build reason, not a preference: the serve
assets are plain files served out of the CLI jar, and there is no JS bundler
anywhere in that build. CM6 is ESM-only and must be bundled; CM5 is a drop-in
`<script>`. Kotlin highlighting rides CodeMirror's existing `clike` mode
(`text/x-kotlin`), so no extra grammar ships.

**Vendored, not CDN-loaded.** This is a public preview server and the playground
is its code-running surface; an external script would add a third-party
dependency to exactly that surface and disclose visitors to its origin. The
asset pipeline content-hashes and immutably caches, so a local copy costs one
cold fetch per release — 429 KB of source, 114 KB on the wire once the host's
text lanes are gzipped, and only on `/playground`, never on the catalog pages.

The editor **degrades to the plain textarea** when the bundle doesn't load: every
buffer read/write goes through `readSource()`/`writeSource()`, so a failed asset
fetch costs highlighting, not the ability to compile.

Two keyboard details are deliberate. `Tab` **indents** rather than moving focus —
but a code box that swallows `Tab` is a keyboard trap, so `Esc` blurs first, the
standard escape hatch (WCAG 2.1.2). `Ctrl`/`Cmd`+`Enter` runs.

![Playground editor — a compiled snippet with its first frame and live-preview link](../images/serve-playground-editor-light.png)

![Playground editor (dark) — a failed compile with file-qualified diagnostics](../images/serve-playground-editor-dark.png)

Still **not** offered: completion. `/api/{version}/compiler/complete` stays the
deferred stub of §4 — BTA compiles, it does not analyse, and real completion
needs a stateful Kotlin analysis session per user, which is a different cost
model from the disposable-child-per-snippet property the rest of the lane keeps.

---

## 5. The preview-token capability

Modelled directly on `ServeDocStore` — the same safety properties, a different
payload:

- **Id is the capability.** 128-bit `SecureRandom`, base64url, prefixed `pg_`.
  Unguessable; a link is safe to hand to one person without listing it.
- **Expiring.** A short TTL (minutes). After expiry `/pg/<token>` 404s without
  disclosing whether the token ever existed.
- **Bounded.** Count + total-memory caps; a burst evicts nearest-expiry first, as
  `ServeDocStore` does. A token holds a temp source dir + compiled classes, so the
  cap also bounds disk.
- **Single redemption target.** A token names *one* compiled snippet and its mode.
  Redeeming stands up (or resumes) exactly that session.

A token is minted in Stage 1 *after* a clean compile (a compile error returns
diagnostics and **no** token). The Stage-1 render and the Stage-2 session share
the compiled output, so redemption never recompiles.

---

## 6. Isolation — the actual hard part

Nothing above changes the one constraint the serve host is built around, stated
in [`docs/public-preview-server.md`](../public-preview-server.md): *"Neither ever
lets untrusted code run on the server."* Trust is fail-closed; `--revisions`
(which runs Gradle) is off on public boxes; unverified bundles serve as baked
PNGs with no re-render. **A playground inverts that premise** — its whole point is
to execute code a stranger wrote.

Concretely: `BtaCompileSession` compiles **in-process**, and a live session loads
the result into a long-lived daemon JVM. On JDK 17+ there is no `SecurityManager`;
a snippet gets filesystem, sockets, `System.exit`, process spawn, and unbounded
heap, in a JVM other sessions may share. **RC mode does not dodge this** — its
*serving* side is safe, but *producing* the `.rc` still runs the snippet on the
server.

`--live-seats` (`LiveSeatLimiter`) is **admission control, not a sandbox** — it
bounds concurrent daemons for *trusted* catalogs; it does not contain hostile
code. A public playground therefore needs a genuinely disposable per-session
sandbox:

- its own process/container, **killed** after a hard wall-clock TTL;
- **no outbound network** (or a strict egress allowlist);
- **read-only / ephemeral** filesystem, quota-bounded;
- **memory + CPU caps** (cgroup), so one snippet can't starve the box;
- **one snippet per JVM** — never hot-swap a stranger's classes into a shared,
  long-lived daemon.

### 6.1 What is built (Phase 4, `--playground-sandbox`)

Half the list was already true and unremarked: every playground lane — the
Stage-1 first frame, the RC capture, and the Stage-2 live session — spawns a
**fresh daemon subprocess over that snippet's own classes**
(`SubprocessRenderSessions.openBundleDaemon`, `ServeBundleDaemon.materializePlaygroundSnippet`).
A stranger's classes are never hot-swapped into a shared, long-lived daemon;
"one snippet per JVM" is the shape the code already had. What Phase 4 adds is
the *containment around that child*, plus the evidence that it works.

[`PlaygroundSandbox`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/PlaygroundSandbox.kt)
is a pure policy type that produces three things for each snippet JVM:

| Requirement | How |
|---|---|
| Own process, killed at a hard TTL | Already one JVM per snippet; `hardTtlSeconds` rides the daemon descriptor and the spawner arms a `destroyForcibly` watchdog — no cooperation needed from a wedged snippet. |
| No outbound network | The jail's network namespace: `bwrap --unshare-net` or `unshare --net`. |
| Read-only / ephemeral FS | `bwrap` binds the host read-only with a tmpfs `/tmp`, and the snippet's work dir is the **only** writable path — and that dir is deleted when its preview token drops. |
| Memory + CPU caps | cgroup (`MemoryMax` / `CPUQuota` / `TasksMax`) on the `systemd`/`strict` profiles, **plus** JVM-level caps on every active profile (`-Xmx`, `-XX:ActiveProcessorCount`, `-XX:+ExitOnOutOfMemoryError`). |
| One snippet per JVM | Structural: each lane opens its own subprocess daemon over one snippet's classes. |
| The compile, too | With a sandbox configured, `kotlinc` runs in the same jail — see [§6.3](#63-the-compile-is-jailed-too). |

Profiles, chosen with `--playground-sandbox`:

| Profile | Jail | Egress | FS | cgroup caps | `--public` |
|---|---|---|---|---|---|
| `none` (default) | — | — | — | — | refused |
| `unshare` | `unshare(1)` user+net+pid ns | ✓ | ✗ (host FS visible) | ✗ | refused |
| `bwrap` | `bwrap(1)`, cleared env | ✓ | ✓ | ✗ (JVM caps only) | refused |
| `systemd` | `systemd-run --scope` | ✗ | ✗ | ✓ | refused |
| **`strict`** | `systemd-run --scope … bwrap …` | ✓ | ✓ | ✓ | **eligible** |
| `custom:<argv>` | operator-supplied | ? | ? | operator's job | eligible on a clean probe |

A transient **scope** takes cgroup resource properties but *not* service exec-context
settings (`PrivateNetwork`, `PrivateTmp`, `ProtectSystem`, `NoNewPrivileges`) — passing
those to `--scope` fails unit creation outright. So `systemd` owns resource control alone
and delegates isolation to `bwrap`; `strict` is the composition, and the only built-in a
`--public` host can run.

Knobs: `--playground-sandbox-memory-mb`, `--playground-sandbox-cpus`,
`--playground-sandbox-pids`, `--playground-sandbox-ttl`, and
`--playground-sandbox-ro <path>[,…]` for caches a render legitimately reads with
no network to fetch them (the Robolectric `android-all` cache, the downloadable
font cache — **prewarm these before going public**; a cold Robolectric inside an
empty netns cannot fetch its `android-all` jar).

### 6.2 The gate is a probe, not a flag

The `--public` refusal has *not* become "you passed `--playground-sandbox`, off
you go". A profile name is a claim: `bwrap` on a kernel with user namespaces
disabled, or a `custom:` wrapper with a typo in it, both claim everything and
contain nothing. So at startup a `--public` host runs
[`PlaygroundSandboxProbe`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/PlaygroundSandboxProbe.kt):
a throwaway JVM launched **through the same jail argv a snippet gets**, which
measures and reports back

- **egress blocked** — every connect to a routable address fails;
- **filesystem contained** — a canary the host wrote outside the jail's bind set
  is invisible;
- **process isolated** — the serve host's own pid is absent from `/proc`;
- **work dir writable** — the jail isn't so tight that a render couldn't run.

`PlaygroundPublicGate` admits the lane only on a clean report, and fails closed
in every other direction: no profile, no report, a jail that wouldn't launch, or
any single failing check all keep the playground disabled with an actionable
log. That also means `unshare` — which blocks egress but leaves the host
filesystem visible — is a fine local rehearsal profile and is **refused** under
`--public`, by measurement rather than by a hard-coded list.

One property the probe *cannot* measure is resource capping: a snippet inside a
perfectly sealed `bwrap` can still spin CPU-bound threads or fork until the box
starves, and `-Xmx` bounds only heap while `-XX:ActiveProcessorCount` merely
sizes JVM pools. So admission has a second condition beyond the probe — the
profile must actually carry cgroup caps. `unshare` and `bwrap` are therefore
refused under `--public` and pointed at `strict`; a `custom:` jail is taken at
its word on caps (they're the operator's to supply, and the startup log says so)
but must still pass the probe.

#### A jail that cannot launch is dropped, not obeyed

The probe answers a second question the gate does not: **can this jail launch on
this host at all?** A configured `unshare` under a kernel/AppArmor policy that
forbids unprivileged user namespaces, or a `bwrap` that isn't installed, does not
merely fail to contain — it fails to *start*, and every snippet JVM and every
jailed compile launches behind that same argv.

Left alone, that is a silent breakage of the worst kind. The gate admits the lane
(on repo access, or because the host is token-gated), `/playground` answers
normally, `status` reports `ok` — and every compile fails to spawn. It reads to a
user as a playground that never renders anything, and to an operator as nothing
at all. It happened on `preview.coo.ee`: `SERVE_PLAYGROUND_SANDBOX=unshare` was
set, the lane came up looking healthy, and the only evidence was
`probe.ran: false` in `/status.json`.

So when the preflight reports that the jail could not launch, and the lane was
admitted on something *other* than containment, `serve` **drops the jail argv and
keeps every other cap** (`PlaygroundSandbox.droppingJail`). Snippets still run in
a disposable child under `-Xmx`, the CPU cap, `ExitOnOutOfMemoryError`, the
temp-dir confinement and the hard TTL — they are simply not contained.

That is better than either alternative — *for a profile whose caps are
JVM-level*. Keeping a jail that cannot launch preserves no isolation, because it
never ran. Falling back to `Profile.NONE` would also discard the caps, and on a
host with a large cgroup limit an uncapped snippet JVM sizes its default heap at
a *quarter of that limit*, which is the more dangerous of the two failures.

**`systemd` and `strict` are excluded, and refuse instead.** Their `MemoryMax` /
`CPUQuota` / `TasksMax` come from the `systemd-run` prefix — the very argv being
dropped — so degrading them would leave heap and JVM pool sizing with no
native-memory bound, no CPU quota and no pid cap. An operator who asked for
enforceable caps should not silently get unenforceable ones, so a cap-declaring
profile that cannot launch disables the lane with a message naming the choice:
fix the jail, or pick a profile (`bwrap`, `unshare`) whose caps are JVM-level
anyway.

It cannot rescue the posture where containment *is* the admission basis: an
anonymous `--public` host whose probe did not run is refused by the gate before
the fallback is reachable. The drop is reported in the startup log, in
`describe()`, and as `playground.sandbox.jailDropped` on `/status.json`.

#### The probe answers "is a *stranger's* snippet contained?" — sometimes nobody is asking

That whole chain is the right question only when a stranger can reach the lane.
All three playground surfaces — `/playground`, `POST /api/{v}/compiler/run`,
`/pg/{token}` — already run `rejectMissingGithubAuth` **and**
`rejectMissingGithubRepoAccess`, so on a host with GitHub auth configured the
snippet comes from someone with **write** access to `--github-auth-repo` (issue
#3313 tightened that check from "any access" to `admin`/`maintain`/`write`): a
collaborator who can already push to the repo whose CI builds this image, at the
same trust level as the token-gated posture Phases 1–3 shipped under, which the
gate admits with no sandbox at all.

So `PlaygroundPublicGate.decide` takes `repoAccessGated` as a second, independent
basis for admission (issue #3210):

| `--public` | repo-access-gated | Decision |
|---|---|---|
| no | — | **Allow** — token-gated; sandbox applied if configured |
| yes | yes | **Allow** — collaborators only; sandbox applied as defence in depth |
| yes | no | the probe + caps chain above; refused unless it comes back clean |
| yes | no, and no sandbox | **Refuse**, naming *both* remedies (issue #3214) |

The gate measures *who can reach the lane*, not just which flag the server was
started with. `Decision.Allow.detail` states which posture admitted it, so an
operator cannot read "admitted" and assume containment when what they configured
was sign-in (or vice versa — the contained-and-anonymous log line says
`ANONYMOUS` in as many words). When a jail is configured under the repo-access
posture and fails its preflight, the lane still serves — admission never rested
on the jail there — but `ServeCommand` logs a warning, because the operator asked
for defence in depth and is not getting it.

This is what makes the playground deployable on a container that cannot jail a
snippet at all: `preview.coo.ee` serves public catalogs, has no `bubblewrap` and
no systemd to build a `strict` scope against (issue #3211), and reaches the lane
through GitHub auth instead.

### 6.3 The compile is jailed too

`kotlinc` used to run **in the serve JVM**, which was never an
arbitrary-execution hole — compiling a snippet does not run it (no annotation
processors, no `init` blocks; only the Kotlin + Compose plugins we ship) — but it
was a **resource** one. The Kotlin compiler is the least predictable thing on the
request path: a pathological snippet can burn CPU and heap inside the host
process, where `-Xmx` is the operator's and no wall clock applies.

So with a sandbox configured, [`PlaygroundJailedCompiler`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/PlaygroundJailedCompiler.kt)
runs the compile in the **same jail** the render lanes use: the catalog classpath
and the `lib-bta/` toolchain bound read-only, the snippet's work dir the one
writable path, the sandbox's `-Xmx`/CPU caps applied, and a compile budget on
top. Parent and child speak the same one-JSON-line protocol as the preflight
probe — the child runs the *same* `PlaygroundBtaCompiler`, so a jailed compile
can't drift from an unjailed one — and every failure mode (jail won't launch,
compiler killed, unparseable output) comes back as an ordinary compile
diagnostic instead of an exception.

**What it costs, measured** (trivial snippet, this container, `bwrap`):

| | first compile | subsequent |
|---|---|---|
| in-process (`--playground-sandbox none`) | 3.8 s | **0.3–0.5 s** (warm `BtaCompileSession`) |
| jailed subprocess | 3.4 s | **3.3–3.5 s** (cold every time) |

The jail itself is free — a jailed cold compile is no slower than an unjailed
one. What costs ~3 s is losing the *warm* toolchain: a fresh JVM re-bootstraps
BTA per request. That is the deliberate v1 trade (a `--public` host takes
predictable seconds over an unbounded compiler in its own process), and it is
why `--playground-sandbox none` — the dev posture — keeps warm compiles and the
sub-second edit→pixel loop [§7.1](#71-latency-note) describes.

**Bounded, like the live lanes.** The in-process compiler serialized compiles
behind one `BtaCompileSession`, so concurrency was implicitly 1; a subprocess per
request removes that, and a per-process memory cap bounds one compile without
bounding the total. `--playground-compile-slots` (default 2) is the compile-side
counterpart to `--live-seats`: peak compile memory an operator budgets for is
`slots × --playground-sandbox-memory-mb`, and a request that finds every slot
taken is told the playground is busy rather than queueing behind an unbounded
fork. The compile budget is also clamped to `--playground-sandbox-ttl` whenever
that is tighter — shortening the sandbox deadline shortens *everything*
snippet-related, not just the render.

The obvious follow-up is a **warm jailed compile JVM per catalog classpath**,
recycled periodically. Worth noting *why* that's admissible where a warm render
JVM isn't: compiling doesn't execute the snippet, so a reused compile process
never runs a stranger's code — the "one snippet per JVM" rule exists for the
lanes that do.

---

## 7. Phased plan

Each phase is independently shippable and useful on its own.

1. **Phase 1 — token-gated, CMP, single snippet (in progress).**
   The `/api/{v}/compiler/run` shim → `BtaCompileSession` against `compose-m3`'s
   live-bundle classpath → first-frame PNG + `previewToken` → `/pg/<token>`
   redeems to the existing desktop live session with the existing `input`
   protocol. Gated (`--playground`, refused under `--public`). Almost entirely
   wiring over shipped parts; proves the loop feels right before any sandbox work.

2. **Phase 2 — the Android backend.** Same loop, Robolectric daemon, 2 permits.
   Compose Android snippets against `wear-m3` / an Android catalog classpath.

3. **Phase 3 — Remote Compose mode.** Compile+run → `.rc` → hand off to the
   existing `/d/<id>` document permalink and `RcdPlayer`. Reuses `ServeDocStore`,
   `ServeDocFormats`, `rc-player`. No live seat. **This is the first mode safe to
   expose to a wider (still gated) audience**, because its serving side already is.

4. **Phase 4 — per-session sandbox (shipped).** Namespace/cgroup isolation, no
   egress, read-only FS, memory/CPU caps, a hard wall-clock TTL enforced by kill,
   one snippet per JVM — configured with `--playground-sandbox` and gated on a
   startup probe that proves the jail contains a snippet before `--public` is
   allowed to serve it. See [§6.1](#61-what-is-built-phase-4---playground-sandbox)
   and [§6.2](#62-the-gate-is-a-probe-not-a-flag).

5. **Editor decision (settled).** We ship **our own** minimal editor — a
   dependency-free page with a mode selector, a file strip, and the result pane —
   *and* keep the stock `kotlin-playground` REST contract ([§4](#4-the-rest-contract-stock-frontend-compatibility))
   working, since honouring it costs little and keeps a stock frontend an option.
   Owning the page is what made per-mode UI (the three modes), the multi-file
   strip, and the "which preview did it draw" note straightforward; a
   CodeMirror/Monaco upgrade stays open for when completion matters.

### 7.1 Latency note

Do **not** size expectations from the stage-0 numbers in
[`docs/daemon/baseline-latency.md`](../daemon/baseline-latency.md) (≈9 s desktop
`render` cold) — those are per-process Gradle forks, not the daemon path. The
resident daemon renders at p50 ≈ 1.9 s warm (`/status.json` `renderStats`), and
the stage-2 in-process BTA compile targets < 1 s warm on desktop. The playground
rides the daemon path, so the warm edit→pixel loop is in that range, not the
cold-fork range.

---

## 8. Open questions — resolved

Each of these is now settled in code, with the test that pins it (issue #3017).

### 8.1 Multi-file snippets — **shipped**

A snippet is a **list of files compiled as one module**, not one buffer. Every
file in the `run` request is staged into the same temp source dir and handed to a
single `compileSources`, so files reference each other's declarations; the editor
grows a file strip (`+ file` / `Remove file`) and posts the whole list.

| One file (unchanged default) | Two files, cross-referencing |
|---|---|
| ![Playground editor with a single file](../images/serve-playground-single-file.png) | ![Playground editor with two files](../images/serve-playground-multifile-light.png) |

![Playground editor, two files, dark](../images/serve-playground-multifile-dark.png)

A diagnostic names the file it belongs to (`file:line`) and clicking it switches
the editor to that file — with several buffers open, "unresolved reference at
line 5" says nothing about where to look:

![A diagnostic naming its file, with that file selected](../images/serve-playground-multifile-diagnostic.png)

Pinned by `PlaygroundCompileServiceTest` ("every file in a multi-file snippet
reaches one compile"), the `serve-playground · multifile` state in the
preview-harness page snapshots, and a browser e2e that splits a snippet across
two files and compiles it on a real daemon.

### 8.2 `@Preview` discovery vs. an entrypoint — **discovery, deterministically**

Discovery won (it reuses `PlaygroundPreviewDiscoverer` and the render pipeline's
id shape). Multi-file made the follow-on question real: a snippet can now declare
several `@Preview`s while exactly **one** drives the first frame and the Stage-2
session. The orchestrator therefore picks the **lowest id in sorted order** — a
ClassGraph scan has no guaranteed order, and a preview that changed between two
runs of the same snippet would be baffling — and the response carries both
`previewId` (what was drawn) and `previews` (everything found), so the editor can
say which one it rendered instead of silently choosing.

![Playground result naming the rendered preview](../images/serve-playground-multifile-result.png)

### 8.3 Token TTL + redemption count — **many within the TTL**

Many, matching `ServeDocStore`: a redeemed token keeps working until it expires,
so refreshing the viewer tab doesn't burn the link. `PlaygroundRedeemServiceTest`
("a live token redeems to a registered session and re-redeem reuses it") pins
both halves — the second redemption reuses the session rather than standing up a
second one.

### 8.4 Classpath source per mode — **pinned by default, selectable at runtime**

v1 compiled against the catalog its mode was configured with
(`--playground-bundle` for CMP, `--playground-android-bundle` for Android/RC) and
nothing else. That is still the default, and it is still what a stock
`kotlin-playground` frontend gets. But on a box already serving twenty verified
catalogs, "try this snippet against a different design system" was an operator
edit plus a restart — for catalogs the host had *already* fetched, verified and
unpacked, each carrying the two things a compile needs: a `manifest.classpath` to
resolve and a `manifest.backend` to pick the renderer.

`--playground` (env `SERVE_PLAYGROUND=1`) moves that choice to the request.

| Flag | Lane | Selector |
|---|---|---|
| `--playground-bundle <path\|system>` | enabled, CMP pinned | offers the pin as **Server default** |
| `--playground` | enabled, nothing pinned | offers every served catalog |
| both | enabled, pin preselected | pin first, then every served catalog |

The two compose, so adding `--playground` to an existing deployment changes
nothing about what it already served — it only adds entries after the default.

![Playground editor with the runtime catalog selector — `compose-m3 (desktop)` chosen at request time, compiled and rendered](../images/serve-playground-catalog-selector-light.png)

**A catalog is the whole compile target, not just a classpath.** Its bundle
backend picks the renderer, so selecting it also selects the mode set: `desktop`
→ CMP; `android` → Android + Remote Compose. `PlaygroundCatalogTargets` intersects
that with the render backends that actually came up (no Robolectric sidecar ⇒ no
Android modes; no `/d/` store ⇒ no Remote Compose) and omits a catalog with
nothing left, so the editor never offers a target the host would refuse on Run.
The page ships each entry's mode list and repopulates the Mode control from the
selection; `GET /api/{v}/compiler/catalogs` returns the same list, and the editor
re-asks once on load because catalogs are fetched *after* the server is up.

Three properties are load-bearing:

- **A named catalog never falls back to the default.** Unknown, unloaded, wrong
  backend, over budget — all answer "catalog '<id>' cannot serve mode X on this
  host". Silently compiling against a different design system than the one asked
  for would report success for the wrong thing.
- **Resolution stays lazy and per catalog.** Resolving twenty catalogs at startup
  is minutes of unpack + Maven work for a lane most visitors never touch; each
  resolves on the first compile that names it, through the same
  `PlaygroundClasspathSupplier` the pinned flags use.
- **The number of *resolved* catalogs is capped** (`--playground-catalog-limit`,
  default 6). A resolved catalog is held for the process's life — its jars are
  open in live snippet JVMs, so it cannot be evicted (same reason auto-refresh
  isn't followed, below). Past the cap a request naming a new catalog is refused
  with a message saying so, rather than letting a visitor clicking through every
  entry grow the host's disk one unpack at a time. `/status.json` reports
  `playground.catalogSelector.{offered,resolved,limit}`.

What this deliberately does **not** change: live-seat accounting. A redeemed
snippet session charges permits exactly as before, whichever catalog compiled it.

#### Naming that catalog: a path, or a system this box already serves

Both flags originally took a **local filesystem path only**, which made enabling
the playground on a box that already serves the same catalog a manual step: fetch
that catalog's liveBundle by hand, drop it on the config volume, keep it there.
Two things fell out of that — it duplicated work `--catalogs` does at startup
(fetch, verify `Trusted(Branch)`, resolve a classpath), and the hand-placed copy
went **silently stale**, since catalog auto-refresh re-points the live lane at a
newer bundle while the pinned file never moves.

So a flag value is read as one of two forms (`PlaygroundBundleSource`, issue
#3212):

| Form | Means |
|---|---|
| `--playground-bundle /config/x.bundle` | a bundle file on disk — the original behaviour |
| `--playground-bundle compose-m3` | the liveBundle of an already-served `--catalogs` system |

They are told apart structurally, not guessed at: a path separator, an existing
file, or a bundle-ish suffix (`.bundle`, `.png`, `.zip`, `.jar`) means a path; a
bare token is a system id. A catalog system is a branch-name component
(`design-artifacts/<system>`) and never carries a separator, so neither form can
be read as the other. Naming a system this box does not serve is a startup error
listing the ones it does — not a mode that quietly never works.

The system form makes the natural deployment `SERVE_PLAYGROUND_BUNDLE=compose-m3`
with no file to place, and it inherits the catalog's trust verdict instead of
trusting whatever bytes landed on the config volume.

**Resolution is deferred**, which the system form forces: catalogs are fetched by
`InitialCatalogLoader` *after* the server is up, so a classpath resolved while the
playground lane is being wired would find nothing and disable the mode forever.
`PlaygroundClasspathSupplier` therefore resolves on first use and memoizes the
first success; a mode whose bundle hasn't landed answers "mode … is not
available" and logs why, and recovers by itself once the catalog loads. This is
also why `PlaygroundCompileService.availableModes` is computed per read rather
than captured in the constructor.

What is deliberately **not** built: following auto-refresh. Once a classpath
resolves it is pinned for the process's lifetime, because its jars are open in
live snippet JVMs and swapping them mid-flight needs generation-scoped unpack
dirs and a retirement policy. A long-running host keeps compiling against the ABI
it first resolved; restart to pick up a newer one. Issue #3212 splits that half
out explicitly.

### 8.4.1 Getting into the playground from a catalog

Two subtle entry points, both on surfaces a visitor is already reading rather
than as controls of their own:

| Where | Link | Opens |
|---|---|---|
| Catalog landing summary line | `try in playground` | `/playground?catalog=<system>` — that design system preselected, starter sample kept |
| Viewer's provenance row, beside `source` | `▶ playground` | `/playground?from=<system>/<previewId>` — that preview's **source file** in the editor, its catalog preselected |

![The viewer's provenance row: source · ▶ playground · report an issue](../images/serve-playground-handoff-viewer.png)

![A catalog landing's summary line carrying "try in playground"](../images/serve-playground-handoff-landing.png)

![The playground opened from a preview: its source file in the editor, its catalog preselected](../images/serve-playground-seeded.png)

The per-preview handoff reads the file off GitHub (`PlaygroundSeedResolver`). Two
properties make that safe to expose publicly:

**The URL is never client-derived.** A request names a system and a preview id;
both resolve through this server's own registry, and the repo/ref/module/path
that build the fetch URL all come from the catalog's trusted metadata
(`catalog.json`'s `source`, plus the per-preview `sourceFile`). The worst a
visitor can name is a preview that doesn't exist, which resolves to null.

**Everything fails soft.** No source path recorded, a fetch that 404s or times
out, a file over the 256 KB cap, bytes that aren't UTF-8 — each opens the
ordinary sample and logs the reason. A link is only rendered when this host has
the lane *and* the preview records a source path, so the affordance is absent
rather than dead. Seeds are cached, and the cache is bounded (256) so a crawler
walking every preview cannot grow it.

**What it is not:** a trimmed snippet. It is the file the preview is declared in,
which for a catalog that declares many previews in one `CatalogPreviews.kt` is
that whole file — the page says so rather than implying otherwise. It compiles
against the catalog's classpath, so anything the file pulls in from *elsewhere in
its own module* comes back as an unresolved reference to delete (measured on
`compose-m3`: 9 diagnostics across 511 lines, all sibling helpers). Extracting
just the named preview would need an exact preview-id → function-name mapping;
the catalog id is a slug (`badge__ideal__default__light`) and guessing at the
function would risk silently showing the wrong code, so it is deliberately left
until that mapping is plumbed through.

### 8.5 Sharing the compile lane — **a per-caller budget, not just capacity**

Everything above bounds *simultaneous resource use across the host*: compile
slots, the 180 s compile timeout, the 256 KB body cap, live seats, the token
store's size and TTL. None of it is a per-caller budget, so two clients issuing
back-to-back long compiles hold every slot indefinitely and everyone else is told
the playground is busy (issue #3214).

`ServeRateLimiter` adds the missing half: a token bucket for the request *rate*
plus a counter for *concurrent* work, keyed per caller.

| Bound | Default | Flag |
|---|---|---|
| Compiles per minute, per caller | 10 | `--playground-rate-limit` (0 = off) |
| Concurrent compiles, per caller | 1 | `--playground-caller-concurrency` |

Four decisions worth recording:

**The key is the GitHub login where there is one**, the client address otherwise,
and the two spaces are prefixed (`gh:` / `ip:`) so they can never collide. A login
survives a changed address and is the identity the repo-access gate already
admitted on; on a repo-access-gated host it is what *every* compile carries, which
is why the address path matters mainly for token-gated and local hosts.

**Anonymous callers behind a proxy share one bucket** unless `--trust-forwarded-for`
is set, and that flag is opt-in on purpose: `X-Forwarded-For` is client-supplied,
so trusting it on a directly-exposed host would let a caller mint a fresh identity
per request. When it *is* set the limiter reads the **last** entry — the one a
single reverse proxy appended from the peer address it saw — not the first, which
the client controls. Exactly one hop's worth of trust.

**The check runs after the gates and before the body read.** A throttled caller
costs the host a 429 and nothing else: no 256 KB of buffered upload, no compile
slot, no work dir, no token. The refusal rides the ordinary run-response shape
(`exception`) with a `Retry-After`, so the editor surfaces it as a status line
rather than an unparseable body.

**Only the compile lane is metered, not `/pg/` redemption.** A redemption is
reachable only with a token a compile just minted, so limiting compiles limits it
transitively — and it already answers to the live-seat budget, the token store's
cap, and the token TTL. Metering it again would refuse a caller the preview they
already paid for.

The key space is bounded (`DEFAULT_MAX_KEYS`, 4096) because a public host is keyed
partly by an address the attacker chooses. Admitting a new key first sweeps every
entry indistinguishable from a fresh one — nothing in flight, bucket fully
refilled — which costs its caller nothing to lose; only if that frees nothing is
the new key refused, which under a key-space spray is the honest answer and the
alternative is memory growth the attacker picks. `/status.json` reports
`playground.rateLimit.{activeCallers,trackedCallers}`; the latter pinned near its
cap is the signature of a spray rather than of an audience.

Item 1 of #3214 — auth as a hard prerequisite on a public box — is already
satisfied by §6.2's gate, which refuses an anonymous *and* uncontained lane
outright. The `/docs` upload lane has the same shape of gap and can reuse
`ServeRateLimiter` as-is.
