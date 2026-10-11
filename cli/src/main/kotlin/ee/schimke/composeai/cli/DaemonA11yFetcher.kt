package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.protocol.DataFetchParams
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.previewdata.A11Y_REPORT_STATUS_ATF_UNAVAILABLE
import ee.schimke.composeai.previewdata.AccessibilityEntry
import ee.schimke.composeai.previewdata.AccessibilityFinding
import ee.schimke.composeai.previewdata.AccessibilityNode
import ee.schimke.composeai.previewdata.AccessibilityReport
import ee.schimke.composeai.previewdata.PreviewModule
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
 * Drives a short-lived [RenderSession] for one module, fetches `a11y/atf` per preview, and
 * aggregates the findings into `build/compose-previews/accessibility.json` for
 * [A11yReportRenderer]. The aggregation is a CLI/agent contract, so it lives here rather than in
 * the render-session library.
 *
 * @param factory render-session factory; defaults to the subprocess backend, tests inject a fake.
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
   * Fetch a11y findings for [previews] in one module, write
   * `<projectDir>/build/compose-previews/accessibility.json`, and return the result.
   *
   * [projectDir] is the resolved module directory (where `daemon-launch.json` lives).
   * [workspaceRoot] is reported to the daemon; defaults to [projectDir].
   *
   * [previews] pairs the id a fetch is addressed with and the id its entry is filed under: a
   * `--permutations` variant `Foo_dark` is fetched as `Foo` with overrides and filed as `Foo_dark`.
   * Permutations are fetched before the declared preview and their artefacts snapshotted as each
   * lands, because the daemon keys `data/<previewId>/` by the addressed id.
   *
   * [narrowed] decides merge vs. wholesale rewrite: a narrowed run carries forward entries it
   * didn't fetch ([carryForward]); a full run rewrites, evicting entries for removed previews.
   * [modulePreviewIds] is every id a consumer may look up and decides
   * [AccessibilityReport.partial]; the two differ under `--permutations`.
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
      // `a11y` is registered inactive; enable it so `a11y/atf` fetches resolve. Failures are logged
      // and the fetches still attempted, so errors show per preview.
      try {
        live.enableExtensions(listOf("a11y"))
      } catch (e: RenderSessionException) {
        onLog("extensions/enable for 'a11y' failed: ${e.message}")
      }
      val entries = mutableListOf<AccessibilityEntry>()
      // Ids whose fetch produced nothing. They still get an entry (a full run shows every attempt),
      // but it must not overwrite a previous run's findings ([mergeEntries]).
      val failedIds = mutableSetOf<String>()
      var anyFetchOk = false

      fun fetchOne(previewId: String, entryId: String, params: JsonElement?): JsonElement? =
        try {
          live
            .fetchData(
              previewId = previewId,
              kind = ATF_KIND,
              inline = true,
              params = params,
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

      // Group by the addressed id: permutations share its artefact directory, cached data product
      // and `renders/<id>.png`.
      for ((previewId, group) in previews.groupBy { it.previewId }) {
        val permutations = group.filter { it.isPermutation }
        val base = group.firstOrNull { !it.isPermutation }
        // Hold a copy of `data/<previewId>/` before any override renders as this preview, so it can
        // be restored if the restoring fetch fails.
        val heldArtifacts =
          if (permutations.isEmpty()) null else holdArtifacts(projectDir, previewId)
        for (preview in permutations) {
          val payload = fetchOne(previewId, preview.entryId, fetchParams(preview))
          if (payload != null) anyFetchOk = true else failedIds += preview.entryId
          // Snapshot this render's artefacts before the next fetch replaces them — only on success,
          // or we'd file the previous configuration's files under this id.
          if (payload != null) snapshotArtifacts(projectDir, from = previewId, to = preview.entryId)
          entries.add(
            AccessibilityEntry(
              previewId = preview.entryId,
              findings = payload?.let(::parseFindings).orEmpty(),
              nodes = if (payload != null) readNodes(projectDir, preview.entryId) else emptyList(),
              annotatedPath =
                if (payload != null) relativeOverlayPath(projectDir, preview.entryId) else null,
            )
          )
        }

        if (permutations.isEmpty()) {
          // Nothing overrode this preview, so the cached fast path is fine.
          val preview = base ?: continue
          val payload = fetchOne(previewId, preview.entryId, null)
          if (payload != null) anyFetchOk = true else failedIds += preview.entryId
          entries.add(
            AccessibilityEntry(
              previewId = preview.entryId,
              findings = payload?.let(::parseFindings).orEmpty(),
              nodes = readNodes(projectDir, preview.entryId),
              annotatedPath = relativeOverlayPath(projectDir, preview.entryId),
            )
          )
          continue
        }

        // After a permutation, both the cached data product and `renders/<previewId>.png` describe
        // the override (an unforced fetch would serve the stale file), so force one default render
        // — even when the declared preview has no entry to file.
        val payload = fetchOne(previewId, base?.entryId ?: previewId, forcedFetchParams(null))
        if (payload == null) {
          onLog(
            "could not restore the default render of '$previewId' after its permutations; " +
              "renders/$previewId.png may still hold a permutation's pixels"
          )
          // The forced fetch failed, so roll `data/<previewId>/` back rather than leave override
          // files that a later unforced fetch would serve. Rolling back, not deleting, keeps a
          // carried-forward entry's `annotatedPath` valid.
          restoreArtifacts(projectDir, previewId, heldArtifacts)
        }
        releaseHeldArtifacts(heldArtifacts)
        if (base == null) continue
        if (payload != null) anyFetchOk = true else failedIds += base.entryId
        // The directory now holds the declared preview's own render either way.
        entries.add(
          AccessibilityEntry(
            previewId = base.entryId,
            findings = payload?.let(::parseFindings).orEmpty(),
            nodes = readNodes(projectDir, base.entryId),
            annotatedPath = relativeOverlayPath(projectDir, base.entryId),
          )
        )
      }
      // If every attempt failed, empty entries would look like a clean run; stamp the report status
      // so consumers can tell. Null when there was nothing to attempt.
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
   * Write an `accessibility.json` stamped `atf-unavailable` with no entries of its own, when no
   * session could be opened. A narrowed run keeps existing entries; the stamp still fails the CLI.
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
   * The entries of [existing] worth keeping: all of them, unless it was stamped
   * [A11Y_REPORT_STATUS_ATF_UNAVAILABLE], in which case only those carrying findings.
   *
   * An empty entry under that stamp may record a fetch that never ran; dropping it leaves the
   * preview uncovered, which [AccessibilityReport.partial] reports honestly. Findings can only come
   * from a decoded payload. Not `nodes`: [readNodes] may read a leftover `a11y-hierarchy.json`.
   */
  private fun carryForward(existing: AccessibilityReport?): List<AccessibilityEntry> {
    val entries = existing?.entries.orEmpty()
    if (existing?.status != A11Y_REPORT_STATUS_ATF_UNAVAILABLE) return entries
    return entries.filter { it.findings.isNotEmpty() }
  }

  /**
   * The previous run's `accessibility.json`, or null when absent or unparseable (then the run
   * simply doesn't merge).
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
   * [fresh] over [existing], keyed by `previewId`, preserving existing order (new ids append) for
   * stable output. Fresh entries for [failedIds] only land where there is nothing to keep, so a
   * failed fetch never deletes real findings.
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
   * The `params` bag for one fetch: null for a declared preview with no permutations in play (may
   * reuse a cached render); every permutation gets [forcedFetchParams]. Forcing follows being a
   * permutation, not having overrides: a variant whose overrides equal the base's would otherwise
   * be served the previous permutation's cached file.
   */
  private fun fetchParams(preview: ReportCommand.RequestedPreview): JsonElement? =
    if (preview.isPermutation) forcedFetchParams(preview.overrides) else null

  /**
   * A forced re-render at [overrides] (or none), so the daemon doesn't serve the shared cached
   * file.
   */
  private fun forcedFetchParams(overrides: PreviewOverrides?): JsonElement = buildJsonObject {
    put(DataFetchParams.PARAM_FORCE_RERENDER, JsonPrimitive(true))
    if (overrides != null) {
      put(
        DataFetchParams.PARAM_OVERRIDES,
        json.encodeToJsonElement(PreviewOverrides.serializer(), overrides),
      )
    }
  }

  /**
   * Delete every per-render artefact under `data/[previewId]/`, including `a11y-atf.json`: the
   * registry only re-renders when that file is missing, so a leftover would serve the override's
   * findings.
   */
  /**
   * Copy `data/[previewId]/`'s per-render artefacts aside before permutations render, returning the
   * hold directory for [restoreArtifacts]. Absent or uncopyable files are recorded as absent, so a
   * roll-back deletes them (a re-render is cheaper than serving the wrong configuration).
   */
  private fun holdArtifacts(projectDir: File, previewId: String): File? {
    val dir = projectDir.resolve("build/compose-previews/data/$previewId")
    val hold = projectDir.resolve("build/compose-previews/.a11y-pre-permutation/$previewId")
    return try {
      hold.deleteRecursively()
      // A hold dir with an earlier run's files would reinstate stale artefacts; start empty or not
      // at all.
      if (PER_RENDER_FILES.any { hold.resolve(it).exists() }) {
        onLog(
          "could not clear the pre-permutation hold for '$previewId' at ${hold.path}; " +
            "rolling back will discard its artefacts instead of restoring them"
        )
        return null
      }
      hold.mkdirs()
      for (name in PER_RENDER_FILES) {
        val source = dir.resolve(name)
        if (source.isFile) source.copyTo(hold.resolve(name), overwrite = true)
      }
      hold
    } catch (e: Exception) {
      onLog("could not hold the pre-permutation artefacts of '$previewId': ${e.message}")
      null
    }
  }

  /**
   * Restore `data/[previewId]/` to what [holdArtifacts] captured, deleting files it didn't hold.
   * With no [held] directory, delete every per-render file so the next fetch re-renders.
   */
  private fun restoreArtifacts(projectDir: File, previewId: String, held: File?) {
    val dir = projectDir.resolve("build/compose-previews/data/$previewId")
    for (name in PER_RENDER_FILES) {
      val target = dir.resolve(name)
      val source = held?.resolve(name)?.takeIf { it.isFile }
      try {
        if (source != null) {
          dir.mkdirs()
          source.copyTo(target, overwrite = true)
          continue
        }
        target.delete()
      } catch (e: Exception) {
        onLog("could not roll back $name for '$previewId': ${e.message}")
      }
      // `File.delete()` returns false rather than throwing; a surviving `a11y-atf.json` would be
      // served.
      if (source == null && target.exists()) {
        onLog(
          "could not roll back $name for '$previewId'; a later fetch of that preview may serve " +
            "a permutation's render"
        )
      }
    }
  }

  /** Drop the hold directory. Non-fatal ([holdArtifacts] re-checks), but worth logging. */
  private fun releaseHeldArtifacts(held: File?) {
    if (held == null) return
    val cleared =
      try {
        held.deleteRecursively()
      } catch (e: Exception) {
        onLog("could not clean up ${held.path}: ${e.message}")
        false
      }
    if (!cleared && held.exists()) onLog("could not clean up ${held.path}")
  }

  /**
   * Copy the daemon's fresh artefacts for [from] into [to]'s directory, so a permutation keeps its
   * own overlay and hierarchy (the daemon's copy is overwritten by the next fetch of the same
   * preview). Best-effort; findings came inline anyway. Files this render didn't produce are
   * deleted from [to].
   */
  private fun snapshotArtifacts(projectDir: File, from: String, to: String) {
    val sourceDir = projectDir.resolve("build/compose-previews/data/$from")
    val targetDir = projectDir.resolve("build/compose-previews/data/$to")
    for (name in SNAPSHOT_FILES) {
      val source = sourceDir.resolve(name)
      val target = targetDir.resolve(name)
      try {
        if (!source.isFile) {
          target.delete()
          continue
        }
        targetDir.mkdirs()
        source.copyTo(target, overwrite = true)
      } catch (e: Exception) {
        onLog("could not snapshot $name for '$to': ${e.message}")
      }
    }
  }

  /**
   * The daemon-side `a11y-overlay.png` for [previewId], relative to the report, or null when
   * absent.
   */
  private fun relativeOverlayPath(projectDir: File, previewId: String): String? {
    val overlay = projectDir.resolve("build/compose-previews/data/$previewId/a11y-overlay.png")
    return overlay.takeIf { it.isFile }?.let { "data/$previewId/a11y-overlay.png" }
  }

  /**
   * Decode `a11y-hierarchy.json`'s `nodes` for [previewId] from disk (the screen-reader view,
   * present even without findings). Empty when absent or unparseable.
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
     * Session opened and fetches completed. [atfAvailable] is false only when every attempted fetch
     * failed; the report's `status` carries the same signal.
     */
    data class Ok(val reportFile: File, val entryCount: Int, val atfAvailable: Boolean) : Outcome

    data class DescriptorMissing(val expected: File) : Outcome

    data class OpenFailed(val reason: String) : Outcome
  }

  companion object {
    private const val ATF_KIND = "a11y/atf"

    /** Per-preview artefacts a permutation needs its own copy of. */
    private val SNAPSHOT_FILES = listOf("a11y-overlay.png", "a11y-hierarchy.json")

    /**
     * Everything `AccessibilityDataProducer.writeArtifacts` writes under `data/<previewId>/` for
     * one render, moved as a unit by [holdArtifacts] / [restoreArtifacts] so the directory never
     * mixes configurations.
     */
    private val PER_RENDER_FILES =
      listOf("a11y-atf.json", "a11y-hierarchy.json", "a11y-touchTargets.json", "a11y-overlay.png")
  }
}
