package ee.schimke.composeai.cli.serve

/**
 * A structured, human-readable reason a served session is **degraded** — i.e. a live/interactive
 * lane the viewer would otherwise offer is unavailable and the server has fallen back to baked PNG
 * snapshots. Recorded by [ServeCatalogStore] at catalog-load time (where the fallback is decided,
 * and where it was previously only written to stderr), then surfaced by the viewer (a session-level
 * banner) and `/api/previews` (a `degradations` array) so a visitor sees *why* a session is
 * snapshot-only instead of guessing.
 *
 * [code] is a stable machine slug a programmatic client can switch on; [detail] is a one-sentence
 * explanation shown in the UI. Keep [code] values in lockstep with any downstream consumer.
 */
data class ServeDegradation(val code: String, val detail: String) {
  companion object {
    /**
     * The catalog publishes baked PNGs only — its delivery branch carries no `liveBundle` (and no
     * source this server can build), so no device/theme/knob control can re-render. The common case
     * for an app catalog that hasn't opted into the live tier yet.
     */
    const val CATALOG_BAKED_ONLY = "catalog-baked-only"

    /**
     * The catalog declared a `liveBundle` but the server couldn't stand a daemon up from it (the
     * bundle or one of its externalized resources failed to fetch/verify, or the daemon didn't
     * start), so it fell back to baked PNGs. [detail] carries the specific cause.
     */
    const val LIVEBUNDLE_UNAVAILABLE = "livebundle-unavailable"

    /**
     * The catalog offers a live lane (a `liveBundle` or a buildable source) but verified as
     * `Unverified`, so the server refuses to re-render it (fail-closed) and serves baked PNGs. The
     * trust badge already shows the amber verdict; this states the consequence.
     */
    const val UNVERIFIED_NO_RERENDER = "unverified-no-rerender"

    /**
     * The catalog declares live-only (`deferred[]`) coverage this session can't produce: those
     * previews have no baked PNG by design, and without a live daemon there is nothing to render
     * them from — so they are omitted from the grid rather than shown as broken cards. The count
     * rides in [detail] so a visitor knows the sheet is thinner than the catalog claims.
     */
    const val DEFERRED_NOT_SERVED = "deferred-not-served"

    /**
     * The session HAD a working live lane and the server has since **switched it off**: its render
     * circuit breaker tripped (a linkage/classpath fault that can never succeed on retry, or a
     * sustained failure rate), so live renders are refused with the underlying reason rather than
     * retried. Distinct from [LIVEBUNDLE_UNAVAILABLE], which is a daemon that never came up.
     */
    const val RENDER_LANE_BROKEN = "render-lane-broken"

    /**
     * The live render lane was disabled by its circuit breaker; [reason] is the breaker's text and
     * [fatal] marks a linkage fault (needs a fixed bundle, not a retry). See
     * [RenderCircuitBreaker].
     */
    fun renderLaneBroken(reason: String, fatal: Boolean): ServeDegradation =
      ServeDegradation(
        RENDER_LANE_BROKEN,
        if (fatal) {
          "This catalog's live render lane is disabled: it hit a non-recoverable error that no " +
            "retry can clear, so device, theme and knob controls serve baked PNG snapshots until " +
            "the catalog is republished. $reason"
        } else {
          "This catalog's live render lane is disabled after a sustained run of render failures — " +
            "it serves baked PNG snapshots and retries periodically. $reason"
        },
      )

    /**
     * [count] live-only previews hidden because this session has no live lane. Paired with
     * whichever reason explains the missing live lane (no live bundle / unverified / bundle
     * unavailable).
     */
    fun deferredNotServed(count: Int): ServeDegradation =
      ServeDegradation(
        DEFERRED_NOT_SERVED,
        "$count preview(s) in this catalog are published live-only (rendered on demand rather " +
          "than baked), and this session has no live render lane — they're hidden rather than " +
          "shown as broken images.",
      )

    /** A baked-only catalog with no live bundle on its delivery branch. */
    fun catalogBakedOnly(): ServeDegradation =
      ServeDegradation(
        CATALOG_BAKED_ONLY,
        "This catalog serves baked PNG snapshots only — its delivery branch publishes no live " +
          "bundle, so device, theme and knob controls can't re-render on this server.",
      )

    /** A declared live bundle that couldn't be brought up; [cause] is the specific reason. */
    fun liveBundleUnavailable(cause: String): ServeDegradation =
      ServeDegradation(
        LIVEBUNDLE_UNAVAILABLE,
        "This catalog publishes a live bundle, but the server couldn't render from it ($cause) — " +
          "falling back to baked PNG snapshots.",
      )

    /** A live-capable catalog that verified as unverified, so re-render is refused. */
    fun unverifiedNoRerender(): ServeDegradation =
      ServeDegradation(
        UNVERIFIED_NO_RERENDER,
        "This catalog is unverified, so the server won't re-render it (fail-closed) — showing " +
          "baked PNG snapshots only.",
      )
  }
}
