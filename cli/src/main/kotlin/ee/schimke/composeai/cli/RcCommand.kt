package ee.schimke.composeai.cli

import ee.schimke.composeai.remotecompose.json.RemoteComposeJson
import ee.schimke.composeai.remotecompose.json.RemoteComposeJsonException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * `compose-preview rc <compile|dump|header>` — the Remote Compose JSON codec at the command line.
 *
 * A `.rc` document is opaque. It is the format a Wear widget, a tile and a watch face actually
 * ship, this repository captures one per Remote preview into `renders/<stem>.rc`, and until now the
 * only way to see what one contains was to play it and look at the pixels. That makes a whole class
 * of question unanswerable at the terminal: did this sticker's padding change, or did the render
 * just get antialiased differently? Is the blank preview a broken document or a working document
 * with a transparent background? Which of these forty stickers declare a profile the target player
 * does not implement?
 *
 * Three subcommands, matching the three things a person wants:
 *
 * - **compile** — authoring JSON → `.rc`. The format AndroidX's `remote_compose_schema.json`
 *   describes. Writes the document, or reports which JSON path the parser gave up on.
 * - **dump** — `.rc` → document JSON. The operation stream, in order, with the fields each
 *   operation carries. Diffable with `diff`, queryable with `jq`.
 * - **header** — `.rc` → its declared size, content description, profile mask and version, without
 *   inflating the rest. The cheap question, answered cheaply.
 *
 * The two JSON dialects are not inverses and `RemoteComposeJson` says so at length; the help text
 * here repeats the one-line version because someone reading `--help` has not read that KDoc.
 *
 * Offline by construction — no daemon, no Gradle, no project. `rc dump` on a `.rc` pulled out of a
 * bundle with `unzip` is a complete workflow, which is the point.
 */
internal class RcCommand(private val args: List<String>) {

  fun run() {
    when (args.firstOrNull()) {
      "compile" -> compile(args.drop(1))
      "dump" -> dump(args.drop(1))
      "header" -> header(args.drop(1))
      null,
      "help",
      "--help",
      "-h" -> usage()
      else -> {
        System.err.println("compose-preview rc: unknown subcommand '${args.first()}'")
        usage()
        exitProcess(2)
      }
    }
  }

  private fun compile(args: List<String>) {
    val input = CliFlags.positionals(args).firstOrNull() ?: fail("rc compile: expected a JSON file")
    val out = args.flagValue("--output") ?: args.flagValue("-o")
    val bytes = guard { RemoteComposeJson.compile(File(input).readTextOrFail("rc compile")) }

    if (out == null) {
      // A document is binary, and a binary written to a terminal is a wrecked terminal. Refuse
      // rather than help: `-o -` is not offered either, because the only reason to want a document
      // on stdout is to pipe it, and a caller that can pipe can name a file.
      fail("rc compile: --output <file.rc> is required (a .rc document is binary)")
    }
    File(out).absoluteFile.also { it.parentFile?.mkdirs() }.writeBytes(bytes)
    System.err.println("compose-preview: wrote ${bytes.size} bytes to $out")
  }

  private fun dump(args: List<String>) {
    // `CliFlags.positionals`, not "the first token without a dash". `rc dump --output out.json
    // input.rc` puts `out.json` first by that reading, so the command would dump the file it was
    // asked to write — and `rc compile -o out.rc in.json` would compile its own output path.
    val input = CliFlags.positionals(args).firstOrNull() ?: fail("rc dump: expected a .rc file")
    val compact = "--compact" in args
    val out = args.flagValue("--output") ?: args.flagValue("-o")
    val file = File(input)

    // A DIRECTORY dumps every `.rc` under it to a `.rc.json` twin beside the original, and that is
    // the mode that carries real weight rather than a convenience over a shell loop.
    //
    // It is how a catalog gets its documents into a published branch. `renders/` holds one `.rc`
    // per Remote preview — forty of them in wear-m3-catalog's `remote-m3` sheet — and the artifact
    // branch that catalog publishes has, until now, carried the PNGs and nothing a reader could
    // diff. Rendering to a picture and then diffing the picture cannot distinguish a changed
    // padding from a changed antialiasing pass; the document can.
    //
    // Deliberately NOT wired into the Gradle plugin, which is where a reader might expect it. The
    // plugin is an isolated included build running inside the CONSUMER's build daemon, and putting
    // the codec there would put `remote-core` + `remote-creation-core` + `org.json` on the
    // classpath of every project that applies the plugin — including projects with no Remote
    // Compose in them at all, and including a `RemoteComposePairing` skew this repository would
    // then own a fourth source of. A publish step calling one CLI command is the cheaper seam.
    if (file.isDirectory) {
      dumpTree(file, compact)
      return
    }

    val text = guard { RemoteComposeJson.dump(file.readBytesOrFail("rc dump"), pretty = !compact) }

    if (out == null) {
      println(text)
    } else {
      File(out).absoluteFile.also { it.parentFile?.mkdirs() }.writeText(text + "\n")
    }
  }

  private fun dumpTree(root: File, compact: Boolean) {
    val entries = walk(root)
    refuseUndecodableNames(root, entries)
    val documents = entries.filter { it.isFile && it.extension == "rc" }.sorted()
    if (documents.isEmpty()) fail("rc dump: no .rc documents under ${root.path}")

    var failed = 0
    for (document in documents) {
      val target = File(document.parentFile, "${document.nameWithoutExtension}.rc.json")
      try {
        if (!mayWrite(target)) {
          failed++
          System.err.println(
            "compose-preview: ${target.path}: refusing to overwrite — it is not a document dump. " +
              "Document JSON cannot be compiled back, so replacing authoring JSON here would " +
              "destroy it."
          )
          continue
        }
        target.writeText(RemoteComposeJson.dump(document.readBytes(), pretty = !compact) + "\n")
      } catch (e: RemoteComposeJsonException) {
        // One unreadable document does not stop the batch. A catalog with a single sticker captured
        // from a newer alpha than this CLI links would otherwise publish NO documents at all, which
        // is a strictly worse outcome than publishing the other thirty-nine and naming the one.
        failed++
        System.err.println("compose-preview: ${document.path}: ${e.message}")
      } catch (e: IOException) {
        // An unreadable input or an unwritable output is the same shape of problem as an
        // unprojectable document, and the batch has to survive it for the same reason. Without
        // this the loop aborts on the first permission error, having neither processed the rest
        // nor printed the count a caller checks.
        failed++
        System.err.println("compose-preview: ${document.path}: ${e.message}")
      }
    }
    System.err.println(
      "compose-preview: dumped ${documents.size - failed}/${documents.size} documents under ${root.path}"
    )
    if (failed > 0) exitProcess(1)
  }

  /**
   * Whether [target] may be written, which is not the same question as whether it exists.
   *
   * `<stem>.rc` dumps to `<stem>.rc.json`, and `<stem>.rc.json` is also a perfectly ordinary name
   * for the **authoring** JSON that produced it — the two dialects collide in the filesystem the
   * same way they collide in conversation. Overwriting is one-way harm: document JSON has no
   * parser, so a clobbered source cannot be recovered from the file that replaced it.
   *
   * So a target is writable when it does not exist, or when it is a previous dump — recognised by
   * the two keys every dump has and no authoring document has (an authoring `header` is an object
   * too, but it never sits beside an `operations` array). Anything else is left alone and reported,
   * which costs a re-run at worst; guessing wrong costs someone's file.
   */
  private fun mayWrite(target: File): Boolean {
    if (!target.exists()) return true
    val existing =
      try {
        Json.parseToJsonElement(target.readText()) as? JsonObject ?: return false
      } catch (_: Exception) {
        return false
      }
    return "operations" in existing && "header" in existing
  }

  /**
   * Walk [root] without following directory symlinks.
   *
   * `File.walkTopDown()` follows them and does not detect cycles, so a link to an ancestor turns
   * the walk into an unbounded one — and even an acyclic link would have the command writing
   * `.rc.json` files outside the tree it was pointed at, which for a publish step is a surprise
   * nobody asked for. `Files.walk` with no `FOLLOW_LINKS` visits the link itself rather than its
   * target, so a linked directory is simply not descended into.
   */
  private fun walk(root: File): List<File> =
    Files.walk(root.toPath()).use { paths -> paths.map { it.toFile() }.toList() }

  /**
   * Refuse a tree this JVM cannot name, rather than reporting it as empty.
   *
   * `File.listFiles()` decodes directory entries with `sun.jnu.encoding`, which follows the process
   * locale. Under `LANG=C` / `POSIX` that is `ANSI_X3.4-1968`, and every filename holding a byte
   * above 0x7F comes back with U+FFFD replacement characters — a `File` whose `isFile()` is false,
   * because the mangled name resolves to nothing on disk.
   *
   * This repository's preview ids **do** hold such bytes: they can carry an em-dash, which is why
   * the design-artifacts workflow sets `LANG: C.UTF-8` and says so. Without this check a batch dump
   * under the wrong locale reports "no .rc documents under …" for a directory full of them — a
   * wrong answer that reads exactly like a correct one, and one that would have published an empty
   * `documents/` tree with nothing anywhere reporting a problem.
   *
   * Measured rather than inferred: the check is for entries that came back **undecodable**, not for
   * an unfortunate-looking `sun.jnu.encoding`. An ASCII-only tree works fine under any locale and
   * must not be refused for a hazard it does not have.
   */
  private fun refuseUndecodableNames(root: File, entries: List<File>) {
    val undecodable = entries.filter { UNDECODABLE in it.name }
    if (undecodable.isEmpty()) return
    fail(
      "rc dump: ${undecodable.size} entr${if (undecodable.size == 1) "y" else "ies"} under " +
        "${root.path} have names this JVM cannot decode — sun.jnu.encoding is " +
        "${System.getProperty("sun.jnu.encoding")}, and a preview id can carry an em-dash. " +
        "Re-run with a UTF-8 locale (LANG=C.UTF-8). Refusing rather than reporting an empty tree, " +
        "which is what this looks like otherwise."
    )
  }

  private fun header(args: List<String>) {
    val input = CliFlags.positionals(args).firstOrNull() ?: fail("rc header: expected a .rc file")
    val header = guard { RemoteComposeJson.header(File(input).readBytesOrFail("rc header")) }

    if ("--json" in args) {
      // `toJsonObject()`, not the data class's own serializer. They disagree: the property is
      // `desiredFps` and the wire field is `desiredFPS`, so serializing the class directly would
      // give `rc header --json` a different shape from `rc dump`'s `header` block for the same
      // document — and a `jq` query written against one would silently miss on the other.
      println(PRETTY.encodeToString(JsonObject.serializer(), header.toJsonObject()))
      return
    }
    println("version              ${header.version}")
    println("size                 ${header.width ?: "-"} x ${header.height ?: "-"}")
    println("contentDescription   ${header.contentDescription ?: "-"}")
    println("profiles             ${header.profiles?.toString() ?: "-"}${header.profileNote()}")
    println("desiredFPS           ${header.desiredFps?.toString() ?: "-"}")
    println("densityAtGeneration  ${header.densityAtGeneration?.toString() ?: "-"}")
    println("bytes                ${header.byteLength}")
  }

  /**
   * Name the two profile masks that appear in practice.
   *
   * A player refuses a document whose profile it does not implement, and it refuses it by drawing
   * nothing — so "this document declares `513`" is only useful next to "which is EXPERIMENTAL", and
   * a bare number sends the reader to a search engine.
   */
  private fun ee.schimke.composeai.remotecompose.json.RemoteComposeDocumentHeader.profileNote() =
    when (profiles) {
      512 -> "  (ANDROIDX)"
      513 -> "  (EXPERIMENTAL)"
      else -> ""
    }

  /**
   * Report a codec failure as a message, not a stack trace.
   *
   * Every failure this command can hit is a statement about the *file the user named* — not a
   * document, no `root`, an operation the inflater does not know. A Kotlin stack trace buries that
   * message under twenty frames of the codec's own call graph, and the message is the entire
   * diagnostic: it names the JSON path the parser stopped at.
   */
  private fun <T> guard(block: () -> T): T =
    try {
      block()
    } catch (e: RemoteComposeJsonException) {
      fail("compose-preview: ${e.message}")
    }

  private fun File.readTextOrFail(what: String): String {
    if (!isFile) fail("$what: no such file: $path")
    return readText()
  }

  private fun File.readBytesOrFail(what: String): ByteArray {
    if (!isFile) fail("$what: no such file: $path")
    return readBytes()
  }

  private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
  }

  private fun usage() {
    println(
      """
      |compose-preview rc — the Remote Compose JSON codec, offline.
      |
      |  rc compile <doc.json> -o <doc.rc>   authoring JSON -> binary document
      |  rc dump <doc.rc> [--compact] [-o f] binary document -> document JSON
      |  rc dump <dir> [--compact]           every .rc under <dir> -> <stem>.rc.json beside it
      |  rc header <doc.rc> [--json]         the document's declared header only
      |
      |The two JSON dialects are NOT inverses. `compile` reads the AUTHORING dialect —
      |AndroidX's remote_compose_schema.json, with named resources, infix expressions and
      |modifier shorthands. `dump` writes the DOCUMENT dialect — the operation stream, for
      |reading and diffing. Dumping a compiled document does not give you back its source.
      """
        .trimMargin()
    )
  }

  private companion object {
    val PRETTY = Json { prettyPrint = true }

    /** The replacement character `File.listFiles()` substitutes for a byte it cannot decode. */
    const val UNDECODABLE: Char = '\uFFFD'
  }
}
