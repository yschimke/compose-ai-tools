package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.TrustStore
import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import java.io.File

/**
 * The normalized configuration the preview server consumes.
 *
 * `ServeCommand` parsed 79 flags and then ran ~3,800 lines of preview server on the results, all
 * inside `:cli`. That is what kept 92 serve symbols on the seam register: #4599 gave the server its
 * own module, but the code that *starts* it stayed on the CLI side of the boundary, so the one file
 * that matters most for separability was the one file the boundary did not cover.
 *
 * [ServeCommandOptions] owns argv syntax, defaults, and normalization inside the server artifact;
 * this interface is the stable shape [ServeRunner] and focused tests consume. Build operations are
 * separate in [ServeBuildHost], whose signatures deliberately name no Gradle type.
 */
public interface ServeOptions {

  public val lan: Boolean

  public val host: String

  public val requestedPort: Int

  public val tokenOverride: String?

  /**
   * Cap on concurrent **live** (daemon-backed) stream sessions — the "live seats". `0` (default) is
   * unbounded (a local dev box); a small positive value bounds the JVM render daemons a constrained
   * public box (e.g. `--allow-render-trusted` on a 4 GB VM) will spawn, so an over-cap stream is
   * refused rather than risking the OOM killer. Only bites when a live daemon actually backs a
   * session; the snapshot + Wasm tiers never take a seat.
   */
  public val liveSeats: Int

  /**
   * Background renders admitted at once, server-wide, when the operator names it — otherwise
   * [ServeBackgroundWork.renderLaneFor] derives one from the seat budget.
   *
   * The derivation clamps at [ServeBackgroundWork.MAX_DERIVED_CONCURRENT_RENDERS] (3), and that
   * ceiling is reached at a seat budget of 8 — so on a box with more seats than that the lane stops
   * widening while everything else does, and there was no way to say otherwise short of rebuilding
   * the image: `composeai.serve.backgroundRenders` is a system property, and the prebuilt image
   * bakes JAVA_TOOL_OPTIONS into its own ENV. Measured on preview.coo.ee, whose container is
   * allowed 24 GB: the seat budget went 8 → 12 and the background lane stayed 3.
   *
   * Deliberately un-clamped. The derivation is conservative because it is guessing; an operator
   * naming a number has looked at their own box, and the seat budget still bounds how many daemons
   * those renders can actually occupy.
   */
  public val backgroundRenders: Int?

  public val exportPath: String?

  public val inlineBundle: Boolean

  /**
   * Project mode: besides the current checkout (the default session), fork a daemon-backed session
   * per git revision requested via `?session=<rev>`, each built in its own worktree and suspended /
   * resumed by the registry. Off by default (just the current module).
   */
  public val revisions: Boolean

  /**
   * Project mode's render-history timeline: the **baseline delivery branch**, as it exists in this
   * checkout, whose publishes the viewer's history strip is computed from ([ServeProjectHistory]).
   *
   * On by default and self-disabling: a clone that never fetched the branch resolves nothing and
   * the strip is simply omitted, so the default costs one `git rev-parse` per refresh window on a
   * project that doesn't publish baselines. `--history-branch <ref>` points it at another branch (a
   * fork's, or a fully-qualified `refs/…`); `--no-history` turns it off outright.
   */
  public val historyBranch: String?

  /**
   * Opt in to local Gradle discovery + build. By default `serve` never runs Gradle: it hosts only
   * the fetched sources (`--bundle` / `--bundles` / `--catalogs` / uploaded bundles) as a pure
   * preview server, even when launched from inside a Gradle checkout. Passing `--discover` (or
   * scoping with `--module <path>`) opts into the old behaviour — discover the project's modules,
   * build their previews, and host one. Kept off by default because a stray `serve` at a repo root
   * would otherwise trigger a full module build (and, on a large multi-module tree, hang).
   */
  public val discover: Boolean

  /**
   * Trusted server-side re-render (SECURITY/RCE, opt-in, default off). When set, a `--catalogs`
   * catalog that verifies as `Trusted` AND declares a `source` is served by a **daemon-backed,
   * re-renderable** session built from that source (full-fidelity overrides) instead of static
   * baked PNGs. Building runs the source's Gradle = code execution, so it's gated three ways: the
   * catalog must be Trusted, its `source.ref` must clear the [revisionAllowRefs] allowlist
   * (fail-closed), and its `source.repo` must be the server's own [catalogRepo]. NEVER enable on a
   * box that can't build the catalog source (e.g. the desktop-only public image can't build the
   * Android catalogs) — leave it off there and let the in-browser Wasm tier carry CMP. Reuses
   * `--revisions-allow` as the ref allowlist.
   */
  public val allowRenderTrusted: Boolean

  /**
   * Optional git repo root the trusted-catalog builder ([buildTrustedCatalogSource]) and its
   * [GitWorktrees] use, instead of the served module's own project root ([findProjectRoot]). Lets a
   * module-less box (e.g. the prebuilt `deploy/image`) live-render a fetched catalog by pointing
   * this at a separate checkout of the catalog's `source.repo` (which the entrypoint clones). The
   * `source.repo == `[catalogRepo] and `--revisions-allow` gates are unchanged — this only moves
   * the worktree root. Off ⇒ the served module's project root, as before.
   */
  public val catalogSourceRoot: File?

  /**
   * Project mode revision policy (SECURITY/RCE): comma-separated refs whose history a requested
   * `?session=<rev>` must be reachable from to be checked out and built. Empty = nothing builds
   * (fail closed), since building runs that revision's Gradle. e.g. `--revisions-allow
   * main,release`. Also gates the trusted-catalog source build ([allowRenderTrusted]).
   */
  public val revisionAllowRefs: List<String>

  /**
   * Ephemeral mode: shut the whole server down once it's been idle — no open connections and no
   * requests — for [idleExitSeconds]. `--exit-when-idle` uses the default window;
   * `--exit-when-idle=<seconds>` sets it (a short value ≈ "exit shortly after the last client
   * disconnects"). Off by default (runs until Ctrl-C).
   */
  public val exitWhenIdle: Boolean

  public val idleExitSeconds: Long

  /**
   * Seconds between re-checks of each `--catalogs` branch's head commit; when it has moved, the
   * catalog is re-fetched in place (no restart) — see [ServeCatalogRefresher]. Default
   * [DEFAULT_CATALOG_REFRESH_SECONDS]; `0` (or negative) disables polling (boot-snapshot only, the
   * pre-refresh behaviour). Wired from `SERVE_CATALOG_REFRESH` by the image entrypoint.
   */
  public val catalogRefreshSeconds: Long

  /**
   * How long an RSS reader keeps a catalog's background change-feed worker interested. Every
   * `feed.xml` request renews the lease; after this many quiet seconds the worker stops fetching
   * the delivery branch while retaining its last XML + shallow Git cache. `0` disables the feed
   * lane.
   */
  public val catalogFeedIdleSeconds: Long

  /**
   * Shared mode: a directory of pre-rendered portable bundles (or a single bundle) to host
   * read-only alongside the live session, each reachable at `?session=<bundle-name>`. No checkout
   * or build — the bundle's `previews/<id>.png` files are served directly.
   */
  public val bundlesDir: String?

  /**
   * Raw repeatable `--bundle` values, unparsed.
   *
   * `ServeStartupBundles.Spec` is the server's shape for a bundle to preload; the CLI's job ends at
   * collecting the strings.
   */
  public val bundleFlags: List<String>

  /**
   * Shared/public mode ingestion: enable `POST /bundles/{name}` so clients can contribute bundles
   * at runtime — upload a zip, or pass `?url=` to a build-results artifact. Off by default;
   * intended for a deployed shared instance (combine with `--lan` + a strong `--token`).
   */
  public val acceptBundles: Boolean

  /**
   * Public mode: serve every route **without** requiring the token (the deployed public preview
   * server, where browsing the published catalogs + uploaded bundles is the point). Safe by
   * construction — no server-side code execution, re-render of untrusted Compose refused, uploads
   * capped + SSRF-gated. Off by default so a normal `serve` stays token-gated.
   */
  public val public: Boolean

  /**
   * Streamlined, Storybook-like presentation. The routes and render products stay the same, but the
   * HTML pages expose only catalog browsing, visual variants, usage source and the small set of
   * controls useful while evaluating a component.
   */
  public val componentBrowser: Boolean

  /** Internal convenience used by [BrowseCommand]; full `serve` keeps its print-only behaviour. */
  public val openBrowser: Boolean

  /**
   * SSRF allowlist for `POST /bundles/{name}?url=` fetches: comma-separated hostnames the server
   * may fetch a bundle from. Empty = no URL fetch is allowed (fail closed), so `--accept-bundles`
   * alone only accepts uploads; a host must be explicitly trusted before the server will reach out.
   */
  public val acceptBundlesFrom: List<String>

  /**
   * Document ingestion (`--accept-docs`): enable `GET /docs` + `POST /docs` so a client can hand
   * the server one **known document** (a Remote Compose `.rc`, a Lottie JSON — [ServeDocFormats])
   * and get back an **expiring permalink** (`/d/<id>`) that plays it in the browser. Off by
   * default.
   *
   * Independent of `--accept-bundles`: a bundle becomes a whole preview session, a document is one
   * file with a short-lived share link and no server-side render at all.
   */
  public val acceptDocs: Boolean

  /** How long an ingested document's permalink lives (`--doc-ttl <seconds>`). */
  public val docTtlSeconds: Long

  /**
   * SSRF allowlist for `POST /docs?url=`: hostnames the server may fetch a document from. Empty =
   * uploads only (fail closed), exactly like [acceptBundlesFrom].
   */
  public val acceptDocsFrom: List<String>

  /**
   * `--playground-bundle <path|system>`: enable the playground lane (`POST /api/{v}/compiler/run`),
   * resolving the CMP compile classpath from a catalog liveBundle. Takes either a local `.bundle`
   * path or — since issue #3212 — the id of a system this box already serves via `--catalogs`
   * (`--playground-bundle compose-m3`), which reuses that catalog's fetched, trust-verified bundle
   * instead of a hand-placed copy that would silently go stale. See [PlaygroundBundleSource].
   *
   * Under `--public` the lane still has to clear [PlaygroundPublicGate], which admits it either
   * behind a verified sandbox or behind GitHub repo-access gating — the compile runs
   * **user-supplied code**, so one of the two must bound who supplies it. See
   * docs/design/PLAYGROUND.md §6.
   */
  public val playgroundBundlePath: String?

  /**
   * `--playground-android-bundle <path|system>`: enable the playground's **Android / Remote
   * Compose** compile lane, resolving its classpath from an Android catalog liveBundle — a local
   * path or a served `--catalogs` system id, exactly like `--playground-bundle`. Snippets sent with
   * `confType=remote-compose` compile against it, render on the Robolectric daemon, and their
   * captured `.rc` is published as a `/d/<id>` permalink (needs the `lib-daemon-android` sidecar +
   * `android.jar` + the `/d/` document store). Gated under `--public` like `--playground-bundle`.
   */
  public val playgroundAndroidBundlePath: String?

  /**
   * `--playground` (env `SERVE_PLAYGROUND=1`): enable the playground lane with **nothing pinned**,
   * offering a runtime selector over the catalogs this host already serves
   * ([PlaygroundCatalogTargets]).
   *
   * The `--playground-bundle` flags pin one catalog per mode for the life of the process, which
   * makes "try this snippet against a different design system" an operator edit and a restart — on
   * a box already serving twenty verified catalogs. With this flag the choice moves to the request:
   * each catalog's bundle backend picks the renderer and its manifest supplies the dependencies, so
   * selecting a catalog selects the whole compile target. The pinned flags still work and become
   * the selector's preselected *default* entry, so an existing deployment is unchanged by adding
   * this.
   *
   * Needs `--catalogs` to be of any use — with no served catalogs there is nothing to select — so a
   * host with neither a pin nor a catalog is refused (loudly) rather than serving an empty
   * selector.
   */
  public val playgroundRuntimeSelection: Boolean

  /**
   * `--playground-catalog-limit <n>`: how many runtime-selected catalogs may hold a resolved
   * compile classpath at once. Each one is an unpacked bundle plus a resolved Maven classpath held
   * for the life of the process (they cannot be evicted while snippet JVMs hold their jars open),
   * so this is the knob that stops a public host from being walked into a full disk by a visitor
   * clicking through every entry in the selector.
   */
  public val playgroundCatalogLimit: Int

  /**
   * `--playground-rate-limit <n>`: compiles per minute **per caller** (0 disables the limiter).
   *
   * Every other playground bound — compile slots, the compile timeout, the body cap, live seats,
   * the token store — is a whole-host one (issue #3214). None of them stops two callers from
   * holding every slot with back-to-back 180-second compiles while everyone else is told the
   * playground is busy. This is the fair-sharing half.
   */
  public val playgroundRateLimit: Int

  /**
   * `--playground-caller-concurrency <n>`: compiles one caller may hold at once. Default 1, which
   * is the knob that answers the complaint directly — with the host's `--playground-compile-slots`
   * at its default 2, one caller cannot hold both.
   */
  public val playgroundCallerConcurrency: Int

  /** Authenticated, explicitly acquired, single-host stateful BTA editing trial. Off by default. */
  public val playgroundEditing: Boolean

  public val playgroundEditLeaseTtlSeconds: Long

  /**
   * `--trust-forwarded-for`: rate-limit an anonymous caller by the **last** `X-Forwarded-For` entry
   * rather than the socket peer.
   *
   * Opt-in, because the header is client-supplied: on a directly-exposed host trusting it would let
   * a caller mint a fresh identity per request and walk straight past the limit. Set it only when
   * this server sits behind a reverse proxy you control that *appends* the peer address it saw
   * (nginx's `$proxy_add_x_forwarded_for`) — that appended last entry is the one a client can't
   * forge. Without it, every caller behind the proxy shares one bucket.
   */
  public val trustForwardedFor: Boolean

  /**
   * `--playground-sandbox <profile>`: the **per-session sandbox** every playground snippet JVM runs
   * inside (`none` | `unshare` | `bwrap` | `systemd` | `strict` | `custom:<argv>`), plus its
   * resource knobs. This is Phase 4 of docs/design/PLAYGROUND.md — one of the two things that lets
   * the playground run under `--public`: with a verified sandbox the snippet no longer executes
   * unconfined on the serve host, so *anyone* may compile. The other is GitHub repo-access gating,
   * which bounds who may compile instead of what a compile can reach; with that configured a
   * sandbox here is defence in depth rather than the precondition. Default `none` — playground
   * allowed token-gated, and under `--public` only when repo-access-gated.
   */
  public val playgroundSandboxSpec: String?

  public val playgroundSandboxMemoryMb: Int

  public val playgroundSandboxCpus: Double

  public val playgroundSandboxPids: Int

  /**
   * `--playground-compile-slots <n>`: how many snippet compiles may hold a jailed JVM at once. The
   * compile-side counterpart to `--live-seats` — per-process caps bound one compile, this bounds
   * the aggregate, so peak compile memory is `slots × --playground-sandbox-memory-mb`.
   */
  public val playgroundCompileSlots: Int

  /** Hard wall-clock lifetime of one snippet JVM; the spawner kills it at the deadline. */
  public val playgroundSandboxTtlSeconds: Long

  /**
   * `--playground-sandbox-ro <path>[,<path>…]`: extra host paths bound **read-only** into the jail.
   * The escape hatch for caches a render legitimately reads while having no network to fetch them —
   * the Robolectric `android-all` cache (`~/.m2/repository`) and the downloadable-font cache are
   * the two that matter in practice; prewarm them before going public.
   */
  public val playgroundSandboxReadOnlyPaths: List<String>

  /**
   * Extra remote Maven repository base URLs the live-daemon classpath resolver may fetch from, on
   * top of Maven Central + Google Maven (`--extra-maven-repos <url>[,<url>…]`; env
   * `SERVE_EXTRA_MAVEN_REPOS`). A served catalog whose module pulls deps from a non-default repo —
   * e.g. `https://jitpack.io`, an Apollo/JetBrains snapshot repo — otherwise has those coordinates
   * skipped by the resolver, leaving the daemon's classpath incomplete so a class that references
   * them fails at bootstrap and the catalog falls back to baked PNGs (`livebundle-unavailable`).
   * Empty by default. Operator-curated: only repos the deployer trusts should be listed, since the
   * server will fetch artifacts from them when resolving a trusted catalog's live bundle.
   */
  public val extraMavenRepos: List<String>

  /**
   * Path to the producer-trust store (`--trust-store <file>`): the JSON allowlist of trusted
   * signing keys / branches / CI identities ([TrustStore]). Uploaded bundles are verified against
   * it and the verdict is surfaced in the API + viewer. Absent ⇒ the empty, fail-closed store
   * (every upload `unverified`), which is correct for a private box; a public server points it at
   * `trust/producers.json`.
   */
  public val trustStorePath: String?

  /**
   * Design systems to serve from their published `design-artifacts/<system>` branches (`--catalogs
   * compose-m3,wear-m3`): each is fetched (catalog.json + images) and registered as a read-only
   * session reachable at `/<system>/` (and, for back-compat, `?session=<system>`),
   * trusted-by-origin when the branch is in the trust store.
   *
   * An entry may carry a **per-system source repo** as `<system>@<owner>/<repo>` so one server can
   * mix catalogs published to different repos (e.g. `meshcore-mobile@yschimke/meshcore-mobile`
   * alongside the default-repo `compose-m3`). Without `@…` the shared `--catalog-repo` is used.
   */
  public val catalogsRaw: String?

  /**
   * Like [catalogsRaw], but these systems are served **without** a front-page nav link — reachable
   * by path (`/<system>/`) / `?session=<system>` but hidden from the landing "Design systems" row
   * (`--catalogs-unlisted meshcore-mobile@yschimke/meshcore-mobile,…`). For app design systems we
   * publish but don't want on the public front door.
   */
  public val catalogsUnlistedRaw: String?

  /**
   * **Top-level sites** (`--sites m3.preview.coo.ee=m3-catalog,…`; also `catalogs.json`'s `sites`):
   * host names on which one already-served catalog is presented as the whole server — its landing
   * at `/`, its links inside the custom domain, no front door and no neighbours. See [ServeSites];
   * it adds no catalog and no work, only a different reading of the same request.
   */
  public val sitesRaw: String?

  /**
   * Raw `--catalogs-file` path, unopened.
   *
   * The CLI knows a path was given; `ServeCatalogsConfigFile` — what that file means, and how it is
   * read and rewritten — is the server's.
   */
  public val catalogsFilePath: String?

  /** Durable feed cache; defaults beside catalogs.json on deployed boxes, temp for local serve. */
  public val catalogFeedCacheDir: File

  /**
   * Shared secret for the runtime admin routes (`--admin-token`; env `SERVE_ADMIN_TOKEN`) — both
   * `/admin/catalogs` and `/admin/trust`. Absent ⇒ neither is registered at all, so a server that
   * didn't opt in has no admin surface. Deliberately distinct from the browse token: a `--public`
   * box hands that one out to every visitor.
   *
   * On a server running `--allow-render-trusted`, treat this as a code-execution credential:
   * `/admin/trust` can make a producer's Compose eligible for server-side re-render here.
   */
  public val adminToken: String?

  /** Optional durable aggregate counters. Null keeps local serve sessions in-memory only. */
  public val engagementFile: File?

  public val githubAuthClientId: String?

  public val githubAuthClientSecret: String?

  public val githubAuthCookieSecret: String?

  public val githubAuthRepo: String?

  public val githubAuthCallbackBaseUrl: String?

  /**
   * Scopes the auth cookies to a parent domain so one sign-in covers it and every `--sites` host
   * under it (`preview.coo.ee` ⇒ valid on `m3.preview.coo.ee`). Unset keeps them host-only, which
   * is right for a single-hostname box; it is deliberately explicit rather than derived, since a
   * cookie domain is the blast radius of a session.
   */
  public val githubAuthCookieDomain: String?

  /**
   * Overrides the OAuth scope. Unset derives it from `--github-auth-repo`'s visibility, which is
   * what a deployment wants unless its GitHub App or org policy demands something specific.
   */
  public val githubAuthScope: String?

  public val githubAuthUsers: Set<String>

  /**
   * Agent access grants (`--agent-grants`): enable the device-grant flow at `/agent-access/…` so an
   * agent with no credential can ask for temporary, scoped, revocable access, and a human approves
   * it from a link the agent prints. See
   * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
   *
   * Off by default and deliberately not derivable: the lane's whole purpose is to mint credentials,
   * so an operator turns it on knowingly or not at all.
   */
  public val agentGrants: Boolean

  /**
   * Raw `--agent-grant-scopes`, unparsed.
   *
   * Deliberately a string: what a scope name means, and which of them is highest, is server policy.
   */
  public val agentGrantScopesFlag: String?

  /**
   * Raw `--agent-grant-max-ttl`, unparsed.
   *
   * The duration grammar and the hard ceiling are both server policy, so both live on that side.
   */
  public val agentGrantMaxTtlFlag: String?

  /**
   * Raw `--agent-grant-capabilities`, unparsed.
   *
   * The CLI reads the flag; the server decides what the names mean. A capability list is a server
   * policy, and parsing it here would put `AgentGrantCapability` on the CLI's classpath for the
   * sake of a string split.
   */
  public val agentGrantCapabilitiesFlag: String?

  public val agentGrantMaxActive: Int

  /**
   * Per-address budget on the two ungated grant routes (`--agent-grant-rate-limit`, requests per
   * minute; `0` disables). The default is generous enough for a polling agent — one poll every
   * three seconds is 20/min — and small enough that an anonymous caller cannot churn the request
   * map.
   */
  public val agentGrantRateLimit: Int

  /**
   * Image ingestion (`--accept-images`): enable `POST /images` so an **agent preparing a pull
   * request** can hand the server a rendered preview PNG and get back `/i/<id>.png` — a URL it can
   * embed in the PR body from a box with neither a GitHub CLI nor push rights to a capture branch.
   * Off by default.
   *
   * Unlike `--accept-docs`, this lane is **never anonymous**: uploading requires a GitHub token
   * whose owner has access to [imageUploadRepository], on a `--public` host as much as on a private
   * one. Reading is open, because the point of the URL is that GitHub's image proxy can fetch it.
   * The whole rationale is in [ServeImageStore].
   */
  public val acceptImages: Boolean

  /** How long an uploaded image's link lives (`--image-ttl <seconds>`); default 7 days. */
  public val imageTtlSeconds: Long

  /**
   * The repository an uploader must have access to (`--image-upload-repo <owner/repo>`), falling
   * back to the GitHub-auth gating repo when the operator already configured one. There is no
   * default beyond that and the lane refuses to start without it: a gate whose repository was
   * guessed is not a gate.
   */
  public val imageUploadRepository: String?

  /** Uploads per minute per GitHub account (`--image-rate-limit`); `0` disables the budget. */
  public val imageRateLimit: Int

  /**
   * Server-wide admission for the catalogs' background theme optimization: it parks while any
   * catalog is loading, and bounds how many of them render at once. Shared by every catalog host
   * this server opens — see [ServeBackgroundWork] for why both halves matter on a public box.
   *
   * The lane is derived from [liveSeatLimiter] because widening it is only safe where something
   * else bounds daemon count: an unbounded budget (the CLI default) keeps the single lane.
   */
  public val optimizerCoordinationDirectory: File?

  /**
   * Raw `--catalog-cache-dir`, or `none` to disable persistence. Unresolved.
   *
   * As with the theme cache, everything the old val did — creating the directory, testing it for
   * writability, printing the operator line about container volumes, falling back to a temp dir —
   * is server startup and now happens there.
   */
  public val catalogCacheDirFlag: String?

  /** Raw `--catalog-cache-max-bytes`; null means "use the server's default". */
  public val catalogCacheMaxBytesFlag: Long?

  /**
   * Raw `--theme-cache-dir`, or `none` to disable. Unresolved.
   *
   * Everything the old `themeCacheStore` val did — deriving the default location beside
   * `--catalogs-file`, creating the directory, testing it for writability, printing the operator
   * line, opening the store and running its first eviction — is server startup, and it now happens
   * in the server. The CLI's share is the two strings.
   */
  public val themeCacheDirFlag: String?

  /** Raw `--theme-cache-max-bytes`; null means "use the server's default". */
  public val themeCacheMaxBytesFlag: Long?

  /** `--theme-cache-evict`: drop every cached generation once, at startup. */
  public val themeCacheEvictRequested: Boolean

  /**
   * In-browser CMP tier (`--wasm-dir <system>=<dir>[,<system>=<dir>…]`): map a design system to the
   * assembled Wasm catalog app (`./gradlew :samples:cmp-wasm-catalog:wasmCatalogDist` →
   * `build/wasmDist`). Its viewer then offers a "Run in browser (Wasm)" toggle that mounts the app
   * client-side. Missing dirs are dropped with a warning. Empty ⇒ no Wasm tier.
   */
  public val wasmDirs: Map<String, File>

  /** Experimental AndroidX-conformant Remote Compose CMP/Wasm player distribution. */
  public val rcPlayerWasmDir: File?

  public val catalogRepo: String

  public val catalogBranchPrefix: String

  public val catalogMaxImages: Int

  // ---- selectors and timeouts the server reads, declared by `Command` rather than by `serve` ----
  //
  // These are not `ServeCommand`'s own flags; they are the shared ones every command parses. The
  // server reads them all the same, so they are part of this contract — a `serve` that could not
  // see `--module` or `--id` would be a different program.

  /** `--module`, the Gradle path a run is scoped to, or null for "every module". */
  public val explicitModule: String?

  /** `--filter`, a substring match over preview ids. */
  public val filter: String?

  /** `--id`, an exact preview id. */
  public val exactId: String?

  /** `--preview`, a loose reference (`Class.method`, a file, a fully-qualified id). */
  public val previewRef: String?

  /** Whether selection keeps a `@PreviewParameter` preview whose *rows* might match. */
  public val rowAwareSelection: Boolean

  /** Per-invocation Gradle timeout. */
  public val timeoutSeconds: Long

  /**
   * True when `serve` was launched by `browse` rather than typed directly.
   *
   * A `ServeCommand` constructor parameter until now. It reads as CLI-only trivia and is not — it
   * changes what the server does (`browse` gets a print-free, auto-opening variant), so it is part
   * of the contract like any flag.
   */
  public val browseProject: Boolean

  // ---- the one selector rule shared with the root CLI ----
  //
  // The server owns its argv, but preview-reference matching remains a tool-wide policy. The root
  // CLI injects that pure callback so `serve`, `render`, and `show` cannot drift apart.

  /** The shared preview-id selector rule, so `serve` and the other commands agree on a match. */
  public fun previewIdMatchesRequest(
    id: String,
    exactId: String?,
    filter: String?,
    previewRef: String? = null,
    className: String? = null,
    functionName: String? = null,
  ): Boolean
}

/**
 * What the server needs back from a discovery build.
 *
 * Deliberately narrower than `Command.RenderModulesOutcome`, which also carries `GradleTaskOutcome`
 * — a `:gradle-preview-driver` type. The server reads exactly two of that class's fields, so
 * narrowing here keeps the Tooling API off this module rather than importing a type for two
 * properties.
 */
public class ServeDiscovery(
  public val buildOk: Boolean,
  public val manifests: List<Pair<PreviewModule, PreviewManifest>>,
)
