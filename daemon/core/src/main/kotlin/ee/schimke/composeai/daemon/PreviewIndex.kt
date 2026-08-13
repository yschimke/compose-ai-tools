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
   * for every absent sub-field (per-field, not per-block).
   *
   * The incremental source-change scan ([IncrementalDiscovery.toDto]) rebuilds a preview's DTO from
   * the class file alone and cannot recover this block, so it emits `params == null`. Two places
   * cooperate so this doesn't drop a preview's known size on every edit (which would flip the
   * desktop interactive render between wrap-content and the fixed 320² frame): [diff] normalizes a
   * null-params rescan to the prior's params before deciding `changed`, so a params-only rescan
   * isn't reported at all; and when a *real* field change happens to also carry null params,
   * [applyDiff] carries the prior `params` forward. A genuine `@Preview(widthDp = …)` edit is
   * therefore reflected only on the next FULL rediscovery (which re-parses `previews.json`), not on
   * the incremental class-file rescan.
   */
  val params: PreviewParamsDto? = null,
  /**
   * Annotation-sourced data products this preview exposes (e.g. `render/scroll/long` from
   * `@ScrollingPreview(modes = [LONG])`). Optional — fixtures predating issue #1528 omit the field
   * entirely, and the daemon only reads the subset it needs (currently the `scroll` sub-shape, used
   * by the daemon's scroll-scenario dispatch to look up axis / maxScrollPx / frameIntervalMs for a
   * given `(previewId, render/scroll/long|gif)` pair).
   */
  val dataProducts: List<PreviewDataProductDto> = emptyList(),
  /**
   * The plugin's per-capture plan for this preview. Only the `scroll` sub-shape is mirrored, and
   * only for the modes that produce an ordinary PNG rather than a data product: `@ScrollingPreview`
   * maps `TOP`/`END` to **captures** and `LONG`/`GIF` to [dataProducts], so an `END` sticker's
   * "scroll before you shoot" intent lives here and nowhere else (see [staticScrollFor]).
   *
   * Optional and additive — fixtures predating this field, and the incremental class-file rescan
   * ([IncrementalDiscovery.toDto], which can't recover it), leave it empty. [diff] and [applyDiff]
   * normalize that the same way they do [params], so a rescan doesn't drop a preview's scroll
   * intent or report it as a change.
   */
  val captures: List<PreviewCaptureDto> = emptyList(),
  /**
   * `@OverrideVariant` seed for a synthetic variant preview (same `functionName` as its base, a
   * `_VARIANT_<name>` id). Parsed straight into the canonical
   * [ee.schimke.composeai.data.overrides.OverrideVariantSpec] — the wire shape the plugin emits —
   * so the interactive resolver can seed it as the base override layer (`RenderSpec.overrides`)
   * under any live per-render override. Optional; `null` on an ordinary preview. Not a plugin
   * dependency: the type lives in the shared `:data-preview-overrides-core` this module already
   * depends on for `PreviewOverrideValue`.
   */
  val overrides: ee.schimke.composeai.data.overrides.OverrideVariantSpec? = null,
)

/**
 * Daemon-side mirror of the gradle plugin's `PreviewDataProduct`
 * (`:gradle-plugin:preview-discovery`'s `PreviewData.kt`). Only carries the fields the daemon needs
 * — `kind` so the daemon can match `render/scroll/long` / `render/scroll/gif`, and `scroll` so the
 * renderer can replay the annotation's axis / maxScrollPx / frameIntervalMs intent. Other
 * plugin-side fields (`extensionId`, `usageMode`, `displayName`, `output`, `cost`, …) are
 * deliberately ignored on parse — the daemon routes the on-disk path via
 * [ScrollDataProductRegistry.fileFor] off the kind alone, so the plugin-supplied `output` would be
 * redundant and brittle (path layout changes would have to land in both places). [Json] is
 * configured with `ignoreUnknownKeys = true` so adding plugin-side fields doesn't require a
 * daemon-side change.
 */
@Serializable
data class PreviewDataProductDto(val kind: String, val scroll: ScrollCaptureDto? = null)

/**
 * Daemon-side mirror of the gradle plugin's `Capture` — one planned render of a preview. Only
 * [scroll] is carried; every other field on the plugin's type (`animation`, `focus`, `tourStep`,
 * `renderOutput`, `cost`, …) drives outputs the daemon's one-frame-per-id surface doesn't produce,
 * and `ignoreUnknownKeys` drops them on parse.
 */
@Serializable data class PreviewCaptureDto(val scroll: ScrollCaptureDto? = null)

/**
 * Daemon-side mirror of the gradle plugin's `ScrollCapture`. Carries only the intent fields the
 * renderer needs to drive the scrolling — outcome fields (`atEnd`, `reachedPx`) the plugin records
 * post-render are skipped because the daemon writes its own fresh artefact rather than reading the
 * plugin's recorded state.
 */
@Serializable
data class ScrollCaptureDto(
  /**
   * Mirrors the gradle plugin's `ScrollMode` (`END` / `LONG` / `GIF`). String-typed so the daemon
   * doesn't depend on the plugin's enum.
   */
  val mode: String,
  /**
   * Mirrors `ScrollAxis` (`VERTICAL` / `HORIZONTAL`). String-typed for the same reason as [mode].
   * Defaults to `VERTICAL` matching the annotation default.
   */
  val axis: String = "VERTICAL",
  /**
   * Annotation-set cap (`@ScrollingPreview(maxScrollPx = N)`). `0` means "no cap — exhaust the
   * scrollable".
   */
  val maxScrollPx: Int = 0,
  /**
   * `@ScrollingPreview(reduceMotion = …)`. Carried for wire parity; the renderer reads it via the
   * same field after population from this DTO.
   */
  val reduceMotion: Boolean = true,
  /**
   * `@ScrollingPreview(frameIntervalMs = N)` for `GIF` mode. `0` means "renderer default
   * (`ScrollGifEncoder.DEFAULT_FRAME_DELAY_MS`)".
   */
  val frameIntervalMs: Int = 0,
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
  /**
   * Bound a **wrapped** axis is measured against, replacing
   * [PreviewManifestEntry.WRAP_SANDBOX_WIDTH_DP] / `WRAP_SANDBOX_HEIGHT_DP`. Mirrors the plugin's
   * `PreviewParams.wrapSandboxWidthDp`: unlike [widthDp] it does NOT pin the axis, so
   * `renderSpecFromInfo` still reports `wrapWidth = true` and the capture still crops to measured
   * size.
   *
   * Must be declared here, not just on the manifest router's own DTO: `bundle daemon` and
   * `compose-preview serve` launch with only `composeai.daemon.previewsJsonPath`, so they resolve
   * through [PreviewIndex] rather than the router. Without these fields `ignoreUnknownKeys` drops
   * them and a Wear `fillMaxWidth` sticker measures against 400 dp live while its baked render used
   * 227 dp — the exact PNG↔Live divergence the wrap plumbing exists to prevent.
   */
  val wrapSandboxWidthDp: Int? = null,
  /** See [wrapSandboxWidthDp]. */
  val wrapSandboxHeightDp: Int? = null,
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
  /**
   * Preview flavour — mirrors `ee.schimke.composeai.plugin.PreviewKind` (`"COMPOSE"` / `"TILE"` /
   * `"NOTIFICATION"` / `"GLANCE_APPWIDGET"`). String-typed so the daemon's renderer-agnostic
   * surface doesn't depend on the plugin's enum; `null` and `"COMPOSE"` both mean the default
   * Compose path. The non-default values route through dedicated render strategies on the Android
   * backend (top-level Tile / Notification functions are not `@Composable`; Glance previews ARE
   * `@Composable` but must be hosted inside a `GlanceAppWidget.providePreview(...)` →
   * `composeForPreview(...)` → `RemoteViews.apply` pipeline, see issue #1440).
   */
  val kind: String? = null,
  /**
   * For `kind="LOTTIE"`: the module-resource-relative path of the discovered Lottie asset, mirrored
   * from the plugin's `PreviewParams.assetPath`. The daemon loads it off the render classpath.
   */
  val assetPath: String? = null,
  /**
   * FQN of the `PreviewWrapperProvider` from `@PreviewWrapper(SomeProvider::class)` when the source
   * preview is annotated. Read at discovery time by `extractWrapperFqn` against the class-file
   * annotation tables (the upstream annotation has `AnnotationRetention.BINARY`, so
   * `Method.annotations` is empty for it at runtime — see issue #1440). Threaded into
   * `RenderSpec.wrapperClassName` for the render body.
   */
  val wrapperClassName: String? = null,
  /**
   * FQN of the `PreviewParameterProvider` from `@PreviewParameter(SomeProvider::class)`, plus its
   * `limit`, when the source preview declares one. Same discovery-time class-file read as
   * [wrapperClassName] (this annotation is `AnnotationRetention.BINARY` too, so runtime reflection
   * can't see it). The render body resolves the preview's parameterized overload from it and
   * invokes it with the provider's first value; without it a parameterized preview fails resolution
   * with `NoSuchMethodException` and produces no render (issue #3027).
   */
  val previewParameterProviderClassName: String? = null,
  val previewParameterLimit: Int? = null,
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
 * also writes `module`, `variant`, and `dataExtensionReports`; all of those are ignored on parse
 * here (the daemon routes per-extension reports through typed data-product payloads rather than the
 * manifest pointer).
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
  /** The discovered preview [id] names, or `null`. Exact match only — see [rowResolved]. */
  fun byId(id: String): PreviewInfoDto? = lock.read { byId[id] }

  /**
   * [byId], widened to accept a **row-addressed** id (`<baseId>_Dark` / `<baseId>_PARAM_4`,
   * issue #3749): the entry it resolves to, plus the row token it named.
   *
   * The index holds base ids only, because discovery can't enumerate a `PreviewParameterProvider`
   * (see [PreviewRowAddress]). A row shares all of its *metadata* with its base — display name,
   * group, source file, class/method — so a caller that wants metadata reads [Resolved.info] and
   * gets a useful answer for a row id where plain [byId] returns null; without it a row render
   * lands in history with no metadata and `recording/generateTest` emits a test with no function
   * name.
   *
   * [Resolved.row] is the other half, and callers that *build a render* must honour it: resolving a
   * row id to its base entry and dropping the token would compose value 0 under the row's id —
   * silently the wrong state, which is worse than the "unknown previewId" a caller used to get.
   * That is why the row rides out here rather than being folded invisibly into [byId].
   */
  fun rowResolved(id: String): Resolved? = lock.read {
    byId[id]?.let {
      return@read Resolved(it, null)
    }
    val split =
      PreviewRowAddress.split(id) { base ->
        byId[base]?.params?.previewParameterProviderClassName?.isNotBlank() == true
      } ?: return@read null
    byId[split.baseId]?.let { Resolved(it, split.row) }
  }

  /** A previewId resolved by [rowResolved]: the entry, and the `@PreviewParameter` row it named. */
  data class Resolved(val info: PreviewInfoDto, val row: String?)

  /**
   * Issue #1528 — resolves the [ScrollCaptureDto] for a given `(previewId, renderMode)` pair so the
   * daemon's `RenderEngine` can drive the scrolling scenario the plugin annotated. `mode` is the
   * daemon's render-mode tag (`scroll-long` / `scroll-gif`) — the matching kind is
   * `render/scroll/long` / `render/scroll/gif`. Returns `null` when no preview matches or the
   * preview has no `dataProducts` entry of that kind (typical for previews without
   * `@ScrollingPreview`).
   */
  fun scrollCaptureFor(previewId: String, mode: String): ScrollCaptureDto? {
    val kind =
      when (mode) {
        "scroll-long" -> "render/scroll/long"
        "scroll-gif" -> "render/scroll/gif"
        else -> return null
      }
    val info = byId(previewId) ?: return null
    return info.dataProducts.firstOrNull { it.kind == kind }?.scroll
  }

  /**
   * The scroll drive an **ordinary static render** of [previewId] has to perform before it shoots,
   * or `null` when the preview asks for none.
   *
   * The sibling of [scrollCaptureFor] for the half of `@ScrollingPreview` that produces a plain PNG
   * instead of a data product. `END` means "drive the first scrollable on the annotated axis to its
   * content end, then capture" — for a Wear `ScreenScaffold` that is the difference between the
   * resting top, where the `EdgeButton` is still collapsed, and the settled bottom where it is
   * revealed. `TOP` is the unscrolled initial frame, i.e. exactly what a render without any drive
   * already produces, so it resolves to `null` rather than a no-op drive.
   *
   * Resolved only when the preview's captures are **unanimous**: every capture that carries a
   * scroll block asks for `END`. The capture grid is the cross-product of the scroll, time and
   * focus fan-outs, so a single `@ScrollingPreview(END)` paired with a two-timing
   * `@RoboComposePreviewOptions` plans two captures that both carry the same `END` — unambiguous,
   * even though there is more than one. `@ScrollingPreview(modes = [TOP, END])` is the case this
   * guards against: it plans `…_SCROLL_top.png` *and* `…_SCROLL_end.png` under one preview id, the
   * daemon's surface is one frame per id, and there is no honest answer to "which one" without a
   * per-capture id — so those previews keep the undriven frame they render today rather than having
   * the daemon silently pick a side.
   *
   * Captures with no scroll at all don't vote. A `@ScrollingPreview(END)` function that also
   * carries `@AnimatedPreview` plans a scroll-less GIF capture beside the END one, but that GIF is
   * a separate output rather than a rival static frame.
   */
  fun staticScrollFor(previewId: String): ScrollCaptureDto? {
    val info = byId(previewId) ?: return null
    val scrolls = info.captures.mapNotNull { it.scroll }
    if (scrolls.isEmpty()) return null
    if (!scrolls.all { it.mode.equals("END", ignoreCase = true) }) return null
    return scrolls.first()
  }

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
      val changed = newScanForFile.filter { fresh ->
        val prior = byId[fresh.id] ?: return@filter false
        // The incremental source-change scan ([IncrementalDiscovery.toDto]) can't recover the
        // `params` block, so it always returns `params == null`. On its own that must NOT read as
        // a change — otherwise every save of any preview in the file re-reports it as `changed`
        // forever (params never come back on the class-file rescan), so the daemon emits
        // `discoveryUpdated` + the client re-reconciles on every keystroke-save. Normalize a
        // null-params rescan to the prior's params before comparing — matching what [applyDiff]
        // stores — so an otherwise-identical rescan is empty and only a *real* field change
        // (displayName / group / a genuinely different params block) is reported.
        // `captures` is unrecoverable from the class file for the same reason, and carries the
        // `@ScrollingPreview(END)` drive — so it gets the same normalization, or an END sticker
        // would re-report as `changed` on every save.
        val normalized =
          fresh
            .let { if (it.params == null) it.copy(params = prior.params) else it }
            .let { if (it.captures.isEmpty()) it.copy(captures = prior.captures) else it }
        normalized != prior
      }
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
      // The incremental source-change scan (IncrementalDiscovery.toDto) rebuilds a preview's DTO
      // from the class file alone, which can't recover the display `params` block (@Preview
      // widthDp / heightDp / device / showSystemUi / …) — so a `changed` DTO always arrives with
      // `params == null`. Blindly replacing the entry would strip the preview's known size until
      // the
      // next FULL rediscovery, which flips the desktop interactive/stream render between the
      // wrap-content sandbox and the fixed 320² frame on every save (the PNG↔Live "size shift"
      // after
      // an edit, from both #2369/#2370's null-params handling). Carry the prior entry's params
      // forward when the incoming DTO omits them, so an edited preview keeps its real size and the
      // wrap decision stays correct in both directions. A genuine `@Preview(widthDp = …)` edit is
      // (still) only reflected on the next full rediscovery — the class-file rescan can't see it
      // either way — but keeping the last-known size is strictly better than nulling it.
      for (dto in diff.changed) {
        val prior = byId[dto.id]
        byId[dto.id] =
          dto
            .let {
              if (it.params == null && prior?.params != null) it.copy(params = prior.params) else it
            }
            // Same carry-forward for `captures`: dropping it would silently turn an edited
            // `@ScrollingPreview(END)` sticker back into an unscrolled capture until the next full
            // rediscovery.
            .let {
              if (it.captures.isEmpty() && !prior?.captures.isNullOrEmpty())
                it.copy(captures = prior.captures)
              else it
            }
      }
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
