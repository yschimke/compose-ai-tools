package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.BundleReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Serves the **design systems we publish** on a public preview server by fetching a
 * `design-artifacts/<system>` catalog (`catalog.json` + `images/`) from GitHub and registering it
 * as a read-only [ServeBundleHost] session — the second pillar of the public server, alongside
 * client-uploaded bundles ([ServeBundleStore]).
 *
 * Trust is by **origin**: the catalog isn't a signed bundle, it's content pulled from a branch the
 * operator listed in the [TrustStore]'s `branches`. When the `repo@branch` is trusted the session
 * carries [BundleVerifier.Verdict.Trusted] with a [BundleVerifier.Basis.Branch]; otherwise it
 * serves as `Unverified` (the images are still data — they execute no code — so they're shown
 * either way, just badged). This is what makes browsing `design-artifacts/<system>` and a live,
 * customisable preview the same render output.
 *
 * Fetch surface: a fixed `https://raw.githubusercontent.com/<repo>/<branch>/…` base derived from
 * the **operator-supplied** `--catalogs` / `--catalog-repo` flags — not client input — so there's
 * no SSRF lever here (unlike the `?url=` upload path). [fetch] is injected so tests can stub the
 * network.
 */
class ServeCatalogStore(
  private val root: File,
  private val register: (name: String, host: ServeBundleHost) -> Unit,
  private val trust: TrustStore,
  private val repo: String = DEFAULT_REPO,
  private val branchPrefix: String = DEFAULT_BRANCH_PREFIX,
  private val fetch: (String) -> ByteArray? = ::httpFetch,
  private val maxImages: Int = DEFAULT_MAX_IMAGES,
  /**
   * Called when the catalog declares an in-browser Wasm app (`webRender` in `catalog.json`) and its
   * files were fetched: the system id → the local directory the app was written to. The server then
   * serves it at `/wasm/<system>/`, so the **CMP-Wasm tier rides the same trusted branch as the
   * catalog** — a deployed public server needs no local `--wasm-dir` build, just `--catalogs`.
   */
  private val registerWasm: (system: String, dir: File) -> Unit = { _, _ -> },
  /**
   * Trusted server-side re-render from a carried **executable bundle** (opt-in,
   * `--allow-render-trusted`). When a catalog is `Trusted` AND declares a `liveBundle` (`{path,
   * file}`), the bundle is fetched and this is invoked to stand up a daemon-backed, re-renderable
   * session built straight from it — no Gradle build, no worktree, no repo clone. It's handed the
   * catalog-id→daemon-id `alias` and a `bakedFallback` factory so it can front the baked catalog
   * with the daemon ([ServeCatalogLiveHost]) rather than replace it — the published `/p/<id>` links
   * keep resolving and unmapped ids fall back to baked PNGs. Preferred over [buildTrustedSource]
   * (tried first): returns true when it registered such a session (then both the Gradle source path
   * and the plain static registration are skipped); false ⇒ fall back to [buildTrustedSource], then
   * the static catalog. Default ⇒ never. The callback owns the `--allow-render-trusted` gate; this
   * store only reaches it for an already-`Trusted` catalog, and only after the whole declared
   * bundle file fetched cleanly (fail-closed, like [fetchWasmApp]). `externalResourcesDir` is the
   * rehydrated font/resource pool the daemon adds to its classpath (see
   * [rehydrateExternalResources]) — null when the bundle carried its resources inline (a
   * self-contained pack).
   *
   * `fetchPerPreviewBundle` lifts the **per-preview live lane** (the default render path, with the
   * monolithic bundle as fallback): given a daemon-preview id it fetches that preview's own FULL
   * split bundle (`<liveBundle.path>/previews/<daemon-id>.png` on the same trusted branch,
   * fail-closed) into a local file, or null when the branch ships none / the fetch fails. The
   * per-preview FULL bundles were split from the *externalised* monolithic bundle, so they share
   * its font pool — the caller re-uses the monolithic bundle's already-rehydrated
   * `externalResourcesDir` rather than re-fetching. The builder pools + materialises these on
   * demand and prefers them over the monolithic daemon; a null resolve falls back to it, so the
   * lane is exercised routinely without ever regressing.
   */
  private val buildTrustedBundle:
    (
      system: String,
      bundleFile: File,
      externalResourcesDir: File?,
      alias: Map<String, String>,
      bakedFallback: () -> ServeHost,
      fetchPerPreviewBundle: (daemonId: String) -> File?,
    ) -> Boolean =
    { _, _, _, _, _, _ ->
      false
    },
  /**
   * Trusted server-side re-render (opt-in, `--allow-render-trusted`). When a catalog is `Trusted`
   * AND declares a `source` (`{repo, ref, module}`), this is invoked to stand up a **daemon-backed,
   * re-renderable** session built from that source — so the viewer's controls re-render live at
   * full fidelity instead of replaying baked PNGs. Like [buildTrustedBundle] it's handed the
   * catalog-id→daemon-id `alias` + a `bakedFallback` factory and fronts the baked catalog with the
   * daemon rather than replacing it. Returns true when it registered such a session (then the plain
   * static registration is skipped); false ⇒ fall back to the static catalog. Default ⇒ never (the
   * safe default + what every public deploy uses). The callback owns the ref-allowlist + build
   * gates; this store only reaches it for an already-`Trusted` catalog.
   */
  private val buildTrustedSource:
    (
      system: String,
      source: CatalogSource,
      alias: Map<String, String>,
      bakedFallback: () -> ServeHost,
    ) -> Boolean =
    { _, _, _, _ ->
      false
    },
) {

  /** A catalog's buildable source — where to check out + build to re-render it live. */
  data class CatalogSource(val repo: String, val ref: String, val module: String)

  /**
   * Build the **catalog-id → daemon-preview-id** alias from a catalog's images: each image's
   * route-safe id ([previewIdFor] of its `path`) mapped to the `previewId` the exporter recorded.
   * Images with no `previewId` (Android-only variants, older catalogs) are skipped — they have no
   * live lane. Later duplicates keep the first mapping (the theme/state variants are distinct ids,
   * so collisions shouldn't arise).
   */
  private fun previewAliasFor(catalog: Catalog): Map<String, String> {
    val alias = LinkedHashMap<String, String>()
    for (component in catalog.components) {
      for (image in component.images) {
        val daemonId = image.previewId?.takeIf { it.isNotBlank() } ?: continue
        alias.putIfAbsent(previewIdFor(image.path), daemonId)
      }
    }
    return alias
  }

  sealed interface Result {
    data class Ok(val system: String, val previewCount: Int, val trust: String) : Result

    data class Failed(val system: String, val reason: String) : Result
  }

  /**
   * Fetch the `<branchPrefix><system>` catalog, lay its images out as previews, and register it.
   *
   * [sourceRepo] / [sourceBranchPrefix] override the store's defaults for this one system, so a
   * single server can serve catalogs published to *different* repos (e.g. `compose-m3` from
   * `yschimke/compose-ai-tools` and `meshcore-mobile` from `yschimke/meshcore-mobile`, each in its
   * own `design-artifacts/<system>` branch). Null ⇒ the store's [repo] / [branchPrefix]. The
   * branch-trust verdict is computed against whichever repo actually served the catalog.
   */
  fun load(system: String, sourceRepo: String? = null, sourceBranchPrefix: String? = null): Result {
    val safe = ServeBundleStore.sanitizeName(system) ?: return Result.Failed(system, "invalid name")
    val repo = sourceRepo?.takeIf { it.isNotBlank() } ?: this.repo
    val branchPrefix = sourceBranchPrefix?.takeIf { it.isNotBlank() } ?: this.branchPrefix
    val branch = "$branchPrefix$system"
    val base = "https://raw.githubusercontent.com/$repo/$branch/"

    val catalogBytes =
      try {
        fetch(base + CATALOG_FILE)
      } catch (e: Exception) {
        return Result.Failed(system, "could not fetch catalog.json: ${e.message}")
      } ?: return Result.Failed(system, "could not fetch $base$CATALOG_FILE")
    val catalog =
      runCatching {
          json.decodeFromString(Catalog.serializer(), catalogBytes.toString(Charsets.UTF_8))
        }
        .getOrNull() ?: return Result.Failed(system, "could not parse catalog.json")

    // Stage the fetch so a re-load (ServeCatalogRefresher) can't turn a healthy catalog into 404s:
    // fetch the images into a sibling `.staging` dir and only swap it over the live `dir` once we
    // know we have a usable catalog (count > 0). A partial/failed fetch (e.g. images temporarily
    // unavailable) leaves the currently-served `dir` untouched. The wasm / figma / liveBundle steps
    // below run *after* the swap and are all fail-soft (they disable their tier or fall back to the
    // baked host), so they never leave the catalog broken — only the image fetch is a hard failure,
    // and that's what staging protects.
    val dir = File(root, safe)
    val staging = File(root, "$safe.staging")
    staging.deleteRecursively()
    val previewsDir = File(staging, "previews")
    val previewsRoot = previewsDir.canonicalFile.toPath()
    var count = 0
    // The component slugs whose baked figma-svg to fetch (a slug is the preview id up to `__`).
    val slugs = LinkedHashSet<String>()
    for (component in catalog.components) {
      for (image in component.images) {
        if (count >= maxImages) break
        val path = image.path
        // Only image-directory PNGs; reject traversal. The path is from a trusted branch, but a
        // containment check costs nothing and guards a compromised/garbled catalog.
        val segments = path.split("/")
        if (!path.startsWith("$IMAGES_DIR/") || !path.endsWith(".png") || ".." in segments) continue
        val bytes = runCatching { fetch(base + path) }.getOrNull() ?: continue
        val id = previewIdFor(path)
        val target = File(previewsDir, "$id.png")
        if (!target.canonicalFile.toPath().startsWith(previewsRoot)) continue
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        slugs.add(id.substringBefore(SLUG_SEPARATOR))
        count++
      }
    }
    if (count == 0) {
      staging.deleteRecursively()
      return Result.Failed(system, "catalog had no usable images")
    }

    // The staged catalog is usable — atomically replace the live dir with it. The delete + rename
    // is near-instant (same filesystem), so the window where `dir` is absent is microseconds, not
    // the multi-second fetch above. From here everything operates on the fresh `dir` as before.
    dir.deleteRecursively()
    if (!staging.renameTo(dir)) {
      // Cross-device or a racing reader held a handle — copy then drop the staging dir.
      staging.copyRecursively(dir, overwrite = true)
      staging.deleteRecursively()
    }

    // Optional in-browser Wasm tier: when the catalog declares a `compose-wasm` webRender, fetch
    // its
    // app files from the same branch into `<dir>/web/wasm/` and register that as the system's Wasm
    // dir. Best-effort — a fetch failure just leaves the catalog without the in-browser tier (the
    // PNG + data tiers still serve). The file list is enumerated by the trusted catalog, not the
    // client, and each file is path-contained + size-capped like the images.
    fetchWasmApp(catalog.webRender, base, dir, safe)

    val verdict =
      if (trust.trustsBranch(repo, branch))
        BundleVerifier.Verdict.Trusted(listOf(BundleVerifier.Basis.Branch(repo, branch)))
      else BundleVerifier.Verdict.Unverified("branch $repo@$branch is not trusted")

    // Fetch the catalog's baked editable vectors (figma/<slug>.svg + crops) so the host can serve
    // an SVG per preview; null when the branch carried none (host then 404s the .svg lane).
    val figmaDir = fetchFigmaSvgs(slugs, base, dir)

    // The static baked-PNG host — the browse surface (grid, deep links, thumbnails), keyed by the
    // catalog ids. This is ALWAYS what a viewer sees; a live builder below fronts it with a daemon
    // rather than replacing it, so the published /p/<id> links keep resolving. Built lazily so the
    // registry can rebuild it on each resume of a live session.
    val bakedFallback: () -> ServeBundleHost = {
      ServeBundleHost(
        dir,
        safe,
        verdict,
        title = catalog.title?.takeIf { it.isNotBlank() },
        subtitle =
          catalog.library.filter { it.isNotBlank() }.take(2).joinToString(" · ").ifBlank { null },
        figmaDir = figmaDir,
      )
    }

    // The catalog-id → daemon-preview-id bridge: a live daemon knows previews by their
    // function-based
    // descriptor id (`FilledButton_Dark`), but the published links/routes use the catalog id
    // (`button-filled__ideal__default__dark`). The exporter records each image's source daemon id
    // in
    // `previewId`; map it against the route-safe catalog id so a live host can answer the published
    // URLs (and unmapped ids — the Android-only variants — fall back to baked PNGs).
    val alias = previewAliasFor(catalog)

    // Trusted server-side re-render from a carried EXECUTABLE BUNDLE (opt-in,
    // --allow-render-trusted) — tried FIRST, ahead of the Gradle `source` build below: no clone, no
    // worktree, no per-request Gradle invocation. Only a Trusted catalog that declares `liveBundle`
    // is even offered to the builder — an Unverified catalog NEVER reaches it — and only once the
    // whole declared bundle file has fetched cleanly (fail-closed, like fetchWasmApp above). The
    // builder fronts [bakedFallback] with the daemon (see ServeCatalogLiveHost), so the baked
    // catalog still serves browsing + the ids the daemon can't render.
    val liveBundle = catalog.liveBundle
    if (verdict is BundleVerifier.Verdict.Trusted && liveBundle != null) {
      val bundleFile = fetchLiveBundle(liveBundle, base, dir, safe)
      if (bundleFile != null) {
        // Rehydrate any resources the bundle externalized (fonts lifted out of classes/app.jar)
        // from
        // the branch's content-addressed pool into a shared cache + a materialized classpath dir.
        // Fail-closed: a declared-but-unfetchable resource means the daemon would render with the
        // fonts missing (the exact ExceptionInInitializerError this feature exists to avoid), so we
        // skip the live bundle and fall through to the source/static path rather than serve a
        // broken
        // live tier.
        when (val res = rehydrateExternalResources(bundleFile, base, liveBundle.path, dir, safe)) {
          is ResRehydrate.Ready -> {
            // The per-preview live lane (default render path): each daemon-preview id maps to its
            // own FULL split bundle beside the monolithic one on the trusted branch. Fetched on
            // demand + pooled by the builder; shares the monolithic bundle's rehydrated font pool
            // ([res.dir]) since both were split from the same externalised bundle.
            //
            // Collision safety: `bundle split` writes colliding sanitised ids as `<base>.png`,
            // `<base>-2.png`, … so a daemon id whose sanitised stem is NOT unique among the alias
            // values would fetch a sibling's bundle under the bare `<stem>.png`. We can't recover
            // which suffix maps to which id without the publisher's ordering, so we only serve the
            // per-preview lane for ids with an unambiguous stem; a colliding id resolves null and
            // falls back to the monolithic daemon (which serves every preview correctly).
            val safeStems = uniquePerPreviewStems(alias.values)
            val fetchPerPreview: (String) -> File? = { daemonId ->
              safeStems[daemonId]?.let { stem ->
                fetchPerPreviewBundle(stem, liveBundle, base, dir, safe)
              }
            }
            if (
              buildTrustedBundle(safe, bundleFile, res.dir, alias, bakedFallback, fetchPerPreview)
            ) {
              return Result.Ok(safe, count, "${BundleVerifier.summary(verdict)} (live bundle)")
            }
          }
          ResRehydrate.Unavailable -> {} // fall through to source/static
        }
      }
    }

    // Trusted server-side re-render from SOURCE (opt-in): only a Trusted catalog that declares a
    // source is even offered to the builder — an Unverified catalog NEVER reaches it, so a
    // compromised/spoofed catalog can't trigger a build. Like the bundle path, the builder fronts
    // the baked host with the daemon rather than replacing it.
    val src = catalog.source
    if (
      verdict is BundleVerifier.Verdict.Trusted &&
        src != null &&
        src.module.isNotBlank() &&
        buildTrustedSource(safe, CatalogSource(src.repo, src.ref, src.module), alias, bakedFallback)
    ) {
      return Result.Ok(safe, count, "${BundleVerifier.summary(verdict)} (live)")
    }

    val host = bakedFallback()
    register(safe, host)
    return Result.Ok(safe, host.previews.size, BundleVerifier.summary(verdict))
  }

  /**
   * Fetch a `compose-wasm` [WebRender] app's files from [base] into `<dir>/web/wasm/` and register
   * the dir. The file list comes from the trusted [render] (not a client); each entry is confined
   * to the declared `path`, rejected on traversal, and size-capped by [fetch]. Needs at least an
   * `index.html` to be usable. No-op for a null / non-`compose-wasm` descriptor.
   */
  private fun fetchWasmApp(render: WebRender?, base: String, dir: File, system: String) {
    if (render == null || render.kind != WEB_RENDER_COMPOSE_WASM) return
    val prefix = render.path.trim('/')
    if (prefix.isEmpty() || render.files.isEmpty()) return
    val wasmDir = File(dir, WEB_WASM_DIR)
    // **Fail closed, all-or-nothing.** Register the app only if *every* declared file is fetched
    // and
    // an index.html is present — a partial app (a 404/timeout on composeApp.wasm or skiko.wasm, a
    // traversal/escaping entry, or a list longer than the cap) would make the viewer advertise "Run
    // in browser (Wasm)" only for the iframe to 404 its module/wasm fetches. The file list is the
    // trusted catalog's complete manifest, so any missing/invalid entry means "don't offer it".
    fun fail(reason: String) {
      wasmDir.deleteRecursively()
      System.err.println("serve: $system web/wasm/ incomplete ($reason) — in-browser tier disabled")
    }
    if (render.files.size > MAX_WASM_FILES) return fail("more than $MAX_WASM_FILES files declared")
    val wasmRoot = wasmDir.canonicalFile.toPath()
    for (name in render.files) {
      val rel = name.trim('/')
      if (rel.isEmpty() || ".." in rel.split("/")) return fail("invalid entry '$name'")
      val target = File(wasmDir, rel)
      if (!target.canonicalFile.toPath().startsWith(wasmRoot)) return fail("escaping entry '$name'")
      val bytes =
        runCatching { fetch("$base$prefix/$rel") }.getOrNull() ?: return fail("missing $rel")
      target.parentFile?.mkdirs()
      target.writeBytes(bytes)
    }
    if (!File(wasmDir, "index.html").isFile) return fail("no index.html")
    registerWasm(system, wasmDir)
  }

  /**
   * Fetch a catalog's `liveBundle` (`{path, file}`) — the executable preview bundle
   * (`<system>-bundle.png`) `design-artifacts.yml` carries alongside the baked PNGs — from
   * `<base><path>/<file>` into `<dir>/$LIVE_BUNDLE_DIR/<file>`. Fail-closed like [fetchWasmApp]: an
   * invalid/escaping file entry or a fetch miss aborts and returns null, so the caller falls back
   * to the Gradle `source` build (or, failing that, the static host). The file list is a single
   * entry from the trusted catalog itself, not client input.
   */
  private fun fetchLiveBundle(
    liveBundle: LiveBundle,
    base: String,
    dir: File,
    system: String,
  ): File? {
    val name = liveBundle.file.trim('/')
    if (name.isEmpty() || ".." in name.split("/")) {
      System.err.println("serve: $system liveBundle has an invalid file entry — skipping")
      return null
    }
    val bundleDir = File(dir, LIVE_BUNDLE_DIR)
    val bundleRoot = bundleDir.canonicalFile.toPath()
    val target = File(bundleDir, name)
    if (!target.canonicalFile.toPath().startsWith(bundleRoot)) {
      System.err.println("serve: $system liveBundle escaping entry '$name' — skipping")
      return null
    }
    val prefix = liveBundle.path.trim('/')
    val url = if (prefix.isEmpty()) "$base$name" else "$base$prefix/$name"
    val bytes = runCatching { fetch(url) }.getOrNull()
    if (bytes == null) {
      System.err.println("serve: $system liveBundle fetch failed ($url) — skipping")
      return null
    }
    target.parentFile?.mkdirs()
    target.writeBytes(bytes)
    return target
  }

  /**
   * The subset of [daemonIds] whose sanitised per-preview stem is **unambiguous** — mapped to that
   * stem. `bundle split` disambiguates colliding stems with `-2`/`-3`/… suffixes it derives from
   * the sheet's preview order, which the server can't reconstruct; so an id sharing a stem with
   * another is dropped here and the caller serves it from the monolithic daemon instead of fetching
   * a sibling's bundle under the bare `<stem>.png`. A blank stem (an id with no usable characters)
   * is dropped too.
   */
  private fun uniquePerPreviewStems(daemonIds: Collection<String>): Map<String, String> {
    val counts = HashMap<String, Int>()
    val stems = LinkedHashMap<String, String>()
    for (id in daemonIds) {
      val stem = sanitizePerPreviewName(id)
      if (stem.isEmpty()) continue
      stems[id] = stem
      counts[stem] = (counts[stem] ?: 0) + 1
    }
    return stems.filterValues { counts[it] == 1 }
  }

  /**
   * Fetch one preview's own **per-preview FULL bundle** (`<liveBundle.path>/previews/<stem>.png` on
   * the trusted branch) into `<dir>/$LIVE_BUNDLE_DIR/$PER_PREVIEW_DIR/<stem>.png` — the unit the
   * per-preview live lane materialises + pools. `design-artifacts.yml` splits the externalised
   * monolithic bundle into these (one re-renderable sticker per preview) beside it. Fail-closed
   * like [fetchLiveBundle]: an escaping [stem] or a fetch miss returns null and the caller simply
   * falls back to the monolithic daemon for that id (so a branch that ships no per-preview bundles
   * still serves live from the monolith). Cached on disk: a second request for the same stem
   * re-uses the already-fetched file rather than re-downloading.
   *
   * [stem] is the route-safe filename `bundle split` wrote the bundle under (a sanitised bundle
   * preview descriptor id), pre-resolved by [uniquePerPreviewStems] so it's unambiguous — the URL
   * stem matches the published filename exactly.
   */
  private fun fetchPerPreviewBundle(
    stem: String,
    liveBundle: LiveBundle,
    base: String,
    dir: File,
    system: String,
  ): File? {
    val previewsDir = File(File(dir, LIVE_BUNDLE_DIR), PER_PREVIEW_DIR)
    val previewsRoot = previewsDir.canonicalFile.toPath()
    val target = File(previewsDir, "$stem.png")
    if (!target.canonicalFile.toPath().startsWith(previewsRoot)) {
      System.err.println("serve: $system per-preview '$stem' escapes — skipping")
      return null
    }
    // Cached on disk from a prior request for the same id (the pool reopens lazily on eviction).
    if (target.isFile && target.length() > 0) return target
    val prefix = liveBundle.path.trim('/')
    val rel = "$PER_PREVIEW_DIR/$stem.png"
    val url = if (prefix.isEmpty()) "$base$rel" else "$base$prefix/$rel"
    val bytes = runCatching { fetch(url) }.getOrNull()
    if (bytes == null) {
      // Expected when the branch ships no per-preview bundle for this id (older catalog, view-only
      // tier); the caller falls back to the monolithic daemon. Quiet — not an error.
      return null
    }
    target.parentFile?.mkdirs()
    target.writeBytes(bytes)
    return target
  }

  /** Filesystem/route-safe stem for a per-preview id (mirrors `bundle split`'s sanitiser). */
  private fun sanitizePerPreviewName(id: String): String = buildString {
    for (c in id) append(if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_')
  }

  /**
   * Outcome of rehydrating a live bundle's externalized resources. See
   * [rehydrateExternalResources].
   */
  private sealed interface ResRehydrate {
    /**
     * Ready to serve: [dir] is the materialized classpath dir, or null when nothing was external.
     */
    data class Ready(val dir: File?) : ResRehydrate

    /**
     * A resource was declared but couldn't be fetched/verified — the live bundle must be skipped.
     */
    data object Unavailable : ResRehydrate
  }

  /**
   * Rehydrate the resources [bundleFile]'s manifest lifted out with `bundle externalize` (fonts,
   * recorded in `externalResources` by name+sha256+size). Each is fetched — once, shared across
   * systems + catalog reloads — into a content-addressed cache under `<root>/$RES_CACHE_DIR/<sha>`,
   * verified against its sha256, then materialized at its recorded classpath [path] under
   * `<dir>/$RES_MATERIALIZED_DIR/` so the daemon's classloader resolves `/fonts/…` exactly as it
   * did with the fonts inline. The pool lives beside the bundle on the trusted branch
   * (`<liveBundle.path>/$RES_POOL_DIR/<sha>`), enumerated by the trusted manifest (not client
   * input) and each write path-contained.
   *
   * Returns [ResRehydrate.Ready] with a null dir when the bundle externalized nothing
   * (self-contained — the caller passes no extra classpath), the materialized dir when it did, or
   * [ResRehydrate.Unavailable] (fail-closed) if any declared resource has a bad sha/path or can't
   * be fetched/verified — the caller then skips the live bundle rather than run the daemon with the
   * fonts missing.
   */
  private fun rehydrateExternalResources(
    bundleFile: File,
    base: String,
    bundlePathPrefix: String,
    dir: File,
    system: String,
  ): ResRehydrate {
    val resources =
      runCatching { BundleReader.readMetadata(bundleFile).manifest.externalResources }.getOrNull()
        ?: emptyList()
    if (resources.isEmpty()) return ResRehydrate.Ready(null)

    val cacheDir = File(root, RES_CACHE_DIR).apply { mkdirs() }
    val materialized = File(dir, RES_MATERIALIZED_DIR)
    val matRoot = materialized.canonicalFile.toPath()
    val prefix = bundlePathPrefix.trim('/')

    for (res in resources) {
      val sha = res.sha256
      if (sha.length != 64 || sha.any { it !in '0'..'9' && it !in 'a'..'f' }) {
        System.err.println(
          "serve: $system external resource '$sha' is not a sha256 — skipping live bundle"
        )
        return ResRehydrate.Unavailable
      }
      // Content-addressed cache: fetch once, reuse across systems + reloads. The cache key IS the
      // sha256, so a hit is only trusted after its bytes hash back to that key — a same-length but
      // corrupt entry (partial write, disk fault) must be refetched, not silently put on the
      // classpath. Verifying on read is cheap (fonts are small, reloads infrequent) and is the
      // whole
      // point of a content-addressed store.
      val cached = File(cacheDir, sha)
      val cacheValid =
        cached.isFile &&
          cached.length() == res.size &&
          runCatching { sha256Hex(cached.readBytes()) == sha }.getOrDefault(false)
      if (!cacheValid) {
        cached.delete()
        val url =
          if (prefix.isEmpty()) "$base$RES_POOL_DIR/$sha" else "$base$prefix/$RES_POOL_DIR/$sha"
        val bytes = runCatching { fetch(url) }.getOrNull()
        if (bytes == null) {
          System.err.println(
            "serve: $system external resource fetch failed ($url) — skipping live bundle"
          )
          return ResRehydrate.Unavailable
        }
        if (sha256Hex(bytes) != sha) {
          System.err.println(
            "serve: $system external resource sha256 mismatch ($url) — skipping live bundle"
          )
          return ResRehydrate.Unavailable
        }
        cached.parentFile?.mkdirs()
        cached.writeBytes(bytes)
      }
      // Materialize at the recorded classpath path (path-contained — reject traversal/absolute).
      if (res.path.isBlank() || res.path.startsWith("/") || ".." in res.path.split("/")) {
        System.err.println(
          "serve: $system external resource path '${res.path}' is invalid — skipping live bundle"
        )
        return ResRehydrate.Unavailable
      }
      val dest = File(materialized, res.path)
      if (!dest.canonicalFile.toPath().startsWith(matRoot)) {
        System.err.println(
          "serve: $system external resource path '${res.path}' escapes — skipping live bundle"
        )
        return ResRehydrate.Unavailable
      }
      dest.parentFile?.mkdirs()
      cached.copyTo(dest, overwrite = true)
    }
    return ResRehydrate.Ready(materialized)
  }

  /**
   * Fetch the catalog's baked `figma/<slug>.svg` exports (+ each hybrid SVG's external
   * `<slug>.figma-raster/<node>.png` crops) from [base] into `<dir>/figma/`, so the static host can
   * serve an editable vector per preview. Best-effort per slug (a missing SVG just means that
   * component carried none); each write is path-contained like the images. Returns the local
   * `figma/` dir when at least one SVG was written, else null.
   */
  private fun fetchFigmaSvgs(slugs: Set<String>, base: String, dir: File): File? {
    val figmaDir = File(dir, FIGMA_DIR)
    val figmaRoot = figmaDir.canonicalFile.toPath()
    var wrote = 0
    for (slug in slugs) {
      if (slug.isEmpty() || "/" in slug || ".." in slug) continue
      val svgBytes = runCatching { fetch("$base$FIGMA_DIR/$slug.svg") }.getOrNull() ?: continue
      val svgFile = File(figmaDir, "$slug.svg")
      if (!svgFile.canonicalFile.toPath().startsWith(figmaRoot)) continue
      svgFile.parentFile?.mkdirs()
      svgFile.writeBytes(svgBytes)
      wrote++
      // A hybrid SVG references external `figma-raster/<node>.png` crops; carry them so the host
      // can
      // inline them. Enumerate from the SVG itself (raw.githubusercontent has no directory
      // listing).
      for (href in figmaRasterHrefs(svgBytes.toString(Charsets.UTF_8))) {
        if (href.isEmpty() || ".." in href.split("/")) continue
        val cropFile = File(figmaDir, href)
        if (!cropFile.canonicalFile.toPath().startsWith(figmaRoot)) continue
        val cropBytes = runCatching { fetch("$base$FIGMA_DIR/$href") }.getOrNull() ?: continue
        cropFile.parentFile?.mkdirs()
        cropFile.writeBytes(cropBytes)
      }
    }
    return if (wrote > 0) figmaDir else null
  }

  /**
   * Minimal mirror of the `design-parity-catalog/v1` schema — only the bits we serve are needed.
   */
  @Serializable
  private data class Catalog(
    /** Human display title (e.g. "Compose Material 3"); surfaced on the public home index. */
    val title: String? = null,
    /** Underlying library coordinate(s); shown as the one-line descriptor on a system card. */
    val library: List<String> = emptyList(),
    val components: List<Component> = emptyList(),
    /** Optional in-browser render descriptor (the CMP-Wasm app carried in the branch). */
    val webRender: WebRender? = null,
    /** Optional buildable source for trusted server-side re-render (`--allow-render-trusted`). */
    val source: Source? = null,
    /**
     * Optional executable preview bundle carried alongside the baked PNGs (desktop-CMP systems only
     * — see `scripts/design-artifacts/generate-design-catalog.mjs`), preferred over [source] for
     * trusted server-side re-render: no Gradle build, no worktree.
     */
    val liveBundle: LiveBundle? = null,
  )

  /** `catalog.json`'s `liveBundle`: the executable bundle at `<path><file>` on this branch. */
  @Serializable private data class LiveBundle(val path: String = "", val file: String = "")

  /** `catalog.json`'s `source`: the repo/ref/module to build to re-render this catalog live. */
  @Serializable
  private data class Source(val repo: String = "", val ref: String = "", val module: String = "")

  @Serializable private data class Component(val images: List<Image> = emptyList())

  @Serializable
  private data class Image(
    val path: String,
    /**
     * The **daemon preview id** that produced this image (`FilledButton_Dark`), recorded by the
     * exporter so a live host can bridge the route-safe catalog id ([previewIdFor] of [path]) to
     * it. Null when the exporter couldn't map it (an older catalog, or an Android-only variant with
     * no runnable desktop preview) — then the id has no live lane and stays baked-PNG.
     */
    val previewId: String? = null,
  )

  /**
   * `catalog.json`'s `webRender`: an app under [path] (e.g. `web/wasm/`) with its [files] listed.
   */
  @Serializable
  private data class WebRender(
    val kind: String = "",
    val path: String = "",
    val files: List<String> = emptyList(),
  )

  companion object {
    const val DEFAULT_REPO = "yschimke/compose-ai-tools"
    const val DEFAULT_BRANCH_PREFIX = "design-artifacts/"
    const val CATALOG_FILE = "catalog.json"
    const val IMAGES_DIR = "images"
    const val FIGMA_DIR = "figma"
    /** A preview id folds the component slug + variant as `<slug>__<variant>`. */
    const val SLUG_SEPARATOR = "__"
    const val WEB_WASM_DIR = "web/wasm"
    const val WEB_RENDER_COMPOSE_WASM = "compose-wasm"
    private const val MAX_WASM_FILES = 64
    /** Local subdir a catalog's `liveBundle` file is fetched into (`<dir>/bundle/<file>`). */
    const val LIVE_BUNDLE_DIR = "bundle"

    /**
     * Branch- and local-relative subdir (under `liveBundle.path` / [LIVE_BUNDLE_DIR]) holding the
     * per-preview FULL split bundles `design-artifacts.yml` writes (`previews/<daemon-id>.png`),
     * one re-renderable sticker per preview. Fetched by [fetchPerPreviewBundle] for the per-preview
     * live lane.
     */
    const val PER_PREVIEW_DIR = "previews"

    /**
     * Branch-relative subdir (under the `liveBundle.path`) holding the bundle's externalized
     * resources, content-addressed by sha256 (`<liveBundle.path>/res/<sha>`). Written by `bundle
     * externalize`'s `--res-out` publish step; fetched by [rehydrateExternalResources].
     */
    const val RES_POOL_DIR = "res"

    /**
     * Shared, content-addressed on-disk cache for externalized resources, under the store root
     * (`<root>/.res-cache/<sha>`). Shared across systems + reloads so a font fetched for one
     * catalog is reused by the next.
     */
    const val RES_CACHE_DIR = ".res-cache"

    /**
     * Per-system subdir the rehydrated resources are materialized into at their classpath paths.
     */
    const val RES_MATERIALIZED_DIR = "bundle-res"

    /** Lowercase-hex SHA-256 of [bytes] — the content-address a rehydrated resource is keyed by. */
    private fun sha256Hex(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * The single-path-segment preview id for a catalog image path. The serve routes (`/p/{name}`,
     * `/render/{name}.png`, `/ws/{name}`) capture one segment, so a catalog image's subdirectory
     * `/` (e.g. `images/button-filled/ideal__default__dark.png`) must be flattened or the preview
     * is listed but can't be opened/rendered. We drop the `images/` prefix + `.png` suffix and
     * replace `/` with `__` (the same separator the variant keys already use), giving a stable,
     * route-safe id like `button-filled__ideal__default__dark`. The design-parity catalog exporter
     * derives the `livePreview` deep link the same way so the link resolves to this id.
     */
    fun previewIdFor(imagePath: String): String =
      imagePath.removePrefix("$IMAGES_DIR/").removeSuffix(".png").replace("/", "__")

    private const val DEFAULT_MAX_IMAGES = 1000
    private const val MAX_FETCH_BYTES = 25L * 1024 * 1024 // 25 MB per file

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient: OkHttpClient by lazy {
      OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    }

    /** Default fetcher: https only, capped + time-bounded. */
    private fun httpFetch(url: String): ByteArray? {
      httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) return null
        val body = response.body ?: return null
        return readCapped(body.byteStream(), MAX_FETCH_BYTES)
      }
    }

    private fun readCapped(input: InputStream, max: Long): ByteArray {
      val out = ByteArrayOutputStream()
      val buffer = ByteArray(64 * 1024)
      var total = 0L
      while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        require(total <= max) { "catalog file exceeds ${max / (1024 * 1024)}MB" }
        out.write(buffer, 0, n)
      }
      return out.toByteArray()
    }
  }
}
