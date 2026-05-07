package ee.schimke.composeai.daemon

import android.content.Intent
import androidx.activity.ComponentActivity
import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.daemon.protocol.DataProductAttachment
import ee.schimke.composeai.daemon.protocol.DataProductCapability
import ee.schimke.composeai.daemon.protocol.DataProductTransport
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Wire payload for the `data/navigation` data product. Captures the held activity's launch
 * `Intent` (action / data URI / categories / simple-typed extras) and the registered
 * back-pressed-callback state — what an agent needs to verify deep-link routing landed and that
 * the screen wired up an `OnBackPressedCallback` for the predictive-back gesture.
 *
 * **Robolectric caveat.** `ActivityScenarioRule<ComponentActivity>` launches the activity with the
 * default `MAIN`/`LAUNCHER` intent under Robolectric, so production renders typically see
 * `intent.action = "android.intent.action.MAIN"` and an empty extras bag — the producer ships the
 * surface anyway, so non-Robolectric backends (or tests that override the launch intent) can
 * populate it meaningfully without adding a new wire field later.
 */
@Serializable
data class NavigationPayload(
  val intent: NavigationIntent? = null,
  val onBackPressed: NavigationBackPressedState,
)

@Serializable
data class NavigationIntent(
  /** Intent action — e.g. `"android.intent.action.VIEW"` for a deep-link Intent. */
  val action: String? = null,
  /** Intent data URI as a string — `null` when the intent has no data. */
  val dataUri: String? = null,
  /** Explicit MIME type set on the intent, when present. */
  val type: String? = null,
  /** ComponentName.flattenToShortString — `pkg/.Activity` form when set explicitly. */
  val component: String? = null,
  /** Restricted-package, when [Intent.setPackage] was called. */
  val packageName: String? = null,
  /** Bitmask of `Intent.FLAG_*` flags. */
  val flags: Int = 0,
  /** Categories added via [Intent.addCategory]. */
  val categories: List<String> = emptyList(),
  /**
   * Extras keyed by string. Only simple types (String, Boolean, Int, Long, Float, Double) make it
   * across the wire — Parcelables, byte arrays, and nested Bundles are dropped because the JSON
   * payload would have to inline a Parcelable serialiser, and there's no path to round-trip them
   * back into an Intent on the agent side. Agents that need to verify a Parcelable extra can use
   * its `toString()` via a follow-up renderer-side custom check.
   */
  val extras: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class NavigationBackPressedState(
  /**
   * Mirror of [`androidx.activity.OnBackPressedDispatcher.hasEnabledCallbacks`]. `true` means a
   * `BackHandler { … }` (Compose) or `OnBackPressedCallback(enabled = true)` (View) has registered
   * with the activity's dispatcher. When `false`, a back press would fall through to the
   * activity's default behaviour (finish / pop the task).
   */
  val hasEnabledCallbacks: Boolean,
)

/**
 * Producer for `data/navigation`. Reads the held activity's `getIntent()` and the
 * `OnBackPressedDispatcher` state at post-capture time and writes a JSON snapshot next to the
 * preview's other data products.
 *
 * Pure post-capture: the activity reference is threaded through
 * [RenderDataArtifactContextKeys.HeldActivity] by [RenderEngine]. Mirrors
 * [I18nTranslationsDataProducer]'s shape (in-module producer + registry; no separate connector
 * module since navigation is Android-only and there are no shared cross-platform types).
 */
object NavigationDataProducer {
  const val KIND: String = "data/navigation"
  const val SCHEMA_VERSION: Int = 1
  const val FILE: String = "navigation.json"

  private val json = Json {
    encodeDefaults = false
    prettyPrint = false
  }

  fun writeArtifacts(rootDir: File, previewId: String, activity: ComponentActivity) {
    val payload =
      NavigationPayload(
        intent = activity.intent?.toWireIntent(),
        onBackPressed =
          NavigationBackPressedState(
            hasEnabledCallbacks = activity.onBackPressedDispatcher.hasEnabledCallbacks()
          ),
      )
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    previewDir.resolve(FILE).writeText(json.encodeToString(payload))
  }

  internal fun Intent.toWireIntent(): NavigationIntent {
    val keys = extras?.keySet().orEmpty()
    val wireExtras: Map<String, JsonElement> =
      if (keys.isEmpty()) emptyMap()
      else
        buildMap {
          for (key in keys) {
            wireExtraFor(this@toWireIntent, key)?.let { put(key, it) }
          }
        }
    return NavigationIntent(
      action = action,
      dataUri = data?.toString(),
      type = type,
      component = component?.flattenToShortString(),
      packageName = `package`,
      flags = flags,
      categories = categories?.toList().orEmpty(),
      extras = wireExtras,
    )
  }

  /**
   * Read one extra from [intent] via its typed `get*Extra` accessor and emit a JSON-compatible
   * primitive. Goes through the typed accessors instead of a single `Bundle.get(key)` call
   * because under Robolectric the `Bundle` returned by `Intent.getExtras()` can be in a state
   * where `keySet()` knows the keys but `Bundle.get(key)` returns null (parcel not fully
   * materialised on the copy). The typed accessors take a different code path on the original
   * intent and don't have that hazard.
   *
   * Type detection uses two probes per integer / boolean / float type — one with a low sentinel,
   * one with a high one — so a real value that happens to equal a single sentinel can't be
   * misclassified. Strings are checked first (`getStringExtra` returns null for any non-string).
   * Returns `null` for unsupported types (Parcelables, byte arrays, nested Bundles); the data
   * product deliberately doesn't ship a Parcel serialiser to round-trip them.
   */
  private fun wireExtraFor(intent: Intent, key: String): JsonElement? {
    intent.getStringExtra(key)?.let { return JsonPrimitive(it) }
    if (probeBoolean(intent, key)) return JsonPrimitive(intent.getBooleanExtra(key, false))
    if (probeInt(intent, key)) return JsonPrimitive(intent.getIntExtra(key, 0))
    if (probeLong(intent, key)) return JsonPrimitive(intent.getLongExtra(key, 0L))
    if (probeFloat(intent, key)) return JsonPrimitive(intent.getFloatExtra(key, 0f))
    if (probeDouble(intent, key)) return JsonPrimitive(intent.getDoubleExtra(key, 0.0))
    return null
  }

  private fun probeBoolean(intent: Intent, key: String): Boolean =
    intent.getBooleanExtra(key, false) == intent.getBooleanExtra(key, true)

  private fun probeInt(intent: Intent, key: String): Boolean =
    intent.getIntExtra(key, Int.MIN_VALUE) == intent.getIntExtra(key, Int.MAX_VALUE)

  private fun probeLong(intent: Intent, key: String): Boolean =
    intent.getLongExtra(key, Long.MIN_VALUE) == intent.getLongExtra(key, Long.MAX_VALUE)

  private fun probeFloat(intent: Intent, key: String): Boolean =
    intent.getFloatExtra(key, Float.NEGATIVE_INFINITY) ==
      intent.getFloatExtra(key, Float.POSITIVE_INFINITY)

  private fun probeDouble(intent: Intent, key: String): Boolean =
    intent.getDoubleExtra(key, Double.NEGATIVE_INFINITY) ==
      intent.getDoubleExtra(key, Double.POSITIVE_INFINITY)
}

/** Registry side for `data/navigation`; reads the latest JSON artefact from disk. */
class NavigationDataProductRegistry(private val rootDir: File) : DataProductRegistry {
  private val json = Json { ignoreUnknownKeys = true }

  override val capabilities: List<DataProductCapability> =
    listOf(
      DataProductCapability(
        kind = NavigationDataProducer.KIND,
        schemaVersion = NavigationDataProducer.SCHEMA_VERSION,
        transport = DataProductTransport.PATH,
        attachable = true,
        fetchable = true,
        requiresRerender = false,
      )
    )

  override fun fetch(
    previewId: String,
    kind: String,
    params: JsonElement?,
    inline: Boolean,
  ): DataProductRegistry.Outcome {
    if (kind != NavigationDataProducer.KIND) return DataProductRegistry.Outcome.Unknown
    val file = fileFor(previewId)
    if (!file.exists()) return DataProductRegistry.Outcome.NotAvailable
    if (!inline) {
      return DataProductRegistry.Outcome.Ok(
        DataFetchResult(
          kind = kind,
          schemaVersion = NavigationDataProducer.SCHEMA_VERSION,
          path = file.absolutePath,
        )
      )
    }
    val payload: JsonObject =
      try {
        json.parseToJsonElement(file.readText()) as JsonObject
      } catch (t: Throwable) {
        return DataProductRegistry.Outcome.FetchFailed(
          message = "could not parse $kind for $previewId: ${t.message}"
        )
      }
    return DataProductRegistry.Outcome.Ok(
      DataFetchResult(
        kind = kind,
        schemaVersion = NavigationDataProducer.SCHEMA_VERSION,
        payload = payload,
      )
    )
  }

  override fun attachmentsFor(previewId: String, kinds: Set<String>): List<DataProductAttachment> {
    if (NavigationDataProducer.KIND !in kinds) return emptyList()
    val file = fileFor(previewId)
    if (!file.exists()) return emptyList()
    return listOf(
      DataProductAttachment(
        kind = NavigationDataProducer.KIND,
        schemaVersion = NavigationDataProducer.SCHEMA_VERSION,
        path = file.absolutePath,
      )
    )
  }

  private fun fileFor(previewId: String): File =
    rootDir.resolve(previewId).resolve(NavigationDataProducer.FILE)
}
