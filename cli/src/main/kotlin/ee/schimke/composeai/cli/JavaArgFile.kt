package ee.schimke.composeai.cli

import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/**
 * Linux refuses any single `execve` argument over `MAX_ARG_STRLEN` (32 pages, 128 KiB) with `E2BIG`
 * ("Argument list too long"), whatever `ARG_MAX` allows. A bundle or Bazel target with ~1,500
 * runtime jars puts ~290 KiB in one `-cp` or `-Dcomposeai.daemon.userClassDirs`
 * (compose-ai-tools#5945).
 */
private const val MAX_INLINE_ARG = 32 * 1024
private const val MAX_INLINE_ARGS = 96 * 1024

/**
 * Windows passes one command line to `CreateProcess`, at most 32,767 characters in all, so there
 * the whole command counts; the margin leaves room for quoting and arguments after the main class.
 */
private const val MAX_WINDOWS_COMMAND_LINE = 24 * 1024

private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

/**
 * One argfile per distinct option list: a catalog renders every preview with the same classpath,
 * and a file per launch would pile up in the temp directory until the CLI exits.
 */
private val argFiles = ConcurrentHashMap<Pair<List<String>, File?>, File>()

/**
 * [command] — `java`, its options, then the main class — as launched: unchanged while it fits the
 * platform's command-line limits, else `java @argfile <main>` with the options moved into one
 * argfile the launcher expands. Written in the platform encoding the launcher decodes argfiles
 * with.
 */
internal fun fitJavaCommand(command: List<String>, directory: File? = null): List<String> {
  require(command.size >= 2) { "A java command needs the launcher and a main class" }
  val options = command.subList(1, command.size - 1).toList()
  val fits =
    if (isWindows) command.sumOf { it.length + 1 } <= MAX_WINDOWS_COMMAND_LINE
    else
      options.none { it.length > MAX_INLINE_ARG } && options.sumOf { it.length } <= MAX_INLINE_ARGS
  if (fits) return command
  val file =
    argFiles.compute(options to directory) { _, existing ->
      existing?.takeIf { it.isFile } ?: writeArgFile(options, directory)
    }!!
  return listOf(command.first(), "@${file.absolutePath}", command.last())
}

private fun writeArgFile(options: List<String>, directory: File?): File {
  val file = File.createTempFile("composeai-java", ".args", directory)
  file.deleteOnExit()
  val charset = runCatching {
    Charset.forName(System.getProperty("sun.jnu.encoding"))
  }
    .getOrDefault(Charset.defaultCharset())
  file.writeText(options.joinToString("\n", postfix = "\n") { quoteJavaArgFileToken(it) }, charset)
  return file
}

/** One argfile token: quoted, with `\` and `"` escaped as the launcher un-escapes them. */
internal fun quoteJavaArgFileToken(token: String): String =
  "\"" + token.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
