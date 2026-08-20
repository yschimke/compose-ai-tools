# Retaining catalog content across a restart

**Proposal.** A durable, commit-addressed home for fetched catalog content, so a redeployed
`compose-preview serve` starts from the catalogs it already had, converges to the branch tip in the
background, and re-fetches only what actually moved — with an admin flush for when someone needs to
force the issue.

Companion to [the theme cache](../public-preview-server.md#the-cache-can-outlive-the-process---theme-cache-dir),
which solved the same problem one layer up: that one persists *derived pixels*, this one persists
*published bytes*. The two are independent and compose — a boot that adopts both is a boot that
fetches nothing and renders nothing before it can serve.

## What a restart costs today

`serve --catalogs` fetches each system's `design-artifacts/<system>` branch into a directory created
by `ServeCommand.registerCatalogs`:

```kotlin
// ServeCommand.kt:3099
java.nio.file.Files.createTempDirectory("serve-catalogs").toFile().also { it.deleteOnExit() }
```

Everything a catalog accumulates lives under that root — the staged `previews/`, the fetched
`bundle/` (the executable `liveBundle`, up to 100 MB, plus its per-preview splits), `web/wasm/`,
`figma/`, `ir/`, the design references and pages, and the shared content-addressed
`.res-cache/<sha>` pool. It is a temp directory, so **a container recreation discards all of it**.
The live-bundle materialisation lands in temp dirs of its own (`ServeCommand.kt:3304`, `:3327`,
`:3496`, `:3510`), so the resolved daemon classpaths go the same way.

`preview.coo.ee` runs [23 catalogs](../../deploy/preview.coo.ee/catalogs.json) and is rolled by
`docker-rollout` on every published image — the webhook rolls on publish, the poll loop every 1200 s
as a fallback. Each roll therefore re-runs the whole fetch: 23 branch heads, 23 `catalog.json`s, 23
Atom feeds, every `liveBundle` and resource pool for the catalogs that carry one, and then every
baked PNG again, lazily, as visitors touch them.

It is not only restarts. `ServeCatalogStore.load` finishes with:

```kotlin
// ServeCatalogStore.kt:678
dir.deleteRecursively()
if (!staging.renameTo(dir)) { … }
```

The staging swap is what stops a failed refresh turning a healthy catalog into 404s, and it should
stay. But it also means **every reload throws away the previous generation entirely** — including
the bundle, the splits, the wasm app and every lazily-filled PNG, none of which the new revision
necessarily changed. Delivery branches regenerate several times a day. Only `.res-cache` survives a
reload, and only because it sits above `dir`.

Two consequences worth naming:

- **Time to serve.** A rolled replica is not useful until its catalogs are up, and `/readyz` gates
  the rollout on a representative preview actually rendering (`start_period: 90s`, and that is a
  floor, not a typical).
- **`raw.githubusercontent.com`.** Every one of those reads is an unauthenticated request to a host
  that rate-limits. `BranchFetchStats` already counts `throttled` separately from `notFound`
  precisely because this has bitten the box before; the cheapest throttle to survive is the request
  never issued.

## The property that makes this easy

A load does not read a *branch*. It resolves the delivery commit from the Atom feed and pins every
subsequent URL to it:

```kotlin
// ServeCatalogStore.kt (load)
val base = deliveryCommit?.let { "https://raw.githubusercontent.com/$repo/$it/" }
  ?: "https://raw.githubusercontent.com/$repo/$branch/"
```

So essentially every byte a catalog fetches is addressed by `(repo, commit, path)` — and that
address is **immutable**. The pinned-permalink lane (`?at=<sha>`, `ServeCatalogRevision`) reads the
same way by construction.

This is a much stronger position than the theme cache started from. That one caches *derived*
pixels, whose inputs it can only approximate — hence `ThemeCacheFingerprint`, the generation
directories, and the load-time sample verification that exists because a fingerprint can only cover
inputs it was told about. Here there is nothing to approximate: a cached blob either was fetched
from that exact immutable URL or it was not. **Staleness cannot mean "wrong bytes for this
revision"; it can only mean "an older revision".** That collapses the whole design down to one
question — *which commit are we serving, and is it still the tip?* — and that question already has a
cheap answer (`gitLsRemoteHead`, one `git ls-remote` per branch; or the Atom feed, one request that
returns the head *and* ~20 commits of history).

The one place the immutability argument does not hold is the **unpinned fallback**, when the feed
could not be read and `base` is the branch ref. Those reads are mutable and must simply never enter
the durable tier — see [Rules the cache must not break](#rules-the-cache-must-not-break).

## What gets persisted

A new `--catalog-cache-dir <dir>|none`, mirroring `--theme-cache-dir`'s conventions exactly
(explicit flag wins; `none` is the off switch; a non-writable directory disables the tier and says
so). Unlike the theme cache, **unset falls back to today's temp directory** rather than to nothing:
temp is the current behaviour and is still correct, just not durable.

Three tiers under that root, in increasing order of ambition:

### Tier 1 — the blob pool (biggest byte win)

`<root>/blobs/<sha256>` — content-addressed, shared across systems, generations and reloads. This is
`.res-cache` promoted out of the temp root and widened to cover the executable bundles it sits
beside: the monolithic `liveBundle`, the per-preview splits under `bundle/previews/`, and the
externalised classpath blobs it already holds.

Correctness here is already established: `fetchExternalClasspathBlob` verifies the declared
`sha256` and size before it will use a cached file, and `isCompleteExecutableBundle` re-reads a
cached bundle's manifest rather than trusting its presence. The change is *where the file lives*,
not whether it is checked.

### Tier 2 — the asset cache

`<root>/assets/<sha256(url)>` for ordinary catalog assets read through `fetchCatalogAsset`, admitted
**only when the URL is commit-pinned**. This covers `catalog.json` itself, the lazily-fetched baked
PNGs, motion captures, figma SVGs, design references and pages — and, for free, the `?at=<sha>`
permalink lane, which is the same shape of read against an older commit.

The seam is small: `fetchCatalogAsset` and `fetchCatalogAssetOutcome` are already the two funnels
every asset read goes through, and `branchRead` already wraps the transport for telemetry. A cache
lookup goes in the same place, and `BranchFetchStats` grows a `cached` counter beside `ok` so the
avoided requests are countable rather than asserted.

### Tier 3 — the generation snapshot

`<root>/catalogs/<system>/<commit>/` holding the whole staged directory a load produces, plus
`<root>/catalogs/<system>/current` naming the commit to adopt. This is what makes a *boot* fast
rather than merely cheap: with tiers 1 and 2 a restart still walks every catalog and re-assembles
its staging directory, it just does so from local bytes. Tier 3 skips the assembly.

The staging swap changes from "delete the old generation" to "the old generation *is* its own
directory, keyed by the commit that produced it; move the `current` pointer". A reload then costs
one directory rename, a revision rollback becomes instant, and the sweeper (below) is what reclaims
superseded generations instead of the swap doing it eagerly.

## Boot: adopt, then converge

Today's startup is: fetch → register → serve. The proposal makes it:

1. **Adopt.** For each configured catalog, read `current`. If that generation is on disk and
   passes its adoption checks, register the host from it immediately — no network on this path.
   The catalog is serving in the time it takes to read a manifest, and `/status` records
   `provenance = adopted@<commit>` with the timestamp it was originally fetched.
2. **Converge, in the background.** Resolve the branch head — the refresher already owns this
   (`ServeCatalogRefresher.checkOne`, `gitLsRemoteHead`). Then:
   - head **equals** the adopted commit → mark the generation `verified`, and the boot has cost
     zero asset fetches;
   - head **differs** → run today's `load()` into a fresh generation and swap the pointer. Exactly
     the current code path, exactly the current staging protection, just off the critical path;
   - head **unresolvable** (offline, `git` absent, throttled) → keep serving the adopted copy and
     say so. This is strictly better than today, where the same condition means the catalog does
     not serve at all.
3. **Reconcile the pointer.** Seed `ServeCatalogRefresher.lastHead` from the adopted commit so the
   poller's SHA short-circuit is correct from the first tick rather than after it.

This is the user-visible shape of "start up with the existing catalogs, and check for changes": the
check happens, it just does not block anyone. It is also where the *write-behind* idea lands
naturally — the cached result is served, the freshness question is answered asynchronously, and the
update is pushed when the answer comes back "moved".

### Adoption checks (fail-closed)

A generation is adopted only when all of these hold. Any failure drops it and falls back to a normal
load — never to serving it anyway.

| check | why |
| --- | --- |
| The generation's recorded `(system, repo, branch, commit)` matches the **current** configuration | An operator repointing a system at a different repo must not adopt the old repo's bytes. |
| The **current** trust store still trusts `repo@branch` | `ServeCatalogStore` deliberately reads `trust()` per fetch, so a producer revoked through `/admin/trust` takes effect on the next load. Adoption must re-evaluate the verdict rather than replay the one baked into the snapshot — otherwise a restart resurrects a revoked producer's `Trusted` badge, and on a `--allow-render-trusted` box that badge is what makes its code eligible to execute here. |
| Executable content (`liveBundle`, splits, `web/wasm/`) re-verifies against the adopted commit's own declarations | Same rule the fetch path applies, applied to bytes that arrived from disk instead of the network. Cheap: a sha check over files we already hold. |
| `catalog.json` parses and the manifest is internally consistent | A half-written generation from a killed process must not be adopted. Writing the pointer last, after an atomic rename, makes this a formality rather than a real risk — which is the point. |

## Flushing

Three levels, cheapest first:

- **`POST /<system>/refresh`** already exists and already forces a branch check. It gains
  `?flush=1`, which drops the adopted generation for that system and re-fetches from scratch.
- **`DELETE /admin/catalog-cache/<system>`** and **`DELETE /admin/catalog-cache`** on the existing
  token-gated admin surface, for "throw it all away and start again" without a shell on the box.
- **The volume.** `docker volume rm` remains the operator's blunt instrument, and the tier is
  designed so losing it is a slow boot rather than an incident.

`--catalog-cache-dir none` is the standing off switch, and the tier degrades to exactly today's
behaviour when it is set.

## Where sampling actually belongs

The "sample previews, serve the cache, prove it out of date" idea is the right instinct, and the
theme cache already implements the honest version of it (`CatalogThemeCache.verifySample` — adopted
entries are *withheld* from the read path until a sample confirms them, and a mismatch discards the
whole generation).

For **content**, that machinery is not needed on the read path and should not be built there: the
bytes are commit-addressed, so verifying them by re-fetching is just fetching. Adopted content
should be served immediately, not quarantined. Where sampling still earns its place is as a **low-rate
background audit**, answering questions immutability does not:

- disk corruption, or an entry written under the wrong key;
- a delivery branch whose history was **rewritten**. The published workflow appends regeneration
  commits rather than force-pushing an orphan ([Delivery-branch history](DESIGN_CATALOGS.md#delivery-branch-history)),
  so an adopted commit stays reachable — but the cache should not *depend* on that promise. The
  Atom feed already returns ~20 commits, so confirming the adopted commit is still in recent history
  costs one request per catalog, on the same pass that resolves the head. That is the
  "check for changes and flush if needed" case, done in 23 requests instead of thousands.

A sampled byte-comparison of N adopted assets against their pinned URLs, run on the background lane
at a low rate, is worth having as a canary. It should **report** loudly and drop the generation on
mismatch — but it must not gate serving, because a mismatch here means something is wrong with the
disk or the branch, not that the design is unsound.

## Rules the cache must not break

1. **Never persist an unpinned read.** When the revisions feed could not be read, `base` is the
   branch ref and the bytes are mutable. Those reads stay exactly as they are today: fetched, used,
   not cached. One rule, no TTLs, no revalidation, nothing to get subtly wrong.
2. **Never adopt without re-evaluating trust.** See the adoption table.
3. **Never let the cache turn a fetch failure into a wrong answer.** A cache miss is a fetch; a
   fetch failure is what it is today. The tier only ever removes work, it never changes an outcome.
4. **Never share a volume with `preview_config`.** The catalog set and trust store live there. This
   cache gets `preview_catalog_cache:/catalog-cache`, for the same reason the theme cache got its
   own volume.

## Disk, sweeping, and the overlapping-replica problem

Budget: `--catalog-cache-max-bytes`, defaulting conservatively, enforced by a sweeper rather than at
write time (the same reasoning as `ThemeCacheStore`: a writer must not block on a byte census).

What the sweeper reclaims, once the catalog pass has finished and the live set is actually known:
superseded generations, and blobs no live generation references. What it spares: anything younger
than a grace window.

That grace window is not optional here. `docker-rollout` boots the new replica **alongside** the
running one and both mount this volume; a new replica that reclaimed generations it does not
recognise would delete the outgoing replica's catalogs out from under it — and if the new replica
then failed `/readyz`, the old one would carry on with its content deleted. `ThemeCacheStore` solved
exactly this with `DEFAULT_SWEEP_GRACE_MILLIS`; reuse the reasoning, and the shape.

Adoption must also be **read-only-safe**: a booting replica adopts a generation by reading it, never
by moving or rewriting it.

## Observability

Without this, a cache that is quietly doing nothing looks identical to one that is working — the
lesson `/status.json`'s `themeCache` block already encodes.

Store-wide, a `catalogCache` block: `bytes`, `blobs`, `generations`, `generationsBySystem`,
`adopted`, `hits`, `misses`, `writes`.

Per catalog, on its status row: `provenance` — `{ source: adopted | fetched, commit, fetchedAt,
adoptedAt, verifiedAt }`. The single number that says whether this feature works is **`adopted`
after a roll**: zero, on a box that should have found warm generations, is the failure, stated
plainly. `generationsBySystem` climbing across restarts that changed nothing is the churn detector.

And on the existing `branchFetch` row, a `cached` counter beside `ok` — the requests this tier did
not make, which is the whole claim.

## Phases

Each ships independently and is provable on its own.

| # | Change | Wins | Risk |
| --- | --- | --- | --- |
| **1 — landed** | `--catalog-cache-dir` + `--catalog-cache-max-bytes`; `.res-cache` and the executable bundles + splits moved into a content-addressed [`CatalogBlobPool`](../../cli/src/main/kotlin/ee/schimke/composeai/cli/serve/CatalogBlobPool.kt) | The largest byte win (100 MB-class bundles), and it survives *reloads* as well as restarts | Low — the sha verification already existed; only the path changed |
| **2 — landed** | Asset cache for commit-pinned reads, behind `fetchCatalogAsset`; `cached` counter on `branchFetch` | Manifests, lazy PNGs, motion, references, and the `?at=` lane stop re-fetching | Low — one funnel, one admission rule |
| **3** | Generation snapshots + `current` pointer; adopt-then-converge boot; seed the refresher's head | The restart win: catalogs serve before any network | Medium — the adoption checks are where the care goes |
| **4 — landed** | `DELETE /admin/catalog-cache`, `POST /<system>/refresh?force=1`, `catalogCache` status block | Operability, and the ability to tell a working cache from write amplification | Low |
| **5 — landed, reduced** | Sampled post-publish audit of the cache's key→content mapping | Catches the one fault content-addressing cannot see | Low — reports, never gates |

Phases 1–2 are worth doing regardless of whether 3 lands: they are pure subtraction from the fetch
path with no new correctness surface. Phase 3 is where the headline number is.

### What phase 1 shipped, and where it differs from this plan

Two deliberate departures, both in the direction of shipping less surface rather than more:

- **The sweeper came with phase 1, not phase 4.** It was planned late because it reads like
  operability, but an opt-in durable cache with no ceiling is a "you turned this on and it filled
  your disk" waiting to happen — and it turned out to be ~30 lines here rather than the careful
  live-set reasoning `ThemeCacheStore.sweep` needs, because evicting a pooled blob is *always* safe:
  the worst it costs is the fetch that produces it again. The grace window came with it, for the
  overlapping replicas a rolling update creates.
- **There is no derived default.** The plan said "mirroring `--theme-cache-dir`'s conventions
  exactly", and the theme and feed caches both default to a directory beside `catalogs.json` — which
  on the prebuilt image is the *configuration* volume. That is fine for a few MB of feed XML and
  wrong for several GB of executable bundles, and the theme cache already had to introduce a `none`
  sentinel to defend against exactly that. So this one has no derived location at all: unset means
  the temp-dir pool the server always had, a path means that path, `none` forces the temp dir back.

Also worth recording, because it is the number phase 3 has to beat: with the pool warm, what a boot
still pays is the *manifests* — one Atom feed, one `catalog.json`, one preview index and the
reference/page indexes per catalog — plus re-assembling each per-system staging directory. The
executable tier, which was the bulk, is now read locally.

### What phase 2 shipped, and what it leaves for phase 3

Phase 2 took the manifests off that list too: every commit-pinned read now goes through the pool, so
a boot on an unchanged revision reads `catalog.json`, the preview and reference indexes, and every
baked asset a visitor touches from disk. One departure from the sketch above, in the same
ship-less-surface direction: the admission rule is decided **from the URL**
(`ServeCatalogRevision.isCommitPinned`) rather than threaded from the load as a flag. That looked
like re-deriving a fact we already had, and for the executable lane it would have been — but these
reads do not all come from the load's own base. A `?at=<sha>` request addresses a commit the load
never resolved and is exactly as immutable, so the URL genuinely is where the answer lives, and the
rule sits beside the regex that already validates a visitor's pin.

What is left for phase 3 is therefore **narrower than when this plan was written**, which sharpens
the open question rather than settling it: the remaining boot cost is resolving each branch head
(one `git ls-remote` per catalog) and re-assembling each per-system staging directory from bytes
that are already local. Generation snapshots would remove the second; nothing removes the first,
because that is the freshness check itself. Measure before building.

### What phase 4 shipped, and the affordance it dropped

The plan promised `?flush=1` and `DELETE /admin/catalog-cache[/<system>]`. Both spellings turned out
to overstate what is expressible, and shipping them as written would have been a lie in the API:

- **There is no per-system delete.** Blobs are named by their own digest and shared between systems
  deliberately — a font fetched for one catalog is the same file the next one reads — so no blob has
  an owning catalog to delete it by. The only way to offer `/<system>` would be to partition the
  pool by system, giving up the deduplication that is one of its two reasons to exist. The clear is
  whole-pool and says so.
- **`?flush=1` became `?force=1`.** A per-catalog *flush* would mean "ignore the cache for this
  load", which needs a bypass threaded down to every read in the load, and it would buy very little:
  the cache is commit-addressed and every blob re-verifies against its own digest on read, so
  "cached bytes are wrong" is structurally prevented rather than merely unlikely. What an operator
  actually lacks is a way to say *read it again anyway*, because the refresh short-circuits on an
  unchanged branch head. That is `?force=1`, it reuses the head-forgetting that trust revocation
  already needed, and composing it after a whole-pool clear gives the full re-fetch.

The rename matters more than it looks: `flush` on a route that does not evict anything would read as
a cache control and behave as a poll, and the next person to reach for it would be reaching for the
wrong thing.

### What phase 5 shipped, and why two thirds of it was dropped

The plan justified phase 5 on three grounds. What actually shipped in phases 1–2 removed two of
them, and it is worth recording that rather than building the audit as specified:

- **Disk corruption** is already caught. Every blob is named by the sha256 of its own bytes and
  re-verified on read (phase 1), so a truncated or bit-rotted file cannot be served. A sampled byte
  comparison adds nothing here.
- **Rewritten history** was a worry for *adopted commits*, which only phase 3 would introduce. As
  built, every load resolves the branch head fresh and nothing is adopted across restarts — and a
  git sha's content is immutable by construction, so a force-push can make a commit unreachable but
  never make it serve different bytes. There is no reachability question to ask.

What remains is a fault neither the plan nor the implementation had named: **the key→content
mapping is the one thing nothing verifies.** A blob is checked against its own name; the pointer
that says "this URL holds that sha" is not checked against anything. A key filed against the wrong
content — a mistaken `write` call site, a refactor reusing a key — passes every existing check,
because the blob under that sha hashes to it perfectly well, and the pool then serves the wrong
bytes for that URL indefinitely. Content-addressing makes corruption impossible and mis-filing
invisible.

So phase 5 is a three-asset sample re-read from the branch after each pinned load, compared against
what the pool would serve, reported as `audited` / `mismatched`. Small on purpose: it is checking
for something the design says cannot happen, so its job is to be running at all rather than to be
exhaustive.

## What this deliberately does not do

- **It does not make the cache authoritative.** The branch is. The cache only ever answers for a
  commit the branch published, and every adopted generation is on its way to being re-checked.
- **It does not cache renders.** That is `--theme-cache-dir`, which already exists and is
  independent of this.
- **It does not add a TTL anywhere.** A TTL would be a guess standing in for the exact answer that
  `git ls-remote` and the Atom feed already give.
- **It does not touch the staging protection.** A failed refresh must still leave the serving copy
  alone; that property is what makes the whole thing safe to iterate on.

## Open questions

1. **Should tier 3 land at all, given tiers 1–2?** With the blob and asset caches warm, a boot's
   remaining cost is assembling 23 staging directories from local bytes. That may already be fast
   enough to make generation snapshots unnecessary complexity. **Measure phases 1–2 before
   committing to 3** — the honest version of this plan is that phase 3's value is a hypothesis.
2. **Where should the default live on the prebuilt image?** The theme cache took a dedicated volume
   and an opt-in `.env` line. The same is right here, but the default sizing needs a real number for
   23 catalogs × a few generations × 100 MB bundles before it is picked.
3. **Does `bundle-deps` (the resolved live-bundle classpath under `~/.cache/composeai`) belong in
   this store?** It is already on a durable volume for the same reason, by a different mechanism.
   Worth a look at whether one store should own both.
