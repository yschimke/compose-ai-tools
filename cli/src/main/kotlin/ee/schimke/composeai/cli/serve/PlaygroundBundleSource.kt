package ee.schimke.composeai.cli.serve

import java.io.File

/**
 * Where a playground mode's compile classpath comes from — the parsed form of `--playground-bundle`
 * / `--playground-android-bundle` (issue #3212).
 *
 * The flags originally resolved a **local filesystem path only**, which made enabling the
 * playground on a box that already serves the same catalog a manual step: the operator downloaded
 * that catalog's `liveBundle` by hand, dropped it on the config volume, and kept it there. Two
 * things fell out of that — it duplicated work `--catalogs` already does at startup (fetch, verify
 * as `Trusted(Branch)`, resolve a classpath), and the hand-placed copy went **silently stale** as
 * catalog auto-refresh re-pointed the live lane at a newer bundle while the playground kept
 * compiling against whatever ABI was current the day someone copied it.
 *
 * So a value is now read as one of:
 *
 * |Form                            |Means                                                                    |
 * |--------------------------------|-------------------------------------------------------------------------|
 * |`/config/x.bundle`, `./x.bundle`|[LocalPath] — the original behaviour, unchanged                          |
 * |`compose-m3`                    |[ServedCatalog] — the liveBundle of an already-served `--catalogs` system|
 *
 * The two are told apart structurally rather than by guessing: anything carrying a path separator,
 * naming an existing file, or ending in a bundle-ish suffix is a path; a bare token is a system id.
 * A catalog system id is a branch-name component (`design-artifacts/<system>`) and never contains a
 * separator, so the split is unambiguous in both directions.
 *
 * Resolution timing differs between the two, which is the whole reason this is a type rather than a
 * string: a [LocalPath] resolves at startup, but a [ServedCatalog]'s bundle does not exist yet when
 * the playground lane is wired — catalogs are fetched asynchronously *after* the server starts
 * (`InitialCatalogLoader`). A served-catalog mode is therefore **deferred**: declared available at
 * startup, resolved on first use. See [PlaygroundClasspathSupplier].
 */
sealed interface PlaygroundBundleSource {

  /** A `.bundle` file on the serve host's filesystem. Resolvable at startup. */
  data class LocalPath(val path: String) : PlaygroundBundleSource

  /**
   * A `--catalogs` system id, e.g. `compose-m3`. The playground reuses the liveBundle that catalog
   * already fetched and verified, so there is no second copy to place and no second trust decision
   * to make — it inherits the catalog's `Trusted(Branch)` verdict.
   */
  data class ServedCatalog(val system: String) : PlaygroundBundleSource

  /** How this source reads back in a startup log line. */
  fun describe(): String =
    when (this) {
      is LocalPath -> path
      is ServedCatalog -> "served catalog '$system'"
    }

  companion object {
    /**
     * Suffixes that mark a bare (separator-less) value as a file rather than a system id — a
     * catalog liveBundle is a packed `.png`, so `bundle.png` in the working directory must not be
     * read as a system called `bundle.png`.
     */
    private val FILE_SUFFIXES = listOf(".bundle", ".png", ".zip", ".jar")

    /**
     * Parse a flag value. Never fails: an unknown system id is not detectable here (the caller
     * knows the configured catalog set) and is reported by the caller instead, where the message
     * can list what *is* configured.
     */
    fun parse(raw: String, fileExists: (String) -> Boolean = { File(it).exists() }) =
      if (looksLikePath(raw, fileExists)) LocalPath(raw.trim()) else ServedCatalog(raw.trim())

    private fun looksLikePath(raw: String, fileExists: (String) -> Boolean): Boolean {
      val value = raw.trim()
      if (value.isEmpty()) return true
      if ('/' in value || '\\' in value) return true
      if (FILE_SUFFIXES.any { value.endsWith(it, ignoreCase = true) }) return true
      // A bare token that happens to name a file in the working directory is that file. Checked
      // last so the cheap structural tests decide the common cases.
      return fileExists(value)
    }
  }
}

/**
 * Resolves one playground mode's compile classpath, **once**, at the moment it is first needed.
 *
 * A [PlaygroundBundleSource.LocalPath] could be resolved eagerly, but a
 * [PlaygroundBundleSource.ServedCatalog] cannot: the playground lane is wired while the server is
 * being brought up, and the catalog whose liveBundle it names is fetched afterwards, in the
 * background. Deferring both keeps one code path and one set of log lines.
 *
 * Memoized on first **success**. A failed resolve is retried on the next request — during startup a
 * served catalog legitimately isn't there yet, and a permanent failure (a bad path, a catalog that
 * never loads) costs one cheap `locate` miss per request rather than a wrong permanent verdict.
 *
 * Deliberately **not** re-resolved when catalog auto-refresh moves a branch head underneath us: the
 * resolved classpath's jars are open in live snippet JVMs, so swapping them mid-flight needs
 * generation-scoped unpack dirs and a retirement policy. Issue #3212 splits that off explicitly —
 * this type removes the manual copy and the trust gap, and a long-running host still pins the ABI
 * it first resolved. Restart to pick up a new one.
 */
class PlaygroundClasspathSupplier(
  private val source: PlaygroundBundleSource,
  /** Finds a served catalog's already-fetched liveBundle file; null until that catalog loads. */
  private val locateServedBundle: (String) -> File?,
  /** Turns a bundle file into a compile classpath; null (having logged) when it can't. */
  private val resolve: (File) -> PlaygroundCompileService.Classpath?,
  private val onLog: (String) -> Unit = {},
) {

  @Volatile private var resolved: PlaygroundCompileService.Classpath? = null

  /** True once [classpath] has produced a real classpath — for status/logging, never for gating. */
  val isResolved: Boolean
    get() = resolved != null

  /** How this mode's bundle was named, for `/status.json` and startup logs. */
  fun describeSource(): String = source.describe()

  /**
   * The served catalog this supplier was classified as at startup, or null for a local bundle.
   *
   * Classification is intentionally exposed from the supplier rather than recomputed from the raw
   * flag: a separator-less value consults the filesystem when it is parsed, and that filesystem can
   * change during a long-running serve process. Consumers must keep using the identity that the
   * classpath supplier itself resolved.
   */
  val servedCatalogSystem: String?
    get() = (source as? PlaygroundBundleSource.ServedCatalog)?.system

  fun classpath(): PlaygroundCompileService.Classpath? {
    resolved?.let {
      return it
    }
    synchronized(this) {
      resolved?.let {
        return it
      }
      val file = bundleFile() ?: return null
      if (!file.isFile) {
        onLog("${file.absolutePath} is not a readable bundle file")
        return null
      }
      return resolve(file)?.also { resolved = it }
    }
  }

  /** The bundle bytes this source points at, or null (having logged) when they aren't there yet. */
  private fun bundleFile(): File? =
    when (source) {
      is PlaygroundBundleSource.LocalPath -> File(source.path)
      is PlaygroundBundleSource.ServedCatalog ->
        locateServedBundle(source.system).also {
          if (it == null) {
            onLog(
              "catalog '${source.system}' has not published a live bundle yet — the mode stays " +
                "unavailable until it loads (or it carries none)"
            )
          }
        }
    }
}
