package ee.schimke.composeai.daemon.history

/**
 * H4 — pruning policy configuration. See HISTORY.md § "Pruning policy".
 *
 * Each of the four knobs is independently disable-able by setting it to `0` or negative; that knob
 * is then skipped on the [LocalFsHistorySource.prune] cascade. When ALL four values are `≤ 0` the
 * config is "all-off" — [HistoryManager] suppresses the auto-prune scheduler entirely.
 *
 * **Defaults** are pinned in HISTORY.md and read by the production daemon mains from sysprops:
 *
 * | knob | default | sysprop |
 * |---|---|---|
 * | [maxEntriesPerPreview] | 50 | `composeai.daemon.history.maxEntriesPerPreview` |
 * | [maxAgeDays] | 14 | `composeai.daemon.history.maxAgeDays` |
 * | [maxTotalSizeBytes] | 500_000_000 (500 MB) | `composeai.daemon.history.maxTotalSizeBytes` |
 * | [autoPruneIntervalMs] | 1h | `composeai.daemon.history.autoPruneIntervalMs` |
 *
 * Manual `history/prune` RPC calls override these per-call (via [HistoryPruneParams][
 * ee.schimke.composeai.daemon.protocol.HistoryPruneParams]); the auto-prune scheduler always uses
 * the configured defaults.
 */
data class HistoryPruneConfig(
  val maxEntriesPerPreview: Int = 50,
  val maxAgeDays: Int = 14,
  val maxTotalSizeBytes: Long = 500_000_000L,
  val autoPruneIntervalMs: Long = 60L * 60L * 1000L,
) {

  /**
   * True when every individual knob is "off" (`≤ 0`). [HistoryManager] uses this to suppress the
   * auto-prune scheduler entirely (HISTORY.md § "Pruning policy" — "all-off disables scheduler").
   */
  val isAllOff: Boolean
    get() =
      maxEntriesPerPreview <= 0 &&
        maxAgeDays <= 0 &&
        maxTotalSizeBytes <= 0L &&
        autoPruneIntervalMs <= 0L

  companion object {
    const val PROP_MAX_ENTRIES: String = "composeai.daemon.history.maxEntriesPerPreview"
    const val PROP_MAX_AGE_DAYS: String = "composeai.daemon.history.maxAgeDays"
    const val PROP_MAX_TOTAL_SIZE_BYTES: String = "composeai.daemon.history.maxTotalSizeBytes"
    const val PROP_AUTO_PRUNE_INTERVAL_MS: String = "composeai.daemon.history.autoPruneIntervalMs"

    /**
     * Builds a config from system properties, falling back to defaults for any unset / unparseable
     * value. Production daemon mains call this; tests construct [HistoryPruneConfig] directly.
     */
    fun fromSysprops(props: (String) -> String? = System::getProperty): HistoryPruneConfig {
      val defaults = HistoryPruneConfig()
      return HistoryPruneConfig(
        maxEntriesPerPreview =
          props(PROP_MAX_ENTRIES)?.toIntOrNull() ?: defaults.maxEntriesPerPreview,
        maxAgeDays = props(PROP_MAX_AGE_DAYS)?.toIntOrNull() ?: defaults.maxAgeDays,
        maxTotalSizeBytes =
          props(PROP_MAX_TOTAL_SIZE_BYTES)?.toLongOrNull() ?: defaults.maxTotalSizeBytes,
        autoPruneIntervalMs =
          props(PROP_AUTO_PRUNE_INTERVAL_MS)?.toLongOrNull() ?: defaults.autoPruneIntervalMs,
      )
    }
  }
}

/**
 * Result of [HistorySource.prune]. [removedEntryIds] is the set of entry ids whose sidecars were
 * removed (or would be in dry-run mode). [freedBytes] sums the `pngSize` of entries whose PNG file
 * actually got deleted — entries that surrendered their sidecar but whose PNG was retained because
 * a surviving sidecar still references it (dedup-by-hash) do NOT contribute to [freedBytes].
 */
data class PruneResult(val removedEntryIds: List<String>, val freedBytes: Long) {
  companion object {
    val EMPTY: PruneResult = PruneResult(emptyList(), 0L)
  }
}

/**
 * Aggregate prune result across every writable [HistorySource] in a [HistoryManager].
 *
 * - [removedEntryIds] — concatenation of every per-source `removedEntryIds`.
 * - [freedBytes] — sum across sources.
 * - [sourceResults] — per-source breakdown keyed by [HistorySource.id]. Read-only sources are NOT
 *   listed (they're skipped by the manager).
 */
data class PruneAggregateResult(
  val removedEntryIds: List<String>,
  val freedBytes: Long,
  val sourceResults: Map<String, PruneResult>,
) {
  companion object {
    val EMPTY: PruneAggregateResult =
      PruneAggregateResult(emptyList(), 0L, emptyMap())
  }
}

/**
 * Reason for a `historyPruned` notification — see HISTORY.md § "historyPruned".
 *
 * - [AUTO] — the auto-prune scheduler ran a non-empty pass.
 * - [MANUAL] — a `history/prune` JSON-RPC call ran a non-empty pass.
 *
 * Empty (no-op) passes do NOT emit a notification at all (don't spam clients).
 */
enum class PruneReason {
  AUTO,
  MANUAL,
}

/** Internal payload [HistoryManager] hands to its `pruneListener`. */
data class PruneNotification(
  val removedIds: List<String>,
  val freedBytes: Long,
  val reason: PruneReason,
)
