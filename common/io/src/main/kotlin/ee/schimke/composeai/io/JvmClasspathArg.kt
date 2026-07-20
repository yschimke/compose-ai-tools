package ee.schimke.composeai.io

import java.io.File

/**
 * Build the `-classpath` portion of a `java …` command in a form that cannot overflow the OS
 * argument limit (`execve` `E2BIG` — "Argument list too long").
 *
 * A large module's render classpath — a full app's *hundreds* of jars — blows past `ARG_MAX` (and
 * the per-argument `MAX_ARG_STRLEN`) when passed as a literal `-cp <jar:jar:…>` argument. The spawn
 * then fails with `error=7`, which silently drops the daemon subprocess: e.g. the
 * `--with-semantics` layout-inspector capture never starts, so the catalog renders without
 * wireframe / figma SVGs. Small catalog/sample modules stay under the limit, so the failure stays
 * invisible until a real app module hits it.
 *
 * The fix is a Java **@argfile**: write `-classpath "<cp>"` to a file and pass the single token
 * `@<file>` on the command line. The launcher reads the classpath from the file, so `argv` stays
 * short no matter how big the classpath is. Argfiles are honoured by every `java` launcher since
 * JDK 9.
 *
 * @return the `@<file>` token to splice into the command **in place of** `-cp`/`-classpath` + the
 *   joined classpath. The file is created under the JVM temp dir and registered for deletion on
 *   exit.
 */
fun classpathArgFile(classpath: List<String>): String {
  require(classpath.isNotEmpty()) { "classpath must not be empty" }
  val joined = classpath.joinToString(File.pathSeparator)
  val file = File.createTempFile("composeai-cp", ".args")
  file.deleteOnExit()
  file.writeText("-classpath ${quoteForArgFile(joined)}\n")
  return "@" + file.absolutePath
}

/**
 * Quote a single token for a Java argfile. Wrap it in double quotes so embedded whitespace stays one
 * argument, and double every backslash so Windows paths survive the argfile's `\`-escape rule (the
 * launcher un-escapes `\\` → `\`, `\"` → `"`). On POSIX classpaths (no backslashes, rarely a space)
 * this is effectively just the surrounding quotes.
 */
internal fun quoteForArgFile(token: String): String =
  "\"" + token.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
