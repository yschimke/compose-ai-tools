package ee.schimke.composeai.data.render

/**
 * The Compose runtime's rewritten `SlotTable` — the "link buffer" composer — as a render-lane
 * opt-in.
 *
 * ## What this is
 *
 * The Compose Runtime team reimplemented the `SlotTable` to improve random-write performance in
 * composition, and shipped it in Compose 1.12.0 behind a global opt-in
 * (`ComposeRuntimeFlags.isLinkBufferComposerEnabled`, present on the 1.11.x line too). It is
 * scheduled to become the default and then to lose its flag entirely, so the interesting question
 * for this repo is a *correctness* one: does a preview render to the same pixels under the new
 * composer as under the old one? Every rendered catalog here is a committed, diffable corpus of
 * Compose output — flipping one flag and re-rendering turns that corpus into a regression suite for
 * the rewrite, which is exactly the feedback the runtime team asked for.
 *
 * Off by default *in the published plugin* — a testing knob, not a behaviour change: nothing
 * renders differently in a consumer's project until it asks for it. This repo is the exception and
 * sets `composePreview.linkBufferComposer=true` in its own `gradle.properties`, so every catalog we
 * render exercises the new composer (see `docs/LINK_BUFFER_COMPOSER.md`). Keep the two straight
 * when reasoning about a render here: an unqualified `./gradlew …composePreviewRenderAll` in THIS
 * repo is a new-composer run, and the old composer is what needs the explicit
 * `-PcomposePreview.linkBufferComposer=false`.
 *
 * ## Why reflection
 *
 * `:data-render-core` is a pure-JVM module with no Compose dependency, and it is consumed by both
 * render lanes — the Android/Robolectric renderer (which deliberately compiles its public API
 * against the older `compose-bom-compat` floor so consumers on Compose 1.9.x can call it without
 * `NoSuchMethodError`, see `docs/RENDERER_COMPATIBILITY.md`) and the Desktop/CMP renderer (whose
 * runtime is JetBrains' build of the same `androidx.compose.runtime` classes). A compile-time
 * reference to `ComposeRuntimeFlags` would raise the renderer's Compose floor for every consumer in
 * order to serve an opt-in almost nobody switches on. Same shape as the coil probe in
 * `ShadowAsyncImagePainter` and the tracing probe in `ComposeRuntimeTracingAvailability`: reach the
 * optional API through the classloader that actually has it, and stay loadable when it is absent.
 *
 * ## Ordering
 *
 * The flag is read by the runtime when a composition starts, so it has to be set **before any
 * content is composed** in that JVM — and, under Robolectric, before any content is composed in
 * that *sandbox classloader*, since each sandbox gets its own copy of the static. Call
 * [applyIfRequested] at the top of a render entry point, not from inside a composable. Applying it
 * twice with the same value is a no-op, which is what makes it safe on the reused lanes (the pooled
 * desktop worker, the daemon, a shard's reused Robolectric sandbox).
 *
 * A lane that renders many previews per JVM therefore cannot honour a *per-preview* value — the
 * first composition fixes it. The knob is deliberately whole-run only, matching the runtime team's
 * own framing ("set the flag before you compose any content").
 *
 * ## Configuring it
 *
 * `-Dcomposeai.render.linkBufferComposer=true` on the render JVM, forwarded by the Gradle plugin
 * from `-PcomposePreview.linkBufferComposer=true` or the `composePreview.linkBufferComposer` DSL
 * value.
 */
object LinkBufferComposer {

  /** System property that opts a render JVM into the rewritten `SlotTable`. */
  const val PROPERTY: String = "composeai.render.linkBufferComposer"

  /** Fully-qualified name of the runtime's flag holder. */
  const val FLAGS_CLASS: String = "androidx.compose.runtime.ComposeRuntimeFlags"

  /** Name of the static `Boolean` field on [FLAGS_CLASS]. */
  const val FLAG_FIELD: String = "isLinkBufferComposerEnabled"

  /** What [applyIfRequested] did, so a caller can log it once per JVM rather than per capture. */
  sealed interface Outcome {
    /** Not requested — the runtime keeps whatever default it ships with. */
    object NotRequested : Outcome

    /** The flag was set to `true` on this classloader's copy of [FLAGS_CLASS]. */
    object Enabled : Outcome
  }

  /**
   * Whether [raw] asks for the new composer. Absent / blank means no.
   *
   * Strict about the value for the same reason `PreviewClock` is strict about its instant: a typo
   * in a flag whose whole purpose is "render everything again and tell me if the pixels moved"
   * would otherwise report a clean run that never enabled anything.
   *
   * @throws IllegalArgumentException when [raw] is set but is not a boolean.
   */
  fun requested(raw: String? = System.getProperty(PROPERTY)): Boolean {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return false
    return value.lowercase().toBooleanStrictOrNull()
      ?: throw IllegalArgumentException(
        "compose-preview: -D$PROPERTY=$value is not a boolean. Use 'true' to render with the " +
          "rewritten Compose SlotTable (ComposeRuntimeFlags.$FLAG_FIELD), or 'false'/unset for " +
          "the runtime's own default."
      )
  }

  /**
   * Applies the opt-in to [classLoader]'s copy of the Compose runtime, and reports what happened.
   *
   * Safe and cheap to call on every render: when the property is unset this is one `getProperty`
   * and nothing else, and when it is set the assignment is idempotent.
   *
   * @throws IllegalStateException when the opt-in is requested but the runtime on [classLoader] has
   *   no such flag — an older Compose, or a future one that has finished the migration and removed
   *   it. Failing loudly is the point: a silently-ignored opt-in would produce a full set of
   *   renders that "tested" the new composer without ever enabling it.
   * @throws IllegalArgumentException when the property is set to a non-boolean (see [requested]).
   */
  @JvmOverloads
  fun applyIfRequested(
    classLoader: ClassLoader =
      Thread.currentThread().contextClassLoader ?: LinkBufferComposer::class.java.classLoader,
    raw: String? = System.getProperty(PROPERTY),
  ): Outcome {
    if (!requested(raw)) return Outcome.NotRequested
    val field = runCatching {
      Class.forName(FLAGS_CLASS, /* initialize= */ true, classLoader)
    }
      .mapCatching { it.getDeclaredField(FLAG_FIELD) }
      .getOrElse { failure ->
        throw IllegalStateException(
          "compose-preview: -D$PROPERTY=true was requested, but this render's Compose runtime " +
            "has no $FLAGS_CLASS.$FLAG_FIELD. The rewritten SlotTable opt-in needs Compose " +
            "1.11.x or newer, and is removed again once the new composer becomes the only " +
            "implementation. Drop the flag, or move the module to a Compose version that has it.",
          failure,
        )
      }
    field.isAccessible = true
    field.setBoolean(/* obj= */ null, true)
    return Outcome.Enabled
  }

  private val announced = java.util.concurrent.atomic.AtomicBoolean(false)

  /**
   * [applyIfRequested], reduced to a one-line notice for a render log.
   *
   * `null` when nothing was requested — an ordinary run stays exactly as quiet as it was before
   * this existed — and `null` again on every call after the first that enabled it, so the lanes
   * that render many previews per JVM (the pooled desktop worker, the daemon, a shard reusing its
   * Robolectric sandbox) announce the opt-in once rather than per capture. "Once" is per copy of
   * this class, which is per Robolectric sandbox on the Android lane: the same granularity the flag
   * itself has.
   */
  @JvmStatic
  fun applyAndDescribe(
    classLoader: ClassLoader =
      Thread.currentThread().contextClassLoader ?: LinkBufferComposer::class.java.classLoader
  ): String? =
    when (applyIfRequested(classLoader)) {
      Outcome.NotRequested -> null
      Outcome.Enabled ->
        if (announced.compareAndSet(false, true))
          "compose-preview: rendering with the rewritten Compose SlotTable " +
            "($FLAGS_CLASS.$FLAG_FIELD=true)"
        else null
    }
}
