package ee.schimke.composeai.daemon.history

import ee.schimke.composeai.daemon.HistoryFeature
import ee.schimke.composeai.daemon.JsonRpcServer
import ee.schimke.composeai.daemon.protocol.HistoryDiffMode
import ee.schimke.composeai.daemon.protocol.HistoryDiffParams
import ee.schimke.composeai.daemon.protocol.HistoryDiffResult
import ee.schimke.composeai.daemon.protocol.HistoryListParams
import ee.schimke.composeai.daemon.protocol.HistoryListResult
import ee.schimke.composeai.daemon.protocol.HistoryPruneParams
import ee.schimke.composeai.daemon.protocol.HistoryPruneResult
import ee.schimke.composeai.daemon.protocol.HistoryPruneSourceResult
import ee.schimke.composeai.daemon.protocol.HistoryReadParams
import ee.schimke.composeai.daemon.protocol.HistoryReadResultDto
import ee.schimke.composeai.daemon.protocol.JsonRpcRequest
import ee.schimke.composeai.daemon.rpc.RpcMethodHandler
import ee.schimke.composeai.daemon.rpc.RpcMethodRegistry
import ee.schimke.composeai.daemon.rpc.RpcPeer
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.SemanticsDiff
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The `history/…` JSON-RPC surface — `history/list`, `history/read`, `history/diff` and
 * `history/prune` — living beside the [HistoryManager] it dispatches into rather than inside
 * `JsonRpcServer`'s dispatch `when` (issue #5166).
 *
 * The handlers are the ones that used to be `JsonRpcServer` members, unchanged apart from reaching
 * the connection through [RpcPeer] instead of the server's private helpers: same params decoding,
 * same error codes, same degradation when no manager is wired (`history/list` and `history/prune`
 * answer empty; `history/read` and `history/diff` answer `HistoryEntryNotFound`).
 *
 * @param historyManager the daemon's manager, or null when history was never wired (in-process
 *   tests, fake-mode harness scenarios) — see `JsonRpcServer`'s `historyManager` parameter.
 */
internal class HistoryRpcHandlers(
  private val peer: RpcPeer,
  private val historyManager: HistoryManager?,
) {

  /**
   * Registers the four `history/…` methods.
   *
   * Every method is registered unconditionally — a gated-off method replies with
   * [JsonRpcServer.ERR_METHOD_NOT_FOUND] from its handler rather than being left out of the
   * registry, so "no handler registered" keeps meaning "the daemon does not know this method at
   * all". The history wire surface is gated to 1.1 (see [HistoryFeature]); clients that pre-handle
   * -32601 (the VS Code panel's `historySource` falls back to "no entries") degrade gracefully
   * without coding against a half-shipped surface.
   */
  fun registerInto(builder: RpcMethodRegistry.Builder) {
    builder.register("history/list", gated { handleHistoryList(it) })
    builder.register("history/read", gated { handleHistoryRead(it) })
    builder.register("history/diff", gated { handleHistoryDiff(it) })
    builder.register("history/prune", gated { handleHistoryPrune(it) })
  }

  /**
   * Wraps [handler] in the [HistoryFeature.ENABLED] gate, which is where the dispatch `when` used
   * to apply it.
   */
  private fun gated(handler: (JsonRpcRequest) -> Unit): RpcMethodHandler = RpcMethodHandler { req ->
    if (HistoryFeature.ENABLED) {
      handler(req)
    } else {
      peer.sendErrorResponse(req.id, JsonRpcServer.ERR_METHOD_NOT_FOUND, HISTORY_DISABLED_MESSAGE)
    }
  }

  private fun handleHistoryList(req: JsonRpcRequest) {
    val params =
      try {
        peer.decodeParams(req.params, HistoryListParams.serializer())
      } catch (e: Throwable) {
        peer.sendErrorResponse(
          id = req.id,
          code = JsonRpcServer.ERR_INVALID_PARAMS,
          message = "invalid history/list params: ${e.message}",
        )
        return
      }
    val mgr = historyManager
    if (mgr == null || !mgr.isEnabled) {
      peer.sendResponse(
        req.id,
        peer.encode(
          HistoryListResult.serializer(),
          HistoryListResult(entries = emptyList(), nextCursor = null, totalCount = 0),
        ),
      )
      return
    }
    val filter =
      HistoryFilter(
        previewId = params.previewId,
        since = params.since,
        until = params.until,
        limit = params.limit,
        cursor = params.cursor,
        branch = params.branch,
        branchPattern = params.branchPattern,
        commit = params.commit,
        worktreePath = params.worktreePath,
        agentId = params.agentId,
        sourceKind = params.sourceKind,
        sourceId = params.sourceId,
        ref = params.ref,
      )
    val page =
      try {
        mgr.list(filter)
      } catch (t: Throwable) {
        peer.sendErrorResponse(
          id = req.id,
          code = JsonRpcServer.ERR_INTERNAL,
          message = "history/list failed: ${t.message}",
        )
        return
      }
    val result =
      HistoryListResult(
        entries = page.entries.map { encodeHistoryEntry(peer.json, it) },
        nextCursor = page.nextCursor,
        totalCount = page.totalCount,
      )
    peer.sendResponse(req.id, peer.encode(HistoryListResult.serializer(), result))
  }

  private fun handleHistoryRead(req: JsonRpcRequest) {
    val params =
      try {
        peer.decodeParams(req.params, HistoryReadParams.serializer())
      } catch (e: Throwable) {
        peer.sendErrorResponse(
          id = req.id,
          code = JsonRpcServer.ERR_INVALID_PARAMS,
          message = "invalid history/read params: ${e.message}",
        )
        return
      }
    val mgr = historyManager
    if (mgr == null || !mgr.isEnabled) {
      peer.sendErrorResponse(
        id = req.id,
        code = JsonRpcServer.ERR_HISTORY_ENTRY_NOT_FOUND,
        message = "history not configured",
      )
      return
    }
    val read =
      try {
        mgr.read(params.id, includeBytes = params.inline, ref = params.ref)
      } catch (t: Throwable) {
        peer.sendErrorResponse(
          id = req.id,
          code = JsonRpcServer.ERR_INTERNAL,
          message = "history/read failed: ${t.message}",
        )
        return
      }
    if (read == null) {
      peer.sendErrorResponse(
        id = req.id,
        code = JsonRpcServer.ERR_HISTORY_ENTRY_NOT_FOUND,
        message = "history entry not found: ${params.id}",
      )
      return
    }
    val previewMetadataElem =
      read.previewMetadata?.let {
        peer.json.encodeToJsonElement(PreviewMetadataSnapshot.serializer(), it)
      }
    val pngBase64 = read.pngBytes?.let { Base64.getEncoder().encodeToString(it) }
    val dto =
      HistoryReadResultDto(
        entry = encodeHistoryEntry(peer.json, read.entry),
        previewMetadata = previewMetadataElem,
        pngPath = read.pngPath,
        pngBytes = pngBase64,
      )
    peer.sendResponse(req.id, peer.encode(HistoryReadResultDto.serializer(), dto))
  }

  /**
   * H3 — `history/diff` metadata mode. Served whenever [HistoryFeature.ENABLED] is on; the
   * experimental sysprop that gated this for 1.0 was retired once H5 and the rest of the History
   * roadmap landed.
   *
   * Resolves [from] and [to] entry ids via the [historyManager] (which iterates configured sources
   * in priority order, so a cross-source diff "LocalFs vs GitRef preview/main" works the same as an
   * intra-source diff). Emits:
   *
   * - `HistoryEntryNotFound` (-32010) when either id is missing.
   * - `HistoryDiffMismatch` (-32011) when the two entries belong to different previews.
   * - `HistorySemanticsNotCaptured` (-32013) when `mode = semantics` but one of the two entries has
   *   no captured `compose/semantics` snapshot (issue #1785).
   *
   * The metadata-mode response is `pngHashChanged + fromMetadata + toMetadata` (full sidecars);
   * pixel-mode fields stay null there by design. PIXEL mode (H5, issue #1873) decodes both archived
   * frames and populates `diffPx` + `ssim` + `diffPngPath` (a marked-diff PNG written under
   * `<historyDir>/<previewId>/.diffs/`) via [HistoryImageDiff]. SEMANTICS mode (issue #1785) adds
   * `semanticsDelta`, the typed structural diff of the two entries' captured semantics trees. DATA
   * mode (issue #1873) rolls the captured `compose/semantics`, `a11y/atf` and `compose/theme`
   * snapshots into one versioned `dataDelta` via [HistoryDataDiff] — each section present only when
   * both entries carry that product. The `-32012` "pixel not implemented" sentinel is retired now
   * that H5 has landed.
   */
  private fun handleHistoryDiff(req: JsonRpcRequest) {
    val params =
      try {
        peer.decodeParams(req.params, HistoryDiffParams.serializer())
      } catch (e: Throwable) {
        peer.sendErrorResponse(
          id = req.id,
          code = JsonRpcServer.ERR_INVALID_PARAMS,
          message = "invalid history/diff params: ${e.message}",
        )
        return
      }
    val mgr = historyManager
    if (mgr == null || !mgr.isEnabled) {
      peer.sendErrorResponse(
        id = req.id,
        code = JsonRpcServer.ERR_HISTORY_ENTRY_NOT_FOUND,
        message = "history not configured",
      )
      return
    }
    // PIXEL mode needs the actual frame bytes; metadata / semantics modes don't, so only pay the
    // PNG read when the caller asked for a pixel diff.
    val includeBytes = params.mode == HistoryDiffMode.PIXEL
    val from =
      try {
        mgr.read(params.from, includeBytes = includeBytes, ref = params.ref)
      } catch (t: Throwable) {
        peer.sendErrorResponse(
          id = req.id,
          code = JsonRpcServer.ERR_INTERNAL,
          message = "history/diff: read(${params.from}) failed: ${t.message}",
        )
        return
      }
    if (from == null) {
      peer.sendErrorResponse(
        id = req.id,
        code = JsonRpcServer.ERR_HISTORY_ENTRY_NOT_FOUND,
        message = "history entry not found: ${params.from}",
      )
      return
    }
    val to =
      try {
        mgr.read(params.to, includeBytes = includeBytes, ref = params.ref)
      } catch (t: Throwable) {
        peer.sendErrorResponse(
          id = req.id,
          code = JsonRpcServer.ERR_INTERNAL,
          message = "history/diff: read(${params.to}) failed: ${t.message}",
        )
        return
      }
    if (to == null) {
      peer.sendErrorResponse(
        id = req.id,
        code = JsonRpcServer.ERR_HISTORY_ENTRY_NOT_FOUND,
        message = "history entry not found: ${params.to}",
      )
      return
    }
    if (from.entry.previewId != to.entry.previewId) {
      peer.sendErrorResponse(
        id = req.id,
        code = JsonRpcServer.ERR_HISTORY_DIFF_MISMATCH,
        message =
          "history/diff: from.previewId='${from.entry.previewId}' but " +
            "to.previewId='${to.entry.previewId}'; a diff across previews would be meaningless",
      )
      return
    }
    // SEMANTICS mode (issue #1785) — diff the two entries' captured `compose/semantics` trees.
    // Each entry's snapshot is frozen at record time in the sidecar (stripped from the lean index),
    // so the diff is the pixel-free regression signal: "Button 'Submit' lost its label" instead of
    // "some pixels moved". The differ ([SemanticsDiff]) ignores positional bounds + the volatile
    // nodeId, matching nodes by their stable `ref`.
    if (params.mode == HistoryDiffMode.SEMANTICS) {
      val missing =
        when {
          from.entry.semantics == null -> params.from
          to.entry.semantics == null -> params.to
          else -> null
        }
      if (missing != null) {
        peer.sendErrorResponse(
          id = req.id,
          code = JsonRpcServer.ERR_HISTORY_SEMANTICS_NOT_CAPTURED,
          message =
            "history/diff: entry '$missing' has no captured compose/semantics snapshot; " +
              "mode='semantics' needs both entries to have been recorded with semantics",
        )
        return
      }
      val delta =
        try {
          val base =
            peer.json.decodeFromJsonElement(
              ComposeSemanticsPayload.serializer(),
              from.entry.semantics!!,
            )
          val head =
            peer.json.decodeFromJsonElement(
              ComposeSemanticsPayload.serializer(),
              to.entry.semantics!!,
            )
          SemanticsDiff.diff(base, head)
        } catch (t: Throwable) {
          peer.sendErrorResponse(
            id = req.id,
            code = JsonRpcServer.ERR_INTERNAL,
            message = "history/diff: semantics diff failed: ${t.message}",
          )
          return
        }
      val result =
        HistoryDiffResult(
          pngHashChanged = from.entry.pngHash != to.entry.pngHash,
          fromMetadata = encodeHistoryEntry(peer.json, from.entry),
          toMetadata = encodeHistoryEntry(peer.json, to.entry),
          semanticsDelta = delta,
        )
      peer.sendResponse(req.id, peer.encode(HistoryDiffResult.serializer(), result))
      return
    }
    // DATA mode (issue #1873) — the data-product diff. Like SEMANTICS, it reads the snapshots
    // frozen
    // in each entry's sidecar (no PNG bytes), so it works the same off the local FS or a reporting
    // ref. Sections (semantics / a11y / theme) are populated only when both entries carry that
    // product; an absent product simply leaves its section null rather than erroring — DATA is a
    // best-effort roll-up, not the strict single-product SEMANTICS contract.
    if (params.mode == HistoryDiffMode.DATA) {
      val delta =
        try {
          HistoryDataDiff.diff(from.entry, to.entry, peer.json)
        } catch (t: Throwable) {
          peer.sendErrorResponse(
            id = req.id,
            code = JsonRpcServer.ERR_INTERNAL,
            message = "history/diff: data diff failed: ${t.message}",
          )
          return
        }
      val result =
        HistoryDiffResult(
          pngHashChanged = from.entry.pngHash != to.entry.pngHash,
          fromMetadata = encodeHistoryEntry(peer.json, from.entry),
          toMetadata = encodeHistoryEntry(peer.json, to.entry),
          dataDelta = delta,
        )
      peer.sendResponse(req.id, peer.encode(HistoryDiffResult.serializer(), result))
      return
    }
    // PIXEL mode (H5, issue #1873) — decode both archived frames, compute diffPx + ssim, and write
    // a
    // reviewer-facing marked-diff PNG to `<historyDir>/<previewId>/.diffs/`. The frame bytes were
    // read above (includeBytes); a null here means the source couldn't supply them (e.g. the PNG
    // was
    // moved out from under the archive) — treat as internal, not entry-not-found, since the index
    // entry resolved fine.
    if (params.mode == HistoryDiffMode.PIXEL) {
      val fromBytes = from.pngBytes
      val toBytes = to.pngBytes
      if (fromBytes == null || toBytes == null) {
        val missing = if (fromBytes == null) params.from else params.to
        peer.sendErrorResponse(
          id = req.id,
          code = JsonRpcServer.ERR_INTERNAL,
          message = "history/diff: entry '$missing' has no PNG bytes to pixel-diff",
        )
        return
      }
      val diff =
        try {
          HistoryImageDiff.diff(fromBytes, toBytes)
        } catch (t: Throwable) {
          peer.sendErrorResponse(
            id = req.id,
            code = JsonRpcServer.ERR_INTERNAL,
            message = "history/diff: pixel diff failed: ${t.message}",
          )
          return
        }
      val diffPngPath =
        diff.markedPng?.let { bytes -> writeDiffPng(to.pngPath, from.entry.id, to.entry.id, bytes) }
      val result =
        HistoryDiffResult(
          pngHashChanged = from.entry.pngHash != to.entry.pngHash,
          fromMetadata = encodeHistoryEntry(peer.json, from.entry),
          toMetadata = encodeHistoryEntry(peer.json, to.entry),
          diffPx = diff.diffPx,
          ssim = diff.ssim,
          diffPngPath = diffPngPath,
        )
      peer.sendResponse(req.id, peer.encode(HistoryDiffResult.serializer(), result))
      return
    }
    val result =
      HistoryDiffResult(
        pngHashChanged = from.entry.pngHash != to.entry.pngHash,
        fromMetadata = encodeHistoryEntry(peer.json, from.entry),
        toMetadata = encodeHistoryEntry(peer.json, to.entry),
        diffPx = null,
        ssim = null,
        diffPngPath = null,
      )
    peer.sendResponse(req.id, peer.encode(HistoryDiffResult.serializer(), result))
  }

  /**
   * Writes the marked-diff [pngBytes] to `<previewDir>/.diffs/<fromId>__<toId>.png`, where
   * `previewDir` is derived from the `to` entry's archived PNG path ([toPngPath]). Best-effort:
   * returns the absolute path on success, or null if the write fails (the pixel metrics are still
   * useful without the artefact). The `.diffs/` subdir is dot-prefixed so `LocalFsHistorySource`'s
   * per-preview sidecar resolution (which addresses `<id>.json` by name) never trips over it.
   */
  private fun writeDiffPng(
    toPngPath: String,
    fromId: String,
    toId: String,
    pngBytes: ByteArray,
  ): String? =
    try {
      val previewDir = java.nio.file.Path.of(toPngPath).toAbsolutePath().parent
      val diffsDir = previewDir.resolve(HistoryDiffArtifacts.DIFFS_DIR_NAME)
      java.nio.file.Files.createDirectories(diffsDir)
      val out = diffsDir.resolve(HistoryDiffArtifacts.fileName(fromId, toId))
      java.nio.file.Files.write(out, pngBytes)
      out.toString()
    } catch (t: Throwable) {
      System.err.println("compose-ai-daemon: history/diff writeDiffPng failed: ${t.message}")
      null
    }

  /**
   * H4 — `history/prune` manual prune trigger. Resolves [HistoryPruneParams] over the daemon's
   * configured defaults (explicit param wins; null leaves the default). When
   * [HistoryPruneParams.dryRun] is true, returns the would-remove set without touching disk and
   * does NOT emit a `historyPruned` notification. Otherwise mutates and (if non-empty) emits
   * `historyPruned` with `reason: "manual"`.
   *
   * See HISTORY.md § "Pruning policy" for the order-of-passes contract.
   */
  private fun handleHistoryPrune(req: JsonRpcRequest) {
    val params =
      try {
        peer.decodeParams(req.params, HistoryPruneParams.serializer())
      } catch (e: Throwable) {
        peer.sendErrorResponse(
          id = req.id,
          code = JsonRpcServer.ERR_INVALID_PARAMS,
          message = "invalid history/prune params: ${e.message}",
        )
        return
      }
    val mgr = historyManager
    if (mgr == null || !mgr.isEnabled) {
      // No history is configured → returns an empty result rather than a hard error. Mirrors how
      // history/list degrades: the consumer asked "did anything get pruned?" and the answer is
      // honestly "no" because nothing's recorded.
      peer.sendResponse(
        req.id,
        peer.encode(
          HistoryPruneResult.serializer(),
          HistoryPruneResult(
            removedEntries = emptyList(),
            freedBytes = 0L,
            sourceResults = emptyMap(),
          ),
        ),
      )
      return
    }
    // Compose effective config from the manager's defaults + per-call overrides.
    val baseConfig = mgr.pruneConfig
    val effective =
      HistoryPruneConfig(
        maxEntriesPerPreview = params.maxEntriesPerPreview ?: baseConfig.maxEntriesPerPreview,
        maxAgeDays = params.maxAgeDays ?: baseConfig.maxAgeDays,
        maxTotalSizeBytes = params.maxTotalSizeBytes ?: baseConfig.maxTotalSizeBytes,
        autoPruneIntervalMs = baseConfig.autoPruneIntervalMs,
      )
    val aggregate =
      try {
        mgr.pruneNow(
          config = effective,
          dryRun = params.dryRun,
          reason = if (params.dryRun) null else PruneReason.MANUAL,
        )
      } catch (t: Throwable) {
        peer.sendErrorResponse(
          id = req.id,
          code = JsonRpcServer.ERR_INTERNAL,
          message = "history/prune failed: ${t.message}",
        )
        return
      }
    val perSource =
      aggregate.sourceResults.mapValues { (_, r) ->
        HistoryPruneSourceResult(removedEntryIds = r.removedEntryIds, freedBytes = r.freedBytes)
      }
    peer.sendResponse(
      req.id,
      peer.encode(
        HistoryPruneResult.serializer(),
        HistoryPruneResult(
          removedEntries = aggregate.removedEntryIds,
          freedBytes = aggregate.freedBytes,
          sourceResults = perSource,
        ),
      ),
    )
  }

  internal companion object {
    /** Message returned for `history/…` requests when [HistoryFeature.ENABLED] is `false`. */
    internal const val HISTORY_DISABLED_MESSAGE: String =
      "history methods are post-1.0 and disabled in this daemon build; tracking for 1.1+"
  }
}

// Strips the heavy captured snapshots (semantics #1785, a11y/theme data products #1869) before
// echoing an entry on the wire: `history/list` / `history/read` / `historyAdded` / metadata-mode
// `history/diff` only want metadata. The payloads are read back off the sidecar exclusively by
// `history/diff mode=SEMANTICS` and (later) the data-diff surfaces.
internal fun encodeHistoryEntry(json: Json, entry: HistoryEntry): JsonElement =
  json.encodeToJsonElement(
    HistoryEntry.serializer(),
    entry.copy(
      semantics = null,
      a11yAtf = null,
      a11yHierarchy = null,
      a11yTouchTargets = null,
      theme = null,
    ),
  )
