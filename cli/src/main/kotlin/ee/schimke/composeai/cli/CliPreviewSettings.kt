package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The defaults a person sets once in `~/.compose-preview/settings.json` — the file the MCP server's
 * `settings_update` writes (compose-preview-server#1242, `PreviewSettings.kt`) — reduced to the
 * keys the CLI has an equivalent for. The CLI only **reads** the file; writing it is the server's
 * job.
 *
 * ```json
 * { "schema": "compose-preview-settings/v1", "values": { "darkTheme": true, "locale": "fr" } }
 * ```
 *
 * Every field has the server's "not set" sentinel as its default ([PREVIEW_DEVICE], `false`, `0`,
 * `""`), and a sentinel contributes nothing, so a missing file behaves exactly like an empty one.
 *
 * Precedence is the server's: an explicit CLI flag beats a setting, which beats the built-in
 * default. [fillOverrides] and [matrixAxis] implement that by only filling what the caller left
 * unset.
 *
 * The validation rules mirror `PreviewSettings.updated` key for key, so a value the server would
 * refuse is refused here too. Keys the CLI does not use (`renderResult`, `imageToModel`,
 * `replicasPerDaemon`, `uiBuilderMcpAppLayout`, or anything a newer writer adds) are ignored
 * without a warning: they are valid in the file, just not ours to act on.
 */
data class CliPreviewSettings(
  /** `id:<device>` applied as the device, or [PREVIEW_DEVICE] for each preview's own. */
  val device: String = PREVIEW_DEVICE,
  /** Force dark mode; off leaves each preview's own mode (it never forces light). */
  val darkTheme: Boolean = false,
  /** Font-scale multiplier; `0` leaves each preview's own scale. */
  val fontScale: Double = 0.0,
  /** BCP-47 locale tag; empty leaves each preview's own locale. */
  val locale: String = "",
) {
  /** True when no setting is away from its sentinel, i.e. nothing for the CLI to apply. */
  val isDefault: Boolean
    get() = this == CliPreviewSettings()

  /** The keys that are set, in schema order — for messages naming what was (not) applied. */
  fun setKeys(): List<String> = buildList {
    if (device != PREVIEW_DEVICE) add("$DEVICE=$device")
    if (darkTheme) add("$DARK_THEME=true")
    if (fontScale > 0.0) add("$FONT_SCALE=${formatScale(fontScale)}")
    if (locale.isNotBlank()) add("$LOCALE=$locale")
  }

  /**
   * [explicit] with each setting filled in where it is silent — the CLI twin of the server's
   * `applyToRenderPreview` overrides step (`putIfAbsent`). A field the caller set always wins; a
   * setting at its sentinel adds nothing.
   */
  fun fillOverrides(explicit: PreviewOverrides?): PreviewOverrides? {
    val base = explicit ?: PreviewOverrides()
    val filled =
      base.copy(
        device = base.device ?: device.takeIf { it != PREVIEW_DEVICE },
        uiMode = base.uiMode ?: UiMode.DARK.takeIf { darkTheme },
        fontScale = base.fontScale ?: fontScale.takeIf { it > 0.0 }?.toFloat(),
        localeTag = base.localeTag ?: locale.takeIf { it.isNotBlank() },
      )
    return if (explicit == null && filled == base) null else filled
  }

  /**
   * One `render-matrix` axis: [flagValues] when the flag was passed, else the setting's single
   * value, else null (the axis is not varied). [axis] is one of [DEVICE], [DARK_THEME],
   * [FONT_SCALE], [LOCALE].
   */
  fun matrixAxis(axis: String, flagValues: List<String>?): List<String>? {
    if (flagValues != null) return flagValues
    val fromSetting =
      when (axis) {
        DEVICE -> device.takeIf { it != PREVIEW_DEVICE }
        DARK_THEME -> "dark".takeIf { darkTheme }
        FONT_SCALE -> fontScale.takeIf { it > 0.0 }?.let(::formatScale)
        LOCALE -> locale.takeIf { it.isNotBlank() }
        else -> throw IllegalArgumentException("unknown matrix axis '$axis'")
      }
    return fromSetting?.let(::listOf)
  }

  companion object {
    const val DEVICE: String = "device"
    const val DARK_THEME: String = "darkTheme"
    const val FONT_SCALE: String = "fontScale"
    const val LOCALE: String = "locale"

    /** [device] value that keeps each preview's own device. */
    const val PREVIEW_DEVICE: String = "preview"

    /** The server's `PreviewSettings.MAX_FONT_SCALE`. */
    const val MAX_FONT_SCALE: Double = 3.0

    /** The server's `PreviewSettings.LOCALE_PATTERN`: a 2–3 letter language, optional subtags. */
    private val LOCALE_REGEX = Regex("^$|^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")

    /** `preview` plus `id:<device>` for every device the daemon's catalog resolves. */
    private val DEVICE_VALUES: Set<String> by lazy {
      setOf(PREVIEW_DEVICE) +
        DeviceDimensions.KNOWN_DEVICE_IDS.map { if (it.startsWith("id:")) it else "id:$it" }
    }

    private fun formatScale(scale: Double): String =
      if (scale % 1.0 == 0.0) "${scale.toLong()}.0" else scale.toString()

    /**
     * Settings from a stored `values` object: each known key that is valid, the default for the
     * rest. A bad value warns and keeps its default without dropping the other keys — the server's
     * `fromStored` rule. Unknown keys are skipped silently.
     */
    fun fromValues(values: JsonObject, warn: (String) -> Unit = {}): CliPreviewSettings {
      var out = CliPreviewSettings()
      for ((key, raw) in values) {
        val value = raw as? JsonPrimitive
        fun bad(want: String) = warn("$key: expected $want, got $raw; using the default")
        when (key) {
          DEVICE ->
            value
              ?.takeIf { it.isString }
              ?.content
              ?.takeIf { it in DEVICE_VALUES }
              ?.let { out = out.copy(device = it) }
              ?: bad("one of `compose-preview devices`' ids as 'id:<id>', or '$PREVIEW_DEVICE'")
          DARK_THEME ->
            value?.takeIf { !it.isString }?.booleanOrNull?.let { out = out.copy(darkTheme = it) }
              ?: bad("a boolean")
          FONT_SCALE ->
            value
              ?.takeIf { !it.isString }
              ?.doubleOrNull
              ?.takeIf { it in 0.0..MAX_FONT_SCALE }
              ?.let { out = out.copy(fontScale = it) } ?: bad("a number from 0 to $MAX_FONT_SCALE")
          LOCALE ->
            value
              ?.takeIf { it.isString }
              ?.content
              ?.takeIf { LOCALE_REGEX.matches(it) }
              ?.let { out = out.copy(locale = it) }
              ?: bad("a BCP-47 tag such as 'fr' or 'ja-JP', or empty")
          else -> Unit // A server-only or newer key: valid in the file, not ours to apply.
        }
      }
      return out
    }
  }
}

/**
 * Reads the shared settings file: [FILE_ENV] when set, else `~/.compose-preview/settings.json` —
 * the same resolution as the server's `PreviewSettingsStore.defaultFile`.
 *
 * Missing file → defaults, silently. Unreadable or malformed file → one warning and defaults; a
 * settings file must never stop a render.
 */
object CliPreviewSettingsFile {
  const val SCHEMA: String = "compose-preview-settings/v1"
  const val FILE_ENV: String = "COMPOSE_PREVIEW_SETTINGS_FILE"

  private val json = Json { ignoreUnknownKeys = true }

  fun defaultFile(
    environment: Map<String, String> = System.getenv(),
    userHome: File = File(System.getProperty("user.home") ?: "."),
  ): File =
    environment[FILE_ENV]?.takeIf { it.isNotBlank() }?.let(::File)
      ?: File(userHome, ".compose-preview/settings.json")

  fun read(
    file: File = defaultFile(),
    warn: (String) -> Unit = { System.err.println("compose-preview: warning: $it") },
  ): CliPreviewSettings {
    if (!file.isFile) return CliPreviewSettings()
    val values = runCatching {
      val root = json.parseToJsonElement(file.readText())
      require(root is JsonObject) { "not a JSON object" }
      when (val v: JsonElement? = root["values"]) {
        null -> JsonObject(emptyMap())
        is JsonObject -> v
        else -> throw IllegalArgumentException("'values' is not an object")
      }
    }
      .getOrElse {
        warn("${file.path}: unreadable settings (${it.message}); using defaults")
        return CliPreviewSettings()
      }
    return CliPreviewSettings.fromValues(values, warn = { warn("${file.path}: $it") })
  }
}
