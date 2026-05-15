package ee.schimke.composeai.data.render.extensions

/**
 * Shared state for one preview-render invocation. Extensions opt into the session via the
 * [appliedExtensionIds] set the host passes in; once applied, a [RenderSessionParticipant] is
 * allowed to mutate the session (CompositionLocals, environment flags) via [configureSession], and
 * to assert preconditions via [validateSession] before its data-producing hooks run.
 *
 * Pure-data — no Compose runtime, no platform types. Concrete state buckets the host wires up (e.g.
 * a list of `CompositionLocal` overrides on Android) live on platform-specific subclasses /
 * holders; this interface is the generic surface every backend can rely on.
 */
interface RenderSession {
  /**
   * Ids of every data extension the host has opted into for this session. The Robolectric runner
   * derives this from `composeai.session.extensions`; the daemon derives it from the active
   * `RenderSpec`/subscription state. Extensions consult this to decide whether they're "on" — no
   * standalone toggle is plumbed through the gradle plugin or VS Code.
   */
  val appliedExtensionIds: Set<DataExtensionId>

  /** Convenience predicate matching by id string. */
  fun isApplied(id: DataExtensionId): Boolean = id in appliedExtensionIds
}

/**
 * Optional contract that a [DataExtension]/[PlannedDataExtension] can implement to participate in
 * session setup and validation. Default implementations are no-ops so existing extensions stay pure
 * data producers without ceremony.
 *
 * Lifecycle:
 * 1. The host builds a [RenderSession] from its applied-extension set.
 * 2. For every applied participant, the host calls [configureSession] — the participant registers
 *    CompositionLocal overrides, flips environment flags, or otherwise mutates session state.
 * 3. Before each per-render hook (post-capture process, frame extractor, etc.) the host calls
 *    [validateSession] — the participant throws (`IllegalStateException`) if the session is not set
 *    up for it. The canonical use is asserting `id in session.appliedExtensionIds` so a
 *    misconfigured render never silently produces data products without the matching opt-in.
 */
interface RenderSessionParticipant {
  /**
   * Called once during session setup if this participant's id is in
   * [RenderSession.appliedExtensionIds]. Implementations register any session-wide configuration
   * they require (e.g. CompositionLocal overrides). No-op by default.
   */
  fun configureSession(session: RenderSession) {}

  /**
   * Called before this participant's per-render hooks run. Throws if the session is not in a valid
   * state for the participant to operate — typically because the participant's id is not in
   * [RenderSession.appliedExtensionIds] (i.e. the host invoked the hook without opting the
   * extension in). Default: no-op (always valid).
   */
  fun validateSession(session: RenderSession) {}
}

/** Minimal, immutable [RenderSession] implementation backed by an explicit applied-id set. */
data class SimpleRenderSession(override val appliedExtensionIds: Set<DataExtensionId>) :
  RenderSession {
  companion object {
    /**
     * Parse a comma- or semicolon-separated string of extension ids (the format the gradle plugin
     * forwards via `composeai.session.extensions`) into a [SimpleRenderSession]. Blank / null input
     * yields an empty session.
     */
    fun fromIdList(raw: String?): SimpleRenderSession {
      if (raw.isNullOrBlank()) return SimpleRenderSession(emptySet())
      val ids =
        raw
          .split(',', ';')
          .map { it.trim() }
          .filter { it.isNotEmpty() }
          .map { DataExtensionId(it) }
          .toSet()
      return SimpleRenderSession(ids)
    }
  }
}
