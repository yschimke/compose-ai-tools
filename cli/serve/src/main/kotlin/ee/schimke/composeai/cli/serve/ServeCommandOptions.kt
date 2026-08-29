package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.TrustStore
import java.io.File

/**
 * The preview server's command-line configuration.
 *
 * Argument names, normalization, defaults, and usage text belong to the server artifact: they
 * describe the server process regardless of which launcher invokes it. The root CLI supplies only
 * the two host-owned facts that cannot be derived here: its default Gradle timeout and the shared
 * preview-selector rule. Gradle operations remain behind [ServeBuildHost].
 *
 * Keeping argv here rather than on `:cli` is the final half of #3824 preparation item 7: changes to
 * a server flag no longer require editing the CLI adapter, while this module still has no
 * dependency on the CLI or the Gradle Tooling API.
 */
public class ServeCommandOptions(
  private val args: List<String>,
  override val browseProject: Boolean = false,
  defaultTimeoutSeconds: Long,
  private val previewMatcher: (String, String?, String?, String?, String?, String?) -> Boolean,
) : ServeOptions {

  override val explicitModule: String? = args.flagValue("--module")

  override val filter: String? = args.flagValue("--filter")

  override val exactId: String? = args.flagValue("--id")

  override val previewRef: String? = args.flagValue("--preview")?.takeIf { it.isNotBlank() }

  override val timeoutSeconds: Long =
    args.flagValue("--timeout")?.toLongOrNull() ?: defaultTimeoutSeconds

  public val helpRequested: Boolean = "--help" in args || "-h" in args

  override val lan: Boolean = "--lan" in args

  override val host: String =
    when {
      lan -> ServeDefaults.HOST_ALL_INTERFACES
      else -> args.flagValue("--host")?.takeIf { it.isNotBlank() } ?: ServeDefaults.HOST_LOOPBACK
    }

  override val requestedPort: Int =
    args.flagValue("--port")?.toIntOrNull() ?: ServeDefaults.DEFAULT_PORT

  override val tokenOverride: String? = args.flagValue("--token")?.takeIf { it.isNotBlank() }

  /**
   * Cap on concurrent **live** (daemon-backed) stream sessions — the "live seats". `0` (default) is
   * unbounded (a local dev box); a small positive value bounds the JVM render daemons a constrained
   * public box (e.g. `--allow-render-trusted` on a 4 GB VM) will spawn, so an over-cap stream is
   * refused rather than risking the OOM killer. Only bites when a live daemon actually backs a
   * session; the snapshot + Wasm tiers never take a seat.
   */
  override val liveSeats: Int = args.flagValue("--live-seats")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

  /**
   * Background renders admitted at once, server-wide, when the operator names it — otherwise
   * [ServeBackgroundWork.renderLaneFor] derives one from the seat budget.
   *
   * The derivation clamps at [ServeDefaults.MAX_DERIVED_CONCURRENT_RENDERS] (3), and that ceiling
   * is reached at a seat budget of 8 — so on a box with more seats than that the lane stops
   * widening while everything else does, and there was no way to say otherwise short of rebuilding
   * the image: `composeai.serve.backgroundRenders` is a system property, and the prebuilt image
   * bakes JAVA_TOOL_OPTIONS into its own ENV. Measured on preview.coo.ee, whose container is
   * allowed 24 GB: the seat budget went 8 → 12 and the background lane stayed 3.
   *
   * Deliberately un-clamped. The derivation is conservative because it is guessing; an operator
   * naming a number has looked at their own box, and the seat budget still bounds how many daemons
   * those renders can actually occupy.
   */
  override val backgroundRenders: Int? =
    args.flagValue("--background-renders")?.toIntOrNull()?.takeIf { it >= 1 }

  override val exportPath: String? = args.flagValue("--export")?.takeIf { it.isNotBlank() }

  override val inlineBundle: Boolean = "--inline" in args

  /**
   * Project mode: besides the current checkout (the default session), fork a daemon-backed session
   * per git revision requested via `?session=<rev>`, each built in its own worktree and suspended /
   * resumed by the registry. Off by default (just the current module).
   */
  override val revisions: Boolean = "--revisions" in args

  /**
   * Project mode's render-history timeline: the **baseline delivery branch**, as it exists in this
   * checkout, whose publishes the viewer's history strip is computed from ([ServeProjectHistory]).
   *
   * On by default and self-disabling: a clone that never fetched the branch resolves nothing and
   * the strip is simply omitted, so the default costs one `git rev-parse` per refresh window on a
   * project that doesn't publish baselines. `--history-branch <ref>` points it at another branch (a
   * fork's, or a fully-qualified `refs/…`); `--no-history` turns it off outright.
   */
  override val historyBranch: String? =
    if ("--no-history" in args) null
    else
      args.flagValue("--history-branch")?.trim()?.takeIf { it.isNotEmpty() }
        ?: ServeDefaults.HISTORY_BRANCH

  /**
   * Opt in to local Gradle discovery + build. By default `serve` never runs Gradle: it hosts only
   * the fetched sources (`--bundle` / `--bundles` / `--catalogs` / uploaded bundles) as a pure
   * preview server, even when launched from inside a Gradle checkout. Passing `--discover` (or
   * scoping with `--module <path>`) opts into the old behaviour — discover the project's modules,
   * build their previews, and host one. Kept off by default because a stray `serve` at a repo root
   * would otherwise trigger a full module build (and, on a large multi-module tree, hang).
   */
  override val discover: Boolean = "--discover" in args

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
  override val allowRenderTrusted: Boolean = "--allow-render-trusted" in args

  /**
   * Optional git repo root the trusted-catalog builder ([buildTrustedCatalogSource]) and its
   * `GitWorktrees` use, instead of the served module's own project root ([findProjectRoot]). Lets a
   * module-less box (e.g. the prebuilt `deploy/image`) live-render a fetched catalog by pointing
   * this at a separate checkout of the catalog's `source.repo` (which the entrypoint clones). The
   * `source.repo == `[catalogRepo] and `--revisions-allow` gates are unchanged — this only moves
   * the worktree root. Off ⇒ the served module's project root, as before.
   */
  override val catalogSourceRoot: File? =
    args.flagValue("--catalog-source-root")?.takeIf { it.isNotBlank() }?.let { File(it) }

  /**
   * Project mode revision policy (SECURITY/RCE): comma-separated refs whose history a requested
   * `?session=<rev>` must be reachable from to be checked out and built. Empty = nothing builds
   * (fail closed), since building runs that revision's Gradle. e.g. `--revisions-allow
   * main,release`. Also gates the trusted-catalog source build ([allowRenderTrusted]).
   */
  override val revisionAllowRefs: List<String> =
    args.flagValue("--revisions-allow")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
      ?: emptyList()

  /**
   * Ephemeral mode: shut the whole server down once it's been idle — no open connections and no
   * requests — for [idleExitSeconds]. `--exit-when-idle` uses the default window;
   * `--exit-when-idle=<seconds>` sets it (a short value ≈ "exit shortly after the last client
   * disconnects"). Off by default (runs until Ctrl-C).
   */
  override val exitWhenIdle: Boolean = args.any {
    it == "--exit-when-idle" || it.startsWith("--exit-when-idle=")
  }

  override val idleExitSeconds: Long =
    args.flagValue("--exit-when-idle")?.toLongOrNull()?.takeIf { it > 0 }
      ?: ServeDefaults.DEFAULT_IDLE_EXIT_SECONDS

  /**
   * Seconds between re-checks of each `--catalogs` branch's head commit; when it has moved, the
   * catalog is re-fetched in place (no restart) — see `ServeCatalogRefresher`. Default
   * [ServeDefaults.DEFAULT_CATALOG_REFRESH_SECONDS]; `0` (or negative) disables polling
   * (boot-snapshot only, the pre-refresh behaviour). Wired from `SERVE_CATALOG_REFRESH` by the
   * image entrypoint.
   */
  override val catalogRefreshSeconds: Long =
    args.flagValue("--catalog-refresh-interval")?.toLongOrNull()
      ?: ServeDefaults.DEFAULT_CATALOG_REFRESH_SECONDS

  /**
   * How long an RSS reader keeps a catalog's background change-feed worker interested. Every
   * `feed.xml` request renews the lease; after this many quiet seconds the worker stops fetching
   * the delivery branch while retaining its last XML + shallow Git cache. `0` disables the feed
   * lane.
   */
  override val catalogFeedIdleSeconds: Long =
    args.flagValue("--catalog-feed-idle-timeout")?.toLongOrNull()
      ?: ServeDefaults.DEFAULT_CATALOG_FEED_IDLE_SECONDS

  /**
   * Shared mode: a directory of pre-rendered portable bundles (or a single bundle) to host
   * read-only alongside the live session, each reachable at `?session=<bundle-name>`. No checkout
   * or build — the bundle's `previews/<id>.png` files are served directly.
   */
  override val bundlesDir: String? = args.flagValue("--bundles")?.takeIf { it.isNotBlank() }

  /** Raw repeatable `--bundle` values; the server parses them into startup specs. */
  override val bundleFlags: List<String> = args.flagValuesAll("--bundle")
  /**
   * Shared/public mode ingestion: enable `POST /bundles/{name}` so clients can contribute bundles
   * at runtime — upload a zip, or pass `?url=` to a build-results artifact. Off by default;
   * intended for a deployed shared instance (combine with `--lan` + a strong `--token`).
   */
  override val acceptBundles: Boolean = "--accept-bundles" in args

  /**
   * Public mode: serve every route **without** requiring the token (the deployed public preview
   * server, where browsing the published catalogs + uploaded bundles is the point). Safe by
   * construction — no server-side code execution, re-render of untrusted Compose refused, uploads
   * capped + SSRF-gated. Off by default so a normal `serve` stays token-gated.
   */
  override val public: Boolean = "--public" in args

  /**
   * Streamlined, Storybook-like presentation. The routes and render products stay the same, but the
   * HTML pages expose only catalog browsing, visual variants, usage source and the small set of
   * controls useful while evaluating a component.
   */
  override val componentBrowser: Boolean = "--component-browser" in args

  /** Internal convenience used by [BrowseCommand]; full `serve` keeps its print-only behaviour. */
  override val openBrowser: Boolean = "--open-browser" in args

  /**
   * SSRF allowlist for `POST /bundles/{name}?url=` fetches: comma-separated hostnames the server
   * may fetch a bundle from. Empty = no URL fetch is allowed (fail closed), so `--accept-bundles`
   * alone only accepts uploads; a host must be explicitly trusted before the server will reach out.
   */
  override val acceptBundlesFrom: List<String> =
    args
      .flagValue("--accept-bundles-from")
      ?.split(",")
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() } ?: emptyList()

  /**
   * Document ingestion (`--accept-docs`): enable `GET /docs` + `POST /docs` so a client can hand
   * the server one **known document** (a Remote Compose `.rc`, a Lottie JSON — `ServeDocFormats`)
   * and get back an **expiring permalink** (`/d/<id>`) that plays it in the browser. Off by
   * default.
   *
   * Independent of `--accept-bundles`: a bundle becomes a whole preview session, a document is one
   * file with a short-lived share link and no server-side render at all.
   */
  override val acceptDocs: Boolean = "--accept-docs" in args

  /** How long an ingested document's permalink lives (`--doc-ttl <seconds>`). */
  override val docTtlSeconds: Long =
    args.flagValue("--doc-ttl")?.toLongOrNull()?.takeIf { it > 0 } ?: ServeDefaults.DOC_TTL_SECONDS

  /**
   * SSRF allowlist for `POST /docs?url=`: hostnames the server may fetch a document from. Empty =
   * uploads only (fail closed), exactly like [acceptBundlesFrom].
   */
  override val acceptDocsFrom: List<String> =
    args.flagValue("--accept-docs-from")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
      ?: emptyList()

  /**
   * `--playground-bundle <path|system>`: enable the playground lane (`POST /api/{v}/compiler/run`),
   * resolving the CMP compile classpath from a catalog liveBundle. Takes either a local `.bundle`
   * path or — since issue #3212 — the id of a system this box already serves via `--catalogs`
   * (`--playground-bundle compose-m3`), which reuses that catalog's fetched, trust-verified bundle
   * instead of a hand-placed copy that would silently go stale. See `PlaygroundBundleSource`.
   *
   * Under `--public` the lane still has to clear `PlaygroundPublicGate`, which admits it either
   * behind a verified sandbox or behind GitHub repo-access gating — the compile runs
   * **user-supplied code**, so one of the two must bound who supplies it. See
   * docs/design/PLAYGROUND.md §6.
   */
  override val playgroundBundlePath: String? = args.flagValue("--playground-bundle")

  /**
   * `--playground-android-bundle <path|system>`: enable the playground's **Android / Remote
   * Compose** compile lane, resolving its classpath from an Android catalog liveBundle — a local
   * path or a served `--catalogs` system id, exactly like `--playground-bundle`. Snippets sent with
   * `confType=remote-compose` compile against it, render on the Robolectric daemon, and their
   * captured `.rc` is published as a `/d/<id>` permalink (needs the `lib-daemon-android` sidecar +
   * `android.jar` + the `/d/` document store). Gated under `--public` like `--playground-bundle`.
   */
  override val playgroundAndroidBundlePath: String? = args.flagValue("--playground-android-bundle")

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
  override val playgroundRuntimeSelection: Boolean = "--playground" in args

  /**
   * `--playground-catalog-limit <n>`: how many runtime-selected catalogs may hold a resolved
   * compile classpath at once. Each one is an unpacked bundle plus a resolved Maven classpath held
   * for the life of the process (they cannot be evicted while snippet JVMs hold their jars open),
   * so this is the knob that stops a public host from being walked into a full disk by a visitor
   * clicking through every entry in the selector.
   */
  override val playgroundCatalogLimit: Int =
    args.flagValue("--playground-catalog-limit")?.toIntOrNull()?.takeIf { it > 0 }
      ?: ServeDefaults.PLAYGROUND_CATALOG_LIMIT

  /**
   * `--playground-rate-limit <n>`: compiles per minute **per caller** (0 disables the limiter).
   *
   * Every other playground bound — compile slots, the compile timeout, the body cap, live seats,
   * the token store — is a whole-host one (issue #3214). None of them stops two callers from
   * holding every slot with back-to-back 180-second compiles while everyone else is told the
   * playground is busy. This is the fair-sharing half.
   */
  override val playgroundRateLimit: Int =
    args.flagValue("--playground-rate-limit")?.toIntOrNull()?.takeIf { it >= 0 }
      ?: ServeDefaults.DEFAULT_PLAYGROUND_RATE_LIMIT

  /**
   * `--playground-caller-concurrency <n>`: compiles one caller may hold at once. Default 1, which
   * is the knob that answers the complaint directly — with the host's `--playground-compile-slots`
   * at its default 2, one caller cannot hold both.
   */
  override val playgroundCallerConcurrency: Int =
    args.flagValue("--playground-caller-concurrency")?.toIntOrNull()?.takeIf { it > 0 } ?: 1

  /** Authenticated, explicitly acquired, single-host stateful BTA editing trial. Off by default. */
  override val playgroundEditing: Boolean = "--playground-editing" in args

  override val playgroundEditLeaseTtlSeconds: Long =
    args.flagValue("--playground-edit-lease-ttl")?.toLongOrNull()?.takeIf { it > 0 }
      ?: ServeDefaults.PLAYGROUND_EDIT_LEASE_TTL_MILLIS / 1000

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
  override val trustForwardedFor: Boolean = "--trust-forwarded-for" in args

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
  override val playgroundSandboxSpec: String? = args.flagValue("--playground-sandbox")

  override val playgroundSandboxMemoryMb: Int =
    args.flagValue("--playground-sandbox-memory-mb")?.toIntOrNull()
      ?: ServeDefaults.PLAYGROUND_SANDBOX_MEMORY_MB

  override val playgroundSandboxCpus: Double =
    args.flagValue("--playground-sandbox-cpus")?.toDoubleOrNull()
      ?: ServeDefaults.PLAYGROUND_SANDBOX_CPUS

  override val playgroundSandboxPids: Int =
    args.flagValue("--playground-sandbox-pids")?.toIntOrNull()
      ?: ServeDefaults.PLAYGROUND_SANDBOX_PIDS

  /**
   * `--playground-compile-slots <n>`: how many snippet compiles may hold a jailed JVM at once. The
   * compile-side counterpart to `--live-seats` — per-process caps bound one compile, this bounds
   * the aggregate, so peak compile memory is `slots × --playground-sandbox-memory-mb`.
   */
  override val playgroundCompileSlots: Int =
    args.flagValue("--playground-compile-slots")?.toIntOrNull()?.takeIf { it > 0 }
      ?: ServeDefaults.PLAYGROUND_COMPILE_SLOTS

  /** Hard wall-clock lifetime of one snippet JVM; the spawner kills it at the deadline. */
  override val playgroundSandboxTtlSeconds: Long =
    args.flagValue("--playground-sandbox-ttl")?.toLongOrNull()
      ?: ServeDefaults.PLAYGROUND_SANDBOX_TTL_SECONDS

  /**
   * `--playground-sandbox-ro <path>[,<path>…]`: extra host paths bound **read-only** into the jail.
   * The escape hatch for caches a render legitimately reads while having no network to fetch them —
   * the Robolectric `android-all` cache (`~/.m2/repository`) and the downloadable-font cache are
   * the two that matter in practice; prewarm them before going public.
   */
  override val playgroundSandboxReadOnlyPaths: List<String> =
    args
      .flagValue("--playground-sandbox-ro")
      ?.split(",")
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() } ?: emptyList()

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
  override val extraMavenRepos: List<String> =
    args.flagValue("--extra-maven-repos")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
      ?: emptyList()

  /**
   * Path to the producer-trust store (`--trust-store <file>`): the JSON allowlist of trusted
   * signing keys / branches / CI identities ([TrustStore]). Uploaded bundles are verified against
   * it and the verdict is surfaced in the API + viewer. Absent ⇒ the empty, fail-closed store
   * (every upload `unverified`), which is correct for a private box; a public server points it at
   * `trust/producers.json`.
   */
  override val trustStorePath: String? = args.flagValue("--trust-store")

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
  override val catalogsRaw: String? = args.flagValue("--catalogs")

  /**
   * Like [catalogsRaw], but these systems are served **without** a front-page nav link — reachable
   * by path (`/<system>/`) / `?session=<system>` but hidden from the landing "Design systems" row
   * (`--catalogs-unlisted meshcore-mobile@yschimke/meshcore-mobile,…`). For app design systems we
   * publish but don't want on the public front door.
   */
  override val catalogsUnlistedRaw: String? = args.flagValue("--catalogs-unlisted")

  /**
   * **Top-level sites** (`--sites m3.preview.coo.ee=m3-catalog,…`; also `catalogs.json`'s `sites`):
   * host names on which one already-served catalog is presented as the whole server — its landing
   * at `/`, its links inside the custom domain, no front door and no neighbours. See `ServeSites`;
   * it adds no catalog and no work, only a different reading of the same request.
   */
  override val sitesRaw: String? = args.flagValue("--sites")

  /** Raw `--catalogs-file` path; the server opens it. */
  override val catalogsFilePath: String? =
    args.flagValue("--catalogs-file")?.takeIf { it.isNotBlank() }

  /** Durable feed cache; defaults beside catalogs.json on deployed boxes, temp for local serve. */
  override val catalogFeedCacheDir: File by lazy {
    val preferred =
      args.flagValue("--catalog-feed-cache")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: catalogsFilePath?.let(::File)?.absoluteFile?.parentFile?.resolve("catalog-feeds")
    if (
      preferred != null && (preferred.isDirectory || preferred.mkdirs()) && preferred.canWrite()
    ) {
      preferred
    } else {
      java.nio.file.Files.createTempDirectory("serve-catalog-feeds").toFile().also {
        it.deleteOnExit()
        if (preferred != null) {
          System.err.println(
            "serve: catalog feed cache ${preferred.absolutePath} is not writable; using ${it.absolutePath}"
          )
        }
      }
    }
  }

  /**
   * Shared secret for the runtime admin routes (`--admin-token`; env `SERVE_ADMIN_TOKEN`) — both
   * `/admin/catalogs` and `/admin/trust`. Absent ⇒ neither is registered at all, so a server that
   * didn't opt in has no admin surface. Deliberately distinct from the browse token: a `--public`
   * box hands that one out to every visitor.
   *
   * On a server running `--allow-render-trusted`, treat this as a code-execution credential:
   * `/admin/trust` can make a producer's Compose eligible for server-side re-render here.
   */
  override val adminToken: String? = args.flagValue("--admin-token")?.takeIf { it.isNotBlank() }

  /** Optional durable aggregate counters. Null keeps local serve sessions in-memory only. */
  override val engagementFile: File? =
    args.flagValue("--engagement-file")?.takeIf { it.isNotBlank() }?.let(::File)

  override val githubAuthClientId: String? =
    args.flagValue("--github-auth-client-id")?.takeIf { it.isNotBlank() }

  override val githubAuthClientSecret: String? =
    args.flagValue("--github-auth-client-secret")?.takeIf { it.isNotBlank() }

  override val githubAuthCookieSecret: String? =
    args.flagValue("--github-auth-cookie-secret")?.takeIf { it.isNotBlank() }

  override val githubAuthRepo: String? =
    args.flagValue("--github-auth-repo")?.takeIf { it.isNotBlank() }

  override val githubAuthCallbackBaseUrl: String? =
    args.flagValue("--github-auth-callback-base-url")?.takeIf { it.isNotBlank() }

  /**
   * Scopes the auth cookies to a parent domain so one sign-in covers it and every `--sites` host
   * under it (`preview.coo.ee` ⇒ valid on `m3.preview.coo.ee`). Unset keeps them host-only, which
   * is right for a single-hostname box; it is deliberately explicit rather than derived, since a
   * cookie domain is the blast radius of a session.
   */
  override val githubAuthCookieDomain: String? =
    args.flagValue("--github-auth-cookie-domain")?.takeIf { it.isNotBlank() }

  /**
   * Overrides the OAuth scope. Unset derives it from `--github-auth-repo`'s visibility, which is
   * what a deployment wants unless its GitHub App or org policy demands something specific.
   */
  override val githubAuthScope: String? =
    args.flagValue("--github-auth-scope")?.takeIf { it.isNotBlank() }

  override val githubAuthUsers: Set<String> =
    args
      .flagValue("--github-auth-users")
      ?.split(",")
      ?.map { it.trim().lowercase() }
      ?.filter { it.isNotEmpty() }
      ?.toSet() ?: emptySet()

  /**
   * Agent access grants (`--agent-grants`): enable the device-grant flow at `/agent-access/…` so an
   * agent with no credential can ask for temporary, scoped, revocable access, and a human approves
   * it from a link the agent prints. See
   * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
   *
   * Off by default and deliberately not derivable: the lane's whole purpose is to mint credentials,
   * so an operator turns it on knowingly or not at all.
   */
  override val agentGrants: Boolean = "--agent-grants" in args

  /** Raw `--agent-grant-scopes`; the server parses it (an unknown scope throws there). */
  override val agentGrantScopesFlag: String? = args.flagValue("--agent-grant-scopes")
  /** Raw `--agent-grant-max-ttl` (e.g. `2h`/`90m`/`3600`); the server parses and clamps it. */
  override val agentGrantMaxTtlFlag: String? = args.flagValue("--agent-grant-max-ttl")
  /** Raw `--agent-grant-capabilities`; the server parses it (an unknown name throws there). */
  override val agentGrantCapabilitiesFlag: String? = args.flagValue("--agent-grant-capabilities")
  /** How many grants may be live at once (`--agent-grant-max-active`). */
  override val agentGrantMaxActive: Int =
    args.flagValue("--agent-grant-max-active")?.let {
      it.toIntOrNull()?.takeIf { n -> n > 0 }
        ?: throw IllegalArgumentException(
          "--agent-grant-max-active '$it' is not a positive whole number"
        )
    } ?: ServeDefaults.AGENT_GRANT_MAX_ACTIVE

  /**
   * Per-address budget on the two ungated grant routes (`--agent-grant-rate-limit`, requests per
   * minute; `0` disables). The default is generous enough for a polling agent — one poll every
   * three seconds is 20/min — and small enough that an anonymous caller cannot churn the request
   * map.
   */
  override val agentGrantRateLimit: Int =
    args.flagValue("--agent-grant-rate-limit")?.let {
      it.toIntOrNull()?.takeIf { n -> n >= 0 }
        ?: throw IllegalArgumentException(
          "--agent-grant-rate-limit '$it' is not a whole number of requests per minute (0 disables)"
        )
    } ?: ServeDefaults.DEFAULT_AGENT_GRANT_RATE_LIMIT

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
  override val acceptImages: Boolean = "--accept-images" in args

  /** How long an uploaded image's link lives (`--image-ttl <seconds>`); default 7 days. */
  override val imageTtlSeconds: Long =
    args.flagValue("--image-ttl")?.toLongOrNull()?.takeIf { it > 0 }
      ?: ServeDefaults.IMAGE_TTL_SECONDS

  /**
   * The repository an uploader must have access to (`--image-upload-repo <owner/repo>`), falling
   * back to the GitHub-auth gating repo when the operator already configured one. There is no
   * default beyond that and the lane refuses to start without it: a gate whose repository was
   * guessed is not a gate.
   */
  override val imageUploadRepository: String? =
    args.flagValue("--image-upload-repo")?.takeIf { it.isNotBlank() } ?: githubAuthRepo

  /** Uploads per minute per GitHub account (`--image-rate-limit`); `0` disables the budget. */
  override val imageRateLimit: Int =
    args.flagValue("--image-rate-limit")?.toIntOrNull()?.takeIf { it >= 0 }
      ?: ServeDefaults.DEFAULT_IMAGE_RATE_LIMIT

  /**
   * Server-wide admission for the catalogs' background theme optimization: it parks while any
   * catalog is loading, and bounds how many of them render at once. Shared by every catalog host
   * this server opens — see [ServeBackgroundWork] for why both halves matter on a public box.
   *
   * The lane is derived from [liveSeatLimiter] because widening it is only safe where something
   * else bounds daemon count: an unbounded budget (the CLI default) keeps the single lane.
   */
  override val optimizerCoordinationDirectory: File? by lazy {
    val explicit =
      args.flagValue("--theme-optimizer-coordination-dir")?.takeIf { it.isNotBlank() }?.let(::File)
    explicit ?: catalogsFilePath?.let(::File)?.absoluteFile?.parentFile?.resolve("optimizer-locks")
  }

  /** Raw `--catalog-cache-dir` (`none` disables persistence); the server resolves and opens it. */
  override val catalogCacheDirFlag: String? =
    args.flagValue("--catalog-cache-dir")?.takeIf { it.isNotBlank() }

  /** Raw `--catalog-cache-max-bytes`; the server applies its own default when unset. */
  override val catalogCacheMaxBytesFlag: Long? =
    args.flagValue("--catalog-cache-max-bytes")?.toLongOrNull()?.takeIf { it > 0 }
  /** Raw `--theme-cache-dir` (`none` disables); the server resolves, creates and opens it. */
  override val themeCacheDirFlag: String? =
    args.flagValue("--theme-cache-dir")?.takeIf { it.isNotBlank() }

  /** Raw `--theme-cache-max-bytes`; the server applies its own default when unset. */
  override val themeCacheMaxBytesFlag: Long? =
    args.flagValue("--theme-cache-max-bytes")?.toLongOrNull()?.takeIf { it > 0 }

  /** `--theme-cache-evict`: drop every cached generation once, at startup. */
  override val themeCacheEvictRequested: Boolean = "--theme-cache-evict" in args
  /**
   * In-browser CMP tier (`--wasm-dir <system>=<dir>[,<system>=<dir>…]`): map a design system to the
   * assembled Wasm catalog app (`./gradlew :samples:cmp-wasm-catalog:wasmCatalogDist` →
   * `build/wasmDist`). Its viewer then offers a "Run in browser (Wasm)" toggle that mounts the app
   * client-side. Missing dirs are dropped with a warning. Empty ⇒ no Wasm tier.
   */
  override val wasmDirs: Map<String, File> =
    args
      .flagValue("--wasm-dir")
      ?.split(",")
      ?.mapNotNull { entry ->
        val eq = entry.indexOf('=')
        if (eq <= 0) null else entry.substring(0, eq).trim() to File(entry.substring(eq + 1).trim())
      }
      ?.toMap() ?: emptyMap()

  /** Experimental AndroidX-conformant Remote Compose CMP/Wasm player distribution. */
  override val rcPlayerWasmDir: File? =
    args
      .flagValue("--rc-player-wasm-dir")
      ?.takeIf { it.isNotBlank() }
      ?.let(::File)
      ?.let { dir ->
        if (File(dir, "index.html").isFile) dir
        else {
          System.err.println(
            "serve: --rc-player-wasm-dir ${dir.path} has no index.html — skipping (build it with " +
              ":rc-player-wasm:wasmPlayerDist)."
          )
          null
        }
      }

  override val catalogRepo: String =
    args.flagValue("--catalog-repo")?.takeIf { it.isNotBlank() } ?: ServeDefaults.CATALOG_REPO

  override val catalogBranchPrefix: String =
    args.flagValue("--catalog-branch-prefix")?.takeIf { it.isNotBlank() }
      ?: ServeDefaults.CATALOG_BRANCH_PREFIX

  override val catalogMaxImages: Int =
    args.flagValue("--catalog-max-images")?.toIntOrNull()?.takeIf { it > 0 }
      ?: ServeDefaults.CATALOG_MAX_IMAGES

  /**
   * `serve` is the one command that turns a `@PreviewParameter` fan-out into addressable row ids
   * (issue #3786), so it is the one that wants module selection to keep previews whose rows *might*
   * match. It can afford to: the render below produces the fan-out, `ServeParameterRows` expands
   * it, and [modulesWithMatchingPreviews] then discards any module whose "maybe" didn't pan out —
   * before the one-module check that would otherwise choke on it.
   */
  override val rowAwareSelection: Boolean
    get() = true

  public fun printUsage() {
    println(
      """
      compose-preview serve [options]

      Start a local HTTP server that renders one module's @Preview functions on demand and serves
      them as PNGs with display overrides, so you can open (or share) a link to a specific preview.
      Read-only today; bound to loopback unless you opt into LAN exposure.

      Options:
        --module <path>   Module to serve, scoping local Gradle discovery + build to it. Implies
                          --discover. Omit it (and --discover) to run module-less — see below.
        --discover        Opt in to local Gradle discovery + build. By default serve NEVER runs
                          Gradle: it hosts only the fetched --bundle(s) / --catalogs / uploaded
                          bundles as a pure preview server, even inside a Gradle checkout (so a
                          stray `serve` at a repo root can't trigger a full — possibly hanging —
                          module build). Pass --discover to build every module's previews and host
                          one, or --module <path> to scope to a single module.
        --bundle <url|path>[, --bundle <name>=<url|path>, …]
                          Serve one or more fetched preview bundles directly — no --module, no
                          build. A URL is fetched at startup (operator-supplied, so no SSRF gate;
                          e.g. a raw.githubusercontent.com/<owner>/<repo>/<branch>/… link); a local
                          path is read. Each is served at /<name>/. A bundle that verifies Trusted
                          (Ed25519 signature, or fetched from a trusted branch in --trust-store) is
                          served LIVE from a render daemon when --allow-render-trusted is set
                          (desktop bundles); otherwise read-only as its baked PNGs. Repeatable.
        --id <exact>      Only serve this exact preview id.
        --filter <substr> Only serve previews whose id contains this substring.
        --preview <ref>   Only serve previews the reference selects: an id, a
                          `Class.function`, a bare function name, or a case-insensitive
                          substring of an id. Combined with --id / --filter it intersects:
                          every selector you pass has to match. Selecting needs a module
                          (--module / --discover) — a bundle-backed server has no manifest.
        --host <addr>     Bind address (default 127.0.0.1 — loopback only).
        --lan             Bind all interfaces (0.0.0.0) so other devices on your network can
                          connect. Prints the token-gated network URL and a security warning.
        --port <n>        Preferred port (default ${ServeDefaults.DEFAULT_PORT}; auto-picks the next free one).
        --token <value>   Use a fixed token instead of a freshly generated one (stable links).
        --public          Serve every route WITHOUT a token (open). For a deployed public preview
                          server — browsing published catalogs / uploaded bundles is the point. Safe
                          by construction (no server-side code exec; untrusted re-render refused;
                          uploads capped + SSRF-gated). Off by default.
        --component-browser
                          Use the streamlined Storybook-like catalog and component browser. Hides
                          administration, diagnostics, comparison and renderer tooling while
                          retaining visual navigation, variants, themes, authored controls,
                          sample source, locale/font scale and PNG/SVG downloads.
        --github-auth-client-id <id>
        --github-auth-client-secret <secret>
        --github-auth-cookie-secret <secret>
        --github-auth-repo <owner/repo>
                          Add GitHub OAuth on top of the browse gate for code-running surfaces:
                          live preview WebSockets and the playground. Live preview accepts any
                          signed-in GitHub user (unless --github-auth-users narrows sign-in);
                          playground additionally requires access to <owner/repo>. After sign-in
                          the server stores only a signed, expiring login cookie plus the repo
                          access verdict. The OAuth scope follows the repo's visibility: a public
                          <owner/repo> needs only read:user, a private one also needs repo (classic
                          OAuth apps have no read-only repository scope). All four flags are
                          required together.
        --github-auth-callback-base-url <url>
                          External origin for the OAuth callback, e.g. https://preview.example.com.
                          Omit for local use; reverse-proxied deploys should set it explicitly.
        --github-auth-cookie-domain <domain>
                          Scope the auth cookies to a parent domain, so one sign-in covers it and
                          every --sites hostname under it (preview.example.com also signs in
                          m3.preview.example.com). Required for sign-in to work on a top-level site
                          when --github-auth-callback-base-url is pinned: without it the cookies are
                          host-only, the state cookie never reaches the callback origin, and the
                          server withholds sign-in on site hosts. Omit on a single-hostname box.
                          Every host under <domain> is inside the session's reach, so name the
                          narrowest one that covers your sites.
        --github-auth-scope <scope>
                          Override the OAuth scope instead of deriving it from --github-auth-repo's
                          visibility. Only needed when a GitHub App or org policy demands a specific
                          one; the derived value is already the narrowest that works.
        --github-auth-users <login>[,<login>…]
                          Optional sign-in allowlist. Empty means any signed-in GitHub user may use
                          live sessions; playground still requires access to --github-auth-repo.
        --agent-grants    Let an agent ask for temporary access it can't otherwise get. The agent
                          POSTs /agent-access/request and prints a link plus a verification code;
                          you open the link, check the code matches, and approve. It then collects a
                          short-lived bearer token scoped to what you ticked. Approving requires a
                          signed-in GitHub user (with --github-auth-*) or the --token holder; a
                          --public server with neither is refused. Revoke any time from /status.
                          Off by default.
        --agent-grant-scopes <list>
                          Ceiling on what a grant may carry: preview, live, playground (cumulative;
                          default preview,live). 'playground' lets an approved agent compile and run
                          Kotlin on this host, and can only be approved by someone who has access to
                          --github-auth-repo themselves.
        --agent-grant-capabilities <list>
                          Extra permissions a grant may carry beside its scope, chosen separately by
                          the approver: currently 'images' (upload rendered previews through the
                          image lane, needs --accept-images). Off by default — a scope ceiling says
                          nothing about these.
        --agent-grant-max-ttl <duration>
                          Longest grant this server will mint, e.g. 90m / 2h / 3600 (default 8h,
                          hard ceiling 24h). The approver picks the actual lifetime on the page.
        --agent-grant-max-active <n>
                          Live grants allowed at once (default ${ServeDefaults.AGENT_GRANT_MAX_ACTIVE}); a new one evicts the
                          nearest to expiry.
        --agent-grant-rate-limit <n>
                          Requests per minute per address on the two ungated grant routes (default
                          ${ServeDefaults.DEFAULT_AGENT_GRANT_RATE_LIMIT}; 0 disables the budget entirely).
        --export <path>   Don't serve: render every preview once and write a portable bundle (a
                          self-contained web gallery + PNGs) to <path>. A '.zip' path writes a zip;
                          any other path writes a directory. The live server also offers this at
                          GET /bundle.zip.
        --inline          With --export, bake the PNGs into the gallery for a single self-contained
                          index.html (vs. separate previews/<id>.png files).
        --revisions       Project mode: also serve other git revisions of this repo on demand. A
                          request with ?session=<rev> checks that revision out into a worktree,
                          builds it, and serves it as its own session (suspended/resumed when idle).
        --history-branch <ref>
                          Project mode: the baseline delivery branch, as fetched in this checkout,
                          whose publishes the viewer's render-history strip is built from (default
                          ${ServeDefaults.HISTORY_BRANCH}; 'origin/<ref>' is tried too). Each
                          entry opens that version's render, served from the local object store.
                          A ref this clone can't resolve simply means no strip.
        --no-history      Don't compute the render-history strip from local git.
        --revisions-allow <ref>[,<ref>…]
                          Project mode SECURITY gate: only revisions reachable from these trusted
                          refs (e.g. main,release) are checked out and built — building runs that
                          revision's own Gradle (code execution). Omitted/empty = nothing builds
                          (fail closed), so arbitrary ?session=<rev> can't run code on the server.
                          Also gates --allow-render-trusted (the catalog source ref allowlist).
        --allow-render-trusted
                          SECURITY gate, opt-in (default off): serve a --catalogs catalog that is
                          Trusted AND declares a source as a live, re-renderable session built from
                          that source (full-fidelity overrides) instead of static baked PNGs. Runs
                          the source's Gradle = code execution, so it's gated by trust + the
                          --revisions-allow ref allowlist + a same-repo check. NEVER set this on a
                          box that can't build the catalog source (e.g. the desktop-only public
                          image can't build the Android catalogs) — leave it off and let the
                          in-browser Wasm tier carry CMP.
        --catalog-source-root <dir>
                          Git repo root the trusted-catalog builder (--allow-render-trusted)
                          worktrees + builds from, instead of the served --module's own project. Use
                          when the server is module-less but the catalog's source.repo is a separate
                          checkout (e.g. a prebuilt image that clones the CMP catalog repo for live
                          render). The trust + same-repo + ref-allowlist gates are unchanged.
        --live-seats <n>  Live (daemon-backed) stream PERMIT BUDGET. Each live session charges permits
                          by backend weight — a desktop CMP daemon costs 1, a heavier Robolectric
                          Android one costs 2 — so one heavy catalog can't hog a flat seat count and
                          starve the cheap CMP lanes. On a small box bound this (e.g. 2) when the live
                          tier is on (--allow-render-trusted); an over-budget stream is refused (WS
                          1013) rather than risking the OOM killer. Default 0 = unbounded. Snapshot +
                          Wasm sessions never take a permit.
        --exit-when-idle[=<seconds>]
                          Ephemeral mode: shut the server down once it's been idle (no open
                          connections and no requests) for <seconds> (default ${ServeDefaults.DEFAULT_IDLE_EXIT_SECONDS}s). Use a small
                          value to exit shortly after the last client disconnects.
        --bundles <dir>   Shared mode: also host pre-rendered portable bundles (no build/daemon). A
                          bundle dir, or a directory of them, is served read-only — each reachable at
                          ?session=<bundle-name>. Bundles are what --export / GET /bundle.zip produce.
        --accept-bundles  Shared mode (public): enable POST /bundles/<name> so clients can contribute
                          bundles at runtime — upload the zip as the body, or pass ?url=<link> to a
                          build-results artifact. Pair with --lan + a strong --token for a shared
                          instance. Uploads only by default; ?url= fetches need --accept-bundles-from.
        --accept-bundles-from <host>[,<host>…]
                          SSRF allowlist for POST /bundles?url=: hostnames the server may fetch a
                          bundle from. Omitted/empty = no URL fetch is allowed (fail closed), so a
                          client can't steer the server at an arbitrary or internal address.
        --accept-docs     Enable the DOCUMENT lane: GET /docs (drop a file) + POST /docs ingest one
                          known document — Remote Compose (.rc) or Lottie (.json) — and hand back an
                          expiring permalink (/d/<id>) that plays it in the viewer's browser. Data
                          only: the server stores bytes and never renders them, so hosting an
                          anonymous document runs nothing. Off by default.
        --doc-ttl <seconds>
                          How long a /d/<id> document link lives (default ${ServeDefaults.DOC_TTL_SECONDS}s). The document is
                          held in memory and dropped when it expires.
        --accept-docs-from <host>[,<host>…]
                          SSRF allowlist for POST /docs?url=: hostnames the server may fetch a
                          document from. Omitted/empty = uploads only (fail closed).
        --accept-images   Enable the IMAGE lane: POST /images ingests a rendered preview (PNG, GIF,
                          WebP, JPEG) and answers with /i/<id>.png — a URL an agent can embed in a
                          pull-request body. Uploading is NEVER anonymous: it needs
                          "Authorization: Bearer <github-token>" from an account with access to
                          --image-upload-repo, on a --public host too. Reading is open, because
                          GitHub's image proxy fetches an embedded image anonymously; the 128-bit id
                          is the access control. Off by default.
        --image-upload-repo <owner/repo>
                          Repository an uploader must have access to. Defaults to --github-auth-repo
                          when that is set; without either, --accept-images refuses to start.
        --image-ttl <seconds>
                          How long a /i/<id> image link lives (default ${ServeDefaults.IMAGE_TTL_SECONDS}s = 7 days). Held in
                          memory and dropped when it expires; ${ServeDefaults.IMAGE_MAX_IMAGES} images / ${ServeDefaults.IMAGE_MAX_TOTAL_BYTES / (1024 * 1024)}MB max, the
                          oldest evicted first.
        --image-rate-limit <n>
                          Uploads per minute per GitHub account (default ${ServeDefaults.DEFAULT_IMAGE_RATE_LIMIT}). 0 disables the
                          budget.
        --trust-store <file>
                          Producer-trust allowlist (JSON: signing keys / branches / CI identities).
                          Uploaded bundles are verified against it and the verdict (signature /
                          branch / provenance / unverified) is returned + badged. Omitted = trust
                          nothing (every upload unverified); the data tiers serve either way.
        --catalogs <system>[@<owner>/<repo>][,…]
                          Serve our published design systems from their design-artifacts/<system>
                          branches (e.g. compose-m3,wear-m3): each is fetched (catalog.json + images)
                          and served read-only at /<system>/ (also ?session=<system>), listed on the
                          front-page nav, trusted-by-origin when the branch is in --trust-store. Add
                          @<owner>/<repo> to fetch a system from a different repo than --catalog-repo
                          (e.g. meshcore-mobile@yschimke/meshcore-mobile).
        --catalogs-unlisted <system>[@<owner>/<repo>][,…]
                          Like --catalogs, but served WITHOUT a front-page nav link — reachable at
                          /<system>/ and ?session=<system> but hidden from the landing "Design
                          systems" row. For app design systems we publish but keep off the front door.
        --catalogs-file <path>
                          The catalog set as CONFIG, not flags: a catalogs.json listing every
                          catalog to serve ({"groups":[…],"catalogs":[{"system","repo","listed",
                          "group"}]}). Meant to live outside the container image (a mounted volume)
                          so publishing a catalog is a config edit, not an image rebuild. The
                          "group" names a front-page section from "groups" — a claim honoured only
                          when the catalog's bytes really came from the entry's repo (or one of its
                          "attributionRepos"), so an id like compose-m3 can't buy a section. Entries
                          here come first; --catalogs / --catalogs-unlisted add to them. May also
                          carry "sites" (see --sites).
        --sites <host>=<system>[,…]
                          Top-level sites: serve an already-published catalog on a hostname of its
                          own, where it looks like the whole server (e.g.
                          m3.preview.coo.ee=m3-catalog serves what /m3-catalog/ serves). On that
                          host the catalog's landing is /, every link stays inside the domain, the
                          front-door index and the "all design systems" back link are gone, /status
                          + /sitemap.xml cover that app only, and /<other-system>/ 404s while
                          /<this-system>/… 301s to the rooted URL. Same sessions, same baked pixels,
                          same daemons — a site is a view of the box, not a second one. The system
                          must be one this server already serves; catalogs.json's "sites" says the
                          same thing as config, and POST /admin/sites says it on a running server
                          (see --admin-token), writing it back to --catalogs-file. Two things this
                          does NOT do, because they are outside the app: DNS for the name must point
                          at this box, and the reverse proxy must match the name and hold a
                          certificate for it.
        --admin-token <value>
                          Enable the runtime admin API and gate it with this secret. Two surfaces:
                          the catalog set — GET /admin/catalogs, POST /admin/catalogs (a
                          catalogs.json entry as the body), DELETE /admin/catalogs/<system> — and
                          the producer-trust store — GET /admin/trust, POST /admin/trust
                          ({"kind":"branch"|"key"|"oidc",…}), DELETE /admin/trust?kind=&repo=…
                          (selectors ride the query string so an owner/repo needn't be escaped).
                          the front-page sections — GET /admin/groups, POST /admin/groups
                          ({"id","heading","noun"}), DELETE /admin/groups/<id>. Defining a section
                          also regroups catalogs already registered, and re-POSTing a published
                          catalog converges its listing (group / listed) in place. And the
                          top-level sites — GET /admin/sites, POST /admin/sites
                          ({"host","system"}), DELETE /admin/sites/<host> — so a hostname can be
                          published on a running box; re-POSTing one whose system changed re-points
                          it in place. The edge still has to route the name and hold a certificate
                          for it (see --sites).
                          Mutations are applied live AND written back to --catalogs-file /
                          --trust-store, so they survive a restart. Separate from --token on purpose
                          (a --public box hands that one to every visitor); omitted = the admin
                          routes don't exist at all. NB with --allow-render-trusted this token can
                          grant server-side execution, since trusting a branch makes that
                          producer's Compose eligible for re-render here.
        --engagement-file <path>
                          Persist privacy-minimal aggregate catalog/app and per-preview view counts
                          as JSON. No IPs, cookies, user agents, or referrers are stored. Omitted =
                          counters last only for this server process.
        --catalog-repo <owner/repo>
                          Default repo the catalogs are fetched from (default
                          yschimke/compose-ai-tools); per-entry @<owner>/<repo> overrides it.
        --catalog-branch-prefix <prefix>
                          Branch prefix for --catalogs (default design-artifacts/).
        --catalog-max-images <count>
                          Maximum baked previews loaded from one published catalog (default
                          ${ServeDefaults.CATALOG_MAX_IMAGES}). Images are fetched lazily; this
                          bounds registered preview metadata and routes, not eager image downloads.
        --catalog-refresh-interval <seconds>
                          Keep a running server fresh: re-check each --catalogs branch's head every
                          <seconds> and re-fetch (catalog.json + renders + web/wasm/ + liveBundle) in
                          place when it moved — so a regenerated branch is picked up with no restart
                          (default ${ServeDefaults.DEFAULT_CATALOG_REFRESH_SECONDS}s; 0 disables, serving the boot snapshot only). Uses
                          `git ls-remote` (no API rate limit), and skips a branch it can't resolve.
        --catalog-feed-idle-timeout <seconds>
                          Publish /<catalog>/feed.xml. A request renews that feed's background
                          history-computation lease; after this many seconds without another request
                          it stops fetching while keeping the last generated feed and shallow Git
                          cache (default ${ServeDefaults.DEFAULT_CATALOG_FEED_IDLE_SECONDS}s; 0 disables).
        --catalog-feed-cache <dir>
                          Durable shallow-Git + generated-XML cache for catalog feeds. Defaults to a
                          catalog-feeds directory beside --catalogs-file, or a temp dir in local mode.
        --catalog-cache-dir <dir>|none
                          Durable, content-addressed store for the heavy bytes a catalog fetches —
                          the executable liveBundle, its per-preview splits and the externalised
                          resource pool — so a reload or a restart re-reads them instead of pulling
                          ~100 MB per live catalog again. Unset (and `none`) keeps the temp-dir pool
                          this always had, which dies with the process; there is no derived default,
                          because the obvious one is the config volume. Only bytes addressed by a
                          commit-pinned URL are cached, so a load that could not resolve its
                          delivery commit populates nothing.
        --catalog-cache-max-bytes <n>
                          Ceiling for that store (default
                          ${ServeDefaults.CATALOG_BLOB_POOL_MAX_BYTES / (1024L * 1024 * 1024)} GB).
                          Reclaimed oldest-first after the startup pass and after each later
                          catalog publication; blobs newer than an hour are spared so the replicas
                          that overlap during a rolling update cannot evict each other's.
        --theme-cache-dir <dir>|none
                          Durable store for warmed theme renders, so background warming survives a
                          restart and a catalog refresh. `none` disables it. Defaults to a
                          theme-cache directory beside --catalogs-file — note that on the prebuilt
                          image that is the persistent config volume, so pass `none` to keep it off;
                          with no durable location the cache stays in memory only
                          (there is deliberately no temp-dir fallback — it would be thrown away with
                          the process). Entries are keyed by a fingerprint of the render classpath,
                          daemon variant, tool version and render config, so a new catalog revision
                          or server build never reads the previous one's pixels.
        --theme-cache-max-bytes <n>
                          Ceiling for that store across every catalog (default
                          ${ServeDefaults.THEME_CACHE_MAX_BYTES / (1024L * 1024 * 1024)} GB). Superseded
                          generations are reclaimed; generations still in use are never evicted, so
                          exceeding this is reported rather than acted on.
        --theme-cache-evict
                          Delete every persisted theme-render generation at startup, before any is
                          opened. For when the pixels on the volume are known to be wrong — a base
                          image that changed the installed fonts, say, which no fingerprint sees.
                          Ordinary renderer changes need no eviction: entries written by another
                          build are withheld until a re-rendered sample agrees with them, and the
                          whole generation is discarded when it does not.
        --background-renders <n>
                          Background (theme-optimizer) renders admitted at once, server-wide.
                          Defaults to a value derived from --live-seats, which clamps at
                          ${ServeDefaults.MAX_DERIVED_CONCURRENT_RENDERS} — a ceiling reached
                          at 8 seats, so a bigger box stops widening this lane while everything else
                          scales. Name it explicitly to go past that; the seat budget still bounds
                          how many daemons the renders can occupy.
        --theme-optimizer-coordination-dir <dir>
                          Shared directory used to coordinate background optimizer lanes across
                          server replicas. Defaults to optimizer-locks beside --catalogs-file; set
                          this explicitly when replicas do not share that directory.
        --wasm-dir <system>=<dir>[,<system>=<dir>…]
                          In-browser CMP tier: map a design system to its assembled Kotlin/Wasm
                          catalog app (./gradlew :samples:cmp-wasm-catalog:wasmCatalogDist →
                          build/wasmDist). That session's viewer then offers a "Run in browser
                          (Wasm)" toggle that mounts the M3 components client-side (no server
                          round-trip), served read-only at /wasm/<system>/. Missing dirs are skipped.
        --rc-player-wasm-dir <dir>
                          Experimental non-JVM Remote Compose player produced by
                          :rc-player-wasm:wasmPlayerDist. Serves it at /rc-player-wasm/ and enables
                          the "CMP Wasm" RC backend for previews carrying a captured .rc document.

      The shareable link carries an unguessable token; requests without it get 404.
      """
        .trimIndent()
    )
  }

  override fun previewIdMatchesRequest(
    id: String,
    exactId: String?,
    filter: String?,
    previewRef: String?,
    className: String?,
    functionName: String?,
  ): Boolean = previewMatcher(id, exactId, filter, previewRef, className, functionName)
}

private fun List<String>.flagValue(flag: String): String? {
  firstOrNull { it.startsWith("$flag=") }
    ?.let {
      return it.substringAfter("=")
    }
  val index = indexOf(flag)
  return if (index >= 0 && index + 1 < size) this[index + 1] else null
}

private fun List<String>.flagValuesAll(flag: String): List<String> = buildList {
  var index = 0
  while (index < this@flagValuesAll.size) {
    val argument = this@flagValuesAll[index]
    when {
      argument == flag && index + 1 < this@flagValuesAll.size -> {
        add(this@flagValuesAll[index + 1])
        index += 2
      }
      argument.startsWith("$flag=") -> {
        add(argument.substringAfter("="))
        index++
      }
      else -> index++
    }
  }
}
