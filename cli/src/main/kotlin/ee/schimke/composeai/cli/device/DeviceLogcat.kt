package ee.schimke.composeai.cli.device

import java.time.LocalDateTime
import java.time.Year
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** One log entry, as the simulator reports it (`logcat` dump `entries`, `log` events). */
internal data class LogcatEntry(
  /** `MM-dd HH:mm:ss.SSS`, wall-clock, as Robolectric stamps it. */
  val time: String,
  val level: Char,
  val tag: String,
  val message: String,
  val pid: Int,
) {
  companion object {
    fun of(json: JsonObject) =
      LogcatEntry(
        time = json["time"]?.jsonPrimitive?.contentOrNull ?: "--",
        level = json["level"]?.jsonPrimitive?.contentOrNull?.firstOrNull() ?: 'V',
        tag = json["tag"]?.jsonPrimitive?.contentOrNull ?: "",
        message = json["message"]?.jsonPrimitive?.contentOrNull ?: "",
        pid = json["pid"]?.jsonPrimitive?.intOrNull ?: 0,
      )
  }
}

/**
 * An `adb logcat` command line: what to do (dump, clear or follow), which entries, and how each is
 * printed — `threadtime` by default, or `-v long` with `-v epoch` as Android Studio's Logcat window
 * asks for, `brief`, `time`, `tag`, `raw`.
 */
internal data class LogcatOptions(
  val op: Op = Op.FOLLOW,
  /** The last N entries before following, or of a dump; null for all. */
  val tail: Int? = null,
  /** Stop after printing this many (`-m`). */
  val max: Int? = null,
  val format: String = "threadtime",
  val epoch: Boolean = false,
  val year: Boolean = false,
  val filters: Map<String, Char> = emptyMap(),
  val defaultLevel: Char? = null,
  val regex: Regex? = null,
) {

  enum class Op {
    DUMP,
    CLEAR,
    FOLLOW,
  }

  /** Whether [entry] passes the tag / level filters and the regex, as logcat's filterspecs do. */
  fun matches(entry: LogcatEntry): Boolean {
    val wanted = filters[entry.tag] ?: defaultLevel ?: 'V'
    if (priority(entry.level) < priority(wanted)) return false
    return regex == null || regex.containsMatchIn(entry.message)
  }

  /** [entry] as logcat prints it in this format; every line ends in a newline. */
  fun print(entry: LogcatEntry): String {
    val time = time(entry.time)
    val tag = entry.tag.padEnd(8)
    val lines = entry.message.split('\n')
    if (format == "long") {
      return "[ $time ${pad(entry.pid)}:${pad(entry.pid)} ${entry.level}/$tag ]\n" +
        lines.joinToString("\n") +
        "\n\n"
    }
    val prefix =
      when (format) {
        "brief" -> "${entry.level}/$tag(${pad(entry.pid)}): "
        "time" -> "$time ${entry.level}/$tag(${pad(entry.pid)}): "
        "tag" -> "${entry.level}/$tag: "
        "process" -> "${entry.level}(${pad(entry.pid)}) "
        "raw" -> ""
        else -> "$time ${pad(entry.pid)} ${pad(entry.pid)} ${entry.level} $tag: "
      }
    return lines.joinToString("") { "$prefix$it\n" }
  }

  /** The dump request for the simulator: its filter, so that `tail` counts the matching ones. */
  fun dumpRequest(): JsonObject = buildJsonObject {
    put("cmd", JsonPrimitive("logcat"))
    put("op", JsonPrimitive("dump"))
    tail?.let { put("tail", JsonPrimitive(it)) }
    if (filters.isNotEmpty()) {
      put(
        "filters",
        JsonObject(filters.mapValues { (_, level) -> JsonPrimitive(level.toString()) }),
      )
    }
    defaultLevel?.let { put("default", JsonPrimitive(it.toString())) }
    regex?.let { put("regex", JsonPrimitive(it.pattern)) }
  }

  private fun time(stamp: String): String {
    if (!epoch && !year) return stamp
    val parsed = parseStamp(stamp) ?: return stamp
    if (year && !epoch) return parsed.format(YEAR_TIME)
    val instant = parsed.atZone(ZoneId.systemDefault()).toInstant()
    return String.format(Locale.ROOT, "%d.%03d", instant.epochSecond, instant.nano / 1_000_000)
  }

  companion object {
    private val STAMP = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS")
    private val YEAR_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS")
    private val FORMATS = setOf("threadtime", "long", "brief", "time", "tag", "process", "raw")
    /** Modifiers with nothing to change here: one process, no colour, no time zone shown. */
    private val IGNORED_MODIFIERS =
      setOf(
        "color",
        "colour",
        "printable",
        "uid",
        "usec",
        "zone",
        "UTC",
        "descriptive",
        "monotonic",
      )

    fun parse(args: List<String>): LogcatOptions {
      var options = LogcatOptions()
      val filters = linkedMapOf<String, Char>()
      var silent = false
      var i = 0
      fun value(option: String): String =
        args.getOrNull(++i) ?: throw IllegalArgumentException("option $option needs a value")
      fun count(option: String, text: String): Int =
        text.toIntOrNull()?.takeIf { it >= 0 }
          ?: throw IllegalArgumentException("$option takes a count, not $text")
      while (i < args.size) {
        val arg = args[i]
        when {
          arg == "-d" -> options = options.copy(op = Op.DUMP)
          arg == "-c" -> options = options.copy(op = Op.CLEAR)
          arg == "-s" -> silent = true
          arg == "-t" -> options = options.copy(op = Op.DUMP, tail = count(arg, value(arg)))
          arg == "-T" -> options = options.copy(tail = count(arg, value(arg)))
          arg == "-m" || arg == "--max-count" ->
            options = options.copy(max = count(arg, value(arg)))
          arg == "-e" || arg == "--regex" -> options = options.copy(regex = Regex(value(arg)))
          arg == "-v" || arg == "--format" -> options = options.withFormat(value(arg))
          arg.startsWith("-v") && arg.length > 2 -> options = options.withFormat(arg.substring(2))
          arg.startsWith("--format=") -> options = options.withFormat(arg.substringAfter('='))
          arg == "-b" || arg == "--buffer" || arg == "--pid" || arg == "--uid" -> value(arg)
          arg.startsWith("-b") ||
            arg.startsWith("--buffer=") ||
            arg.startsWith("--pid=") ||
            arg.startsWith("--uid=") -> Unit
          arg.startsWith("-") -> throw IllegalArgumentException("option $arg is not supported")
          else -> {
            val colon = arg.lastIndexOf(':')
            val tag = if (colon < 0) arg else arg.substring(0, colon)
            val level =
              if (colon < 0) 'V' else arg.substring(colon + 1).uppercase(Locale.ROOT).single()
            if (level !in LEVELS) {
              throw IllegalArgumentException("level is one of V D I W E F S, not $level")
            }
            if (tag == "*") options = options.copy(defaultLevel = level) else filters[tag] = level
          }
        }
        i++
      }
      if (silent && options.defaultLevel == null) options = options.copy(defaultLevel = 'S')
      return options.copy(filters = filters)
    }

    private fun LogcatOptions.withFormat(value: String): LogcatOptions =
      value
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .fold(this) { o, part ->
          when (part) {
            in FORMATS -> o.copy(format = part)
            "epoch" -> o.copy(epoch = true)
            "year" -> o.copy(year = true)
            in IGNORED_MODIFIERS -> o
            else -> throw IllegalArgumentException("format $part is not supported")
          }
        }

    private const val LEVELS = "VDIWEFAS"

    fun priority(level: Char): Int =
      when (level) {
        'V' -> 2
        'D' -> 3
        'I' -> 4
        'W' -> 5
        'E' -> 6
        'F',
        'A' -> 7
        else -> 8
      }

    private fun pad(n: Int) = n.toString().padStart(5)

    /**
     * Robolectric's stamp has no year: this one's, or last year's for a stamp that would otherwise
     * be in the future (the turn of a year).
     */
    fun parseStamp(stamp: String, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
      val thisYear =
        runCatching { LocalDateTime.parse("${Year.from(now)}-$stamp", STAMP) }.getOrNull()
          ?: return null
      return if (thisYear.isAfter(now.plusDays(1))) thisYear.minusYears(1) else thisYear
    }
  }
}

/**
 * The device's followers of the log: each `adb logcat` that follows, with its own filter and
 * format. The simulator has one follower, so it follows unfiltered for all of them and its `log`
 * events are handed to each here; it is asked to again when an install boots a new simulator.
 */
internal class LogcatHub {

  private val listeners = CopyOnWriteArraySet<(LogcatEntry) -> Unit>()

  val active: Boolean
    get() = listeners.isNotEmpty()

  /** Adds [listener]; [arm] starts the simulator following when it is the first. */
  fun subscribe(listener: (LogcatEntry) -> Unit, arm: () -> Unit): AutoCloseable {
    val first = listeners.isEmpty()
    listeners.add(listener)
    if (first) arm()
    return AutoCloseable { listeners.remove(listener) }
  }

  /** Whether [line] is a `log` event, which it hands to the followers when there are any. */
  fun offer(line: String): Boolean {
    if (!line.contains("\"log\"")) return false
    val json =
      runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return false
    if ((json["event"] as? JsonPrimitive)?.contentOrNull != "log") return false
    if (listeners.isEmpty()) return false
    val entry = LogcatEntry.of(json)
    for (listener in listeners) listener(entry)
    return true
  }

  companion object {
    /** The simulator following everything, for the hub to filter per follower. */
    val FOLLOW: JsonObject =
      JsonObject(
        mapOf<String, JsonElement>(
          "cmd" to JsonPrimitive("logcat"),
          "op" to JsonPrimitive("follow"),
        )
      )
  }
}
