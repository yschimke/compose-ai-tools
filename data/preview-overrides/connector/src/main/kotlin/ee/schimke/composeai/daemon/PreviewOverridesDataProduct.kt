package ee.schimke.composeai.daemon

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideValue
import ee.schimke.composeai.data.overrides.PreviewOverridesPayload
import ee.schimke.composeai.data.overrides.dedupeResourceOverrideDeclarations
import ee.schimke.composeai.data.overrides.PreviewOverridesProduct
import ee.schimke.composeai.data.render.PreviewContext
import ee.schimke.composeai.data.render.extensions.DataExtension
import ee.schimke.composeai.data.render.extensions.DataExtensionCapability
import ee.schimke.composeai.data.render.extensions.DataExtensionConstraints
import ee.schimke.composeai.data.render.extensions.DataExtensionHookKind
import ee.schimke.composeai.data.render.extensions.DataExtensionId
import ee.schimke.composeai.data.render.extensions.DataExtensionPhase
import ee.schimke.composeai.data.render.extensions.PlannedDataExtension
import ee.schimke.composeai.data.render.extensions.compose.AroundComposableHook
import ee.schimke.composeai.data.render.extensions.compose.ExtensionComposeContext
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
) : AroundComposableHook {

  override val id: DataExtensionId = ID

  override val hooks: Set<DataExtensionHookKind> = setOf(DataExtensionHookKind.AroundComposable)

  override val constraints: DataExtensionConstraints =
    DataExtensionConstraints(
      phase = DataExtensionPhase.OuterEnvironment,
      provides = setOf(DataExtensionCapability(PreviewOverridesProduct.KIND)),
    )

  @Composable
  override fun Around(context: ExtensionComposeContext, content: @Composable () -> Unit) {
    // Stamp the active previewId before content composes so the sandbox-side `record` forwards land
    // in this preview's bridge scope, not a concurrently-rendering preview's (pooled sandboxes).
    // Plain call (not a SideEffect) so it runs during composition, ahead of any `previewOverride*`
    // lookup in `content()`.
    PreviewOverrideController.beginRender(context.previewId)
    DisposableEffect(seed) {
      PreviewOverrideController.set(seed)
      // Reset the bridge scope at render *start* (not on dispose): a render's declarations must
      // survive until the host-side registry reads them post-render. `clearDeclarations` resets
      // this
      // preview's bridge entries, then the `record` SideEffects repopulate them. On dispose only
      // the
      // seed is cleared (mirrors `PermissionsOverrideExtension`) — disposing the bridge here would
      // race the host's `data/fetch` read and drop the declarations (caught by
      // `PreviewOverridesDataFetchE2ETest`).
      PreviewOverrideController.clearDeclarations()
      onDispose { PreviewOverrideController.set(null) }
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
class PreviewOverridesPreviewOverrideExtension :
  DataExtension<PreviewOverrides>, AlwaysOnPreviewOverrideExtension {
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
    // Read the declarations the sandbox stamped under the render's own previewId (the exact key the
    // controller's bridge forward used); fall back to the registry's `previewId` argument when no
    // context is attached (connector-only tests, where the bridge is absent and the in-CL
    // controller
    // answers). Mirrors `PermissionsDataProductRegistry.onRender`.
    val scope = previewContext?.previewId ?: previewId
    val declarations = readDeclarationsAcrossClassloaders(scope)
    if (declarations.isEmpty()) {
      latest.remove(previewId)
      return
    }
    latest[previewId] = PreviewOverridesPayload(declarations = declarations)
  }

  /**
   * Read the declared knobs with cross-classloader awareness. On the Android daemon the registry
   * runs in the host classloader while `previewOverride*`-driven `record` writes land in the
   * *sandbox* classloader's [PreviewOverrideController] static state — a different `static` per
   * classloader, so the host-CL controller's `declarations()` is empty even though knobs were
   * declared. The do-not-acquire `SandboxPreviewOverridesBridge` is shared across the boundary, so
   * we prefer it when reachable (its JSON snapshot is decoded back into typed declarations). The
   * fallback to [PreviewOverrideController.declarations] keeps connector-only unit tests and the
   * desktop daemon (no sandbox, no bridge) working unchanged — there the in-CL controller IS the
   * source of truth.
   */
  private fun readDeclarationsAcrossClassloaders(scope: String): List<PreviewOverrideDeclaration> {
    val bridge = SandboxPreviewOverridesBridgeReader.tryLoad()
    // `declarations()` already merges + dedupes the explicit and resource-string buckets. The bridge
    // path decodes raw per-key JSON snapshots that never went through it, so dedupe that list too
    // (a no-op until the Android sandbox bridge also forwards resource knobs).
    val controllerDeclarations = PreviewOverrideController.declarations()
    if (bridge == null) return controllerDeclarations
    val bridgeJson = bridge.snapshot(scope)
    if (bridgeJson.isEmpty()) return controllerDeclarations
    val decoded =
      bridgeJson.mapNotNull { entry ->
        try {
          json.decodeFromString(PreviewOverrideDeclaration.serializer(), entry)
        } catch (_: Exception) {
          null
        }
      }
    return dedupeResourceOverrideDeclarations(decoded)
  }

  /**
   * Reflective lookup of `ee.schimke.composeai.daemon.bridge.SandboxPreviewOverridesBridge`. Cached
   * per JVM. `null` means the bridge isn't on the classpath (connector-only unit tests; the desktop
   * daemon) — the registry falls back to the in-classloader controller state. Mirrors
   * `PermissionsDataProductRegistry`'s `SandboxPermissionsBridgeReader`.
   */
  private class SandboxPreviewOverridesBridgeReader(
    private val snapshotMethod: java.lang.reflect.Method
  ) {
    fun snapshot(scope: String): List<String> =
      try {
        @Suppress("UNCHECKED_CAST") (snapshotMethod.invoke(null, scope) as Array<String>).toList()
      } catch (_: ReflectiveOperationException) {
        emptyList()
      }

    companion object {
      private const val BRIDGE_FQN: String =
        "ee.schimke.composeai.daemon.bridge.SandboxPreviewOverridesBridge"

      @Volatile private var resolved: SandboxPreviewOverridesBridgeReader? = null
      @Volatile private var resolutionAttempted: Boolean = false

      fun tryLoad(): SandboxPreviewOverridesBridgeReader? {
        if (resolutionAttempted) return resolved
        synchronized(this) {
          if (resolutionAttempted) return resolved
          val r =
            try {
              val cls =
                Class.forName(
                  BRIDGE_FQN,
                  true,
                  PreviewOverridesDataProductRegistry::class.java.classLoader,
                )
              SandboxPreviewOverridesBridgeReader(
                snapshotMethod = cls.getMethod("snapshot", String::class.java)
              )
            } catch (_: ClassNotFoundException) {
              null
            } catch (_: NoSuchMethodException) {
              null
            }
          resolved = r
          resolutionAttempted = true
          return r
        }
      }
    }
  }

  companion object {
    const val KIND: String = PreviewOverridesProduct.KIND
    const val SCHEMA_VERSION: Int = PreviewOverridesProduct.SCHEMA_VERSION

    private val json = Json {
      encodeDefaults = true
      prettyPrint = false
      ignoreUnknownKeys = true
    }
  }
}
