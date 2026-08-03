# Running the preview server as a hosted service — a minimal plan

A sketch, not a commitment. It answers four questions — what would it cost, what breaks as it
grows, who would hear about it, and what it plugs into — and grounds each in what
[`preview.coo.ee`](public-preview-server.md) already does today rather than a greenfield design.

The short version: **the expensive half of this product is already paid for by someone else.**
Rendering happens in the consumer's own Gradle build or CI, so a hosted service receives, stores,
diffs, and shows artefacts — Argos economics, not Chromatic economics. That is the whole reason
this is worth writing down: the minimum viable version costs about **€7/month** and mostly exists.

## 1. What is already built

Not a wishlist — this is running.

| Piece | State | Why it matters here |
|---|---|---|
| `compose-preview serve` | Ships in the CLI | The service *is* the product; no new server to write. |
| Prebuilt image `ghcr.io/yschimke/compose-preview-host` | Published per release, zero-downtime rollout via `docker-rollout` + publish webhook | Deploying is `docker pull`. Multi-tenant later; single-tenant already works. |
| `preview.coo.ee` | ~17 catalogs, 8 GB host, 5 auto-derived live seats | A live reference deployment with real content on it. |
| Trust store (Ed25519 / branch / OIDC) | Fail-closed, gates server-side re-render | The security model for "run a stranger's UI" is already decided and enforced. |
| Live-seat permit budget (`SERVE_LIVE_SEATS`) | Weighted (CMP 1, Android/Robolectric 2), auto-sized from container memory, refuses with WS `1013` | This is the meter. Billing has a unit already. |
| GitHub OAuth gate (`SERVE_GITHUB_AUTH_*`) | Signed cookie, repo-access verdict, per-user allowlist | Accounts + entitlement exist. No auth work to start charging. |
| Admin API (`/admin/catalogs`, `/admin/trust`, `/admin/groups`) | Live mutation, written back to `/config` | Self-serve onboarding is an HTTP call, not a redeploy. |
| Storybook-compatible surface (`/index.json`, `/iframe.html`, `&format=svg`) | Shipped | Percy / Chromatic / BackstopJS / Applitools can crawl a serve host with no Compose-specific code. |
| Wasm tier (CMP in-browser) | Shipped, cached + ETag'd | Free-tier browsing costs ~nothing server-side. |
| Playground (jailed snippet compile+run) | Gated on sandbox preflight, hard TTL, compile slots | The one genuinely expensive surface — and it is already opt-in and capped. |

The gaps for a *service* (not a deployment) are narrower than the list above: multi-tenant identity,
per-tenant storage/retention, a receive-artefacts endpoint for CI, billing, and an SLA story.

## 2. What it would actually sell

Two products hide inside "hosted preview server". They have opposite cost shapes, and conflating
them is the main way this goes wrong.

**A. The gallery** — a hosted, shareable, always-warm design-system / component showcase.
Marginal cost ≈ static bytes (baked PNG + Wasm are cacheable). This is the free tier and the
funnel. It is what `preview.coo.ee` is today.

**B. The PR loop** — renders arrive from the customer's CI, get stored, diffed against a baseline,
and commented back onto the PR with history. Marginal cost ≈ object storage + bandwidth, because
**the render already happened on the customer's runner**. This is the thing worth money, and it is
cheap to run.

The paid axis should be **privacy, history, and retention** — private repos, baselines that persist,
diffs you can walk backwards through — not rendering throughput. Rendering is the part the OSS tool
gives away for free and does well; competing with your own free tool on compute is a losing trade.

## 3. Parallels worth stealing from

| Precedent | Model | The lesson for this |
|---|---|---|
| **Storybook → [Chromatic](https://www.chromatic.com/pricing)** | OSS component explorer; hosted service bills per *snapshot* (free 5k/mo; ~$179/mo for 35k) | The canonical shape: give away the explorer, sell the hosted diff + review. But per-snapshot billing punishes the exact behaviour you want (render everything, every PR), and overage is where users get burned. |
| **[Argos CI](https://argos-ci.com/pricing)** | 100% OSS, sells hosting; free 5k screenshots/mo, $100/mo for 40k, free for OSS | Closest economic twin. Argos *receives* screenshots rather than rendering them — same posture available here, since the Gradle plugin already renders in CI. Also proves a fully-OSS tool can still sell hosting; it just caps what you can charge. |
| **[Emerge Tools Snapshots](https://docs.emergetools.com/docs/android-snapshots-v1)** | Commercial, Gradle-plugin-driven Compose `@Preview` snapshots, PR comments + status checks | The direct competitor in *this* ecosystem, and the proof the demand is real. They render server-side and price opaquely (contact-sales); an open, self-hostable, transparently-priced alternative is a legible wedge. |
| **[Appetize.io](https://appetize.io/pricing)** | $0.05/streaming-minute; free 100 min/mo, 1 active session | Prices *concurrency and time*, not artefacts — which is exactly the right unit for the live-daemon lane. The live-seat permit budget is already this meter. |
| **Gradle → [Build Scan / Develocity](https://gradle.com/pricing/)** | Free public `scans.gradle.com` with a one-line opt-in that mints a shareable URL; paid product on a different axis (history, observability); free instances for major OSS | The best model for `preview.coo.ee`: a free public service whose output is a *link you paste into a PR*. Every shared link is a demo. And the paid axis is data/history, not the thing that's free. |
| **[Cypress Cloud](https://www.cypress.io/pricing)** | Free 500 test results/mo; $6/1k overage; **free for public OSS** (100k results, 5 users) | OSS-free is table stakes in this category, not generosity. Budget for it as marketing spend. |
| **Kotlin Playground / `play.kotlinlang.org`** | Free hosted sandbox, no monetisation | What `/playground` should be understood as: adoption spend with a hard cost cap, never a revenue line. |

Two patterns recur across all seven: **the free tier is public and shareable** (links are the
marketing), and **the paid tier is private and durable** (history, retention, access control).

## 4. Costs

Grounded in the deployment docs, not estimates from nowhere.

**Today (single box, what `preview.coo.ee` costs):**

| Item | Cost |
|---|---|
| Hetzner CAX21 (4 ARM vCPU / 8 GB) — see [`deploy/vps`](../deploy/vps/README.md) | ~€7/mo |
| Domain | ~$15/yr |
| GHCR + CI (public repo) | €0 |
| **Total** | **≈ €8/mo** |

Alternative floors already documented: [`deploy/oracle`](../deploy/oracle/README.md) is genuinely
**$0/mo** on Always Free Ampere A1 (4 cores / 24 GB) — a real always-warm box, no scale-to-zero
cold starts; [`deploy/cloudrun`](../deploy/cloudrun/README.md) scales to zero and fits in the free
tier at ~25 h/mo of active 2 vCPU compute, at the price of cold starts.

**Unit economics of the two products:**

- **Gallery (free tier):** baked PNGs and Wasm assets are `Cache-Control` + ETag'd already. Put
  them behind a CDN (Cloudflare free / R2 at ~$0.015/GB-mo, no egress fee) and the origin serves
  almost nothing. Effectively fixed-cost.
- **Live seats:** ~1.2 GB RAM held for the session, +1 GB reserved for the host. On the €7 box
  that is 5 permits ⇒ **~€1.40/permit-month if permanently occupied** — and they are not.
  Comfortably profitable at any sane price; the constraint is concurrency, not money.
- **PR loop:** storage + bandwidth only. A PNG catalogue for a mid-size app is single-digit MB;
  1,000 repos × 50 MB retained ≈ 50 GB ≈ **$1/mo** at R2 rates. The cost of this product is
  rounding error — which means retention can be generous and the price can be about the *review
  experience*, not the bytes.
- **Playground:** the only surface where a stranger's code burns your CPU. Already capped by
  `SERVE_PLAYGROUND_COMPILE_SLOTS`, per-snippet jails, and hard TTL. Keep it a fixed, small,
  never-autoscaled slice — a loss leader with a hard ceiling, like Kotlin Playground.

**Costs that appear the moment it is a *service* rather than a deployment:** Stripe (2.9% + $0.30),
support time (the real cost), an on-call expectation, and an SLA. Those are why the first paid
tier should be deliberately unambitious.

## 5. Scalability — in the order things actually break

Staged, cheapest fix first. Nothing here needs a rewrite.

1. **Live-seat exhaustion under a traffic spike** *(first, and most likely — one HN post does it).*
   Already handled correctly: refuse with WS `1013`, don't OOM. Fixes in order: make sure CMP
   catalogs default to the Wasm tier (they do — no seat consumed), then add a queue-with-position
   instead of a flat refusal, then more permits (a CAX31, 8 vCPU / 16 GB, ~€13/mo, roughly doubles
   them).
2. **Origin bandwidth on the gallery.** Wasm bundles are ~8 MB gzipped. Front it with a CDN before
   this matters; the caching headers are already right.
3. **One box = one failure domain.** The live daemon is already a separate process per session, so
   horizontal is a router with session affinity plus N identical image pulls — the seat budget
   auto-sizes per container. Config (`/config/catalogs.json`, `producers.json`) has to move from a
   host volume to shared storage at this point; the admin API already writes it, so this is a
   storage swap, not a redesign.
4. **Cold catalog fetch / live-bundle Maven resolution.** External dependency (GitHub raw, Central,
   Google Maven, jitpack). Cache aggressively; treat a fetch failure as the documented
   `livebundle-unavailable` degradation rather than an outage — the fallback to baked PNGs already
   exists and is user-visible.
5. **Multi-tenant isolation.** Only genuinely required for private catalogs and the playground.
   The sandbox profiles (`strict` / `bwrap` / `custom:`) with startup preflight already exist for
   the hardest case; per-tenant *storage* separation is the new work.

**Deliberate non-goal:** a render farm. If server-side rendering ever becomes the bottleneck,
that's a signal the pricing is pointed at the wrong thing (see §2), not a signal to buy machines.

## 6. Marketing

Distribution that already exists, in rough order of leverage:

- **Shareable links as the growth loop** (the Build Scan lesson). Every `?session=` link, every
  expiring `/docs` permalink, every catalog URL pasted into a PR or a Slack is an unpaid demo.
  Optimise for "paste this into a PR" — OpenGraph preview images on catalog and preview pages
  would do more than any blog post.
- **The agent angle is the only unclaimed position.** Chromatic, Argos, Percy and Emerge all sell
  to humans reviewing UI. Nobody sells *"your coding agent can see the screen it just changed"* —
  and that is literally this project's tagline and its MCP server. Lead with it.
- **Existing installed base:** the VS Code / Open VSX extension, the one-line skills installer
  (Claude Code / Codex / Gemini), the Gradle Plugin Portal listing, the MCP server.
- **The catalogs are the content.** `compose-m3`, `wear-m3`, `remote-m3` and the
  `android/compose-samples` set are a genuinely useful public reference. "The Wear Material 3
  component gallery you can actually poke at" is a better front page than a product pitch.
- **OSS-free tier, stated loudly and early** — every precedent in §3 does it, and it's what makes
  the shareable links land in public repos where they're visible.
- **Where the audience is:** Android Weekly, Kotlin Weekly, r/androiddev, the Kotlin Slack
  `#compose` / `#compose-desktop` channels, and droidcon/KotlinConf talk material. One good
  "here is your design system, hosted, free, in one command" post beats a launch.

## 7. Integrations

**Already shipped — use these before building anything:**

- **Storybook-compatible surface.** `/index.json` + `/iframe.html` means Percy, Chromatic,
  Applitools (via `&format=svg`, vector, DOM-capturable), BackstopJS, storycap/reg-suit and
  `@storybook/test-runner` can all consume a serve host today. *Integrating with the incumbents
  is a better first move than competing with them* — it makes Compose a first-class citizen in
  toolchains teams already pay for, and puts this in front of their users.
- **Figma.** `figma-svg` export and Code Connect emission exist in
  [`scripts/design-artifacts/`](../scripts/design-artifacts). A Figma plugin reading `/api/previews`
  (the `degradations` array is already there for exactly this consumer) closes the design↔code loop
  — the highest-value integration on this list and the one competitors don't have.
- **MCP server** ([`mcp/`](../mcp)) — the agent integration, already published.
- **VS Code / Cursor / Windsurf** extension.
- **GitHub Actions** — `compose-preview.yml`, `vscode-preview-comment.yml`, `design-artifacts.yml`.

**Worth building, in order:**

1. **A GitHub App.** The Actions workflows already produce the artefacts and the comments; an App
   turns that from "copy this workflow" into "click install", and gives per-installation identity
   for free — which is also the tenancy model. Biggest single unlock.
2. **A receive-artefacts endpoint** (`POST` a bundle from CI → baseline + diff + retained history).
   Argos' entire product, and the missing half of product B. The bundle format and the trust model
   both already exist.
3. **Slack notification on visual diff** — cheap, and it is where design review actually happens.
4. **Gradle-side one-liner** (`composePreview { publish = true }`) that mints a shareable URL,
   Build-Scan style. This is the growth loop in §6 made into a single config line.

## 8. The minimal path

Three phases. Stop after any one of them and nothing is wasted.

**Phase 0 — harden what runs (weeks, no new product).** CDN in front of the gallery; OpenGraph
images on catalog/preview pages; queue-with-position instead of a flat `1013`; publish the free-tier
limits explicitly. Cost stays ≈ €8/mo. Success signal: sustained non-zero traffic to
`preview.coo.ee` catalogs from outside this project.

**Phase 1 — the free public service (the funnel).** GitHub App + self-serve catalog publishing over
the existing admin API + the Gradle one-liner that mints a link. Still free, still one box. Success
signal: third-party repos publishing their own catalogs. **This is the phase that decides whether
there is a business** — if nobody publishes, the answer is "keep it as a great free demo", which is
a perfectly good outcome.

**Phase 2 — charge for private and durable.** Private catalogs (GitHub repo-access verdict already
computed), retained PR baselines and history, dedicated live seats. Price it like the precedents
suggest: free for public/OSS, roughly $20–50/mo per org for private, self-hosted stays free forever
(the image is public — the paid thing is *not running it yourself*, plus support and SSO). Deliberately
do **not** price per snapshot; per-*repo* or per-*concurrency* matches the actual cost shape and
doesn't punish rendering more.

**Kill criteria.** If Phase 1 produces no third-party catalogs in ~3 months, don't build Phase 2.
The free service is still worth running: it costs €8/month, it is the best demo the project has,
and every shared link sells the OSS tool.

## 9. The honest risks

- **Everything is Apache-2.0 and self-hosts in one command** — by design, and documented in three
  deployment guides. Hosting is therefore a convenience business with a low price ceiling. Argos
  shows that works; it also shows the ceiling.
- **Emerge Tools is incumbent** in exactly this niche with a sales team. The counter-position is
  open, self-hostable, transparently priced, and agent-native — not feature parity.
- **One person's on-call.** The first paying customer converts a hobby deployment into an
  obligation. That, not infrastructure, is the real cost of Phase 2 — and the strongest argument
  for stopping at Phase 1 unless demand is unambiguous.
- **The playground is the only unbounded-risk surface.** It runs strangers' code. It is already
  fail-closed behind sandbox preflight; keep it capped and never let it autoscale.

## See also

- [public-preview-server.md](public-preview-server.md) — what the server does today.
- [`deploy/image`](../deploy/image/README.md) — the prebuilt host image and rollout chain.
- [`deploy/vps`](../deploy/vps/README.md), [`deploy/oracle`](../deploy/oracle/README.md),
  [`deploy/cloudrun`](../deploy/cloudrun/README.md) — the three cost profiles.
