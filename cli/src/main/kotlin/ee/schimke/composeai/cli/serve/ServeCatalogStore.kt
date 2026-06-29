package ee.schimke.composeai.cli.serve

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
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
) {

  sealed interface Result {
    data class Ok(val system: String, val previewCount: Int, val trust: String) : Result

    data class Failed(val system: String, val reason: String) : Result
  }

  /**
   * Fetch the `<branchPrefix><system>` catalog, lay its images out as previews, and register it.
   */
  fun load(system: String): Result {
    val safe = ServeBundleStore.sanitizeName(system) ?: return Result.Failed(system, "invalid name")
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

    val dir = File(root, safe)
    dir.deleteRecursively()
    val previewsDir = File(dir, "previews")
    val previewsRoot = previewsDir.canonicalFile.toPath()
    var count = 0
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
        count++
      }
    }
    if (count == 0) {
      dir.deleteRecursively()
      return Result.Failed(system, "catalog had no usable images")
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
    val host = ServeBundleHost(dir, safe, verdict)
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
    val wasmRoot = wasmDir.canonicalFile.toPath()
    var written = 0
    for (name in render.files.take(MAX_WASM_FILES)) {
      val rel = name.trim('/')
      if (rel.isEmpty() || ".." in rel.split("/")) continue
      val target = File(wasmDir, rel)
      if (!target.canonicalFile.toPath().startsWith(wasmRoot)) continue
      val bytes = runCatching { fetch("$base$prefix/$rel") }.getOrNull() ?: continue
      target.parentFile?.mkdirs()
      target.writeBytes(bytes)
      written++
    }
    if (written > 0 && File(wasmDir, "index.html").isFile) registerWasm(system, wasmDir)
  }

  /**
   * Minimal mirror of the `design-parity-catalog/v1` schema — only the bits we serve are needed.
   */
  @Serializable
  private data class Catalog(
    val components: List<Component> = emptyList(),
    /** Optional in-browser render descriptor (the CMP-Wasm app carried in the branch). */
    val webRender: WebRender? = null,
  )

  @Serializable private data class Component(val images: List<Image> = emptyList())

  @Serializable private data class Image(val path: String)

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
    const val WEB_WASM_DIR = "web/wasm"
    const val WEB_RENDER_COMPOSE_WASM = "compose-wasm"
    private const val MAX_WASM_FILES = 64

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
