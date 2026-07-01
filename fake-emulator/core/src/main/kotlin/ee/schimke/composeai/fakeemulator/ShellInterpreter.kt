package ee.schimke.composeai.fakeemulator

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * The fake emulator's tiny shell. A real device runs thousands of commands; we answer only the few
 * that matter for **device detection** (`getprop`, `wm size`, `echo`, `id`), **preview launch**
 * (`am start … PreviewActivity`), `screencap` for screenshots, and the **device-settings** commands
 * Android Studio issues to drive its emulator UI toggles (`cmd uimode night`, `settings put system
 * font_scale`, `wm density|size`, TalkBack via `settings put secure …accessibility…`, color
 * correction, locale, layout bounds) — each translated into a [DeviceSettings] change so the app
 * can re-render the preview under that override. Everything else returns empty output with exit 0.
 */
class ShellInterpreter(
  private val properties: Map<String, String>,
  private val frameSource: FrameSource,
  private val previewLauncher: PreviewLauncher,
  private val settings: DeviceSettingsController = DeviceSettingsController(),
  /** Records `adb install` / Studio deploys (captured APK bytes + parsed package). */
  val apkStore: ApkStore = ApkStore(),
) {
  /** stdout/stderr are raw bytes because `screencap -p` returns binary PNG. */
  class Result(val stdout: ByteArray, val stderr: ByteArray = ByteArray(0), val exitCode: Int = 0)

  /** Backing store for `settings get/put/delete`, keyed `<namespace>.<key>`. */
  private val settingsStore = java.util.concurrent.ConcurrentHashMap<String, String>()

  /**
   * [stdin] carries the piped APK for streaming installs (`pm install -S`); null for most commands.
   */
  fun execute(commandLine: String, stdin: InputStream? = null): Result =
    executeArgv(tokenize(commandLine), stdin)

  /** Argv entry point — used directly by `abb_exec:` (NUL-separated args, no shell tokenizing). */
  fun executeArgv(tokens: List<String>, stdin: InputStream? = null): Result {
    if (tokens.isEmpty()) return ok("")
    return when (tokens[0]) {
      "getprop" -> getprop(tokens.drop(1))
      "am" -> am(tokens.drop(1))
      "pm" -> packageCommand(tokens.drop(1), stdin)
      "screencap" -> screencap(tokens.drop(1))
      "wm" -> wm(tokens.drop(1))
      "cmd" -> cmd(tokens.drop(1), stdin)
      "settings" -> settingsCmd(tokens.drop(1))
      "setprop" -> setprop(tokens.drop(1))
      "echo" -> ok(tokens.drop(1).joinToString(" ") + "\n")
      "id" -> ok("uid=0(root) gid=0(root) groups=0(root) context=u:r:su:s0\n")
      "true" -> ok("")
      "false" -> Result(ByteArray(0), exitCode = 1)
      else -> ok("") // unknown command — succeed quietly
    }
  }

  private fun getprop(args: List<String>): Result {
    if (args.isNotEmpty()) {
      // `getprop <name>` prints just that value (empty line if unset).
      return ok((properties[args[0]] ?: "") + "\n")
    }
    // `getprop` (no args) prints every prop in `[key]: [value]` form.
    val sb = StringBuilder()
    for ((k, v) in properties.toSortedMap()) sb
      .append("[")
      .append(k)
      .append("]: [")
      .append(v)
      .append("]\n")
    return ok(sb.toString())
  }

  private fun am(args: List<String>): Result {
    if (args.firstOrNull() != "start") return ok("")
    val intent = AmStart.parse(args.drop(1))
    val sb = StringBuilder()
    sb.append("Starting: Intent { ")
    intent.component?.let { sb.append("cmp=").append(it).append(' ') }
    sb.append("}\n")
    val launch = AmStart.toPreviewLaunch(intent)
    if (launch != null) {
      when (val result = previewLauncher.launch(launch)) {
        is PreviewLaunchResult.Launched -> Unit
        is PreviewLaunchResult.Rejected ->
          sb.append("Warning: preview launch rejected: ").append(result.reason).append('\n')
      }
    }
    return ok(sb.toString())
  }

  private fun screencap(args: List<String>): Result {
    // We only ever produce PNG; `-p` is the PNG flag, anything else still gets PNG.
    val frame = frameSource.latest()
    val png =
      frame?.png
        ?: PlaceholderImage.solidPng(
          frameSource.display.width,
          frameSource.display.height,
          0xFF202124.toInt(),
        )
    return Result(png)
  }

  private fun wm(args: List<String>): Result {
    when (args.firstOrNull()) {
      "size" -> {
        val arg = args.getOrNull(1)
        return when {
          arg == null -> {
            val w = settings.current.widthPx ?: frameSource.display.width
            val h = settings.current.heightPx ?: frameSource.display.height
            ok("Physical size: ${w}x${h}\n")
          }
          arg == "reset" -> {
            settings.update { it.copy(widthPx = null, heightPx = null) }
            ok("")
          }
          else -> {
            val wh = parseSize(arg)
            if (wh != null) settings.update { it.copy(widthPx = wh.first, heightPx = wh.second) }
            ok("")
          }
        }
      }
      "density" -> {
        val arg = args.getOrNull(1)
        return when {
          arg == null ->
            ok(
              "Physical density: ${settings.current.densityDpi ?: frameSource.display.densityDpi}\n"
            )
          arg == "reset" -> {
            settings.update { it.copy(densityDpi = null) }
            ok("")
          }
          else -> {
            arg.toIntOrNull()?.let { dpi -> settings.update { s -> s.copy(densityDpi = dpi) } }
            ok("")
          }
        }
      }
      else -> return ok("")
    }
  }

  /**
   * `cmd uimode night yes|no|auto`, `cmd locale set-app-locales … --locales <tag>`, and `cmd
   * package install…` (the modern install service — same handlers `pm` uses).
   */
  private fun cmd(args: List<String>, stdin: InputStream?): Result {
    when (args.firstOrNull()) {
      "package" -> return packageCommand(args.drop(1), stdin)
      "uimode" ->
        if (args.getOrNull(1) == "night") {
          val mode =
            when (args.getOrNull(2)) {
              "yes" -> UiMode.DARK
              "no" -> UiMode.LIGHT
              else -> UiMode.UNSET // "auto"/"custom"
            }
          settings.update { it.copy(uiMode = mode) }
          return ok("Night mode: ${args.getOrNull(2) ?: "auto"}\n")
        }
      "locale" ->
        if (args.getOrNull(1) == "set-app-locales") {
          val idx = args.indexOf("--locales")
          val tag = args.getOrNull(idx + 1)?.takeIf { idx >= 0 }?.substringBefore(',')
          if (!tag.isNullOrBlank()) settings.update { it.copy(localeTag = tag) }
          return ok("")
        }
    }
    return ok("")
  }

  /**
   * The install surface of `pm` / `cmd package`. We accept the APK (legacy pushed path, single-shot
   * `-S <size>` stream, or the `install-create`/`install-write`/`install-commit` session flow),
   * record it via [ApkStore], and reply with the `Success` line the real `pm` prints so `adb
   * install` and Studio's deploy don't error. We don't execute anything — the APK is metadata +
   * discovery.
   */
  private fun packageCommand(args: List<String>, stdin: InputStream?): Result {
    return when (args.firstOrNull()) {
      "install" -> installSingle(args.drop(1), stdin)
      "install-create" -> ok("Success: created install session [${apkStore.createSession()}]\n")
      "install-write" -> installWrite(args.drop(1), stdin)
      "install-commit" -> {
        args.getOrNull(1)?.let { apkStore.commitSession(it) }
        ok("Success\n")
      }
      "install-abandon" -> {
        args.getOrNull(1)?.let { apkStore.abandonSession(it) }
        ok("Success\n")
      }
      "list" -> if (args.getOrNull(1) == "packages") listPackages() else ok("")
      else -> ok("")
    }
  }

  /** `pm install [-flags] (-S <size> | <pushed-path>)`. */
  private fun installSingle(args: List<String>, stdin: InputStream?): Result {
    val sizeIndex = args.indexOf("-S")
    if (sizeIndex >= 0) {
      val size = args.getOrNull(sizeIndex + 1)?.toLongOrNull() ?: return failure("bad -S size")
      val bytes = readExactly(stdin, size) ?: return failure("no install stream")
      apkStore.install(bytes, ApkStore.Transport.STREAMING)
      return ok("Success\n")
    }
    // Legacy: install the APK a prior `sync: SEND` pushed to <path> (last non-flag arg).
    val path = args.lastOrNull { !it.startsWith("-") }
    val bytes = path?.let { apkStore.takePushedFile(it) }
    if (bytes != null) apkStore.install(bytes, ApkStore.Transport.LEGACY_PUSH)
    return ok("Success\n")
  }

  /** `pm install-write [-S <size>] <session> <split> [-|<path>]`. */
  private fun installWrite(args: List<String>, stdin: InputStream?): Result {
    val sizeIndex = args.indexOf("-S")
    if (sizeIndex >= 0) {
      val size = args.getOrNull(sizeIndex + 1)?.toLongOrNull() ?: return failure("bad -S size")
      val session = args.getOrNull(sizeIndex + 2) ?: return failure("no session id")
      val bytes = readExactly(stdin, size) ?: return failure("no install stream")
      apkStore.writeSession(session, bytes)
      return ok("Success: streamed ${bytes.size} bytes\n")
    }
    val session = args.getOrNull(0) ?: return failure("no session id")
    val bytes = args.getOrNull(2)?.let { apkStore.takePushedFile(it) }
    if (bytes != null) apkStore.writeSession(session, bytes)
    return ok("Success: streamed ${bytes?.size ?: 0} bytes\n")
  }

  private fun listPackages(): Result {
    val sb = StringBuilder()
    for (apk in apkStore.installed()) apk.packageName?.let {
      sb.append("package:").append(it).append('\n')
    }
    return ok(sb.toString())
  }

  /** Read exactly [size] bytes of the piped APK from [input] (short at early EOF). */
  private fun readExactly(input: InputStream?, size: Long): ByteArray? {
    if (input == null) return null
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var remaining = size
    while (remaining > 0) {
      val n = input.read(buf, 0, remaining.coerceAtMost(buf.size.toLong()).toInt())
      if (n < 0) break
      out.write(buf, 0, n)
      remaining -= n
    }
    return out.toByteArray()
  }

  /** `pm`/`cmd package` failure line, mirroring pm's `Failure [<reason>]` on stdout. */
  private fun failure(reason: String): Result = ok("Failure [$reason]\n")

  /** `settings put|get|delete <namespace> <key> [value]`. */
  private fun settingsCmd(args: List<String>): Result {
    val verb = args.getOrNull(0)
    val namespace = args.getOrNull(1)
    val key = args.getOrNull(2)
    return when (verb) {
      "put" -> {
        val value = args.getOrNull(3) ?: return ok("")
        if (namespace != null && key != null) {
          settingsStore["$namespace.$key"] = value
          applySetting(namespace, key, value)
        }
        ok("")
      }
      "delete" -> {
        if (namespace != null && key != null) {
          settingsStore.remove("$namespace.$key")
          applySetting(namespace, key, null)
        }
        ok("")
      }
      "get" -> ok((settingsStore["$namespace.$key"] ?: "null") + "\n")
      else -> ok("")
    }
  }

  private fun setprop(args: List<String>): Result {
    val key = args.getOrNull(0)
    val value = args.getOrNull(1) ?: ""
    if (key == "debug.layout") settings.update { it.copy(showLayoutBounds = truthy(value)) }
    return ok("")
  }

  /** Map one changed `settings` key onto the [DeviceSettings] field it drives. */
  private fun applySetting(namespace: String, key: String, value: String?) {
    when (namespace to key) {
      "system" to "font_scale" -> settings.update { it.copy(fontScale = value?.toFloatOrNull()) }
      "system" to "user_rotation" ->
        settings.update {
          it.copy(rotation = RotationQuadrant.fromUserRotation(value?.toIntOrNull() ?: 0))
        }
      "system" to "system_locales" ->
        settings.update { it.copy(localeTag = value?.substringBefore(',')?.ifBlank { null }) }
      // Accessibility (TalkBack) + color correction are computed from several secure keys.
      "secure" to "accessibility_enabled",
      "secure" to "enabled_accessibility_services",
      "secure" to "accessibility_display_inversion_enabled",
      "secure" to "accessibility_display_daltonizer_enabled",
      "secure" to "accessibility_display_daltonizer" ->
        settings.update { it.copy(talkBack = computeTalkBack(), colorMode = computeColorMode()) }
    }
  }

  private fun computeTalkBack(): Boolean {
    val enabled = settingsStore["secure.accessibility_enabled"] == "1"
    val services = settingsStore["secure.enabled_accessibility_services"].orEmpty()
    val hasReader =
      services.isNotBlank() &&
        services != "null" &&
        services.contains("talkback", ignoreCase = true)
    return enabled && hasReader
  }

  private fun computeColorMode(): ColorMode {
    if (settingsStore["secure.accessibility_display_inversion_enabled"] == "1")
      return ColorMode.INVERTED
    if (settingsStore["secure.accessibility_display_daltonizer_enabled"] != "1")
      return ColorMode.NONE
    return when (settingsStore["secure.accessibility_display_daltonizer"]) {
      "0" -> ColorMode.PROTANOMALY
      "1" -> ColorMode.DEUTERANOMALY
      "2" -> ColorMode.TRITANOMALY
      "12",
      "11" -> ColorMode.GRAYSCALE
      else -> ColorMode.DEUTERANOMALY
    }
  }

  private fun parseSize(value: String): Pair<Int, Int>? {
    val parts = value.split('x', 'X')
    if (parts.size != 2) return null
    val w = parts[0].trim().toIntOrNull() ?: return null
    val h = parts[1].trim().toIntOrNull() ?: return null
    return w to h
  }

  private fun truthy(value: String): Boolean =
    value.equals("true", ignoreCase = true) ||
      value == "1" ||
      value.equals("yes", ignoreCase = true)

  private fun ok(text: String) = Result(text.toByteArray(StandardCharsets.UTF_8))

  companion object {
    /**
     * Split a shell command line into argv, honouring single and double quotes and backslash
     * escapes. Good enough for the argv `adb shell` / Studio actually send; not a full POSIX shell.
     */
    fun tokenize(line: String): List<String> {
      val tokens = mutableListOf<String>()
      val current = StringBuilder()
      var inSingle = false
      var inDouble = false
      var started = false
      var i = 0
      while (i < line.length) {
        val c = line[i]
        when {
          inSingle -> if (c == '\'') inSingle = false else current.append(c).also { started = true }
          inDouble ->
            when (c) {
              '"' -> inDouble = false
              '\\' ->
                if (i + 1 < line.length) {
                  current.append(line[++i])
                  started = true
                } else current.append(c).also { started = true }
              else -> current.append(c).also { started = true }
            }
          c == '\'' -> {
            inSingle = true
            started = true
          }
          c == '"' -> {
            inDouble = true
            started = true
          }
          c == '\\' ->
            if (i + 1 < line.length) {
              current.append(line[++i])
              started = true
            } else current.append(c).also { started = true }
          c.isWhitespace() -> {
            if (started) {
              tokens.add(current.toString())
              current.setLength(0)
              started = false
            }
          }
          else -> current.append(c).also { started = true }
        }
        i++
      }
      if (started) tokens.add(current.toString())
      return tokens
    }
  }
}

/** Parses the `am start` argv into the intent fields we care about. */
object AmStart {
  const val PREVIEW_ACTIVITY = "androidx.compose.ui.tooling.PreviewActivity"

  data class Intent(val component: String?, val stringExtras: Map<String, String>)

  fun parse(args: List<String>): Intent {
    var component: String? = null
    val extras = LinkedHashMap<String, String>()
    var i = 0
    while (i < args.size) {
      when (args[i]) {
        "-n" -> if (i + 1 < args.size) component = args[++i]
        "--es" ->
          if (i + 2 < args.size) {
            extras[args[i + 1]] = args[i + 2]
            i += 2
          }
        else -> Unit
      }
      i++
    }
    return Intent(component, extras)
  }

  /**
   * Build a [PreviewLaunchRequest] from a parsed intent, or `null` when it isn't a PreviewActivity
   * launch carrying a `composable` extra.
   */
  fun toPreviewLaunch(intent: Intent): PreviewLaunchRequest? {
    val activity = intent.component?.substringAfter('/', "") ?: return null
    if (!activity.endsWith("PreviewActivity")) return null
    val fqn = intent.stringExtras["composable"] ?: return null
    return PreviewLaunchRequest(
      composableFqn = fqn,
      parameterProviderClassName = intent.stringExtras["parameterProviderClassName"],
      component = intent.component,
      extras = intent.stringExtras,
    )
  }
}
