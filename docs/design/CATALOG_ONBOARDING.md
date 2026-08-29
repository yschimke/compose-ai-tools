# Onboarding a GitHub project by URL

**Status:** plan, for [#4789](https://github.com/yschimke/compose-ai-tools/issues/4789). Nothing here
is built yet. It answers the issue's TODO — *how it works, the lifecycle, approvals* — against what
[`preview.coo.ee`](../public-preview-server.md) and this repo's CI already do, and is staged so each
phase is useful alone.

The ask: paste `https://github.com/owner/repo` and get a catalog at `/<system>/`, **with no change to
the target repository**. Today that is an operator with a shell, a `POST /admin/trust` and a
`POST /admin/catalogs` — [the admin API](../public-preview-server.md#the-admin-api) already makes it
two HTTP calls rather than a redeploy, but both need `SERVE_ADMIN_TOKEN`, which is
[a code-execution credential](../public-preview-server.md#trusted-producers) — and it presumes the
target repo already publishes a catalog branch, which a stranger's repo does not.

## The shape: a builder repo, and a PR as the approval

**The target repo is never edited, and never asked for anything.** A separate **builder repository**
holds one config entry per onboarded project, checks the target out, renders it, and publishes the
catalog branch the preview server serves. Onboarding a project is a **pull request against the
builder** carrying that entry; CI on the PR does a trial build and posts the renders it got. Merging
is the approval, and the merge publishes. A schedule re-renders each entry as its target moves.

Three properties fall out, and they are why this beats asking the target to add a workflow:

- **Nothing upstream to negotiate.** No workflow file, no `catalog.spec.json`, no plugin block, no
  maintainer to persuade. The CLI already
  [auto-injects the plugin](../isolated-projects-autoinject.md) into a build it does not own, via an
  init script, with no edit to the consumer — that is the primitive this whole design rests on.
- **The approval is a code review with evidence attached**, not a button. The diff is small and
  legible, CI proves the thing actually renders before anyone merges, and the audit trail is a PR.
- **No new server surface at all.** No consent page, no pending-request queue, no per-repo identity
  verification. The server keeps doing exactly what it does today: fetch a catalog branch from a repo
  it is configured to fetch from.

### It is mostly already built

[`integration.yml`](../../.github/workflows/integration.yml) is this system, at a smaller scale and
pointed at a different goal. It carries a matrix of external projects — `android/wear-os-samples`,
`android/androidify`, `android/nowinandroid`, `android/adaptive-apps-samples`, `yschimke/cadence`,
`yschimke/meshcore-mobile` … — as entries of exactly the shape an onboarding registry needs:

```yaml
- name: wear-os-samples (ComposeStarter)
  slug: wear-os-samples
  repo: android/wear-os-samples
  ref: main
  workdir: ComposeStarter
  module: app
```

It checks each one out, injects the plugin, discovers and renders previews, stages a gallery, and
**force-pushes a preview branch** — on a nightly cron as well as on push. Per-entry Gradle args
already absorb the awkward targets (ComposeStarter's isolated-projects + configuration-cache
settings), a `_skip_if_unchanged` marker already suppresses no-op pushes, and one drifty cell already
runs `continue-on-error` as a *signal* rather than a gate.

So the work is not "build a builder". It is: generalise that matrix into a registry anyone may PR
into, and point its publish at `design-artifacts/<system>` branches instead of integration baselines.

### Why a separate repository, and not a folder here

Blast radius. **Building a target repo executes that repo's code** — Gradle build scripts, plugins
and annotation processors all run at configuration and build time. A registry that accepts strangers'
projects is therefore a job that runs hostile code by design, and it must not share a repository with
this project's release credentials, remote-cache tokens, or `contents: write` on `main`. Separate
minutes accounting and a separate branch namespace are the lesser reasons.

## The one safety property, and how it survives

The [original constraint](../public-preview-server.md#two-axes-trust--format) — *a public box never
runs a stranger's Compose* — is unchanged and still load-bearing. **The preview server still builds
nothing.** What this design adds is that a *disposable CI runner* does, and that runner is arranged
so a hostile target gets nothing worth having:

| Control | Why | Precedent |
|---|---|---|
| **The build job holds no write credential.** Build and render run under a read-only token; a **separate publish job** takes the staged bytes and pushes them, and runs no target code at all. | The one job that can write is the one the target cannot influence. | Already exactly how `integration.yml` is structured — *"the only place a write token is exposed, and it runs NO consumer code"*. |
| **No secrets reach the build job.** Notably not the Gradle remote-cache token: `integration.yml` passes one, and an onboarding builder must not. | A hostile build's cheapest win is exfiltrating whatever is in the environment. | New, and the single most important difference from the workflow it is copied from. |
| **Targets are pinned by commit SHA**, resolved at PR time and bumped by the refresh job. | A reviewed entry stays the thing that was reviewed; a branch can be re-pointed after approval. | New. |
| **Hard timeout, concurrency cap, no self-hosted runners.** | Bounds the cost of a target that mines or spins. | Partly present (job timeouts). |
| **Public repos only, and the entry records the licence.** | Rendering source we were not given is a policy question, not a technical one — see below. | New. |

### The correction this forces on the trust model

With one builder publishing every catalog, all of them arrive from **one producer**. That is a
simplification for the trust store — one `POST /admin/trust` entry rather than one per project — and
it is a trap, because trust is not a badge:
[it gates server-side re-render](../public-preview-server.md#trusted-server-side-re-render---allow-render-trusted).
Blanket-trusting the builder's branch namespace would make *every onboarded project's* Compose
eligible to be built and executed on the box, on a deployment where `SERVE_ALLOW_RENDER_TRUSTED`
defaults on. One trusted producer would silently mean forty trusted targets.

So:

- **Onboarded catalogs publish untrusted, and serve baked PNGs.** They badge `unverified` and show
  the existing `catalog-baked-only` / `unverified-no-rerender`
  [degradation banner](../public-preview-server.md#why-a-session-is-snapshot-only--the-degradation-banner).
  This is a complete product — the gallery tier of
  [`HOSTED_SERVICE_PLAN.md` §2](../HOSTED_SERVICE_PLAN.md) is exactly this — and a CMP catalog is
  still live in-browser via the [Wasm tier](../public-preview-server.md#two-axes-trust--format), which
  executes nothing server-side.
- **Provenance names the target, not the builder.** The published bundle records
  `{targetRepo, targetSha, builderRun}`, so a badge can never say "trusted" about bytes whose origin
  is a repo nobody vetted. The existing
  [`attributionRepos`](../public-preview-server.md#the-catalog-set-is-config-not-image-content)
  mechanism is the precedent and probably the mechanism: it exists so Android's samples, fetched from
  preview branches in a fork, are credited to `android/compose-samples`.
- **Re-render eligibility, if it is ever granted, is per target**, decided once someone has watched a
  project publish for a while — never a property of the builder's branch prefix.

## What the builder cannot do, and how you find out

Auto-injection has a documented hard limit: it applies the plugin through
`allprojects { buildscript { … } }`, which **Gradle's Isolated Projects rejects outright**, and there
is [no mechanism that is simultaneously IP-clean, edit-free, and on AGP's
classloader](../isolated-projects-autoinject.md). A target with `org.gradle.unsafe.isolated-projects`
on in its own `gradle.properties` cannot be onboarded this way at all. Other targets will need per-entry
Gradle args, a `workdir`, an AGP floor, an SDK component, or a secret they are never going to get.

This is precisely why **the trial build belongs on the PR**: a target that cannot be built without
upstream cooperation says so in CI, before anyone merges, with the log attached. An entry that only
works with per-project args records them in the entry, where they are reviewed. The unonboardable
minority is then a small, visible set — and for *those*, the fallback is the original route: a PR
against the **target** repo adding a caller of
[`design-artifacts-reusable.yml`](../../.github/workflows/design-artifacts-reusable.yml), which its
maintainers merge and own. That route stays supported and is the better one for any project that
*wants* to publish its own catalog under its own name; it is no longer the default.

## The consent question the PR does not answer

A PR into the builder is **our** approval to host. It is not the target project's approval to be
hosted, and this design has no moment where the target says yes. That is a defensible position —
rendering public, open-source UI into a public gallery is close to what a docs site or a CI badge
does — but it has to be a stated policy rather than an omission:

- **Public repositories only**, with an OSS licence recorded in the entry.
- **Attribution, not endorsement.** Every card and page names the source repo and the exact commit,
  and nothing implies the project published it. `attributionRepos` already does the naming half;
  a *"rendered from `owner/repo@sha` by preview.coo.ee, unaffiliated"* line is the other half.
- **Opt-out on request, honoured fast** — a documented address, and removal by reverting the entry.
  Cheap to offer and worth far more than it costs.
- **Opt-in is still better where it is available.** If a maintainer asks to own their catalog, hand
  them the reusable workflow and retire the builder entry.

Whether to require a signal from the target repo (a marker file, an issue, a maintainer's comment)
before onboarding it is [open question 1](#open-questions-for-4789).

## Lifecycle

```
PR against the builder ──► trial build on the PR ──┬─► fails ──► entry not merged
  (entry: repo, ref/sha,                           │            (log says what upstream would need)
   workdir, module, args)                          │
                                                   └─► renders ──► review ──► merge = approval
                                                                                  │
                                    ┌─────────────────────────────────────────────┘
                                    ▼
                       publish job pushes design-artifacts/<system>
                                    │
                                    ▼
                       server registers it (POST /admin/catalogs, unlisted, untrusted)
                                    │
                    ┌───────────────┼────────────────┐
                    ▼               ▼                ▼
              scheduled       operator lists    N consecutive
              re-render        it / promotes     build failures
              (SHA bump)       to trusted             │
                    │                                 ▼
                    └──────────► stale ──────────► entry disabled, catalog retired
```

- **Scheduled re-render.** A cron per entry (nightly for a few, weekly for most), resolving the
  target's ref to a new SHA and skipping the push when nothing changed — `_skip_if_unchanged` already
  does that half. The server's own branch refresher
  ([`SERVE_CATALOG_REFRESH`](../public-preview-server.md#image-seed-vs-deployment-config)) then picks
  the new head up with no restart, so nothing needs to poke the box.
- **Failure is expected and must be boring.** Targets drift, and one broken entry must never fail the
  run for the others (`fail-fast: false`, per-entry `continue-on-error`, exactly as the drifty
  `androidchka` cell is handled today). An entry that fails N runs in a row is auto-disabled with an
  issue filed; the already-published catalog keeps serving its last good bytes until it goes stale.
- **Retire** is `DELETE /admin/catalogs/<system>`, which already drops the session and any daemon.

## Phases

**Phase 1 — the builder, seeded by hand.** Stand the repo up with the registry format, the
untrusted-build/trusted-publish split, the trial-build PR check, and the cron. Seed it with three or
four projects already known to build (the `integration.yml` matrix is a ready-made list, and its
`yschimke/cadence` cell is already a published catalog). Publishing into the server stays an operator
`POST /admin/catalogs` at this stage — a handful of entries does not need automating.

**Phase 2 — the paste-a-URL front door.** A page on the server that takes a GitHub URL, probes the
repo (build-free: does it exist, is it public, is it a Gradle/Compose project, is Isolated Projects
on in `gradle.properties` — the one known-fatal marker), and **opens the builder PR for the
requester**, prefilled. The request becomes a PR without the requester needing to know the registry
exists. This is where a GitHub App earns its place
([`HOSTED_SERVICE_PLAN.md` §7](../HOSTED_SERVICE_PLAN.md)) — opening the PR is the one thing OAuth
alone cannot do.

**Phase 3 — publish on merge, and upkeep.** The builder calls `POST /admin/catalogs` itself with a
scoped token when an entry first publishes; auto-disable on repeated failure; the operator's view of
the self-serve set on `/status`; per-target promotion to trusted as a separate, typed decision.

## What this supersedes

An earlier revision of this document (merged in
[#4793](https://github.com/yschimke/compose-ai-tools/pull/4793)) had onboarding end in a PR against
the **target** repo, gated by a per-repo GitHub identity check and an operator consent page with a
pending-request queue persisted on `/config`. The builder inverts that, and most of it is no longer
needed:

| Dropped | Because |
|---|---|
| Consent page + pending queue on `/config` | The PR *is* the queue and the consent record. |
| Per-repo `push`-permission verification | The requester no longer needs rights on the target; approval is ours to give. |
| A workflow PR against the target repo | Now the fallback for the IP/awkward minority, not the main path. |

What survives unchanged: untrusted-and-unlisted by default, trust as a separate typed decision,
staleness eviction, and the observation that self-serve growth makes
[`HOSTED_SERVICE_PLAN.md` §5.3](../HOSTED_SERVICE_PLAN.md)'s single-process admin config debt larger.
Unlisted-by-default still holds for the same reason as before — `listed: false` keeps the front page
curated while `/<system>/` is live immediately, and `POST /admin/catalogs` already converges a changed
`listed` in place.

## Non-goals

- **The preview server building anything.** The builder is CI; the box that serves traffic keeps
  executing nothing. The [playground](PLAYGROUND.md) remains the single deliberate exception to that
  rule, and it is jailed, capped and preflighted.
- **Auto-trust, auto-list, auto-live-seats.** Each stays a separate operator decision.
- **Private repos, multi-tenancy, billing.** Later phases of the hosted-service plan, if ever.

## Open questions for #4789

1. **Does a target have to signal consent before we render it?** Public + licensed + attributed +
   opt-out is the low-friction posture and matches what CI badges and docs sites already do. Requiring
   a marker file or a maintainer's comment is politer and roughly halves the addressable set.
2. **Where does the builder live, and what is it called?** A new repo is argued for above; the entry
   format and the reusable render steps still come from here.
3. **How much builder time is this worth?** ~3 minutes per target per run on the measured
   `integration.yml` cells; 40 targets nightly is ~2 hours of runner time a day.
4. **Disk cap per catalog and in total**, on an 8 GB box already holding ~17 catalogs — unchanged from
   the previous revision, and now more pressing, since this path can add catalogs much faster.

## See also

- [`integration.yml`](../../.github/workflows/integration.yml) — the builder, already running at small scale.
- [`isolated-projects-autoinject.md`](../isolated-projects-autoinject.md) — why edit-free injection works, and where it stops.
- [`public-preview-server.md`](../public-preview-server.md) — the admin API, trust, degradations, attribution.
- [`HOSTED_SERVICE_PLAN.md`](../HOSTED_SERVICE_PLAN.md) — §5.3 multi-replica config, §7 the GitHub App.
- [`DESIGN_CATALOGS.md`](DESIGN_CATALOGS.md) — what a catalog is.
- [`design-artifacts-reusable.yml`](../../.github/workflows/design-artifacts-reusable.yml) — the fallback route, owned by the target.
