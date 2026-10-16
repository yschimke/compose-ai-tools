package ee.schimke.composeai.cli

import java.io.File
import java.nio.charset.Charset

/**
 * Linux refuses any single `execve` argument over `MAX_ARG_STRLEN` (32 pages, 128 KiB) with `E2BIG`
 * ("Argument list too long"), whatever `ARG_MAX` allows. A bundle or Bazel target with ~1,500
 * runtime jars puts ~290 KiB in one `-cp` or `-Dcomposeai.daemon.userClassDirs`
 * (compose-ai-tools#5945).
 */
private const val MAX_INLINE_ARG = 32 * 1024
private const val MAX_INLINE_ARGS = 96 * 1024

/**
 * [command] — `java`, its options, then the main class — as launched: unchanged while every option
 * fits on the command line, else `java @argfile <main>` with the options moved into one argfile the
 * launcher expands. Written in the platform encoding the launcher decodes argfiles with.
 */
internal fun fitJavaCommand(command: List<String>, directory: File? = null): List<String> {
  require(command.size >= 2) { "A java command needs the launcher and a main class" }
  val options = command.subList(1, command.size - 1)
  if (
    options.none { it.length > MAX_INLINE_ARG } && options.sumOf { it.length } <= MAX_INLINE_ARGS
  ) {
    return command
  }
  val file = File.createTempFile("composeai-java", ".args", directory)
  file.deleteOnExit()
  val charset = runCatching {
    Charset.forName(System.getProperty("sun.jnu.encoding"))
  }
    .getOrDefault(Charset.defaultCharset())
  file.writeText(options.joinToString("\n", postfix = "\n") { quoteJavaArgFileToken(it) }, charset)
  return listOf(command.first(), "@${file.absolutePath}", command.last())
}

/** One argfile token: quoted, with `\` and `"` escaped as the launcher un-escapes them. */
internal fun quoteJavaArgFileToken(token: String): String =
  "\"" + token.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
