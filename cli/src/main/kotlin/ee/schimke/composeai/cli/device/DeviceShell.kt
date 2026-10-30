package ee.schimke.composeai.cli.device

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Where a shell command writes: stdout and stderr, kept apart for the v2 shell protocol. */
internal class ShellOutput(val stdout: OutputStream, val stderr: OutputStream) {
  fun write(bytes: ByteArray) = stdout.write(bytes)

  fun line(text: String, exit: Int = 0): Int {
    stdout.write((text + "\n").toByteArray())
    return exit
  }

  fun err(text: String) = stderr.write((text + "\n").toByteArray())

  /** An error on stderr, exit 1. */
  fun fail(message: String): Int {
    err(message)
    return 1
  }

  /** `pm`'s failure line, on stdout where `adb install` reads it. */
  fun failure(code: String, message: String? = null): Int =
    line(if (message == null) "Failure [$code]" else "Failure [$code: $message]", exit = 1)
}

/**
 * `adb shell` on the virtual device. A line is split as `sh` would split it, `;` / `&&` / `||`
 * sequences and `| grep` / `| head` filters included, and each command is answered by the device
 * itself — packages, `getprop`, files under `/data/local/tmp` and `/sdcard`, `screencap` — or by
 * the simulator's own adb translation (`am`, `input`, `settings`, `wm`, `dumpsys`, `logcat`,
 * `run-as`, `content`, `svc`, `cmd`…, see `docs/SIMULATOR.md`), whose JSON answer is printed as the
 * text a device prints where tools read it: `am start`'s `Starting: Intent { … }`, `Broadcast
 * completed`, a value's own line.
 */
internal class DeviceShell(private val device: VirtualDevice) {

  @Volatile private var lastExit = 0

  /** Runs one shell line; returns its exit code. */
  fun run(line: String, stdin: InputStream, out: ShellOutput): Int {
    val pipeline =
      try {
        ShellSyntax.parse(line.replace("\$?", lastExit.toString()))
      } catch (e: IllegalArgumentException) {
        return out.fail("/system/bin/sh: ${e.message}")
      }
    var exit = 0
    for (step in pipeline) {
      if (step.after == ShellSyntax.Joiner.AND && exit != 0) continue
      if (step.after == ShellSyntax.Joiner.OR && exit == 0) continue
      exit = runPipe(step, stdin, out)
      lastExit = exit
    }
    return exit
  }

  private fun runPipe(step: ShellSyntax.Step, stdin: InputStream, out: ShellOutput): Int {
    val first = step.commands.first()
    val filters = step.commands.drop(1)
    val buffered = filters.isNotEmpty() || step.redirect != null
    val capture = ByteArrayOutputStream()
    val target =
      if (buffered) ShellOutput(capture, if (step.discardErrors) NULL else out.stderr)
      else if (step.discardErrors) ShellOutput(out.stdout, NULL) else out
    val exit =
      try {
        command(first, stdin, target)
      } catch (e: SimulatorLink.SimulatorError) {
        target.fail("Error: ${e.message}")
      } catch (e: Exception) {
        target.fail("${first.firstOrNull()}: ${e.message ?: e}")
      }
    if (!buffered) return exit
    var bytes = capture.toByteArray()
    for (filter in filters) bytes =
      filter(filter, bytes) ?: return out.fail("${filter.first()}: not supported in a pipe")
    val redirect = step.redirect
    if (redirect == null) {
      out.write(bytes)
    } else if (redirect != "/dev/null") {
      val file =
        device.storage.resolve(redirect)
          ?: return out.fail("sh: can't create $redirect: Read-only file system")
      file.parentFile.mkdirs()
      if (step.append) file.appendBytes(bytes) else file.writeBytes(bytes)
    }
    return exit
  }

  private fun filter(command: List<String>, input: ByteArray): ByteArray? {
    val lines = String(input).lines().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
    val kept =
      when (command.first()) {
        "grep" -> {
          val invert = "-v" in command
          val ignoreCase = "-i" in command
          val pattern = command.drop(1).lastOrNull { !it.startsWith("-") } ?: return null
          val regex = Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
          lines.filter { regex.containsMatchIn(it) != invert }
        }
        "head" -> lines.take(lineCount(command))
        "tail" -> lines.takeLast(lineCount(command))
        "cat" -> lines
        else -> return null
      }
    return if (kept.isEmpty()) ByteArray(0) else (kept.joinToString("\n") + "\n").toByteArray()
  }

  /** `head` / `tail`'s `-n N` or `-N`; ten lines without either. */
  private fun lineCount(command: List<String>): Int {
    val n = command.indexOf("-n")
    if (n >= 0) return command.getOrNull(n + 1)?.toIntOrNull() ?: 10
    return command.firstNotNullOfOrNull {
      if (it.startsWith("-")) it.drop(1).toIntOrNull() else null
    } ?: 10
  }

  private fun command(argv: List<String>, stdin: InputStream, out: ShellOutput): Int {
    if (argv.isEmpty()) return 0
    val name = argv[0].removePrefix("/system/bin/").removePrefix("/system/xbin/")
    val args = argv.drop(1)
    return when (name) {
      "echo" -> {
        val noNewline = args.firstOrNull() == "-n"
        val text = (if (noNewline) args.drop(1) else args).joinToString(" ")
        if (noNewline) out.write(text.toByteArray()).let { 0 } else out.line(text)
      }
      "true",
      "sync",
      "sleep",
      "exit" -> args.firstOrNull()?.toIntOrNull()?.takeIf { name == "exit" } ?: 0
      "false" -> 1
      "id" -> out.line("uid=2000(shell) gid=2000(shell) groups=2000(shell) context=u:r:shell:s0")
      "whoami" -> out.line("shell")
      "uname" ->
        out.line(
          if ("-a" in args) "Linux localhost 6.1.0 #1 SMP PREEMPT aarch64 Toybox" else "Linux"
        )
      "getprop" -> getprop(args, out)
      "logcat" -> logcat(args, stdin, out)
      "date" -> date(args, out)
      "setprop",
      // `adb logcat` sends `export ANDROID_LOG_TAGS="''"; exec logcat …`.
      "export" -> 0
      "exec" -> command(args, stdin, out)
      "pm" -> packageManager(argv, args, stdin, out)
      "cmd" ->
        when (args.firstOrNull()) {
          "package" -> packageManager(argv, args.drop(1), stdin, out)
          "activity" -> activity(listOf("am") + args.drop(1), args.drop(1), out)
          else -> simulator(argv, out)
        }
      "am" -> activity(argv, args, out)
      "screencap" -> screencap(args, out)
      "ls" -> files.ls(args, out)
      "mkdir" -> files.mkdir(args, out)
      "rm" -> files.rm(args, out)
      "cat" -> files.cat(args, out)
      "touch" -> files.touch(args, out)
      "stat" -> files.stat(args, out)
      "chmod",
      "chown",
      "chgrp",
      "restorecon" -> files.exists(args.lastOrNull(), out)
      "test",
      "[" -> files.test(args.filter { it != "]" })
      else -> {
        if (name.startsWith("/")) {
          val file = device.storage.resolve(name)
          if (file != null && file.isFile) {
            out.err("/system/bin/sh: $name: can't execute: the simulator runs no native binaries")
            126
          } else {
            out.err("/system/bin/sh: $name: inaccessible or not found")
            127
          }
        } else {
          simulator(argv, out)
        }
      }
    }
  }

  private val files = DeviceFiles(device)

  /**
   * `logcat`: dumps (`-d`, `-t`), clears (`-c`), or prints the log and then follows it — each new
   * entry as it is logged, in the format asked for, until the client closes the stream. Android
   * Studio's Logcat window follows with `-v long -v epoch`.
   */
  private fun logcat(args: List<String>, stdin: InputStream, out: ShellOutput): Int {
    val options =
      try {
        LogcatOptions.parse(args)
      } catch (e: IllegalArgumentException) {
        return out.fail("logcat: ${e.message}")
      }
    if (options.op == LogcatOptions.Op.CLEAR) {
      device.simulator().request("logcat", "op" to JsonPrimitive("clear"))
      return 0
    }
    val incoming = LinkedBlockingQueue<LogcatEntry>()
    // Subscribed before the dump, so nothing logged in between is missed.
    val following =
      if (options.op == LogcatOptions.Op.FOLLOW) device.followLog { incoming.put(it) } else null
    try {
      var printed = 0
      fun print(entry: LogcatEntry): Boolean {
        out.write(options.print(entry).toByteArray())
        printed++
        return options.max == null || printed < options.max
      }
      val dump = device.simulator().request(options.dumpRequest())
      for (element in dump["entries"] as? JsonArray ?: JsonArray(emptyList())) {
        if (!print(LogcatEntry.of(element as JsonObject))) return 0
      }
      out.stdout.flush()
      if (following == null) return 0
      // The client closing the stream is the end of stdin: logcat runs until then.
      val closed = AtomicBoolean()
      Thread(
          {
            runCatching { while (stdin.read() >= 0) Unit }
            closed.set(true)
          },
          "logcat-stdin",
        )
        .apply {
          isDaemon = true
          start()
        }
      while (!closed.get() && !device.exit.isDone) {
        val entry = incoming.poll(250, TimeUnit.MILLISECONDS) ?: continue
        if (!options.matches(entry)) continue
        val more = print(entry)
        out.stdout.flush()
        if (!more) return 0
      }
      return 0
    } catch (_: IOException) {
      // The client went away mid-write.
      return 0
    } finally {
      following?.close()
    }
  }

  /** `date`: now, or `date +%s` (what Android Studio's Logcat asks first) in epoch seconds. */
  private fun date(args: List<String>, out: ShellOutput): Int {
    val now = ZonedDateTime.now()
    return when (args.firstOrNull()) {
      null ->
        out.line(
          now.format(DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss zzz yyyy", Locale.ROOT))
        )
      "+%s" -> out.line(now.toEpochSecond().toString())
      else -> out.fail("date: only date and date +%s are supported")
    }
  }

  private fun getprop(args: List<String>, out: ShellOutput): Int {
    val properties = device.properties()
    if (args.isEmpty()) {
      for ((k, v) in properties) out.line("[$k]: [$v]")
      return 0
    }
    return out.line(properties[args[0]] ?: args.getOrNull(1) ?: "")
  }

  private fun packageManager(
    argv: List<String>,
    args: List<String>,
    stdin: InputStream,
    out: ShellOutput,
  ): Int {
    if (args.firstOrNull() == "resolve-activity") return resolveActivity(args.drop(1), out)
    return device.packages.run(args, stdin, out) ?: simulator(listOf("pm") + args, out)
  }

  /**
   * `pm resolve-activity --brief <package>`: the installed app's launcher activity, as tools look
   * up what to start. Only the launcher is resolved; an intent given with `-a` / `-c` / `-d`
   * resolves to it too.
   */
  private fun resolveActivity(args: List<String>, out: ShellOutput): Int {
    val pkg = args.lastOrNull { !it.startsWith("-") } ?: return out.line("No activity found")
    if (device.installed?.packageName != pkg) return out.line("No activity found")
    val launcher =
      (device.simulator().request("components")["launcher"] as? JsonPrimitive)?.contentOrNull
        ?: return out.line("No activity found")
    if ("--brief" in args) {
      out.line("priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true")
      return out.line("$pkg/$launcher")
    }
    return out.line("Activity Resolver Table:\n  $pkg/$launcher")
  }

  private fun activity(argv: List<String>, args: List<String>, out: ShellOutput): Int {
    when (args.firstOrNull()) {
      "get-current-user" -> return out.line("0")
      "get-config" -> return out.line("config: ${device.config.formFactor.qualifiers}")
    }
    return simulator(listOf("am") + args, out, errorsOnStdout = true)
  }

  private fun screencap(args: List<String>, out: ShellOutput): Int {
    val file = args.lastOrNull {
      !it.startsWith("-") && args.getOrNull(args.indexOf(it) - 1) != "-d"
    }
    val display = args.getOrNull(args.indexOf("-d") + 1)?.takeIf { "-d" in args }
    val result =
      device
        .simulator()
        .request(
          "screenshot",
          *listOfNotNull(
              display?.let { "display" to JsonPrimitive(it.toInt()) },
              "square" to JsonPrimitive(true),
            )
            .toTypedArray(),
        )
    val png = Base64.getDecoder().decode(result["png"]!!.jsonPrimitive.content)
    if (file == null) {
      out.write(png)
      return 0
    }
    val target =
      device.storage.resolve(file) ?: return out.fail("screencap: $file: Read-only file system")
    target.parentFile.mkdirs()
    target.writeBytes(png)
    return 0
  }

  /** The simulator's translation of an adb shell command, printed as a device prints it. */
  private fun simulator(
    argv: List<String>,
    out: ShellOutput,
    errorsOnStdout: Boolean = false,
  ): Int {
    val reply =
      try {
        device.simulator().request("shell", "args" to JsonArray(argv.map(::JsonPrimitive)))
      } catch (e: SimulatorLink.SimulatorError) {
        val message = AdbText.error(e.message ?: "failed")
        if (errorsOnStdout) out.line("Error: $message") else out.err(message)
        return if (message.contains("not supported") || message.contains("is not mapped")) 127
        else 1
      }
    val command = reply["command"] as? JsonObject ?: JsonObject(emptyMap())
    val result = reply["result"] as? JsonObject ?: JsonObject(emptyMap())
    for (line in AdbText.render(argv, command, result)) out.line(line)
    return 0
  }

  private companion object {
    val NULL: OutputStream = OutputStream.nullOutputStream()
  }
}

/** A JSON answer from the simulator, as the text a device's command prints. */
internal object AdbText {

  /**
   * The simulator's error as a device's tool would print it: without the exception class it carries
   * (`java.lang.IllegalArgumentException: `) and the frame after it (` at …`).
   */
  fun error(message: String): String =
    message
      .replace(Regex("""^(?:[a-z][\w.]*\.)+[A-Z]\w*(?:\$\w+)*: """), "")
      .replace(Regex(""" at [\w.$]+\([^)]*\)$"""), "")

  fun render(argv: List<String>, command: JsonObject, result: JsonObject): List<String> {
    if (argv.firstOrNull() == "wm" && command.string("cmd") == "display") {
      val width = (result["width"] as? JsonPrimitive)?.intOrNull
      val height = (result["height"] as? JsonPrimitive)?.intOrNull
      val density = (result["density"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
      return when (argv.getOrNull(1)) {
        "size" -> if (argv.size > 2) emptyList() else listOf("Physical size: ${width}x$height")
        "density" ->
          if (argv.size > 2) emptyList()
          else listOf("Physical density: ${density?.let { (it * 160).toInt() }}")
        else -> emptyList()
      }
    }
    result["output"]?.let { output ->
      return (output as? JsonPrimitive)?.contentOrNull?.lines()?.let {
        if (it.lastOrNull() == "") it.dropLast(1) else it
      } ?: listOf(output.toString())
    }
    return when (command.string("cmd")) {
      "startActivity" -> listOf("Starting: Intent { ${intent(command["intent"])} }")
      "startService" -> listOf("Starting service: Intent { ${intent(command["intent"])} }")
      "stopService" ->
        listOf("Stopping service: Intent { ${intent(command["intent"])} }", "Service stopped")
      "broadcast" ->
        listOf(
          "Broadcasting: Intent { ${intent(command["intent"])} }",
          "Broadcast completed: result=0",
        )
      "debugSurface" -> {
        val index = (result["index"] as? JsonPrimitive)?.intOrNull
        listOf(
          if (index != null) "Broadcast completed: result=1, data=\"Index=[$index]\""
          else "Broadcast completed: result=1"
        )
      }
      "forceStop",
      "kill",
      "tap",
      "swipe",
      "key",
      "text",
      "permissions",
      "settings",
      "qualifiers",
      "clearData" -> if (command.string("cmd") == "clearData") listOf("Success") else emptyList()
      "logcat" ->
        (result["lines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
          ?: emptyList()
      else -> listOf(Json.encodeToString(JsonElement.serializer(), result))
    }
  }

  private fun intent(element: JsonElement?): String {
    val intent = element as? JsonObject ?: return ""
    return buildList {
        intent.string("action")?.let { add("act=$it") }
        (intent["categories"] as? JsonArray)?.let { cats ->
          add("cat=[${cats.joinToString(",") { (it as JsonPrimitive).content }}]")
        }
        intent.string("data")?.let { add("dat=$it") }
        intent.string("type")?.let { add("typ=$it") }
        intent.string("package")?.let { add("pkg=$it") }
        intent.string("component")?.let { add("cmp=$it") }
      }
      .joinToString(" ")
  }

  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/**
 * Splits a shell line: words (quotes and backslashes as `sh` reads them), `;` `&&` `||` between
 * commands, `|` into a filter, and the redirections tools append (`2>/dev/null`, `2>&1`, `> file`).
 */
internal object ShellSyntax {

  enum class Joiner {
    ALWAYS,
    AND,
    OR,
  }

  /** One `cmd | filter …` group, run when [after] says, given the previous group's exit. */
  data class Step(
    val commands: List<List<String>>,
    val after: Joiner,
    val redirect: String? = null,
    val append: Boolean = false,
    val discardErrors: Boolean = false,
  )

  fun parse(line: String): List<Step> {
    val steps = mutableListOf<Step>()
    var commands = mutableListOf<List<String>>()
    var words = mutableListOf<String>()
    var joiner = Joiner.ALWAYS
    var redirect: String? = null
    var append = false
    var discardErrors = false
    val word = StringBuilder()
    var inWord = false
    var pendingRedirect: String? = null // ">" or ">>" or "2>"

    fun endWord() {
      if (!inWord) return
      val text = word.toString()
      word.clear()
      inWord = false
      when (pendingRedirect) {
        null -> words += text
        "2>" -> if (text == "/dev/null") discardErrors = true
        else -> {
          redirect = text
          append = pendingRedirect == ">>"
        }
      }
      pendingRedirect = null
    }

    fun endCommand() {
      endWord()
      if (pendingRedirect != null)
        throw IllegalArgumentException("syntax error: redirection without a file")
      if (words.isNotEmpty()) commands += words
      words = mutableListOf()
    }

    fun endStep(next: Joiner) {
      endCommand()
      if (commands.isNotEmpty()) {
        steps += Step(commands, joiner, redirect, append, discardErrors)
      }
      commands = mutableListOf()
      redirect = null
      append = false
      discardErrors = false
      joiner = next
    }

    var i = 0
    while (i < line.length) {
      val c = line[i]
      when {
        c == '\'' -> {
          val end = line.indexOf('\'', i + 1)
          if (end < 0) throw IllegalArgumentException("syntax error: unterminated quoted string")
          word.append(line, i + 1, end)
          inWord = true
          i = end
        }
        c == '"' -> {
          i++
          while (i < line.length && line[i] != '"') {
            if (line[i] == '\\' && i + 1 < line.length && line[i + 1] in "\"\\$`") i++
            word.append(line[i])
            i++
          }
          if (i >= line.length)
            throw IllegalArgumentException("syntax error: unterminated quoted string")
          inWord = true
        }
        c == '\\' && i + 1 < line.length -> {
          word.append(line[i + 1])
          inWord = true
          i++
        }
        c.isWhitespace() -> endWord()
        c == ';' -> endStep(Joiner.ALWAYS)
        c == '&' && line.startsWith("&&", i) -> {
          endStep(Joiner.AND)
          i++
        }
        c == '|' && line.startsWith("||", i) -> {
          endStep(Joiner.OR)
          i++
        }
        c == '|' -> endCommand()
        c == '2' && !inWord && line.startsWith("2>&1", i) -> i += 3
        c == '2' && !inWord && line.startsWith("2>", i) -> {
          endWord()
          pendingRedirect = "2>"
          i++
        }
        c == '>' -> {
          endWord()
          pendingRedirect = if (line.startsWith(">>", i)) ">>".also { i++ } else ">"
        }
        c == '&' -> throw IllegalArgumentException("background jobs are not supported")
        else -> {
          word.append(c)
          inWord = true
        }
      }
      i++
    }
    endStep(Joiner.ALWAYS)
    return steps
  }
}
