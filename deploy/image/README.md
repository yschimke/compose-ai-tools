# Prebuilt preview-host image (instant deploy)

A **prebuilt** Docker image that runs `compose-preview serve` with **no build on
the host**. It's the fast alternative to [`deploy/cloudrun/Dockerfile`](../cloudrun/Dockerfile),
which compiles the whole tool from source (~8 min) — instead this installs the
**released** `compose-preview` CLI and serves published catalogs/live bundles
without a local Gradle project. Built once in CI and pushed to GHCR, so hosts just
pull it.

- **Image:** `ghcr.io/yschimke/compose-preview-host:<version>` (and `:latest`)
- **Render targets:** Compose **Desktop** live bundles (Skiko software GL), **plus** a baked
  **Android/Robolectric** daemon + minimal Android SDK so a served Android **Wear** catalog
  (`wear-m3`) renders live server-side. Lighting the Android live lane needs the catalog's stickers to
  carry the `previewId` daemon mapping **and** the bundle to carry the app's resource table under
  `android/`; both shipped in **0.16.50** (previewId #2492, app-resource carriage #2498 + missing-
  resource placeholder fallback #2499), so `wear-m3` renders live once the box rolls that image and
  re-fetches the regenerated bundle. See `docs/public-preview-server.md`. This is what
  `preview.coo.ee` runs.
- **Bundle uploads:** disabled. **Auth:** public browsing, optional GitHub sign-in gate for
  live/playground surfaces, shared token for private boxes. **TLS:** via Caddy.

## How it's fast

The from-source image compiles `build-logic` + the Gradle plugin + the CLI + ~50
data modules. This one skips all of that:

| | From source (`deploy/cloudrun`) | Prebuilt (`deploy/image`) |
|---|---|---|
| Tool | compiled in the image (~8 min) | **release CLI tarball, staged by CI** |
| Release modules | built locally | **same-tag Maven tree, baked into the image** |
| Content served | the whole repo's `:samples:cmp` | published catalogs + live bundles |
| Host build | yes, every deploy | **none — `docker pull`** |

The image carries the release's Maven modules and both live-render backends, so
catalog bundles can start without building a local project or waiting for the
release to propagate through Maven Central.

## Publishing the image (one-time / per release)

The [`preview-host-image.yml`](../../.github/workflows/preview-host-image.yml)
workflow builds and pushes to GHCR. The automatic path starts as soon as the
release tag exists and builds its inputs directly from that tag, in parallel
with the core release. Trigger it either way:

- **On a CLI release** (`v*` tag) — automatic; bundles that version + tags `latest`.
- **Manually** — Actions → *Publish preview-host image* → run with a `cli_version`
  (e.g. `0.16.33`).

First publish makes the GHCR package; set it **public** (Packages → settings) if
you want hosts to pull without auth.

## Deploying (on any VPS — Hetzner, etc.)

DNS `A` record → host IP, ports 80/443 reachable, then:

```bash
git clone https://github.com/yschimke/compose-ai-tools.git
cd compose-ai-tools/deploy/image
DOMAIN=preview.example.com ./setup.sh
```

`setup.sh` installs Docker (if needed), writes `.env` (generated token), and
`docker compose pull && up -d` — **no build**. It prints your
`https://preview.example.com/?token=<TOKEN>` link once Caddy has a cert.

Pin a version with `IMAGE_TAG=0.16.33` in `.env` (a bare tag; defaults to the
`latest` tag when unset).

The image also serves its release-matched Compose/Wasm preview browser at
`/wasm/preview-ui/`. It is packaged inside the CLI distribution and enabled automatically: no
`.env` entry or host directory is required. `SERVE_WASM_DIR` is only for adding another static Wasm
application (`system=/path`) or deliberately replacing the built-in `preview-ui` mapping; every
path named there must exist inside the container.

### Serving a catalog on its own hostname

A published catalog can additionally be served on a hostname of its own, where it presents as the
whole server — `m3.preview.coo.ee` shows what `preview.coo.ee/m3-catalog/` shows, with the catalog's
landing at `/` and no front door. What that changes about the pages is
[docs/public-preview-server.md](../../docs/public-preview-server.md#top-level-sites-one-catalog-on-a-hostname-of-its-own);
what it takes to stand one up is a DNS record, an entry in `catalogs.json`, and a caddy restart.

**1. DNS.** A `CNAME` to the box's existing hostname is the simplest form and is what
`m3.preview.coo.ee` uses — it is a subdomain, so there is no apex-CNAME problem, the IP stays in one
place, and an `AAAA` added later is inherited for free:

```
m3.preview.coo.ee.    CNAME  preview.coo.ee.
wear.preview.coo.ee.  CNAME  preview.coo.ee.
```

An `A` record straight to the host IP works identically; it is just a second copy of the IP to keep
in step. Let it resolve before the restart below — Caddy needs it to answer the HTTP-01 challenge,
which follows the alias without caring that it is one.

**2. `catalogs.json`** — one entry beside the catalog it names:

```json
"sites": [
  { "host": "m3.preview.coo.ee",   "system": "m3-catalog" },
  { "host": "wear.preview.coo.ee", "system": "wear-m3-catalog" }
]
```

That one list now configures **both** processes. The app learns which catalog a `Host` means, and
the caddy container derives `SITE_DOMAINS` from the same file at start
([`caddy-entrypoint.sh`](caddy-entrypoint.sh)), which is what makes Caddy match the name **and**
provision its Let's Encrypt certificate. There is nothing else to configure for TLS, and no second
list to keep in step — the failure this used to invite was a name in one place and not the other,
which is either a site nothing routes to or a hostname the app doesn't recognise.

It reaches a **running** box the same way a catalog does: `publish-config-to-box.sh` POSTs each
entry to `/admin/sites` on every push to `main`, after the catalogs (a site may only name a catalog
the box already serves). `SERVE_SITES` in `.env` still works — it adds any host the file doesn't
already claim — but it is no longer how a site is added: it predates the admin route, and an
untracked file is exactly where a hostname goes to drift from `main`. Same for `SITE_DOMAINS`: set
it only for a name the app doesn't serve as a site.

**3. Restart caddy.** The app needs no restart — the reconcile applies the site live. Caddy reads
its config once at start, so the *edge* half lands on the next recreate. `./rollout.sh` won't do it:
it rolls `preview` only when the *image* changes, and `caddy` isn't rollable at all (fixed 80/443
ports).

```bash
docker compose up -d          # caddy re-reads catalogs.json and asks for any new certificate
curl -sI https://m3.preview.coo.ee/ | head -1                            # 200 — catalog landing at /
curl -sI https://m3.preview.coo.ee/m3-catalog/p/button-filled | head -1  # 308 → /p/button-filled
curl -sI https://m3.preview.coo.ee/wear-m3/ | head -1                    # 404 — neighbours unreachable
curl -sI https://wear.preview.coo.ee/ | head -1                          # 200 — the Wear catalog's landing
```

If this box uses GitHub sign-in, set `SERVE_GITHUB_AUTH_COOKIE_DOMAIN` as well — see *GitHub auth*
below; without it sign-in is withheld on a site host and its live and playground surfaces stay
snapshot-only. The entrypoint derives it from `DOMAIN` whenever the box has a site configured at
all — `SERVE_SITES` or a `sites` entry in the catalogs file — so a site delivered by config gets the
same treatment as one spelled out in `.env`.

> On this image `/config/catalogs.json` is a volume the box seeds once and never overwrites, so the
> file the *app* reads is the box's copy, not the one in git. `/admin/sites` is what bridges them —
> it applies the committed entry live and rewrites the box's copy, so the two converge without the
> overlay below. Adopt the overlay if you'd rather the committed file be authoritative outright.

### Config from version control (`docker-compose.deploy-config.yml`)

`/config/catalogs.json` and `/config/producers.json` live on the `preview_config` **named volume**,
seeded on first boot and never overwritten (#2879 / #2897) so an image roll can't stomp a runtime
edit. The cost is that config the box has never seen cannot arrive by `git pull` — only by a
hand-edit or by the additive admin reconcile.

The opt-in overlay in this directory makes the committed deployment config authoritative instead:

```bash
docker compose -f docker-compose.yml -f docker-compose.deploy-config.yml up -d
```

or set `COMPOSE_FILE=docker-compose.yml:docker-compose.deploy-config.yml` in `.env` so every later
`docker compose` here picks it up. It bind-mounts the two files read-only from `DEPLOY_CONFIG_DIR`
(default `../preview.coo.ee`, the same variable `publish-config-to-box.sh` uses), so deploying
config becomes `git pull && docker compose up -d`.

Read-only does **not** break the admin API: `ServeCatalogAdmin.persist()` fails soft, so
`POST /admin/catalogs` still registers and serves the catalog and only reports back
`"warning": "not persisted: …"`. In steady state the reconcile never reaches that path — every entry
it POSTs is already in the file the box booted from, so the admin API answers `409`, which the
script counts as success. What you give up is a runtime edit outliving a restart, which is the
point: that edit is drift from `main`.

> **Diff before adopting this on a box that has been running.** The volume file is a superset
> whenever anyone added a catalog or a trusted producer directly on the host, and the overlay swaps
> it wholesale — anything on the box and not in git stops being served at the next restart, with
> nothing logged. Compare first, and commit whatever is missing:
> ```bash
> curl -sH "X-Compose-Preview-Admin-Token: $SERVE_ADMIN_TOKEN" https://<host>/admin/catalogs
> docker compose exec preview cat /config/catalogs.json
> ```

### Reading `/status.json` before you tune anything

Four readings mislead if taken at face value. Each cost real debugging time on `preview.coo.ee`, so
they are written down rather than rediscovered.

**`pressure.reason` describes the HOLD, not the moment.** `host recovering` means a limb tripped at
some point and the gate is now waiting out `optimizerResumeQuietMillis` (30s) of *continuous* calm
before reopening — not that anything is over a threshold now. A box can read `memory available 81%`,
`CPU 29%`, `load 0.42 per CPU` — every limb comfortably inside its resume side — and still be held.
On a workload as bursty as rendering, the optimizer's own next burst re-trips a limb inside the
recovery window, so the gate can stay shut almost permanently while every individual sample looks
healthy. **If throughput is poor and the numbers look fine, this is why**, and
`optimizerResumeQuietMillis` is the knob.

**`daemons.running` counts sessions, not processes.** Thirteen "running" daemons on a box with four
JVMs is normal. Multiply that by a per-daemon memory estimate and you will invent a memory crisis
that does not exist. `docker compose exec preview ps -eo rss,comm --sort=-rss` is the honest answer.

**`pressure.memoryAvailableFraction` is a spot sample, and it is spiky.** One low reading is not a
trend — consecutive samples on this box have run 0.19, 0.34, 0.23, 0.48, 0.08, 0.81. Take two before
believing one, and cross-check against `docker stats`.

**`pressure.loadPerCpu` is a queue depth, not a percentage.** One runnable task per core reads `1.0`;
a box rendering flat out sits above that. The thresholds take the same units.

### Sizing the box

| Knob | What it bounds | Default |
| --- | --- | --- |
| `PREVIEW_MEM_LIMIT` | Memory the whole container may use | `0` (unlimited) |
| `SERVE_LIVE_SEATS` | Concurrent daemon *residency*, weighted (Android costs 2) | derived: `min(memory, cores × 2)`, clamped to `[2, 32]` |
| `SERVE_BACKGROUND_RENDERS` | Optimizer renders admitted at once | derived from seats, clamped to 3 |

The seat budget derives from **both** memory and cores — a permit buys a render daemon and a render
is CPU-bound, so a RAM-rich, core-poor box must not derive a budget it cannot work. `cores × 2` is
one Android daemon per core, Android being the heaviest backend at two permits each.

**`SERVE_BACKGROUND_RENDERS` still clamps at 3**, though, so on a box deriving more than 8 seats the
optimizer lane becomes the binding constraint even once the seats are right. Name it explicitly
until that derivation scales too; `(seats − 2) / 2` is the arithmetic the seat budget can afford.

Measure before choosing, because the container's own `free` reports the HOST's memory and will
happily claim 60 GiB available inside a 3 GiB container:

```bash
free -h; nproc                                                   # what the host has
docker stats --no-stream --format \
  'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}'          # what the container uses
docker compose exec preview ps -eo rss,comm --sort=-rss | head   # what is actually resident
```

### Tidying `.env`

An `.env` accumulates lines faster than anyone removes them — a value copied from this README during
setup, a knob turned once during an incident and turned back, a setting that later *became* the
default. The file then reads as configuration when most of it is decoration, and the few lines that
genuinely explain why this box differs from stock are buried.

```bash
./env-redundant.sh
```

It first refuses any line ending in a backslash — `.env` is not a shell script, and that typo takes
the box down with a 502 and no explanation. Then it reports which entries restate a compose or
entrypoint default and which are set but empty (the same as unset), then lists the keys that
genuinely differ — **by name only**. It never prints a value: the file holds `SERVE_TOKEN`,
`SERVE_ADMIN_TOKEN`, the GitHub OAuth secret and the deploy hook token, and the output is meant to
be safe to paste into an issue or a chat. A malformed line is located by **line number**, for the
same reason — a broken continuation's second line is a value fragment, not a key.

Its advice is acted on by deleting a line, so it follows Compose's own reading rather than
approximating it, and two of its sections exist because deleting is not always reversible:

- **`DUPLICATED`** comes first when a key is assigned twice. Compose uses the last assignment, so
  deleting the winning line exposes an earlier one — `SERVE_PUBLIC=1` over `SERVE_PUBLIC=0` takes a
  public box token-gated. Resolve those before acting on anything below.
- **"Set but empty, and NOT the same as unset"** covers keys whose `setup.sh` migration
  distinguishes missing from empty. `DEPLOY_HOOK_TOKEN=` left deliberately empty disarms the
  instant-roll webhook; deleting the line has `setup.sh` generate a token and arm it again.

It reads every compose file `COMPOSE_FILE` selects, so the deploy overlay's defaults count as stock;
it strips an inline comment from an unquoted value the way Compose does, so a line copied from this
README complete with its `# the default` still reads as redundant; and where a compose file only
passes a variable through (`${VAR:-}`), the entrypoint's default is the one compared against.

### Warming the theme cache aggressively

The pressure gate's defaults assume a box whose spare capacity belongs to visitors. While the cache
is still filling, that trade is backwards: an empty cache means every theme a visitor picks is a
cold render, so the fastest route to a *responsive* box is to let the optimizer have the machine for
a few hours and then hand it back.

> **One line, no backslashes.** `.env` is not a shell script: docker compose reads every line as its
> own `KEY=VALUE`, so a trailing `\` becomes part of the value and the continuation lines are parsed
> as junk keys. That backslash then reaches `JAVA_TOOL_OPTIONS`, the JVM refuses to start on an
> unrecognised option, and the container restart-loops behind a 502 — with nothing in the compose
> output naming the cause. This example is long; keep it on one line anyway.

```
SERVE_JAVA_OPTS=-Dcomposeai.serve.themeOptimizationIdleMillis=10000 -Dcomposeai.serve.optimizerResumeQuietMillis=5000 -Dcomposeai.serve.optimizerStopLoadPerCpu=3.0 -Dcomposeai.serve.optimizerResumeLoadPerCpu=2.0 -Dcomposeai.serve.optimizerStopCpuUtilization=0.98 -Dcomposeai.serve.optimizerResumeCpuUtilization=0.92
```

Read that as: start a pass after ten seconds of quiet rather than sixty, only stand down when the
box is genuinely saturated rather than merely busy, and — the one that usually matters most — stop
demanding half a minute of unbroken calm before coming back.

**`optimizerResumeQuietMillis` is the knob people miss.** Rendering is bursty by nature, so a
30-second recovery window is long enough for the optimizer's own next burst to re-trip whichever
limb it just cleared. The gate then holds more or less permanently on a box whose every individual
reading looks healthy — see *Reading `/status.json`* above for the signature. Measured on
`preview.coo.ee`, gate-wait ran 5-35x the actual render time in that state, and roughly half of all
turns had to be granted by the ten-minute forced-turn ceiling rather than by the gate opening.

**Leave the memory thresholds alone.** CPU and load contention costs latency and recovers on its
own; running out of memory kills render daemons and takes the catalog with them. `0.15` stop /
`0.25` resume is the one limb where the conservative default is earning something.

**The load thresholds are per-CPU load averages, not percentages.** One runnable task per core reads
`1.0`, so a box rendering flat out sits *above* 1.0 — `3.0` means "three deep per core". Values
above `1.0` were silently discarded before the fix that added this section, which is why raising
this limb appeared to do nothing.

Watch `themeOptimizer.pressure.reason` on `/status.json` to see which limb is actually holding:
`load N per CPU`, `CPU N%`, `memory available N%`, or `host recovering` (a limb that tripped and has
not yet fallen back to its resume side). Tune the one that is named; the others are not the problem.

Undo it by removing the properties and restarting once the catalogs report converged.

### Regenerating a catalog's warmed theme renders

Two admin routes for pixels you suspect are wrong for a reason no fingerprint sees. A base-image
bump that changed the installed fonts was the case that motivated them; the fingerprint now reads
the font inventory and the rasteriser sonames itself, so these are for whatever is left over.

```bash
# Mark this catalog's warmed renders for re-render. Nothing is deleted: every preview keeps
# serving while the background pass replaces them. Answers {"queued": true, "entries": N}.
curl -sX POST -H "X-Compose-Preview-Admin-Token: $SERVE_ADMIN_TOKEN" \
  https://<host>/admin/catalogs/m3-catalog/theme-cache/regenerate

# Take them instead. Every preview for that catalog goes cold at once and is re-rendered from
# nothing — the cost `regenerate` exists to avoid, so this is the second choice of the two.
curl -sX POST -H "X-Compose-Preview-Admin-Token: $SERVE_ADMIN_TOKEN" \
  https://<host>/admin/catalogs/m3-catalog/theme-cache/drop
```

Both wake the catalog's optimizer, so the work starts with the response rather than waiting on the
next rotation — and both skip the wake when theme optimization is switched off on this box, because
there is then no pass with a queue to work and resuming a suspended host would cold-start a daemon
and spend a live seat on a refill that cannot happen. A mistyped system is a `404`, not a silent
success.

**A `409` from either is "contended, retry"** — a render held the generation write lock as you
called, and reporting that as success is the one failure mode a drop must not have. From
`regenerate` it can also mean the request could not be made durable (a full or read-only cache
volume) or that theme optimization is switched off on this box, so nothing would ever work the
queue. Each route reports the refusal in its **own** field: `regenerate` answers `"queued": false`
rather than a count, and `drop` answers `"dropped": false`. Retry logic reading the wrong one finds
it absent and takes a refusal for a success.

**Deliberately not buttons on `/status`.** That page authenticates with `SERVE_TOKEN`; these routes
require `SERVE_ADMIN_TOKEN`, which is a different and much smaller audience. A button would have to
bridge that gap, and every way of bridging it either puts the admin credential in a page that
merely holding the serve token can read, or invents a second, weaker way into an admin route. These
are rare operator actions, and a drop takes a warm catalog cold across every one of its renders —
not a thing to make one click away from a page a wider audience can already see.

What `/status` *does* show is when a catalog needs one: a row reading
`themes optimized 10440/10440 · 10440 awaiting re-render` is warm everywhere and finished nowhere —
every one of those renders is still queued for replacement, and until the pass gets to them the
catalog is serving pixels it does not consider current. `/status.json` carries the same figure as
`catalogList[].themeOptimization.dirty`.

The row deliberately does not say where those pixels came from or what the pass is doing right now.
Both are usually "another build" and "re-rendering", but `regenerate` marks *this* build's renders
too, and the queue can be paused or waiting on admission — the count cannot tell you which, so it
does not claim to. A `· N failed` beside it is the one to act on: those are targets the pass has
given up re-rendering, and they stay on the old pixels until something changes.

### GitHub auth on `preview.coo.ee`

To keep catalog browsing public while requiring GitHub sign-in for live sessions and playground,
create a GitHub OAuth app with callback:

```text
https://preview.coo.ee/auth/github/callback
```

Then add the OAuth values to the host `.env` and recreate the preview service:

```bash
SERVE_GITHUB_AUTH_CLIENT_ID=...
SERVE_GITHUB_AUTH_CLIENT_SECRET=...
SERVE_GITHUB_AUTH_COOKIE_SECRET=... # openssl rand -hex 32
```

The compose profile derives the callback base URL from `DOMAIN`; set
`SERVE_GITHUB_AUTH_CALLBACK_BASE_URL` to override.

**With top-level sites configured, the cookies need a domain.** `SERVE_GITHUB_AUTH_COOKIE_DOMAIN`
scopes both auth cookies to a parent domain, so one sign-in covers this host and every site
hostname under it — sign in on `preview.coo.ee` and `m3.preview.coo.ee` is signed in too. Without it
the cookies are host-only: a sign-in started on a site host writes its state cookie there while
GitHub returns to the pinned callback origin, the callback sees no state, and the server withholds
the sign-in affordance on every site (live and playground stay snapshot-only there). The entrypoint
derives it from `DOMAIN` when the box has any site configured — `SERVE_SITES`, or a `sites` entry in
`catalogs.json`; set it explicitly to narrow it, or to `none` to keep cookies host-only:

```bash
SERVE_GITHUB_AUTH_COOKIE_DOMAIN=preview.coo.ee
```

Every host under that domain is inside the session's reach, so name the narrowest one covering your
sites — on a box whose `DOMAIN` is an apex, set this explicitly rather than letting one session span
the whole zone. A public suffix, or a domain that doesn't cover the callback host, is refused at
startup rather than producing cookies the browser drops silently. Empty `SERVE_GITHUB_AUTH_USERS` allows any
signed-in GitHub user to use live previews; playground additionally requires **write** access to
`SERVE_GITHUB_AUTH_REPO` (default `yschimke/compose-ai-tools`). Set `SERVE_GITHUB_AUTH_USERS` to a
comma-separated login list only if you want to narrow sign-in for both surfaces. The OAuth app
requests GitHub's `repo` scope so private repository access can be checked during sign-in; the
server stores only the signed login and the access verdict, not the OAuth token.

### Playground on `preview.coo.ee`

`/playground` is disabled unless the preview service is started with a catalog live bundle that can
seed the snippet classpath **and** the `--public` admission gate lets the lane through. On a public
host the gate admits on either of two independent bases (issue #3210):

| Posture | What admits it | What it costs |
|---|---|---|
| **repo-access-gated** | GitHub auth configured, so `/playground`, `POST /api/{v}/compiler/run` and `/pg/` all require a signed-in user with **write** access to `SERVE_GITHUB_AUTH_REPO` | Nothing beyond OAuth secrets — **this is the posture this image supports** |
| **contained** | `SERVE_PLAYGROUND_SANDBOX` set to a profile that both passes the startup containment probe *and* applies CPU/pid caps | A jail this image cannot currently provide (see below) |

Anonymous **and** uncontained is refused outright, with a startup log line naming both remedies.

**Configure the repo-access-gated posture** — set the GitHub OAuth secrets from the section above,
then name a catalog this box already serves:

```bash
SERVE_PLAYGROUND_BUNDLE=compose-m3
SERVE_PLAYGROUND_COMPILE_SLOTS=1
```

For the opt-in incremental-editing trial, enable the single whole-host lease:

```bash
SERVE_PLAYGROUND_EDITING=1
SERVE_PLAYGROUND_EDIT_LEASE_TTL=900   # idle seconds; optional, default 15 minutes
```

This knob is ignored unless GitHub auth is configured. A signed-in user explicitly presses
**Acquire editing lease**; while they hold it, Run reuses that lease's source tree, class output,
and Kotlin Build Tools API incremental state. Exactly one user can hold the lease across the host,
and each successful revision still gets an immutable preview-token snapshot. Release it from the
same button or let the idle TTL expire.

The trial is deliberately easy to observe at `/status.json` → `playground.editing`: `active`,
`lastRevision`, `acquisitions`, `compileAttempts`, `incrementalCompiles`, `fullFallbacks`, and
`lastCompileMillis` are process-lifetime signals suitable for a production soak. No login or lease
capability is exposed there. With a configured sandbox, compilation remains in the capped child;
the current trial reuses its on-disk IC state but still starts a child for each Run. A recycled warm
jailed worker is the next latency step, not a prerequisite for safely collecting correctness data.

`SERVE_PLAYGROUND_BUNDLE` takes either a **served catalog system id** (as above) or a local
`.bundle` path (`/config/playground-cmp.bundle`); a value with a path separator, a `.bundle`/`.png`
suffix, or one that names an existing file is read as a path, anything else as a system id. Prefer
the system id: it reuses the bundle `--catalogs` already fetched and verified, so there is no file
to place on `/config` and nothing to go stale when the catalog's delivery branch moves. Naming a
system this box doesn't serve is a startup error listing the ones it does.

The classpath resolves the first time someone compiles, not at boot — the catalog it names is
fetched in the background after startup, so `serve: playground cmp classpath resolved from served
catalog 'compose-m3'` appears on the first run, and until then `/playground` reports the mode as
unavailable. Once resolved it is **pinned for the life of the process**: a later catalog refresh
does not move it, because live snippet JVMs hold those jars open. Restart the container to compile
against a newer catalog ABI.

### Image lane on `preview.coo.ee`

`POST /images` lets an agent that just rendered a preview hand the PNG to this box and get back an
embeddable `/i/<id>.png` for a pull-request body — the mechanism
[`share-preview --mechanism serve`](../../docs/public-preview-server.md#uploading-a-preview-image---accept-images)
uses when it has neither `gh` nor push rights. Uploading is **never anonymous, on a `--public` box
too**: the caller presents `Authorization: Bearer <github-token>` and GitHub itself is asked whether
that account has access to the gating repository. Reading is open by design — GitHub's image proxy
fetches an embedded image anonymously, so the unguessable 128-bit id is the whole grant.

Turning it on is naming that repository:

```bash
SERVE_IMAGE_UPLOAD_REPO=yschimke/compose-ai-tools
# optional: SERVE_IMAGE_TTL=604800 (7d default) · SERVE_IMAGE_RATE_LIMIT=60 (uploads/min/account)
```

`SERVE_ACCEPT_IMAGES` is derived from it (`entrypoint.sh`, guarded by
[`test-image-lane-default.sh`](test-image-lane-default.sh)), because that variable exists for
nothing but this lane — naming it and still getting a 404 from `POST /images` is nobody's intent,
and the miss is silent at both ends. Set `SERVE_ACCEPT_IMAGES=0` to keep the lane shut with the
repository named, or `=1` without one to get the server's own startup refusal.

The derivation deliberately does **not** key on GitHub auth being configured, the way
`SERVE_AGENT_GRANTS` does. The gating repository falls back to `SERVE_GITHUB_AUTH_REPO`, which this
image defaults to `yschimke/compose-ai-tools` for the playground — so keying on auth would open an
upload lane on every adopter's box gated by *our* collaborators rather than theirs. An operator who
wants that fallback still gets it by naming the repository explicitly.

Verify after a roll — the row on `/status`, and the refusal an unauthenticated caller gets, which is
the same route answering rather than a 404:

```bash
curl -s https://preview.coo.ee/status.json | jq '.config | {acceptImages, imageUploadRepository}'
curl -sS -o /dev/null -w '%{http_code}\n' -X POST --data-binary @render.png \
  'https://preview.coo.ee/images?name=after.png'                       # 401 — lane is up, no credential
curl -sS -H "Authorization: Bearer $(gh auth token)" --data-binary @render.png \
  'https://preview.coo.ee/images?name=after.png'                       # 201 + the markdown to paste
```

### Widening the background render lane (`SERVE_BACKGROUND_RENDERS`)

The theme optimizer's renders run in their own server-wide lane, so a visitor's render is never
queued behind more than a bounded number of background ones. Unset, the server derives that lane
from `SERVE_LIVE_SEATS`:

```
(seats − stream reserve) / Android seat weight,  clamped to [1, 3]
```

**The clamp is reached at 8 seats**, so on a bigger box the lane stops widening while everything
else scales with the budget. Raising `SERVE_LIVE_SEATS` from 8 to 12 on `preview.coo.ee` — a
container allowed 24 GB — left the lane at 3, unchanged.

Name it explicitly to go past that:

```
SERVE_BACKGROUND_RENDERS=5
```

Deliberately un-clamped: the derivation is conservative because it is guessing, and an operator
naming a number has looked at their own box. The seat budget still bounds how many daemons those
renders can actually occupy, so this widens the queue rather than licensing unbounded memory.

Before this existed the knob was reachable only as a system property, and the prebuilt image bakes
`JAVA_TOOL_OPTIONS` into its own `ENV` — so on this deployment there was no way to set it short of
rebuilding the image. `SERVE_JAVA_OPTS` below is the general answer to that; this flag stays because
it is one an operator reaches for often enough to deserve a name.

### Setting any other JVM or `composeai.*` property (`SERVE_JAVA_OPTS`)

**Do not set `JAVA_TOOL_OPTIONS` in the compose file.** The image bakes its own — the heap ceiling,
the daemon library directories, the render timeouts, the sandbox boot settings — and an environment
entry *replaces* that whole string rather than adding to it. The container then starts without its
heap ceiling and without the paths the daemon lane needs, for the sake of one `-D`. Nothing in the
resulting failure points back at the change.

Use this instead. The entrypoint appends it to whatever the image already carries:

```
SERVE_JAVA_OPTS=-Dcomposeai.serve.themeOptimizationIdleMillis=10000
```

Space-separate several. Last wins within `JAVA_TOOL_OPTIONS`, so this can override a baked value as
well as add one.

It is **inherited by the daemon JVMs** the server spawns, exactly as the baked options are. That is
what makes it the right layer for a render-side property, and the thing to know before putting a
heap flag here — every sandbox would take it as its own.

The knobs this reaches that nothing else did:

| Property | What it does |
| --- | --- |
| `composeai.serve.themeOptimizationIdleMillis` | Quiet window before an optimizer pass may **start** (default 60000). See the section below. |
| `composeai.serve.themeOptimizationGateCeilingMillis` | How long the gate may withhold a turn before one is forced anyway (default 600000). The floor under throughput on a box that never goes quiet. |
| `composeai.serve.themeOptimizerSliceMillis` | How long one admitted catalog holds a lane before handing it back. |
| `composeai.serve.optimizerStopMemoryAvailableFraction` / `…Resume…` | Where the pressure gate stops and resumes on memory. |
| `composeai.serve.optimizerStopCpuUtilization` / `…Resume…` | The same for CPU, and `…LoadPerCpu` for load average. |
| `composeai.serve.optimizerStarvationCapMillis`, `…DutyCycleMillis` | How long a hold may last before the gate opens a window anyway, and how long that window is. |
| `composeai.serve.catalogRenderCacheMaxBytes` | Per-catalog memory window for rendered pixels. |
| `composeai.serve.themeOptimization` | `false` switches the optimizer off entirely. |

The pressure thresholds in particular are documented in the source as "a property of the host, not
of the code, and it needs to be settable without a rebuild" — which was true of the intent and not
of this image until now. A box whose steady state is 18% available memory has a resume threshold set
below its own baseline, so the gate latches on the first transient dip and never reopens.

### Letting the optimizer start on a busy box

A theme-optimizer pass may only *start* once the whole server has been untouched for a quiet window,
60 seconds by default. That is the "don't begin work on a box someone might be using" rule, and it
assumes a box that goes quiet.

**A public box serving many catalogs may never do so.** When it does not, the pass falls back on its
forced-turn ceiling — one preview per catalog per ten minutes — and throughput collapses to roughly
a hundred entries an hour against a warm render of well under a second. What that looks like on
`/status.json`, and worth confirming before reaching for this:

- `turnsGranted` and `turnsYielded` within a handful of each other on every catalog: every turn the
  gate hands out is being taken straight back.
- `gateWaitMillis` several times `renderMillis` — measured on `preview.coo.ee`, 1,789s against 427s.
- `themeOptimizer.pressure.constrained` **false** and memory healthy, so nothing else is the cause.

```
SERVE_JAVA_OPTS=-Dcomposeai.serve.themeOptimizationIdleMillis=10000
```

This does not remove the courtesy the window encodes. It governs *entry*, asked once per pass; a
request arriving **during** a pass still takes the turn back within about two seconds, so a visitor
waits at most the render already in flight either way.

### Warmed theme renders survive a deploy (`SERVE_THEME_CACHE_DIR`)

The server pre-renders every catalog preview under every declared theme while the box is idle, so a
visitor's theme selection is instant rather than a cold render. On `preview.coo.ee` that is 18,604
renders, and it takes the better part of a day of quiet to fill.

**That work is held on disk by default**, on the dedicated `preview_theme_cache` volume:

```
SERVE_THEME_CACHE_DIR=/theme-cache        # the default; `none` for a memory-only cache
SERVE_THEME_CACHE_MAX_BYTES=4294967296    # 4 GiB — see below for where that comes from
```

The default was `none` — memory-only — and while it was, **theme optimization could not finish**.
Without a disk tier the warmed renders live only in the serve process's heap, so every container
recreation starts the pass again from zero, and the rolling update is a container recreation.
Measured across one morning: 1,502 of 18,604 entries after a 40-hour uptime, then 0 an hour later,
then 0 again — three releases, three resets. A pass that needs a day to complete cannot outrun a
deploy cadence measured in hours. The in-memory tier is deliberately a 128 MB window onto a
generation several times that size, so it was never going to hold the whole answer by itself.

Sizing: a fully warmed set measures **~0.93 GiB** here — 1,503 cached renders occupied 76.8 MiB,
i.e. ~52 KiB/entry, across 18,604 declared targets. The 4 GiB cap is ~4x headroom for superseded
generations awaiting their sweep, and is deliberately below the server's own 8 GB default because
the catalog blob pool on the same disk really does sit at its full 8 GB.

Staleness is handled rather than assumed away. A generation is keyed by a fingerprint of everything
that decides the pixels; because an input nobody thought of could still escape it, the server
re-renders a sample of the adopted entries at startup and discards the entire generation on any
mismatch, so a fingerprint miss costs a re-render rather than serving wrong pixels.

**A release keeps the warmed renders; a new base image does not.** The tool version used to be part
of that fingerprint, which meant every release orphaned the whole store — and with four versions
shipping inside four hours here against a pass that needs the better part of a day, the cache was
invalidated faster than it could ever fill and was adopted exactly zero times. The version was never
proof of anything anyway: it stood *proxy* for the container image, which a base-image bump changes
without moving the version at all.

So the fingerprint reads the **render environment** directly instead — the JVM's own version, the
architecture, the inventory of `/usr/share/fonts` and friends, and the freetype / fontconfig /
harfbuzz sonames. Four builds out of one base image share a generation and adopt each other's
renders; one build on a bumped base image gets its own directory and warms from cold, which is the
correct answer and used to depend on a five-entry sample happening to draw one of the rows that
moved. Renders written by another build of the same image are adopted and treated like any other
adopted entry — withheld from reads until the re-rendered sample agrees, whole generation discarded
when it does not. Each generation's manifest names the build that last **opened** it (not
necessarily one that wrote a PNG there — a replica that fails readiness still leaves its version
behind).

For the case where you already **know** the pixels moved for a reason none of that reads, evict the
store outright:

```
SERVE_THEME_CACHE_EVICT=1
```

It fires on **every** start while set, so leave it in for one roll and then take it out again. The
eviction also leaves an `evicted-at` marker at the store root, because the rollout is zero-downtime:
the outgoing replica keeps rendering into the same volume while the incoming one boots, and anything
it writes inside the following grace window would otherwise repopulate the generation with exactly
the pixels you evicted. The marker makes those renders dirty instead, so you do not have to stop
every writer first. Clearing the marker by hand is safe once the roll has settled. An ordinary
renderer change needs none of this; the fingerprint and the sample verification are what that is
for.

Read `themeCache` on [`/status.json`](https://preview.coo.ee/status.json) to confirm it is on — it
is `null` when there is no disk tier, and each catalog's `renderCache.persisted` is `null` beside
it. `renderCache.persistenceOff` names the reason when a catalog fell back to memory-only for some
*other* cause (an unreadable launch descriptor, a fingerprint it could not compute).

### Letting visitors pick the catalog

`SERVE_PLAYGROUND=1` adds a **Catalog** selector to the editor, offering every catalog this box
already serves instead of only the pinned one:

```bash
SERVE_PLAYGROUND=1
SERVE_PLAYGROUND_BUNDLE=compose-m3     # optional: preselected as "Server default"
SERVE_PLAYGROUND_CATALOG_LIMIT=6       # optional: how many may resolve at once (default 6)
```

The two compose — with both set, the pin stays the preselected default and the served catalogs
follow it, so adding `SERVE_PLAYGROUND=1` to a working deployment changes nothing about what it
already compiled. Set on its own it enables the lane with **nothing** pinned; the selector then
starts on the first catalog that has loaded, and `/playground` says "No catalogs available yet…"
until one does.

The selected catalog decides the mode too: its bundle `backend` is what picks the renderer, so a
`desktop` catalog offers Compose (Desktop) and an `android` one offers Compose (Android) + Remote
Compose. Catalogs whose modes this container can't render — Android ones without the
`lib-daemon-android` sidecar — are simply not listed.

**This image already carries the Android bits**, so that exclusion does not apply here: the
Dockerfile's `android-daemon` stage bakes the Robolectric sidecar at `/opt/lib-daemon-android`
(pointed at by `-Dcomposeai.cli.libDaemonAndroidDir` in `JAVA_TOOL_OPTIONS`) and a minimal Android
SDK at `/opt/android-sdk` (`ANDROID_HOME`), because the Wear catalog's *live* tier needs exactly the
same two things. Turning the selector on is therefore all it takes for an Android-backed catalog
here — `wear-m3`, `remote-m3`, the Wear app catalogs — to be offered and to compile. No image
change, no extra download, no `SERVE_PLAYGROUND_ANDROID_BUNDLE`.

Remote Compose mode has one extra requirement: it publishes its capture into the `/d/` document
store, so it needs `SERVE_ACCEPT_DOCS=1`. Without it the Android catalogs still offer Compose
(Android); only the Remote Compose mode stays absent.

Confirm what actually came up rather than assuming — the startup line
`serve: playground enabled (POST /api/1/compiler/run) — … android-render✓ catalog-selector✓(≤6)`
in `docker compose logs preview`, and `/status.json` at `playground.catalogSelector` (null means the
selector never came on) and `playground.modes`.

> **An existing box needs its `docker-compose.yml` updated too, not just its `.env`.** Compose does
> not forward the host environment — a variable absent from the `preview` service's `environment:`
> block never reaches the container, and the entrypoint then skips the flag silently. Every variable
> on this page is forwarded by the compose file in this directory, but a clone predating that is why
> `SERVE_PLAYGROUND` could be set in `.env` for months and do nothing. `git pull` the compose file
> before debugging a knob that appears to be ignored. CI guards the two files against drifting again
> (`deploy/image/test-compose-env-passthrough.sh`).

Each catalog resolves the first time someone compiles against it and is then held for the life of
the process (its jars are open in live snippet JVMs, so it can't be evicted). That is what
`SERVE_PLAYGROUND_CATALOG_LIMIT` bounds: past it, a run naming a *new* catalog is refused with a
message rather than unpacking yet another bundle onto the disk. Watch it on `/status.json` at
`playground.catalogSelector` (`offered`, `resolved`, `limit`).

Peak compile memory is `SERVE_PLAYGROUND_COMPILE_SLOTS × SERVE_PLAYGROUND_SANDBOX_MEMORY_MB`
(≈3 GB at defaults) on top of the catalogs already loaded, so review `SERVE_LIVE_SEATS` in the same
change on a small box.

### Fair sharing between callers

Compile slots, the compile timeout, the body cap, live seats and the token store are all
**whole-host** bounds — none of them stops one caller from holding every slot with back-to-back
compiles while everyone else is told the playground is busy. A per-caller budget is on by default
and needs no configuration:

```bash
SERVE_PLAYGROUND_RATE_LIMIT=10           # compiles per minute per caller (0 disables)
SERVE_PLAYGROUND_CALLER_CONCURRENCY=1    # compiles one caller may hold at once
```

The caller is the **signed-in GitHub login** where there is one — which on a repo-access-gated host
is every compile — and the client address otherwise. Over budget answers `429` with `Retry-After`,
before the request body is read, so a throttled caller costs the box nothing. Watch it on
`/status.json` at `playground.rateLimit` (`activeCallers`, `trackedCallers`).

> **Behind a reverse proxy, anonymous callers share one bucket** unless you opt in with
> `SERVE_TRUST_FORWARDED_FOR=1`. That makes the limiter key on the **last** `X-Forwarded-For` entry
> — the one nginx's `$proxy_add_x_forwarded_for` appended from the peer address it actually saw,
> which a client cannot forge. Do **not** set it on a directly-exposed host: the header is
> client-supplied there, so a caller could mint a fresh identity per request and bypass the limit
> entirely. It assumes exactly one proxy hop. This matters much less than it sounds on this image,
> where the playground is repo-access-gated and every compile therefore carries a GitHub login.

### Containment

`bubblewrap` **is** installed in this image, so `bwrap` is the profile to use here:

```bash
SERVE_PLAYGROUND_SANDBOX=bwrap
SERVE_PLAYGROUND_SANDBOX_RO=/root/.m2/repository,/root/.cache/composeai/fonts
```

That gives a snippet no network, no host filesystem beyond the read-only binds, no view of host
processes, and a cleared environment (so it cannot read `--admin-token` or cloud credentials out of
`/proc/self/environ`). Exactly one path is writable — the session work dir, which is deleted with
the snippet's token. Confirm it took on `/status.json` → `playground.sandbox.probe`: all four of
`egressBlocked`, `filesystemContained`, `processIsolated`, `workDirWritable` should be `true`.

Two things to know about which profile admits the lane, because earlier revisions of this file got
it wrong in both directions:

- **Repo-access-gated (this image's posture).** `PlaygroundPublicGate.decide` returns `Allow` for a
  repo-access-gated host *before* it reaches any profile check, so `bwrap` is accepted here. The
  shipped Compose defaults do not impose container CPU, PID, or memory cgroup caps: operators must
  configure `--memory`, `--cpus`, and `--pids-limit` (or their orchestrator equivalents). The JVM
  ceilings (`-Xmx`, `-XX:ActiveProcessorCount`) are defense in depth, not host-level resource caps.
- **Anonymous (no GitHub auth).** There, and only there, `bwrap` is refused for declaring no CPU or
  process-count cap, and the gate points at `strict` — which needs `systemd-run` and so remains
  unreachable in a container. An anonymous public playground on this image is still not a thing;
  issue #3211 tracks that.

> **Do not reach for `custom:` to work around this.** A `custom:` argv is a **static prefix**
> (`PlaygroundSandbox.command`, `Profile.CUSTOM -> customCommand`) — it is handed no `Paths`, so it
> cannot bind the per-session work dir, whose path does not exist until a compile starts. The two
> reachable outcomes are a jail so tight the render cannot write (preflight fails `workDirWritable`)
> or one loose enough to contain nothing (preflight fails the other three) — and the second is
> admitted silently in the repo-access-gated posture, because admission never rested on the jail
> there. `Profile.BWRAP` builds its argv from `Paths` and binds the work dir correctly. Use it.

`SERVE_PLAYGROUND_ANDROID_BUNDLE` — and `SERVE_PLAYGROUND`'s runtime catalog selector, which offers
every Android-backed catalog — additionally enable the Android / Remote Compose modes. Those need
the `lib-daemon-android` sidecar plus `android.jar`; the sidecar lives in `/opt`, which is already
ro-bound, but Robolectric also resolves an `android-all` jar out of `~/.m2/repository` at run time
and the jail has no network to fetch it. Downloadable fonts are likewise prewarmed under
`/root/.cache/composeai/fonts`. Bind both with
`SERVE_PLAYGROUND_SANDBOX_RO=/root/.m2/repository,/root/.cache/composeai/fonts` —
**prewarm that cache before going public**, since a cold cache inside `--unshare-net` cannot fill
itself. Render an Android preview once with the sandbox off, then turn it on.

If the bundle vars are missing, `/playground` returns a styled “Playground unavailable” page instead
of falling through as a missing design system. If they are set but the gate refuses, you get the
same page plus a refusal line in the startup log saying why — check `docker compose logs preview`
for `serve: playground admitted` / `serve: --playground-bundle …` before assuming the config never
landed.

## Auto-updates (zero-downtime)

Updates are **rolling** — existing traffic keeps being served on the old
container until the new one is up and healthy, so a deploy never 502s. Two
services split the work:

- **`rollout`** updates the `preview` server with
  [docker-rollout](https://github.com/wowu/docker-rollout). It polls GHCR and,
  when a new `:latest` lands, boots a **second** `preview` replica alongside the
  live one, waits for that replica's `/readyz` healthcheck to pass, lets Caddy
  drain traffic onto it, then retires the old replica. The chain stays hands-off:

  > merge → cut a `v*` tag → core release + `preview-host-image.yml` start in
  > parallel → image publishes `:latest` →
  > `rollout` pulls it → new replica boots + goes healthy → traffic drains over →
  > old replica retired

- **`watchtower`** updates only the `caddy` reverse-proxy (below). Caddy publishes
  fixed `80`/`443` ports, so it can't be scaled/rolled; Watchtower's in-place
  recreate is a ~1s proxy blip, and only when the baked-Caddyfile image changes.

### Instant roll on publish (webhook — skips the poll wait)

The `rollout` poll only notices a new image on its next tick, so a fresh release
sits up to `ROLLOUT_INTERVAL` (default 1200s) before the box even *starts* rolling.
The **`hook`** service closes that gap: it exposes a token-gated
`POST /__hooks/rollout` (routed through Caddy) that runs `rollout.sh` immediately, so
the publish CI can push the roll the moment the image lands:

> merge → release tag → `preview-host-image.yml` builds & pushes `:latest` → **its final
> step POSTs `/__hooks/rollout`** → `hook` runs `rollout.sh` → new replica boots +
> goes healthy → traffic drains over → old replica retired

**Fire on the image, not the release.** The webhook is triggered from the *end of the
image build*, not a `release: published` event — at image-publish time the GHCR image
is fully self-contained (baked CLI + plugin jars + live-render daemons), so
the box needs **only GHCR** to roll and **no Maven propagation can race it** (the
image workflow builds and seeds its local `m2` directly from the release tag).
A `release: published` webhook would fire *before* the image exists and roll the box onto
the *old* `:latest`.

**Safe to expose (behind Caddy TLS):**
- A bearer `DEPLOY_HOOK_TOKEN` is required; **fail-closed** — with none set the `hook`
  service stays up but idle and never opens its port (never an unauthenticated exec
  endpoint).
- The only effect is `rollout.sh` on the **already-configured** image tag. The caller
  can't choose what image runs, so a leaked/replayed token forces at most a rollout
  *check* of the tag the box is already pinned to — a bounded no-op, and idempotent
  (`rollout.sh` rolls only if the pulled digest actually changed).
- Single-flight: overlapping calls fold into the in-progress roll instead of launching
  parallel rollouts.

**Wiring it up.** `setup.sh` generates `DEPLOY_HOOK_TOKEN` into `.env` and prints it;
add the **same value** as the repo's `DEPLOY_HOOK_TOKEN` Actions secret. If the box
isn't `preview.coo.ee`, also set a `DEPLOY_HOOK_URL` repo *variable* to
`https://<your-domain>/__hooks/rollout`. The CI step is **best-effort** — with no
secret it's skipped, and any failure just falls back to the poll loop, which still
rolls within one interval. To disable the webhook entirely, comment out the `hook`
service **and** the Caddyfile `/__hooks/rollout` route; the poll loop keeps working.

**How the swap stays seamless.** `preview` has a Docker `healthcheck` on the
app's ungated `/readyz` **readiness** route — green only once the new replica has
actually rendered a preview, not merely bound its port (that's `/healthz`), so a
replica with a broken render pipeline never gets promoted. docker-rollout won't
retire the old replica until the new one reports `healthy`. Meanwhile the Caddyfile proxies to
`preview` via **dynamic upstreams** (re-resolving the service's Docker DNS every
few seconds) with cross-replica **retry**, so during the brief two-replica
overlap a request that hits the still-booting replica is retried onto the warm
one. Net effect: no dropped requests across an update.

Both `rollout` and `watchtower` poll every `1200`s (set `ROLLOUT_INTERVAL` in
`.env` to change the rollout cadence) and need the Docker socket (root-equivalent
on the host — fine for your own box). `rollout` also mounts this directory
read-only so it can `docker compose pull` + scale `preview`; the vendored
[`docker-rollout`](./docker-rollout) plugin is mounted into the container's CLI
plugins dir (no runtime download).

> **Manual rollout.** `setup.sh` also installs the plugin on the host, so you can
> force a zero-downtime update by hand with `sudo docker rollout preview` (or
> `./rollout.sh`, which pulls first and only rolls if the image changed).

> **Adopting this on a box first started before the project name was pinned.**
> `docker-compose.yml` now sets `name: compose-preview` so the `rollout`
> container's Compose commands target the same project as the host. A box brought
> up before that change ran under the **directory-derived** project name (`image`
> when deployed the documented way, from `deploy/image/`). Because the new `name:`
> takes precedence, a plain `docker compose down` here would target the *new,
> empty* `compose-preview` project and leave the old stack running — colliding on
> ports 80/443. So stop the **old** project by name first, then start the pinned
> one:
>
> ```bash
> docker compose ls                # find the old project name (e.g. `image`)
> docker compose -p image down     # stop the OLD stack explicitly
> docker compose up -d             # start the pinned `compose-preview` project
> ```
>
> One brief restart; rolling from then on.

**The reverse-proxy config auto-deploys too.** The `caddy` service runs
`ghcr.io/…/compose-preview-caddy:latest` — a `caddy:2` image with
`deploy/image/Caddyfile` **baked in** — rather than `caddy:2` + a bind-mounted
Caddyfile. Watchtower watches image digests, not files, so this is what lets a
Caddyfile change roll out on its own:

> edit `deploy/image/Caddyfile` → merge → `preview-caddy-image.yml` publishes
> `compose-preview-caddy:latest` → Watchtower pulls it → caddy recreated with the
> new config

Certs survive a recreate (they live in the `caddy_data` volume, so no
re-provision / rate-limit). `{$DOMAIN}` is still read from `.env` at runtime.
Pin a specific config with `CADDY_IMAGE_TAG=sha-<commit>` in `.env`.

> **Migrating an existing box** from the old `caddy:2` + `./Caddyfile` mount: pull
> this compose and `docker compose up -d` once — it swaps in the baked image. After
> that, Caddyfile edits ride Watchtower with no manual `caddy reload`.

> **First publish is private — make it public once.** GHCR packages default to
> private, so after `preview-caddy-image.yml`'s first run, set the new
> `compose-preview-caddy` package **public** (Packages → settings), exactly like
> `compose-preview-host` above. Otherwise a fresh or migrating box's unauthenticated
> `docker compose pull` fails on the caddy image *before Caddy can start* — no TLS,
> no proxy. (Alternatively, give the box registry creds — see *Private GHCR
> package* below; it now covers the caddy image too, not just the server.)

> **Image:** this uses the maintained
> [`nicholas-fedor/watchtower`](https://github.com/nicholas-fedor/watchtower) fork,
> pinned by tag+digest. The original `containrrr/watchtower` is effectively
> unmaintained and its baked Docker SDK negotiates API 1.25, which modern engines
> reject (`client version 1.25 is too old. Minimum supported API version is 1.40`) —
> so it silently never updates. Bump the tag **and** digest together to adopt a newer
> release.

Requirements / options:
- **Leave `IMAGE_TAG` unset (it defaults to the `latest` tag)** — both pollers only
  track a moving tag. A pinned `IMAGE_TAG=0.16.32` won't auto-update (by design).
  The value is a bare tag like `latest`, not `:latest` — the compose image string
  already supplies the colon (`…host:${IMAGE_TAG:-latest}`).
- **Zero-downtime updates:** the old `preview` keeps serving until the new replica
  is healthy, so there's no 502 window (contrast the old Watchtower recreate, which
  restarted `preview` in place for ~1 min). The swap briefly runs **two** `preview`
  replicas; on a memory-tight shared host cap the transient overlap with
  `PREVIEW_MEM_LIMIT` (which also lowers the derived live-seat budget per replica).
- **Private GHCR package:** mount registry creds so the in-container pull can
  authenticate — add `- ~/.docker/config.json:/root/.docker/config.json:ro` to the
  `rollout` service **and** the `hook` service (both run `rollout.sh` → `docker
  compose pull preview`) and `- ~/.docker/config.json:/config.json:ro` to
  `watchtower` (for `caddy`), after `docker login ghcr.io`. Public packages need
  nothing.
- **Pause auto-rollout:** comment out the `rollout` service and update `preview` by
  hand with `sudo docker rollout preview` (still zero-downtime) or the blunt
  `docker compose pull preview && docker compose up -d preview` (recreates in place).
- **Don't want any of it:** comment out both `rollout` and `watchtower` and update
  by hand with `docker compose pull && docker compose up -d`.

### Even simpler (no Caddy/TLS — quick test)

```bash
docker run -d --restart always -p 8080:8080 \
  -e SERVE_TOKEN="$(openssl rand -hex 24)" \
  ghcr.io/yschimke/compose-preview-host:latest
```

(Token rides in the clear over HTTP — throwaway only.)

## Files

| File | Purpose |
|------|---------|
| `Dockerfile` | Downloads the released CLI and carries the published Maven modules + live-render daemons. |
| `entrypoint.sh` | Maps `$PORT`/`$SERVE_TOKEN` onto serve flags; generous `--timeout`. |
| `docker-compose.yml` + `Caddyfile` | Pull the image + Caddy auto-HTTPS + zero-downtime (`rollout`) / Watchtower auto-updates + the `hook` instant-roll webhook. |
| `docker-compose.deploy-config.yml` + `test-deploy-config-mount.sh` | Opt-in overlay serving a deployment's `catalogs.json` / `producers.json` read-only from version control instead of the volume (see *Config from version control*), and its offline self-test (run by `ci.yml`). |
| `rollout.sh` | Poll loop / one-shot that pulls `preview` and rolls it via docker-rollout. |
| `deploy-hook.sh` | Token-gated `POST /__hooks/rollout` webhook (the `hook` service) that runs `rollout.sh` on demand — instant roll on publish. |
| `docker-rollout` | Vendored [docker-rollout](https://github.com/wowu/docker-rollout) CLI plugin (adds `docker rollout`). |
| `prewarm-fonts.sh` + `test-prewarm-fonts.sh` | Bake the downloadable-font cache into the image at build time (see *Fonts* below), and its offline self-test (run by `ci.yml`). |
| `setup.sh` | Install Docker + the docker-rollout plugin, write `.env`, pull + start. |
| `env-migrations.sh` + `test-env-migrations.sh` | One-off `.env` rewrites `setup.sh` applies to an already-deployed box (currently: drop the legacy three-app `SERVE_CATALOGS` pin so the baked catalog default applies), and their tests. |

## Fonts

The Android renderer resolves `Font(DeviceFontFamilyName("roboto-flex"))` — the shape Wear
Material3's type scale uses — by downloading the matching Google Fonts TTF and seeding it into
Robolectric's system font map (`PixelSystemFontAliases`); Robolectric's own `/system/fonts` only
carries a small AOSP subset. A slug it can't resolve falls back to plain Roboto **silently**.

That is why the image bakes the faces rather than leaving them to a runtime fetch: it removes the
first-render download, works with no egress to `fonts.googleapis.com`, and pins the live daemon to
the same bytes CI rendered the catalog PNGs with — the live lane and the baked PNG of one preview
should never show two different typefaces.

- The `fonts` build stage runs `prewarm-fonts.sh` into `/opt/font-cache/fonts`, mirroring
  `GoogleFontInterceptor`'s cache filenames and CSS2 query exactly. A family that won't resolve
  **fails the build** — a silently font-less image would just reintroduce the drift.
- `entrypoint.sh` installs the baked faces into `~/.cache/composeai/fonts` on every boot. It has to
  be a copy, not a `COPY` to that path: the cache is a named volume, and a volume only inherits
  image content when first created, so a long-lived box would otherwise never see them. The baked
  bytes win on a mismatch — the volume's copy has no authority (the PNGs are rendered in CI from
  *its* cache, not this host), so an entry left by an earlier runtime fetch is unknown-provenance
  and an upgrade must be able to correct it. Replacement is temp + `mv`, before serve starts.
  Faces the image doesn't ship are left alone.
- **Licensing.** Baking redistributes font binaries, which the runtime fetch did not. All baked
  families except one are in the [google/fonts](https://github.com/google/fonts) corpus under
  OFL-1.1 or Apache-2.0. **`Google Sans Flex` is the exception** — the CSS2 endpoint serves it, but
  it is in no license directory of that repo, so its terms can't be read off the corpus; it is baked
  because the project owner confirmed redistribution is cleared for this deployment. **A fork does
  not inherit that clearance** — re-check it, or drop the family from `FONT_PREWARM_FAMILIES`.
  Dropping it only forgoes the baked copy; the renderer still fetches it at runtime, as every family
  did before this stage existed. Override `FONT_PREWARM_FAMILIES` at build time to change the set.

## Notes / caveats

- The default runtime is module-less: it serves fetched catalogs and launches
  their trusted live bundles without running Gradle.
- Live bundles resolve coordinate dependencies from the baked Maven tree first,
  then the configured remote repositories. `SERVE_TIMEOUT` (default 1800s)
  guards slow first renders.
- `SERVE_CATALOG_MAX_IMAGES` forwards to `serve --catalog-max-images`; leave it empty for the CLI
  default, or raise it when the configured catalogs legitimately contain more images.
