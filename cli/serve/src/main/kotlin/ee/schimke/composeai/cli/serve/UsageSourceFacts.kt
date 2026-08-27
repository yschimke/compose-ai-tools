package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.locateBundleSidecarJars
import java.io.File
import java.net.URLClassLoader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The call structure [PlaygroundSourceCleaner] used to guess at, read off a real parse.
 *
 * Produced by `:usage-source-psi` inside an isolated classloader (see [UsageSourceParser]) and
 * carried across as JSON, so no Kotlin frontend type ever reaches the CLI's own classpath.
 *
 * Offsets are 0-based character indices into the source that was analysed, end-exclusive. `-1`
 * means the element is absent — a call with no parentheses, an argument with no name, a call that
 * is not qualified.
 */
@Serializable
data class UsageSourceFacts(
  @SerialName("calls") val calls: List<Call> = emptyList(),
  /**
   * The file's top-level declarations, in source order. Empty from an analyzer predating the field,
   * which is why every reader treats "no declarations" as "fall back", not as "an empty file".
   */
  @SerialName("declarations") val declarations: List<Span> = emptyList(),
  /**
   * Set when the analyzer could not parse at all; the caller then behaves as if it had no facts.
   */
  @SerialName("error") val error: String? = null,
) {

  /** A half-open character range into the analysed source. */
  @Serializable
  data class Span(@SerialName("start") val start: Int, @SerialName("end") val end: Int) {
    operator fun contains(offset: Int): Boolean = offset in start until end
  }

  /**
   * The top-level declaration containing [offset], or null when no parsed declaration does.
   *
   * Null is a real answer and not a shrug: an offset in the file header, in a blank run between
   * declarations, or past the end of the last one belongs to no declaration, and a caller
   * attributing calls must be able to say so rather than picking the nearest.
   */
  fun declarationAt(offset: Int): Span? = declarations.firstOrNull { offset in it }

  @Serializable
  data class Call(
    @SerialName("callee") val callee: String,
    @SerialName("start") val start: Int,
    @SerialName("end") val end: Int,
    /** The `(…)` list, `-1` when the call has only a trailing lambda (`counted { }`). */
    @SerialName("argsStart") val argsStart: Int = -1,
    @SerialName("argsEnd") val argsEnd: Int = -1,
    @SerialName("lambdaStart") val lambdaStart: Int = -1,
    @SerialName("lambdaEnd") val lambdaEnd: Int = -1,
    @SerialName("lambdaBodyStart") val lambdaBodyStart: Int = -1,
    @SerialName("lambdaBodyEnd") val lambdaBodyEnd: Int = -1,
    /**
     * The receiver of `receiver.callee(…)`, exactly as written, or null when the call is not
     * qualified.
     *
     * This is the field the whole exercise turned on. `ee.schimke.composeai.overrides` and
     * `state.metrics` are the same tree shape, so the parse cannot say which is a package — but it
     * can hand over each one as an exact string, which makes the rules' allow-list a lookup instead
     * of the regex-over-surrounding-text that got it wrong.
     */
    @SerialName("receiver") val receiver: String? = null,
    @SerialName("qualifiedStart") val qualifiedStart: Int = -1,
    @SerialName("qualifiedEnd") val qualifiedEnd: Int = -1,
    @SerialName("args") val args: List<Arg> = emptyList(),
  ) {
    /** The range to replace when substituting the call: the qualified form if there is one. */
    val replaceStart: Int
      get() = if (qualifiedStart >= 0) qualifiedStart else start

    val replaceEnd: Int
      get() = if (qualifiedEnd >= 0) maxOf(qualifiedEnd, end) else end
  }

  @Serializable
  data class Arg(
    /** The `name` of `name = value`, or null for a positional argument. */
    @SerialName("name") val name: String? = null,
    @SerialName("text") val text: String = "",
    @SerialName("start") val start: Int = -1,
    @SerialName("end") val end: Int = -1,
  )

  /**
   * Resolve this call's arguments to the positions a `$0`/`$1` template cites, the way Kotlin binds
   * them: positional arguments fill [params] left to right, named ones fill by name.
   *
   * A parse supplies the *labels*; it does not supply the callee's signature, so [params] is still
   * required — for a positional call there is nothing in the syntax saying which slot is `default`,
   * and for a labelled one the label does not say which *index* the template means. See
   * `docs/design/PSI_PARSE_SPIKE.md`.
   *
   * Returns null when [params] is empty and any argument is named: without the parameter list there
   * is no way to place it, and guessing emits Kotlin that looks right and does not compile.
   */
  fun bind(call: Call, params: List<String>): List<String?>? {
    if (params.isEmpty()) {
      return if (call.args.any { it.name != null }) null else call.args.map { it.text }
    }
    val bound = arrayOfNulls<String>(params.size)
    var next = 0
    for (arg in call.args) {
      val name = arg.name
      if (name == null) {
        while (next < params.size && bound[next] != null) next++
        if (next >= params.size) continue
        bound[next++] = arg.text
      } else {
        val at = params.indexOf(name)
        if (at >= 0) bound[at] = arg.text
      }
    }
    return bound.toList()
  }
}

/**
 * Loads `:usage-source-psi` into an isolated classloader and runs it.
 *
 * ### The isolation, and why it is the same one the compiler already uses
 *
 * The analyzer needs a Kotlin frontend; the CLI must not have one. So the jars — the staged
 * `lib-bta/` (which carries `kotlin-compiler-embeddable`) plus this module's own `lib-usage-psi/` —
 * are loaded into a [URLClassLoader] parented to the **platform** loader, exactly as
 * [PlaygroundBtaCompiler] loads the compiler. Nothing is shared with the CLI's classpath: the whole
 * contract across the boundary is one method taking a String and returning JSON.
 *
 * ### Absent is a normal state
 *
 * A non-installed run (`./gradlew run`, a test, a developer's IDE) has no staged sidecars. [of]
 * returns null there and the cleaner keeps its text passes, which is what it always did. The parse
 * is an upgrade where it is available, not a new hard requirement — a serve host must not stop
 * cleaning seeds because a directory is missing.
 */
class UsageSourceParser
private constructor(private val analyzer: Any, private val analyze: java.lang.reflect.Method) {

  fun facts(source: String): UsageSourceFacts? =
    try {
      val raw = analyze.invoke(analyzer, source) as? String ?: return null
      json.decodeFromString<UsageSourceFacts>(raw).takeIf { it.error == null }
    } catch (e: Throwable) {
      // Throwable, not Exception: a staged closure that is incomplete or built for a newer JVM
      // raises `NoClassDefFoundError` / `UnsupportedClassVersionError`, which are `Error`s. Letting
      // one through would take down a playground request over an optional sidecar.
      null
    }

  companion object {
    private val json = Json {
      ignoreUnknownKeys = true
      isLenient = true
    }

    private const val ANALYZER = "ee.schimke.composeai.usagepsi.UsageSourceAnalyzer"

    /** Path-separated jar list, for tests and for a host that stages the sidecars elsewhere. */
    const val JARS_PROPERTY = "composeai.usagePsi.jars"

    @Volatile private var cached: UsageSourceParser? = null
    @Volatile private var attempted = false

    /**
     * The process-wide parser, or null when the sidecars are not present.
     *
     * Built once and reused: the frontend's environment setup is ~0.5 s and per-file parsing is ~3
     * ms, so paying setup per seed would invert those numbers. Also attempted only once — a host
     * missing the sidecar should not retry a classloader build on every seed it serves.
     */
    fun of(onLog: (String) -> Unit = {}): UsageSourceParser? {
      cached?.let {
        return it
      }
      synchronized(this) {
        cached?.let {
          return it
        }
        if (attempted) return null
        attempted = true
        val jars = jars()
        if (jars.isEmpty()) {
          onLog("usage-source-psi not staged; cleaning with the text passes")
          return null
        }
        return try {
          val loader =
            URLClassLoader(
              jars.map { it.toURI().toURL() }.toTypedArray(),
              ClassLoader.getPlatformClassLoader(),
            )
          val type = loader.loadClass(ANALYZER)
          val instance = type.getDeclaredConstructor().newInstance()
          val method = type.getMethod("analyze", String::class.java)
          UsageSourceParser(instance, method).also { cached = it }
        } catch (e: Throwable) {
          // Same reason as [facts]: a broken or mismatched staged closure fails with a
          // `LinkageError`, not an `Exception`, and the documented behaviour for an unusable
          // sidecar is to fall back rather than to fail the request.
          onLog("usage-source-psi failed to load ($e); cleaning with the text passes")
          null
        }
      }
    }

    private fun jars(): List<File> {
      System.getProperty(JARS_PROPERTY)
        ?.takeIf { it.isNotBlank() }
        ?.let { property ->
          return property
            .split(File.pathSeparator)
            .filter { it.endsWith(".jar") }
            .map(::File)
            .filter { it.isFile }
        }
      val psi = locateBundleSidecarJars("lib-usage-psi")
      // The analyzer is nothing without the frontend, so both sidecars or neither.
      val bta = locateBundleSidecarJars("lib-bta")
      return if (psi.isEmpty() || bta.isEmpty()) emptyList() else psi + bta
    }

    /** Test seam: forget the cached parser so a test can install different jars. */
    internal fun resetForTest() {
      synchronized(this) {
        cached = null
        attempted = false
      }
    }
  }
}
