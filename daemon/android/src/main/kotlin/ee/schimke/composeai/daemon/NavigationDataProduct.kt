package ee.schimke.composeai.daemon

import android.content.Intent
import androidx.activity.ComponentActivity
import ee.schimke.composeai.data.navigation.NavigationBackPressedState
import ee.schimke.composeai.data.navigation.NavigationDataProduct
import ee.schimke.composeai.data.navigation.NavigationIntent
import ee.schimke.composeai.data.navigation.NavigationPayload
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Producer for `data/navigation`. Reads the held activity's `getIntent()` and the
 * `OnBackPressedDispatcher` state at post-capture time and writes a JSON snapshot next to the
 * preview's other data products. Android-side only — the registry that serves the file back lives
 * in `:data-navigation-connector` (JVM, consumed by both `:daemon:android` and `:daemon:desktop`
 * per issue #1201).
 *
 * Pure post-capture: the activity reference is threaded through
 * [RenderDataArtifactContextKeys.HeldActivity] by [RenderEngine].
 */
object NavigationDataProducer {
  /** @see NavigationDataProduct.KIND */
  const val KIND: String = NavigationDataProduct.KIND

  /** @see NavigationDataProduct.SCHEMA_VERSION */
  const val SCHEMA_VERSION: Int = NavigationDataProduct.SCHEMA_VERSION

  /** @see NavigationDataProduct.FILE */
  const val FILE: String = NavigationDataProduct.FILE

  private val json = Json {
    encodeDefaults = false
    prettyPrint = false
  }

  fun writeArtifacts(
    rootDir: File,
    previewId: String,
    activity: ComponentActivity,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    val payload =
      NavigationPayload(
        intent = activity.intent?.toWireIntent(),
        onBackPressed =
          NavigationBackPressedState(
            hasEnabledCallbacks = activity.onBackPressedDispatcher.hasEnabledCallbacks()
          ),
      )
    val previewDir = rootDir.resolve(previewId).also { it.mkdirs() }
    fileSystem.write(previewDir.resolve(FILE).path.toPath()) {
      writeUtf8(json.encodeToString(payload))
    }
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
   * primitive. Goes through the typed accessors instead of a single `Bundle.get(key)` call because
   * under Robolectric the `Bundle` returned by `Intent.getExtras()` can be in a state where
   * `keySet()` knows the keys but `Bundle.get(key)` returns null (parcel not fully materialised on
   * the copy). The typed accessors take a different code path on the original intent and don't have
   * that hazard.
   *
   * Type detection uses two probes per integer / boolean / float type — one with a low sentinel,
   * one with a high one — so a real value that happens to equal a single sentinel can't be
   * misclassified. Strings are checked first (`getStringExtra` returns null for any non-string).
   * Returns `null` for unsupported types (Parcelables, byte arrays, nested Bundles); the data
   * product deliberately doesn't ship a Parcel serialiser to round-trip them.
   */
  private fun wireExtraFor(intent: Intent, key: String): JsonElement? {
    intent.getStringExtra(key)?.let {
      return JsonPrimitive(it)
    }
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
