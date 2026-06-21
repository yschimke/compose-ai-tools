package ee.schimke.composeai.fakeemulator

import java.nio.charset.StandardCharsets

/**
 * The fake emulator's tiny shell. A real device runs thousands of commands; we answer only the few
 * that matter for **device detection** (`getprop`, `wm size`, `echo`, `id`) and **preview launch**
 * (`am start … PreviewActivity`), plus `screencap` for screenshots. Everything else returns empty
 * output with exit 0 so detection scripts don't choke.
 */
class ShellInterpreter(
  private val properties: Map<String, String>,
  private val frameSource: FrameSource,
  private val previewLauncher: PreviewLauncher,
) {
  /** stdout/stderr are raw bytes because `screencap -p` returns binary PNG. */
  class Result(val stdout: ByteArray, val stderr: ByteArray = ByteArray(0), val exitCode: Int = 0)

  fun execute(commandLine: String): Result {
    val tokens = tokenize(commandLine)
    if (tokens.isEmpty()) return ok("")
    return when (tokens[0]) {
      "getprop" -> getprop(tokens.drop(1))
      "am" -> am(tokens.drop(1))
      "screencap" -> screencap(tokens.drop(1))
      "wm" -> wm(tokens.drop(1))
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
    if (args.firstOrNull() == "size") {
      return ok("Physical size: ${frameSource.display.width}x${frameSource.display.height}\n")
    }
    if (args.firstOrNull() == "density") {
      return ok("Physical density: ${frameSource.display.densityDpi}\n")
    }
    return ok("")
  }

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
