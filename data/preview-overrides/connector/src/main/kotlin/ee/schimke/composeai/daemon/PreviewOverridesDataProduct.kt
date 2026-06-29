package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.overrides.PreviewOverridesPayload
import ee.schimke.composeai.data.overrides.PreviewOverridesProduct
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.extensions.DataExtension
import ee.schimke.composeai.data.render.extensions.DataExtensionCapability
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableExtension
import ee.schimke.composeai.overrides.ControllerPreviewOverrideHost
import ee.schimke.composeai.overrides.LocalPreviewOverrideHost
import ee.schimke.composeai.overrides.PreviewOverrideController
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * `AroundComposable` extension that owns the plain-Compose named-override surface. **Always
 * active** — the planner emits an instance for every render so [LocalPreviewOverrideHost] is in
 * scope whether or not the client sent `renderNow.overrides.namedOverrides`. Mirrors
 * `RemoteComposeOverrideExtension`.
 *
 * On enter (and whenever [seed] changes) it seeds the process-static [PreviewOverrideController]
 * with the daemon-supplied replacement values and clears any declarations recorded by a prior
 * render. The clear lives in a [DisposableEffect] (a `RememberObserver`); Compose invokes every
 * `RememberObserver` before any `SideEffect`, and the `previewOverride*` lookups record their
 * declarations from a `SideEffect`, so the clear always precedes this render's declarations — even
 * when a held interactive session re-renders with a smaller list (stale indexed knobs drop). On
 * dispose it resets the controller so the next preview starts fresh.
 *
 * Runs in [DataExtensionPhase.OuterEnvironment] so the composition local is in place before user
 * preview content reaches a `previewOverride*` call.
 */
class PreviewOverridesOverrideExtension(
  private val seed: Map<String, PreviewOverrideValue>? = null
) :
  AroundComposableExtension(
    id = ID,
    constraints =
      DataExtensionConstraints(
        phase = DataExtensionPhase.OuterEnvironment,
        provides = setOf(DataExtensionCapability(PreviewOverridesProduct.KIND)),
      ),
  ) {
  @Composable
  override fun AroundComposable(content: @Composable () -> Unit) {
    DisposableEffect(seed) {
      PreviewOverrideController.set(seed)
      PreviewOverrideController.clearDeclarations()
      onDispose { PreviewOverrideController.resetForNewSession() }
    }
    CompositionLocalProvider(LocalPreviewOverrideHost provides ControllerPreviewOverrideHost) {
      content()
    }
  }

  companion object {
    val ID: DataExtensionId = DataExtensionId(PreviewOverridesProduct.KIND)
  }
}

/**
 * Planner mapping `renderNow.overrides.namedOverrides` to a [PreviewOverridesOverrideExtension].
 * **Always** returns a non-null extension (like `RemoteComposePreviewOverrideExtension`) so the
 * composition local is installed on every render — a preview that only later begins calling
 * `previewOverride*` still finds the host wired without needing a fresh override.
 */
class PreviewOverridesPreviewOverrideExtension : DataExtension<PreviewOverrides> {
  override val id: DataExtensionId = PreviewOverridesOverrideExtension.ID

  override fun plan(request: PreviewOverrides): PlannedDataExtension =
    PreviewOverridesOverrideExtension(seed = request.namedOverrides)
}

/**
 * Daemon-side registry for `compose/overrides`. After a render it snapshots the editable knobs the
 * preview declared (from [PreviewOverrideController]) into a per-preview [PreviewOverridesPayload];
 * a `data/fetch` then returns that set so a client (or, once carried into a bundle sidecar, a
 * detached viewer) can present editable controls. Before any render — or after a render that
 * declared nothing — it returns [DataProductRegistry.Outcome.NotAvailable]. Mirrors
 * `RemoteComposeDataProductRegistry`.
 */
class PreviewOverridesDataProductRegistry : DataProductRegistry {
  private val latest = ConcurrentHashMap<String, PreviewOverridesPayload>()

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        transport = DataProductTransport.INLINE,
        attachable = true,
        fetchable = true,
        // Editing a knob sends a fresh `renderNow.overrides.namedOverrides`, which re-renders
        // anyway;
        // the declarations themselves are a by-product of that render, not something to re-render
        // for.
        requiresRerender = false,
      )
    )

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    if (kind != KIND) return DataProductRegistry.Outcome.Unknown
    val payload = latest[previewId] ?: return DataProductRegistry.Outcome.NotAvailable
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        payload = json.encodeToJsonElement(PreviewOverridesPayload.serializer(), payload),
      )
    )
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (KIND !in kinds) return emptyList()
    val payload = latest[previewId] ?: return emptyList()
    return listOf(
      DataProductAttachment(
        kind = KIND,
        schemaVersion = SCHEMA_VERSION,
        payload = json.encodeToJsonElement(PreviewOverridesPayload.serializer(), payload),
      )
    )
  }

  override fun onRender(previewId: String, result: RenderResult) {
    onRender(previewId, result, overrides = null, previewContext = result.previewContext)
  }

  override fun onRender(
    previewId: String,
    result: RenderResult,
    overrides: PreviewOverrides?,
    previewContext: PreviewContext?,
  ) {
    val declarations = PreviewOverrideController.declarations()
    if (declarations.isEmpty()) {
      latest.remove(previewId)
      return
    }
    latest[previewId] = PreviewOverridesPayload(declarations = declarations)
  }

  companion object {
    const val KIND: String = PreviewOverridesProduct.KIND
    const val SCHEMA_VERSION: Int = PreviewOverridesProduct.SCHEMA_VERSION

    private val json = Json {
      encodeDefaults = true
      prettyPrint = false
    }
  }
}
