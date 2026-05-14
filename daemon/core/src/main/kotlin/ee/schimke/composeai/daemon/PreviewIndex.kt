package ee.schimke.composeai.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Daemon-side parse target for the gradle plugin's `previews.json`.
 *
 * **Layer-2-only DTO.** [LAYERING.md](../../../../../../../docs/daemon/LAYERING.md) forbids
 * `:daemon:core` from depending on `:gradle-plugin`. The plugin owns the authoritative
 * [PreviewInfo] type (`gradle-plugin/.../PreviewData.kt`) and writes it to disk via
 * kotlinx-serialization; the daemon parses the same JSON shape with this minimal mirror, capturing
 * only the fields the daemon actually needs. Extra fields the plugin emits (captures list,
 * accessibility report pointer, …) are ignored at parse time via `ignoreUnknownKeys`, so adding new
 * plugin-side fields does NOT break the daemon's parser.
 *
 * **Why duplicate instead of share.** Sharing the type would either pull `:gradle-plugin` onto the
 * daemon's classpath (heavy, and a layering inversion) or carve a third "shared protocol" module
 * out of the plugin. Phase 1 deliberately picks duplication: ~30 LOC of mirror keeps the layering
 * invariant and the daemon's parse surface scoped to fields it consumes today.
 *
 * Field naming follows the wire JSON, NOT the plugin's internal field names. The plugin emits
 * `functionName` (the `@Preview`-annotated function), so we read `functionName` here.
 *
 * **B2.2 phase 2** added [displayName] and [group] so the diff path can detect "ID present on both
 * sides but a tracked field changed" (a renamed preview, a `group =` rewrite). Both fields are
 * optional in `previews.json` and absent in older fixtures; the diff treats `null == null` as
 * unchanged.
 *
 * **Issue #420** added the nested [params] block so the v2 interactive resolver can build a
 * [RenderSpec][ee.schimke.composeai.daemon.RenderSpec]-shaped scene that matches `@Preview(widthDp
 * = …, heightDp = …, density = …, …)` exactly. All sub-fields are optional and default to `null` so
 * older `previews.json` fixtures (and the harness's flat fake schema) still parse — the resolver
 * falls back to its built-in `320x320 / density 2.0` defaults when a field is absent. Additive per
 * [PROTOCOL.md § 7](../../../../../../../docs/daemon/PROTOCOL.md#7-versioning) — no
 * `protocolVersion` bump.
 */
@Serializable
data class PreviewInfoDto(
  val id: String,
  /** Fully-qualified class containing the `@Preview` function. */
  val className: String,
  /** Method name of the `@Preview` function. The plugin's JSON key is `functionName`. */
  @SerialName("functionName") val methodName: String,
  /**
   * Source file path captured by the discovery task (`ClassInfo.sourceFile`). Optional — older
   * `previews.json` files predate B2.0 and don't include it.
   */
  val sourceFile: String? = null,
  /**
   * Display name surfaced to the client (typically the `name = "…"` argument on `@Preview`).
   * Optional — phase 1's parse predates this field, so older fixtures emit `null`. Tracked by
   * [diff] for "changed" detection.
   */
  val displayName: String? = null,
  /**
   * Preview group (the `group = "…"` argument on `@Preview`). Tracked by [diff] for "changed"
   * detection.
   */
  val group: String? = null,
  /**
   * Display-property block sourced from the gradle plugin's `PreviewParams`. Optional — fixtures
   * predating issue #420 omit it; the v2 interactive resolver falls back to its built-in defaults
   * for every absent sub-field (per-field, not per-block). The block is also tracked by [diff], so
   * a `widthDp =` edit on an existing `@Preview` shows up as a `changed` entry on the next
   * incremental rescan.
   */
  val params: PreviewParamsDto? = null,
)

/**
 * Per-`@Preview` display properties carried over the wire by the gradle plugin's `PreviewParams`
 * (see `gradle-plugin/.../PreviewData.kt`). All fields are optional / nullable so `previews.json`
 * fixtures predating issue #420 — and the harness's flat fake schema, which omits the `params`
 * block entirely — still parse cleanly.
 *
 * Names mirror the plugin's `PreviewParams` JSON keys verbatim. The daemon side maps subsets of
 * these onto `RenderSpec` (`widthDp`/`heightDp`/`density` → pixel dimensions; `localeTag` from
 * `locale`; `uiMode` bitmask → `SpecUiMode`; `fontScale` straight through). Backends that don't
 * model a particular knob (for example, desktop has no display rotation concept for `orientation`)
 * ignore the field but still carry it for wire parity with `PreviewOverrides` — see
 * [PROTOCOL.md § 5](../../../../../../../docs/daemon/PROTOCOL.md#5-client--daemon-requests).
 *
 * `uiMode` here is the raw Android `Configuration.uiMode` bitmask the plugin reads off the
 * `@Preview` annotation (`0` means unset; bit `0x20` = `UI_MODE_NIGHT_YES`). The daemon decodes it
 * via [uiModeIsNight] when constructing a `RenderSpec`; `orientation` is the same string the
 * `PreviewOverrides` wire enum uses (`"portrait"` / `"landscape"`), but the plugin doesn't emit it
 * today — present here so a future plugin-side addition lands without another DTO bump.
 */
@Serializable
data class PreviewParamsDto(
  val widthDp: Int? = null,
  val heightDp: Int? = null,
  val density: Float? = null,
  val fontScale: Float? = null,
  /** BCP-47 locale tag — the plugin's `PreviewParams.locale`. */
  val locale: String? = null,
  /**
   * Raw `@Preview(uiMode = …)` bitmask. `null` and `0` are interchangeable on the daemon side (both
   * mean "unset, fall back to renderer default"). Decoded via [uiModeIsNight].
   */
  val uiMode: Int? = null,
  /**
   * Raw `@Preview(device = …)` string when set. The desktop interactive resolver uses this only
   * when no explicit `widthDp`/`heightDp` came through; the device catalog lookup happens at
   * resolve time, not at parse time, so unknown device ids degrade to the catalog's default
   * (400x800 dp at xxhdpi).
   */
  val device: String? = null,
  /**
   * `@Preview(showBackground = …)`. Threaded into `RenderSpec.showBackground` so a preview that
   * opted in continues to paint a white background under the interactive scene.
   */
  val showBackground: Boolean? = null,
  /**
   * `@Preview(backgroundColor = …)`. Same plumbing as [showBackground]; encoded as Long because the
   * plugin's annotation reader hands back the raw `0xAARRGGBB` value.
   */
  val backgroundColor: Long? = null,
)

/**
 * `true` when [uiMode] decodes to night/dark via Android's `UI_MODE_NIGHT_YES` bit (0x20). `null`
 * (and `0`) decode to `false` — the daemon treats both as "unset". Pulled into a free function so
 * the desktop daemon's `previewIndexBackedSpecResolver` and the daemon-android side can share the
 * decode without re-importing Android's `Configuration` constants on the desktop classpath.
 */
fun uiModeIsNight(uiMode: Int?): Boolean {
  if (uiMode == null) return false
  return (uiMode and UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES
}

private const val UI_MODE_NIGHT_MASK: Int = 0x30

private const val UI_MODE_NIGHT_YES: Int = 0x20

/**
 * Wire-shape of `previews.json`'s top-level object — only the fields the index needs. The plugin
 * also writes `module`, `variant`, `dataExtensionReports`, and the legacy `accessibilityReport`
 * mirror; all of those are ignored on parse here (the daemon routes per-extension reports through
 * typed data-product payloads rather than the manifest pointer).
 */
@Serializable
private data class PreviewManifestDto(val previews: List<PreviewInfoDto> = emptyList())

/**
 * Diff produced by [PreviewIndex.diff] — what changed between the cached index and a fresh scan
 * scoped to one source file. Mirrors the wire shape of `discoveryUpdated` ([PROTOCOL.md §
 * 6](../../../../../../../docs/daemon/PROTOCOL.md)).
 */
data class DiscoveryDiff(
  /** Previews present in the new scan but not in the cached index. */
  val added: List<PreviewInfoDto>,
  /**
   * Ids present in the cached index whose `sourceFile` matches the saved path AND that are absent
   * from the new scan (= deleted from this file).
   */
  val removed: List<String>,
  /** Ids present in both sides but with at least one tracked field different. */
  val changed: List<PreviewInfoDto>,
  /** Total preview count after the diff is applied to the index. */
  val totalPreviews: Int,
)

/** True when the diff has no `added`, `removed`, or `changed` entries. */
fun discoveryDiffEmpty(diff: DiscoveryDiff): Boolean =
  diff.added.isEmpty() && diff.removed.isEmpty() && diff.changed.isEmpty()

/**
 * In-memory preview index owned by the daemon.
 *
 * **B2.2 phase 1.** The daemon parses `previews.json` once at startup and exposes the resulting map
 * for `initialize.manifest.{path, previewCount}`.
 *
 * **B2.2 phase 2.** The index is now mutable — [diff] computes the delta against a freshly-scanned
 * `Set<PreviewInfoDto>` for one source file, and [applyDiff] merges that delta in-place. Reads use
 * a [ReentrantReadWriteLock] so concurrent renders observing the index can never see a torn map.
 *
 * **Degraded mode.** [loadFromFile] never throws on a malformed or missing input. It returns
 * [empty] and writes a single warn-level diagnostic to stderr (free-form log per
 * [PROTOCOL.md § 1](../../../../../../../docs/daemon/PROTOCOL.md)). The daemon should still come up
 * on a corrupt manifest; clients see `previewCount = 0` and can re-trigger discovery.
 */
class PreviewIndex
internal constructor(
  /**
   * Absolute path to the file the index was loaded from. `null` when the index is the empty
   * placeholder — i.e. no `composeai.daemon.previewsJsonPath` sysprop was set, or the file didn't
   * exist / was malformed.
   */
  val path: Path?,
  initial: Map<String, PreviewInfoDto>,
) {

  private val lock = ReentrantReadWriteLock()
  private val byId: MutableMap<String, PreviewInfoDto> = LinkedHashMap(initial)

  /** Total number of previews known to the daemon. */
  val size: Int
    get() = lock.read { byId.size }

  /** Lookup by `PreviewInfo.id`. `null` if the id is unknown. */
  fun byId(id: String): PreviewInfoDto? = lock.read { byId[id] }

  /** All known preview ids. Phase 2 will diff a fresh scan against this set. */
  fun ids(): Set<String> = lock.read { byId.keys.toSet() }

  /**
   * Snapshot of the current `id → PreviewInfoDto` map. Not live; safe to iterate without locking.
   */
  fun snapshot(): Map<String, PreviewInfoDto> = lock.read { LinkedHashMap(byId) }

  /**
   * Computes a [DiscoveryDiff] for one source file.
   *
   * - `added` = previews in [newScanForFile] whose id is NOT currently in the index.
   * - `removed` = ids in the current index whose `sourceFile == sourceFile.toString()` AND that are
   *   absent from [newScanForFile].
   * - `changed` = ids present in both, but whose [PreviewInfoDto] differs by `==`.
   * - `totalPreviews` = the index's size AFTER applying the diff (so callers can emit it on the
   *   wire without a second lookup).
   *
   * Pure — does NOT mutate the index. Call [applyDiff] separately to commit.
   */
  fun diff(newScanForFile: Set<PreviewInfoDto>, sourceFile: Path): DiscoveryDiff {
    val sourceKey = sourceFile.toString()
    return lock.read {
      val newById = newScanForFile.associateBy { it.id }
      val added = newScanForFile.filter { it.id !in byId }
      val changed = newScanForFile.filter { fresh -> byId[fresh.id]?.let { it != fresh } == true }
      // Removed scopes to ids whose previous DTO claimed `sourceFile` matches the saved path.
      // Without this scoping, a scan returning 0 previews for one file would mis-remove every
      // preview in the index (including those owned by sibling files).
      val removed =
        byId.entries
          .filter { (_, dto) -> dto.sourceFile == sourceKey && dto.id !in newById }
          .map { it.key }
      // Compute the post-update size: start with current, drop removed, add additions.
      // Changed entries don't move the count.
      val newTotal = byId.size - removed.size + added.size
      DiscoveryDiff(added = added, removed = removed, changed = changed, totalPreviews = newTotal)
    }
  }

  /**
   * Applies [diff] in-place. Scoped by [sourceFile] — `removed` ids leave the map; `added` and
   * `changed` DTOs replace any prior entry by id. Idempotent if invoked twice with the same diff
   * (the second call is a no-op against the now-current state).
   *
   * Holds the write lock for the duration of the merge.
   */
  fun applyDiff(diff: DiscoveryDiff) {
    lock.write {
      for (id in diff.removed) byId.remove(id)
      for (dto in diff.added) byId[dto.id] = dto
      for (dto in diff.changed) byId[dto.id] = dto
    }
  }

  companion object {
    /**
     * The empty placeholder. Used when no `composeai.daemon.previewsJsonPath` was supplied — e.g.
     * fake-mode harness scenarios, the in-process integration tests, the pre-B2.2 default. `path =
     * null`, `size = 0`.
     */
    fun empty(): PreviewIndex = PreviewIndex(path = null, initial = emptyMap())

    /**
     * Constructs an index from an in-memory map. Used by the harness's `FakeDaemonMain` to seed a
     * daemon-side index from its own fixture manifest without round-tripping a JSON file. [path]
     * may be null (harness path) or absolute (production / desktop daemon path).
     */
    fun fromMap(path: Path?, byId: Map<String, PreviewInfoDto>): PreviewIndex =
      PreviewIndex(path = path?.toAbsolutePath(), initial = byId)

    /**
     * Parses [path] as a plugin-emitted `previews.json` and returns an index over its `previews`
     * array. Returns [empty] (and prints a warn-level diagnostic to stderr) if the file is missing,
     * unreadable, or malformed; never throws.
     */
    fun loadFromFile(path: Path): PreviewIndex {
      val absolute = path.toAbsolutePath()
      if (!Files.exists(absolute)) {
        System.err.println(
          "compose-ai-daemon: PreviewIndex.loadFromFile($absolute): file does not exist; " +
            "starting with empty index"
        )
        return empty()
      }
      val text =
        try {
          Files.readString(absolute)
        } catch (t: Throwable) {
          System.err.println(
            "compose-ai-daemon: PreviewIndex.loadFromFile($absolute): read failed " +
              "(${t.javaClass.simpleName}: ${t.message}); starting with empty index"
          )
          return empty()
        }
      val manifest =
        try {
          JSON.decodeFromString(PreviewManifestDto.serializer(), text)
        } catch (t: Throwable) {
          System.err.println(
            "compose-ai-daemon: PreviewIndex.loadFromFile($absolute): parse failed " +
              "(${t.javaClass.simpleName}: ${t.message}); starting with empty index"
          )
          return empty()
        }
      val byId = LinkedHashMap<String, PreviewInfoDto>(manifest.previews.size)
      for (preview in manifest.previews) {
        byId[preview.id] = preview
      }
      return PreviewIndex(path = absolute, initial = byId)
    }

    /**
     * System property the per-target [DaemonMain] reads to locate `previews.json`. The gradle
     * plugin emits this as part of `composePreviewDaemonStart`'s descriptor (see
     * [DaemonClasspathDescriptor.systemProperties]); when unset, the daemon comes up with [empty] —
     * preserves pre-B2.2 in-process / fake-mode behaviour.
     */
    const val PREVIEWS_JSON_PATH_PROP: String = "composeai.daemon.previewsJsonPath"

    private val JSON: Json = Json {
      ignoreUnknownKeys = true
      // Plugin-side `PreviewParams.fontScale = 1.0f` etc. are encoded with default values; we
      // don't decode them, but staying lenient about defaults keeps the parse path forgiving.
      isLenient = false
    }
  }
}
