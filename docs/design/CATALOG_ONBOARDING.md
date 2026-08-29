# Onboarding a GitHub project by URL

**Status:** plan, for [#4789](https://github.com/yschimke/compose-ai-tools/issues/4789). Nothing here
is built yet. It answers the issue's TODO — *how it works, the lifecycle, approvals* — against what
[`preview.coo.ee`](../public-preview-server.md) already does, and is deliberately staged so each
phase is useful alone.

The ask: paste `https://github.com/owner/repo` into the preview server and get a catalog at
`/<system>/`. Today that is an operator with a shell, a `POST /admin/trust`, and a
`POST /admin/catalogs` — [the admin API](../public-preview-server.md#the-admin-api) already makes it
two HTTP calls rather than a redeploy, but both need `SERVE_ADMIN_TOKEN`, which is
[a code-execution credential](../public-preview-server.md#trusted-producers). Self-serve is not a new
serving path; it is a way for somebody who is *not* the operator to reach that path safely.

## The one claim everything else hangs off

**The server never builds the repo.** Rendering happens in the project's own CI — that is the whole
economic shape in [`HOSTED_SERVICE_PLAN.md` §2](../HOSTED_SERVICE_PLAN.md), and "a public box runs a
stranger's Gradle" is an [explicit non-goal](../public-preview-server.md#two-axes-trust--format)
(`--revisions` off, trust fail-closed). So onboarding is never *"the server renders your repo"*; it
is:

> **your CI publishes a `design-artifacts/<system>` branch, and this box agrees to serve it.**

The "trivial" part of a trivial onboarding flow is removing the **operator round-trip**, not moving
the render. Everything below follows from that.

## Two cases, and only one of them is short

A URL lands in one of two states, and the probe's first job is to tell them apart:

**A — the repo already publishes.** A `design-artifacts/*` branch exists (the repo already calls
[`design-artifacts-reusable.yml`](../../.github/workflows/design-artifacts-reusable.yml), as
Horologist and the Confetti app do). The bytes exist; onboarding is a *decision*, not a build. This
is the case that becomes genuinely one paste.

**B — it does not.** There is nothing to serve, and the server cannot make it. The flow's terminal
step here is **a pull request against the repo** adding a caller workflow + a starter
`catalog.spec.json`. Its maintainers merge it, their CI pushes the branch, and the request re-enters
case A. This is slower, and it is also the *right* shape: the repo's own code review is the
strongest consent signal available, and it costs this box nothing.

Nothing in case B is novel machinery — the reusable workflow, the spec, and the `bundle pack`
pipeline all ship. What is missing is the thing that opens the PR (see [Phase 2](#phase-2--case-b-the-workflow-pr)).

## Lifecycle

One request, one state machine, persisted (see [Where the queue lives](#where-the-queue-lives)):

```
                        ┌──────────────── needs-workflow ──► pr-open ──► (CI pushes branch)
                        │                      │                              │
requested ──► probing ──┤                      └──► abandoned (PR closed/     │
                        │                            30d idle)                │
                        └──────────────── publishable ◄────────────────────────┘
                                                │
                                                ▼
                                        pending-approval ──► denied
                                                │
                                                ▼
                                    published (unlisted, unverified)
                                          │        │
                              operator ───┤        └─── stale (branch gone / no refresh in 30d)
                              lists it    │                       │
                              and/or      ▼                       ▼
                              trusts it   listed · trusted     retired
```

Four transitions carry the design; the rest is bookkeeping.

- **probing** — cheap, build-free, and entirely GitHub API reads: does the repo exist and is it
  public; does a `design-artifacts/*` branch exist and what systems does it name; does the branch
  head carry a readable catalog manifest; how big is it; is there a `catalog.spec.json` to validate
  (the reusable workflow's own fast, build-free spec check, reused here). Everything the probe learns
  is shown to the requester *before* they commit to a request, and again to the approver.
- **published** — registers through the existing `ServeCatalogAdmin.register` + `ServeCatalogStore.load`
  path, so a self-serve catalog is in every way an ordinary one: same refresh loop, same provenance
  snapshot, same `catalogs.json` write-back. No second code path to keep honest.
- **stale** — the reason a self-serve set does not grow without bound. The branch refresher
  ([`SERVE_CATALOG_REFRESH`](../public-preview-server.md#image-seed-vs-deployment-config)) already
  re-checks each head; a catalog whose branch has been deleted, or which has not moved and has served
  no traffic in 30 days, auto-retires with a mail-free notice on `/status`. Retiring is
  `DELETE /admin/catalogs/<system>`, which already drops the session and any daemon.
- **retired by its owner** — the same proof that created it (repo write, below) also deletes it.
  Self-serve that cannot self-unserve is a support queue.

## Approvals: three consents, currently one token

`SERVE_ADMIN_TOKEN` conflates three questions that have different answers, different risk, and
different people. Splitting them is the substance of this plan.

| # | Question | Answered by | When |
|---|---|---|---|
| 1 | **Is the requester entitled to speak for this repo?** | GitHub identity + `push` permission on `owner/repo`, verified server-side | at request time |
| 2 | **Will this box host it?** | the operator, on a consent page | before it serves |
| 3 | **May its code execute here?** | the operator, separately and later — a trust-store entry | never automatically |

**(1) is proven, never claimed.** The pieces exist: `GitHubOAuthVerifier` already exchanges a code,
reads `GET /repos/{owner}/{repo}`, and reports the caller's `permissions`. What it cannot do today is
say it about an *arbitrary* repo — the session cookie carries
[`SessionPayload(login, repositoryAccess)`](../public-preview-server.md#the-admin-api): one login and
one Boolean about the single configured `--github-auth-repo`. Onboarding needs a verdict per repo,
checked at request time rather than baked into a cookie. That is the same gap
[`HOSTED_SERVICE_PLAN.md` §1](../HOSTED_SERVICE_PLAN.md) flags as the real work before a tenancy model
exists, and this is the smallest useful step into it: **verify on demand, cache nothing security-relevant
in the cookie.**

**(2) is a human, and the UI for it is already written.** [Agent access
grants](../public-preview-server.md#granting-an-agent-temporary-access---agent-grants) are the same
shape — a stranger asks, a human with rights approves on a page that states plainly what is being
granted, and `/status` lists what is pending and what is live with a Revoke beside each. Onboarding
should reuse that furniture rather than invent a second consent surface: an approval page naming the
repo, the probe's findings, the disk it will take, and the requester's verified login. A catalog costs
disk, front-page space and reputation; those are the operator's to spend.

**(3) is the one that must not be folded into (2), and this is the crux.** Trust gates
[server-side re-render](../public-preview-server.md#trusted-server-side-re-render---allow-render-trusted):
trusting a branch makes that producer's Compose eligible to be *built and executed on the box*, on a
deployment where `SERVE_ALLOW_RENDER_TRUSTED` defaults on. An onboarding flow that trusted what it
published would turn "paste a URL" into remote code execution behind two clicks.

So: **an onboarded catalog is published untrusted.** It serves baked PNGs, badges `unverified`, and
shows the existing `unverified-no-rerender` / `catalog-baked-only`
[degradation banner](../public-preview-server.md#why-a-session-is-snapshot-only--the-degradation-banner).
That is a complete, useful product — the gallery tier of
[`HOSTED_SERVICE_PLAN.md` §2](../HOSTED_SERVICE_PLAN.md) is exactly this — and for a CMP catalog the
[Wasm tier](../public-preview-server.md#two-axes-trust--format) still gives an in-browser live
experience with no server-side execution at all. Promoting a catalog to trusted (and thus to live
Android/Robolectric seats) stays a typed operator decision through `POST /admin/trust`, made about a
producer the operator has by then watched publish for a while. Not a step in the paste-a-URL flow, and
not a checkbox on its approval page.

**Unlisted by default, for the same reason.** A published catalog gets `listed: false` — reachable at
`/<system>/`, off the front page, exactly how `cadence` is published today. Listing is a second, cheap
operator gesture (`POST /admin/catalogs` already converges a changed `listed` in place). The front page
stays curated while the flow stays self-serve; the requester still gets a URL to paste into their PR
the moment it publishes, which is the whole growth loop in
[`HOSTED_SERVICE_PLAN.md` §6](../HOSTED_SERVICE_PLAN.md).

## Phases

Each is shippable alone, and stopping after any one wastes nothing.

### Phase 1 — case A: paste a URL for a repo that already publishes

The narrow, high-value slice. `GET /onboard` is a page with one field; `POST /onboard` takes
`{"url"}`, probes, and queues a request. Approval publishes it via the existing administrators.

- **Slug.** Derive `system` from the branch name (`design-artifacts/<system>`). A slug already served
  falls back to `owner-repo`; the requester sees the resolved slug before approving. Impersonation is
  already handled a layer down — a `group` claim is
  [checked against provenance](../public-preview-server.md#the-catalog-set-is-config-not-image-content),
  so serving third-party bytes under a well-known id cannot make them read as an official design
  system. Onboarded catalogs claim no group and fall to the owner heading.
- **Caps, at probe time and not after the fetch.** A catalog branch is fetched to disk today with no
  size ceiling; a self-serve door makes that a liability rather than a hypothetical. Refuse above a
  configured tarball/branch size, cap requests per login and per repo, and rate-limit the probe (it
  spends this box's GitHub API quota).
- **Reuses:** `ServeCatalogAdmin`, `ServeCatalogStore.load`, `CatalogLoadTracker`, the branch
  refresher, `/status`, the agent-grant consent furniture. New: the probe, the queue, two pages.

### Phase 2 — case B: the workflow PR

Needs a **GitHub App** — the biggest single unlock in
[`HOSTED_SERVICE_PLAN.md` §7](../HOSTED_SERVICE_PLAN.md), and it pays for itself three times here:
installation *is* consent (1) without asking a user for broad OAuth scopes, installation identity is
the seed of a tenancy model, and only an App can open the PR. The PR adds a caller of
`design-artifacts-reusable.yml` plus a starter `catalog.spec.json` scaffolded from the repo's
preview-enabled modules; the body says what it does and what it costs. Merging is the repo's own
decision, reviewed by its own maintainers.

Everything after the merge is already automatic: CI pushes `design-artifacts/<system>`, and the box
picks the head up on its next refresh.

### Phase 3 — lifecycle upkeep

Staleness eviction, self-service retire, per-requester quotas, and the operator's view of the whole
self-serve set on `/status`. Small, and the difference between a demo and something that can be left
running.

## Where the queue lives

Agent grants keep their store in memory, and that is right for an 8-hour credential — a redeploy
ending every grant is a feature. **An onboarding request waiting on a human is not that.** A box rolls
onto a new image [the instant a release publishes](../public-preview-server.md#deploying-previewcooee);
an in-memory queue would silently drop every pending request each time. The queue belongs on the
`/config` volume beside `catalogs.json` and `producers.json`, with the same best-effort-and-reported
write-back semantics.

Which lands it in the same place as the rest of that config, and inherits the same limit:
`ServeCatalogAdmin` and `ServeTrustAdmin` hold `@Volatile` in-memory state and serialise whole-file
read-modify-write on a JVM-local lock. Correct for one process, wrong for several — and self-serve
raises the write rate against it. This does not need fixing to ship (`preview.coo.ee` is one box), but
it is worth naming: [`HOSTED_SERVICE_PLAN.md` §5.3](../HOSTED_SERVICE_PLAN.md) already owes a
centralised store at replica #2, and this feature makes that debt larger rather than creating it.

## Non-goals

- **Server-side building of an arbitrary repo.** See the claim at the top. The
  [playground](PLAYGROUND.md) remains the single deliberate exception to "no stranger's code on
  the server", and it is jailed, capped and preflighted; onboarding is not a second one.
- **Auto-trust, auto-list, or auto-live-seats.** Each is a separate operator decision.
- **Private repos and multi-tenancy.** Phase 2 of the hosted-service plan, gated on there being
  demand; this flow is public repos only, and says so on the page.
- **Billing.** Nothing here meters anything.

## Open questions for #4789

1. **Anonymous requests at all?** Requiring a signed-in GitHub account with repo write is the strong
   posture and makes consent (1) real. The weaker "anyone may nominate any public repo, operator
   approves" is a smaller build and a bigger moderation surface. This plan assumes the former.
2. **Unlisted by default** — as argued above — or listed on approval, on the grounds that an approved
   catalog has already been looked at by a human?
3. **Should Phase 1 ship without the App?** It can: OAuth plus a per-repo permission check covers case
   A. Phase 2 is what needs the App.
4. **Who pays for disk**, and what is the cap — per catalog and in total — on an 8 GB box already
   holding ~17 catalogs?

## See also

- [`public-preview-server.md`](../public-preview-server.md) — the admin API, trust, degradations, agent grants.
- [`HOSTED_SERVICE_PLAN.md`](../HOSTED_SERVICE_PLAN.md) — §1 identity gap, §5.3 multi-replica config, §7 the GitHub App.
- [`DESIGN_CATALOGS.md`](DESIGN_CATALOGS.md) — what a catalog is, and what a repo has to own to have one.
- [`design-artifacts-reusable.yml`](../../.github/workflows/design-artifacts-reusable.yml) — the workflow Phase 2's PR adds.
