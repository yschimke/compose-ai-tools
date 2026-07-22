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
