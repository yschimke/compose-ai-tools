package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.protocol.DataFetchParams
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.render.session.DataProductException
import ee.schimke.composeai.render.session.RenderSession
import ee.schimke.composeai.render.session.RenderSessionConfig
import ee.schimke.composeai.render.session.RenderSessionException
import ee.schimke.composeai.render.session.RenderSessionFactory
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Drives a short-lived [RenderSession] for one module, walks every preview through `data/fetch` for
 * `a11y/atf`, aggregates the findings into the canonical `build/compose-previews
 * /accessibility.json` shape that [A11yReportRenderer] reads, and closes the session.
 *
 * Lives in the CLI rather than the render-session library because the aggregation shape is a CLI /
 * agent contract — third-party consumers that want raw `a11y/atf` payloads use the
 * [RenderSession.fetchData] API directly without buying into this aggregation format.
 *
 * @param factory pluggable render-session factory; defaults to the subprocess backend. Test
 *   scaffolding can inject a fake by constructing a custom [RenderSessionFactory].
 */
internal class DaemonA11yFetcher(
  private val factory: RenderSessionFactory = SubprocessRenderSessions,
  private val onLog: (String) -> Unit = {},
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }

  /**
   * Fetch a11y findings for [previewIds] in one module, aggregate into
   * `<projectDir>/build/compose-previews/accessibility.json`, return the result.
   *
   * [projectDir] is the module's project directory (i.e. [PreviewModule.projectDir]), already
   * resolved by the Tooling API — `daemon-launch.json` sits directly under
   * `<projectDir>/build/compose-previews/` regardless of the module's gradle path, so we don't
   * route through [SubprocessRenderSessions.descriptorFile] (which derives the dir from a workspace
   * root + gradle path).
   *
   * [workspaceRoot] is the repository root the daemon reports back through the initialize
   * handshake; defaults to [projectDir] when the caller doesn't have a separate workspace root
   * handy (single-module projects).
   *
   * [previews] pairs the id each fetch is *addressed* with against the id its entry is *filed*
   * under. They differ for a `--permutations` variant: the daemon only knows the previews the
   * plugin discovered, so `Foo_dark` is fetched as `Foo` carrying the dark-mode
   * [ee.schimke.composeai.daemon.protocol.PreviewOverrides] in the params bag, and filed as
   * `Foo_dark` (issue #3762).
   *
   * Permutations are fetched **before** the declared preview, and their artefacts snapshotted as
   * each one lands. The daemon writes overlay + hierarchy to `data/<previewId>/`, keyed by the id
   * it was asked for rather than by the overrides — so without both of those, four permutations of
   * one preview would overwrite each other and every entry would end up pointing at whichever
   * render finished last.
   *
   * [narrowed] says whether [previews] is a subset of what the module declares — true when `--id` /
   * `--filter` cut the fan-out down (issue #3742). It decides **merge vs. wholesale rewrite**: a
   * narrowed run carries forward the entries of previews it didn't ask about (minus the ones that
   * never really ran — see [carryForward]), the same bargain the `.cli-state.json` carry-forward
   * strikes for previews a narrowed render skipped (#3730), while a full run rewrites and so stays
   * the one thing that evicts the entry of a preview that no longer exists.
   *
   * [modulePreviewIds] is every id a **consumer** of this report may look up, which is a different
   * question and deliberately a different parameter. It decides [AccessibilityReport.partial]: the
   * report is stamped partial when the merged entries don't cover this set, so consumers read an
   * absent id as "not checked" rather than folding it in as a clean row. The two can disagree —
   * under `--permutations`, an unnarrowed run fetches every *declared* preview (so nothing is
   * carried forward) and still leaves every *synthetic* id uncovered (so the report is partial).
   * Deriving one from the other conflates them and costs the wholesale rewrite.
   */
  fun fetch(
    projectDir: File,
    modulePath: String,
    moduleName: String,
    previews: List<ReportCommand.RequestedPreview>,
    workspaceRoot: File = projectDir,
    modulePreviewIds: List<String> = previews.map { it.entryId },
    narrowed: Boolean = false,
  ): Outcome {
    val descriptorFile = File(projectDir, "build/compose-previews/daemon-launch.json")
    if (!descriptorFile.isFile) {
      writeAtfUnavailableReport(projectDir, moduleName, modulePreviewIds, narrowed)
      return Outcome.DescriptorMissing(descriptorFile)
    }

    val config =
      RenderSessionConfig(
        descriptorPath = descriptorFile,
        workspaceRoot = workspaceRoot.absoluteFile,
        workspaceName = workspaceRoot.name.ifBlank { moduleName },
        logSink = onLog,
      )

    val session: RenderSession =
      try {
        factory.open(config)
      } catch (e: RenderSessionException) {
        writeAtfUnavailableReport(projectDir, moduleName, modulePreviewIds, narrowed)
        return Outcome.OpenFailed(reason = e.message ?: e.javaClass.simpleName)
      }

    return session.use { live ->
      // The daemon registers `a11y` as inactive metadata; `extensions/enable` flips it on so
      // `data/fetch` for `a11y/atf` resolves instead of returning `kind not advertised`. Mirrors
      // the MCP supervisor's handshake. Failures here are logged but non-fatal — we still try
      // the fetches so the user sees the error mode per preview rather than a single blanket
      // open-failed message.
      try {
        live.enableExtensions(listOf("a11y"))
      } catch (e: RenderSessionException) {
        onLog("extensions/enable for 'a11y' failed: ${e.message}")
      }
      val entries = mutableListOf<AccessibilityEntry>()
      // Ids whose `a11y/atf` fetch produced nothing this run. They still get an entry — a full run
      // has to show that the preview was attempted (#1453) — but that entry is not an observation,
      // so it must not overwrite what a previous run actually found. See [mergeEntries].
      val failedIds = mutableSetOf<String>()
      var anyFetchOk = false
      // Permutations first, the declared preview last. Every fetch writes its artefacts to
      // `data/<previewId>/`, so the last render of a given preview is the one left there — and that
      // should be the preview's own, since its entry is the one keyed by that id.
      for (preview in previews.sortedBy { !it.isPermutation }) {
        val previewId = preview.previewId
        val entryId = preview.entryId
        val payload =
          try {
            live
              .fetchData(
                previewId = previewId,
                kind = ATF_KIND,
                inline = true,
                params = fetchParams(preview),
                timeout = 120.seconds,
              )
              .payload
          } catch (e: DataProductException) {
            onLog("a11y fetch for '$entryId' failed: code=${e.code} ${e.wireMessage}")
            null
          } catch (e: RenderSessionException) {
            onLog("a11y fetch for '$entryId' transport error: ${e.message}")
            null
          }
        if (payload != null) anyFetchOk = true else failedIds += entryId
        // Take the artefacts this render just produced before the next fetch of the same preview
        // overwrites them.
        if (preview.isPermutation) snapshotArtifacts(projectDir, from = previewId, to = entryId)
        val findings = payload?.let(::parseFindings).orEmpty()
        entries.add(
          AccessibilityEntry(
            previewId = entryId,
            findings = findings,
            nodes = readNodes(projectDir, entryId),
            annotatedPath = relativeOverlayPath(projectDir, entryId),
          )
        )
      }
      // If we attempted at least one preview and none succeeded, the empty-findings entries we
      // accumulated above are indistinguishable from a clean run. Stamp the report-level status
      // so downstream consumers (the python PR-comment helper, CLI exit-code policy) can tell
      // "ATF didn't run" apart from "ATF ran cleanly." When `previewIds` is empty there's nothing
      // to report on either way, so leave `status` null.
      val atfAvailable = anyFetchOk || previews.isEmpty()
      val status = if (atfAvailable) null else A11Y_REPORT_STATUS_ATF_UNAVAILABLE
      val reportFile =
        writeReport(
          projectDir,
          moduleName,
          entries = entries,
          status = status,
          modulePreviewIds = modulePreviewIds,
          narrowed = narrowed,
          failedIds = failedIds,
        )
      Outcome.Ok(reportFile = reportFile, entryCount = entries.size, atfAvailable = atfAvailable)
    }
  }

  /**
   * Write an `accessibility.json` stamped with `status = "atf-unavailable"` and no entries of its
   * own, for cases where we can't even open a render session (descriptor missing / open failed).
   * Lets the python PR-comment helper see the same signal it gets on a per-preview-failure run
   * instead of silently finding nothing on disk. On a narrowed run the entries already on disk
   * survive — a run that couldn't reach the daemon has learned nothing about the previews it wasn't
   * going to fetch either — and the stamped status still fails the CLI.
   */
  private fun writeAtfUnavailableReport(
    projectDir: File,
    moduleName: String,
    modulePreviewIds: List<String>,
    narrowed: Boolean,
  ) {
    writeReport(
      projectDir,
      moduleName,
      entries = emptyList(),
      status = A11Y_REPORT_STATUS_ATF_UNAVAILABLE,
      modulePreviewIds = modulePreviewIds,
      narrowed = narrowed,
    )
  }

  private fun writeReport(
    projectDir: File,
    moduleName: String,
    entries: List<AccessibilityEntry>,
    status: String?,
    modulePreviewIds: List<String>,
    narrowed: Boolean,
    failedIds: Set<String> = emptySet(),
  ): File {
    val reportFile = projectDir.resolve("build/compose-previews/accessibility.json")
    reportFile.parentFile?.mkdirs()
    val existing = if (narrowed) readExistingReport(reportFile) else null
    val merged = mergeEntries(carryForward(existing), entries, failedIds)
    val covered = merged.map { it.previewId }.toSet()
    val report =
      AccessibilityReport(
        module = moduleName,
        entries = merged,
        status = status,
        partial = !covered.containsAll(modulePreviewIds),
      )
    fileSystem.write(reportFile.path.toPath()) {
      writeUtf8(json.encodeToString(AccessibilityReport.serializer(), report))
    }
    return reportFile
  }

  /**
   * The entries of [existing] that are worth keeping — everything, unless that report was stamped
   * [A11Y_REPORT_STATUS_ATF_UNAVAILABLE], in which case only the ones **carrying findings** do.
   *
   * An entry with no findings under that stamp records a fetch that produced nothing — quite
   * possibly one that never ran at all (#1453) — so keeping it would let a later narrowed success
   * republish it with no stamp of its own and have every consumer read it as "checked, found
   * nothing". Dropping it instead leaves that preview *uncovered*, which
   * [AccessibilityReport.partial] already reports honestly, so the run needs no second mechanism to
   * say "don't trust these". Findings are the only sound proof: they can only come from a decoded
   * `a11y/atf` payload, and an entry that has them is data whatever the report-level stamp says (a
   * stamp can come from a failed session open landing on top of a previous run's genuine results).
   *
   * **Not `nodes`** — tempting, and wrong. [readNodes] reads `a11y-hierarchy.json` off disk for
   * every preview whether or not its ATF fetch succeeded, and that file can be left over from an
   * earlier render, so a node list says nothing about whether ATF ran. The cost of the strict rule
   * is that a genuinely clean preview carried through a stamped report reads as "not checked" until
   * the next full run — an understatement of coverage, which is the safe direction to be wrong in.
   */
  private fun carryForward(existing: AccessibilityReport?): List<AccessibilityEntry> {
    val entries = existing?.entries.orEmpty()
    if (existing?.status != A11Y_REPORT_STATUS_ATF_UNAVAILABLE) return entries
    return entries.filter { it.findings.isNotEmpty() }
  }

  /**
   * The `accessibility.json` a previous run left at [reportFile], or `null` when there is none / it
   * can't be parsed. A report we can't read is one we can't preserve, and refusing to write over it
   * would leave the run with no report at all — so an unreadable file degrades to the non-merging
   * behaviour rather than failing the fetch.
   */
  private fun readExistingReport(reportFile: File): AccessibilityReport? {
    if (!reportFile.isFile) return null
    return try {
      val text = fileSystem.read(reportFile.path.toPath()) { readUtf8() }
      json.decodeFromString(AccessibilityReport.serializer(), text)
    } catch (_: Exception) {
      null
    }
  }

  /**
   * [fresh] layered over [existing], keyed by `previewId`: a preview this run fetched takes the new
   * entry, one it didn't keeps the old, and each id appears once. Existing order is preserved (new
   * ids append) so consecutive narrowed runs produce a stable file rather than reshuffling it.
   *
   * An id in [failedIds] is the exception: its fresh entry records a fetch that produced nothing,
   * so letting it win would delete findings a previous run really observed — and, if some *other*
   * preview in the same run succeeded, republish the deleted one as checked-and-clean under this
   * run's null status. Those entries only land where there is nothing to keep, which is what makes
   * a full run still show every attempted preview.
   */
  private fun mergeEntries(
    existing: List<AccessibilityEntry>,
    fresh: List<AccessibilityEntry>,
    failedIds: Set<String>,
  ): List<AccessibilityEntry> {
    if (existing.isEmpty()) return fresh
    val freshById = fresh.associateBy { it.previewId }
    val emitted = mutableSetOf<String>()
    val out = mutableListOf<AccessibilityEntry>()
    for (entry in existing) {
      if (!emitted.add(entry.previewId)) continue
      val replacement = freshById[entry.previewId]?.takeIf { it.previewId !in failedIds }
      out += replacement ?: entry
    }
    for (entry in fresh) {
      if (emitted.add(entry.previewId)) out += entry
    }
    return out
  }

  /**
   * The `params` bag for one fetch: the permutation's render overrides, plus a forced re-render so
   * the daemon produces the artefact at *those* overrides rather than serving whatever the shared
   * per-preview file already holds. `null` for a declared preview, which wants its own defaults and
   * can reuse a cached render.
   */
  private fun fetchParams(preview: ReportCommand.RequestedPreview): JsonElement? {
    val overrides = preview.overrides ?: return null
    return buildJsonObject {
      put(DataFetchParams.PARAM_FORCE_RERENDER, JsonPrimitive(true))
      put(
        DataFetchParams.PARAM_OVERRIDES,
        json.encodeToJsonElement(PreviewOverrides.serializer(), overrides),
      )
    }
  }

  /**
   * Copy the artefacts the daemon just wrote for [from] into [to]'s own directory, so a permutation
   * keeps the overlay and hierarchy of *its* render.
   *
   * The daemon keys `data/<previewId>/` by the id it was asked for, and a permutation is fetched
   * under its declared preview's id — so these files are transient: the next fetch of the same
   * preview replaces them. `fetchData` returns only after the render completes, so copying here is
   * safe. Best-effort: a permutation whose artefacts can't be copied still keeps its findings,
   * which came back inline in the payload rather than through the filesystem.
   */
  private fun snapshotArtifacts(projectDir: File, from: String, to: String) {
    val sourceDir = projectDir.resolve("build/compose-previews/data/$from")
    if (!sourceDir.isDirectory) return
    val targetDir = projectDir.resolve("build/compose-previews/data/$to")
    for (name in SNAPSHOT_FILES) {
      val source = sourceDir.resolve(name)
      if (!source.isFile) continue
      try {
        targetDir.mkdirs()
        source.copyTo(targetDir.resolve(name), overwrite = true)
      } catch (e: Exception) {
        onLog("could not snapshot $name for '$to': ${e.message}")
      }
    }
  }

  /**
   * Resolve the daemon-side overlay PNG (`a11y-overlay.png`) for [previewId] relative to the
   * accessibility.json that will be written. Returns null when the file is absent.
   */
  private fun relativeOverlayPath(projectDir: File, previewId: String): String? {
    val overlay = projectDir.resolve("build/compose-previews/data/$previewId/a11y-overlay.png")
    return overlay.takeIf { it.isFile }?.let { "data/$previewId/a11y-overlay.png" }
  }

  /**
   * Read the daemon-side `a11y-hierarchy.json` for [previewId] and decode its `nodes` so the
   * aggregated `accessibility.json` carries the "what a screen reader sees" node list alongside the
   * overlay PNG — the desktop overlay-only path populates these even when `findings` is empty.
   * Reads off disk (rather than a second `data/fetch`) so it's robust to the file being a sibling
   * of the overlay the re-render already produced; returns empty when absent or unparseable.
   */
  private fun readNodes(projectDir: File, previewId: String): List<AccessibilityNode> {
    val file = projectDir.resolve("build/compose-previews/data/$previewId/a11y-hierarchy.json")
    if (!file.isFile) return emptyList()
    return try {
      val text = fileSystem.read(file.path.toPath()) { readUtf8() }
      val obj = json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
      val nodes = obj["nodes"] ?: return emptyList()
      json.decodeFromJsonElement(
        kotlinx.serialization.builtins.ListSerializer(AccessibilityNode.serializer()),
        nodes,
      )
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun parseFindings(payload: JsonElement): List<AccessibilityFinding> {
    val obj = payload as? JsonObject ?: return emptyList()
    val findings = obj["findings"] ?: return emptyList()
    return try {
      json.decodeFromJsonElement(
        kotlinx.serialization.builtins.ListSerializer(AccessibilityFinding.serializer()),
        findings,
      )
    } catch (_: Exception) {
      emptyList()
    }
  }

  sealed interface Outcome {
    /**
     * Render session opened and per-preview fetches completed (possibly with some failures — see
     * [atfAvailable]). [atfAvailable] is `true` when at least one preview's `a11y/atf` fetch
     * succeeded, or when the module had no previews to attempt; `false` only when every attempted
     * fetch failed. The on-disk `accessibility.json` carries the same signal via its `status`
     * field.
     */
    data class Ok(val reportFile: File, val entryCount: Int, val atfAvailable: Boolean) : Outcome

    data class DescriptorMissing(val expected: File) : Outcome

    data class OpenFailed(val reason: String) : Outcome
  }

  companion object {
    private const val ATF_KIND = "a11y/atf"

    /**
     * The per-preview artefacts a permutation needs a copy of. Both are written by the daemon into
     * `data/<previewId>/` and read back from there by [readNodes] / [relativeOverlayPath].
     */
    private val SNAPSHOT_FILES = listOf("a11y-overlay.png", "a11y-hierarchy.json")
  }
}
