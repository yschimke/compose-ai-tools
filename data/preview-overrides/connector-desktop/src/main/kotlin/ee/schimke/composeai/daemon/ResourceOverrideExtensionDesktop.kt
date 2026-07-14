package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.render.extensions.DataExtension
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableExtension
import ee.schimke.composeai.overrides.PreviewOverrideController
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.LocalResourceReader

/**
 * Always-on desktop extension that makes every
 * `org.jetbrains.compose.resources.stringResource(...)` a preview loads an editable override knob,
 * without the author writing `previewOverrideString(...)`.
 *
 * It wraps [LocalResourceReader] with a [RecordingResourceReader] whose sink records each resolved
 * string as a `compose/overrides` declaration on the process-static [PreviewOverrideController] and
 * substitutes the daemon-seeded replacement (the seed rides the same `namedOverrides` map the
 * portable [PreviewOverridesOverrideExtension] already installs, keyed by the reader's `res:` key).
 * Because the recorded knobs live on the same controller and product, this connector introduces no
 * new data product — it just widens what shows up under `compose/overrides`.
 *
 * **Per-render reset.** `remember(this)` runs once per render (the planner mints a fresh instance
 * per render, mirroring the pseudolocale desktop extension) and drops the previous render's
 * resource knobs ([PreviewOverrideController.resetResourceDeclarations]) so this render
 * re-discovers its own set. This is deliberately separate from the explicit-knob
 * `clearDeclarations` the named-override extension runs post-composition: the resource reader
 * records eagerly while resources load (an async coroutine dispatcher on desktop), so folding the
 * two lifecycles together would let the explicit-knob clear erase a resource knob mid-render.
 *
 * **Cache clear keyed on the seed.** CMP keeps a process-wide string-item cache
 * ([ResourceOverrideStringCache]) that short-circuits the wrapped reader once a key is warm, so the
 * clear can't run just once at render start: the seed for a `res:` key is installed by the sibling
 * `PreviewOverridesOverrideExtension` from a `DisposableEffect`, which runs *after* the first
 * composition. If we only cleared on entry, the first `stringResource` read would happen before the
 * seed lands — recording/returning the default and warming the cache with it — and the
 * recomposition that `set(seed)` triggers would hit that cached default instead of re-invoking this
 * reader, so editing an auto-discovered string would never affect the render until a later clear /
 * JVM restart (Codex review on #2477). Instead we read [PreviewOverrideController.seededValues] as
 * snapshot state and clear the cache in a `remember(seed)`: when the seed changes, this composable
 * recomposes and clears the cache *before* `content()` re-reads, so the next `stringResource`
 * misses the cache and the reader resolves against the now-present seed. Unlike the resource
 * reader, the explicit `previewOverride*` host reads the controller's seeded state directly during
 * composition, so it has no CMP cache to invalidate.
 *
 * Runs in [DataExtensionPhase.OuterEnvironment] so [LocalResourceReader] is swapped before user
 * preview content reaches a `stringResource(...)` call, alongside the named-override host.
 */
@OptIn(ExperimentalResourceApi::class)
class ResourceOverrideExtensionDesktop :
  AroundComposableExtension(
    id = ID,
    constraints = DataExtensionConstraints(phase = DataExtensionPhase.OuterEnvironment),
  ) {

  private val sink = ResourceOverrideSink { key, default ->
    PreviewOverrideController.resolveResourceString(key, default, label = default)
  }

  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    remember(this) { PreviewOverrideController.resetResourceDeclarations() }
    // Snapshot read: recomposes this scope when the sibling override extension seeds
    // `namedOverrides`
    // (or a later edit changes it), so the cache clear below re-runs before `content()` re-reads.
    val seed = PreviewOverrideController.seededValues.value
    remember(seed) { ResourceOverrideStringCache.clearBestEffort() }
    val baseReader = LocalResourceReader.current
    val wrappedReader = remember(baseReader) { RecordingResourceReader(baseReader, sink) }
    CompositionLocalProvider(LocalResourceReader provides wrappedReader) { content() }
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId("data-resource-overrides")
  }
}

/**
 * Always-on planner for [ResourceOverrideExtensionDesktop]. Returns a fresh extension on every
 * render (including no-override renders — see [AlwaysOnPreviewOverrideExtension]) so the resource
 * reader is installed unconditionally; the actual replacement values ride the named-override seed
 * the portable connector already threads through, so this planner ignores the request bag.
 */
class ResourceOverridePreviewOverrideExtensionDesktop :
  DataExtension<PreviewOverrides>, AlwaysOnPreviewOverrideExtension {
  override val id: DataExtensionId = ResourceOverrideExtensionDesktop.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension =
    ResourceOverrideExtensionDesktop()
}
