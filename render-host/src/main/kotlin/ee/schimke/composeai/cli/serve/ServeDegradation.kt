package ee.schimke.composeai.cli.serve

/**
 * A structured reason a served session is degraded: a live lane the viewer would offer is
 * unavailable and the server serves baked PNGs instead. Recorded by [ServeCatalogStore] at catalog
 * load and surfaced as a viewer banner and `/api/previews` `degradations`. [code] is a stable slug
 * for clients (keep in lockstep with consumers); [detail] is a one-sentence UI explanation.
 */
public data class ServeDegradation(val code: String, val detail: String) {
  public companion object {
    /**
     * The catalog publishes baked PNGs only (no `liveBundle` or buildable source), so nothing can
     * re-render.
     */
    public const val CATALOG_BAKED_ONLY: String = "catalog-baked-only"

    /**
     * The catalog declared a `liveBundle` but no daemon could be stood up from it; [detail] has the
     * cause.
     */
    public const val LIVEBUNDLE_UNAVAILABLE: String = "livebundle-unavailable"

    /**
     * A live lane exists but the catalog verified as `Unverified`, so re-rendering is refused
     * (fail-closed).
     */
    public const val UNVERIFIED_NO_RERENDER: String = "unverified-no-rerender"

    /**
     * Live-only (`deferred[]`) previews have no baked PNG and no live lane here, so they are
     * omitted; the count is in [detail].
     */
    public const val DEFERRED_NOT_SERVED: String = "deferred-not-served"

    /**
     * A working live lane was switched off by its render circuit breaker (fatal linkage fault or
     * sustained failures). Distinct from [LIVEBUNDLE_UNAVAILABLE], a daemon that never started.
     */
    public const val RENDER_LANE_BROKEN: String = "render-lane-broken"

    /**
     * The live lane was disabled by its [RenderCircuitBreaker]; [fatal] marks a linkage fault that
     * needs a fixed bundle, not a retry.
     */
    public fun renderLaneBroken(reason: String, fatal: Boolean): ServeDegradation =
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
     * [count] live-only previews hidden for lack of a live lane, paired with the reason that
     * explains it.
     */
    public fun deferredNotServed(count: Int): ServeDegradation =
      ServeDegradation(
        DEFERRED_NOT_SERVED,
        "$count preview(s) in this catalog are published live-only (rendered on demand rather " +
          "than baked), and this session has no live render lane — they're hidden rather than " +
          "shown as broken images.",
      )

    /** A baked-only catalog with no live bundle on its delivery branch. */
    public fun catalogBakedOnly(): ServeDegradation =
      ServeDegradation(
        CATALOG_BAKED_ONLY,
        "This catalog serves baked PNG snapshots only — its delivery branch publishes no live " +
          "bundle, so device, theme and knob controls can't re-render on this server.",
      )

    /** A declared live bundle that couldn't be brought up; [cause] is the specific reason. */
    public fun liveBundleUnavailable(cause: String): ServeDegradation =
      ServeDegradation(
        LIVEBUNDLE_UNAVAILABLE,
        "This catalog publishes a live bundle, but the server couldn't render from it ($cause) — " +
          "falling back to baked PNG snapshots.",
      )

    /** A live-capable catalog that verified as unverified, so re-render is refused. */
    public fun unverifiedNoRerender(): ServeDegradation =
      ServeDegradation(
        UNVERIFIED_NO_RERENDER,
        "This catalog is unverified, so the server won't re-render it (fail-closed) — showing " +
          "baked PNG snapshots only.",
      )
  }
}
