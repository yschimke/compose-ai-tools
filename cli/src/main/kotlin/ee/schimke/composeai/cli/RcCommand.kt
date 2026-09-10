package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.remotecompose.json.RemoteComposeJson
import ee.schimke.composeai.remotecompose.json.RemoteComposeJsonException
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.IOException as OkioIOException
import okio.Path
import okio.Path.Companion.toPath

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
internal class RcCommand(
  private val args: List<String>,
  private val fileSystem: FileSystem = SystemFileSystem,
  private val stdout: (String) -> Unit = ::println,
  private val stderr: (String) -> Unit = System.err::println,
  /**
   * Injectable so the refusal paths are testable without killing the JVM — the same seam
   * `HistoryManifestCommand` uses, and needed here for the same reason: the interesting behaviour
   * of the batch dump is what it declines to do, and every one of those paths ends in a non-zero
   * exit.
   */
  private val exit: (Int) -> Nothing = { exitProcess(it) },
) {

  fun run() {
    // Find the subcommand skipping any leading flags, then hand it the args with only the
    // subcommand token removed — the same shape `BundleCommand` uses, and for the same reason.
    // The router deliberately preserves a leading option, so `compose-preview --compact rc dump
    // doc.rc` arrives here with `--compact` at index 0; reading element zero as the subcommand
    // rejected it as "unknown subcommand --compact".
    // `--help` anywhere wins, so `rc dump --help` explains itself rather than complaining that it
    // was given no file. It cannot be handled in the `when` below: it is a flag, so the subcommand
    // scan skips it and `rc dump --help` would dispatch to `dump` with nothing to read.
    if ("--help" in args || "-h" in args) {
      usage()
      return
    }
    val subIndex = CliFlags.firstPositionalIndex(args)
    val sub = if (subIndex >= 0) args[subIndex] else null
    val subArgs =
      if (subIndex >= 0) args.toMutableList().apply { removeAt(subIndex) } else emptyList()
    when (sub) {
      "compile" -> compile(subArgs)
      "dump" -> dump(subArgs)
      "header" -> header(subArgs)
      null,
      "help" -> usage()
      else -> {
        stderr("compose-preview rc: unknown subcommand '$sub'")
        usage()
        exit(2)
      }
    }
  }

  private fun compile(args: List<String>) {
    val input = CliFlags.positionals(args).firstOrNull() ?: fail("rc compile: expected a JSON file")
    val out = args.flagValue("--output") ?: args.flagValue("-o")
    val bytes = guard { RemoteComposeJson.compile(readTextOrFail(input.toPath(), "rc compile")) }

    // A document is binary, and a binary written to a terminal is a wrecked terminal. Refuse
    // rather than help. `-` is refused explicitly and not just left to fall through: it is the
    // conventional stdout sentinel, so a caller reaching for it means the pipe, and writing them a
    // file literally named `-` is the one outcome nobody wants. The comment used to claim this was
    // refused while the code created that file.
    if (out == null || out == "-") {
      fail(
        "rc compile: --output <file.rc> is required (a .rc document is binary; `-` is not stdout here)"
      )
    }
    writing("rc compile", out) { path -> fileSystem.write(path) { write(bytes) } }
    stderr("compose-preview: wrote ${bytes.size} bytes to $out")
  }

  private fun dump(args: List<String>) {
    // `CliFlags.positionals`, not "the first token without a dash". `rc dump --output out.json
    // input.rc` puts `out.json` first by that reading, so the command would dump the file it was
    // asked to write — and `rc compile -o out.rc in.json` would compile its own output path.
    val input = CliFlags.positionals(args).firstOrNull() ?: fail("rc dump: expected a .rc file")
    val compact = "--compact" in args
    val out = args.flagValue("--output") ?: args.flagValue("-o")
    val file = input.toPath()

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
    if (fileSystem.metadataOrNull(file)?.isDirectory == true) {
      // Directory mode writes each document's twin beside it, which is what the delivery lane
      // wants and what makes a re-dump idempotent. It has no destination to redirect, so `-o` is
      // REFUSED rather than ignored: the flag is on this command's allowlist, so an ignored one
      // draws no "unrecognised option" warning, and `rc dump renders -o reports` would quietly
      // rewrite `renders` while leaving `reports` empty.
      if (out != null) {
        fail(
          "rc dump: --output does not apply to a directory — each document is written beside its " +
            "source as <stem>.rc.json. Drop it, or dump one file at a time."
        )
      }
      dumpTree(file, compact)
      return
    }

    val text = guard { RemoteComposeJson.dump(readBytesOrFail(file, "rc dump"), pretty = !compact) }

    if (out == null || out == "-") {
      // Text, so `-` meaning stdout is honoured here rather than refused — the opposite of
      // `compile`, and for the reason that separates them: this output is pipeable.
      stdout(text)
    } else {
      writing("rc dump", out) { path -> fileSystem.write(path) { writeUtf8(text + "\n") } }
    }
  }

  /**
   * Create [out]'s parent and run [write], turning a filesystem refusal into a diagnostic.
   *
   * `guard` covers the codec; this covers the disk. Neither `createDirectories` nor `write` is a
   * codec failure, so without this a read-only destination — or one naming an existing directory —
   * left `main` to print a Kotlin stack trace, which is the one thing every other message in this
   * command is written to avoid. The batch loop already had its own handler; the single-file paths
   * did not.
   */
  private fun writing(what: String, out: String, write: (Path) -> Unit) {
    val path = out.toPath()
    try {
      path.parent?.let(fileSystem::createDirectories)
      write(path)
    } catch (e: OkioIOException) {
      // One catch covers both: on the JVM `okio.IOException` is a typealias for
      // `java.io.IOException`, so this is not narrower than it looks.
      fail("$what: cannot write $out: ${e.message}")
    }
  }

  private fun dumpTree(root: Path, compact: Boolean) {
    val entries = walk(root)
    refuseUndecodableNames(root, entries)
    val documents =
      entries
        .filter { fileSystem.metadataOrNull(it)?.isRegularFile == true && it.name.endsWith(".rc") }
        .sorted()
    if (documents.isEmpty()) fail("rc dump: no .rc documents under $root")

    var failed = 0
    for (document in documents) {
      val target = document.parent!! / "${document.name.removeSuffix(".rc")}.rc.json"
      try {
        if (!mayWrite(target)) {
          failed++
          stderr(
            "compose-preview: $target: refusing to overwrite — it is not a document dump. " +
              "Document JSON cannot be compiled back, so replacing authoring JSON here would " +
              "destroy it."
          )
          continue
        }
        val text =
          RemoteComposeJson.dump(fileSystem.read(document) { readByteArray() }, pretty = !compact)
        fileSystem.write(target) { writeUtf8(text + "\n") }
      } catch (e: RemoteComposeJsonException) {
        // One unreadable document does not stop the batch. A catalog with a single sticker captured
        // from a newer alpha than this CLI links would otherwise publish NO documents at all, which
        // is a strictly worse outcome than publishing the other thirty-nine and naming the one.
        failed++
        stderr("compose-preview: $document: ${e.message}")
      } catch (e: OkioIOException) {
        // An unreadable input or an unwritable output is the same shape of problem as an
        // unprojectable document, and the batch has to survive it for the same reason. Without
        // this the loop aborts on the first permission error, having neither processed the rest
        // nor printed the count a caller checks.
        failed++
        stderr("compose-preview: $document: ${e.message}")
      }
    }
    stderr(
      "compose-preview: dumped ${documents.size - failed}/${documents.size} documents under $root"
    )
    if (failed > 0) exit(1)
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
  private fun mayWrite(target: Path): Boolean {
    if (!fileSystem.exists(target)) return true
    val existing =
      try {
        Json.parseToJsonElement(fileSystem.read(target) { readUtf8() }) as? JsonObject
          ?: return false
      } catch (_: Exception) {
        return false
      }
    return "operations" in existing && "header" in existing
  }

  /**
   * Walk [root] without following directory symlinks.
   *
   * A link to an ancestor would otherwise make the walk unbounded, and even an acyclic one would
   * have the command writing `.rc.json` files outside the tree it was pointed at — a surprise
   * nobody asked for in a publish step. Okio's `listRecursively` defaults to `followSymlinks =
   * false`, which is the behaviour wanted here; it is passed explicitly so the default changing
   * cannot change this quietly.
   */
  private fun walk(root: Path): List<Path> =
    fileSystem.listRecursively(root, followSymlinks = false).toList()

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
  private fun refuseUndecodableNames(root: Path, entries: List<Path>) {
    val undecodable = entries.filter { UNDECODABLE in it.name }
    if (undecodable.isEmpty()) return
    fail(
      "rc dump: ${undecodable.size} entr${if (undecodable.size == 1) "y" else "ies"} under " +
        "$root have names this JVM cannot decode — sun.jnu.encoding is " +
        "${System.getProperty("sun.jnu.encoding")}, and a preview id can carry an em-dash. " +
        "Re-run with a UTF-8 locale (LANG=C.UTF-8). Refusing rather than reporting an empty tree, " +
        "which is what this looks like otherwise."
    )
  }

  private fun header(args: List<String>) {
    val input = CliFlags.positionals(args).firstOrNull() ?: fail("rc header: expected a .rc file")
    val header = guard { RemoteComposeJson.header(readBytesOrFail(input.toPath(), "rc header")) }

    if ("--json" in args) {
      // `toJsonObject()`, not the data class's own serializer. They disagree: the property is
      // `desiredFps` and the wire field is `desiredFPS`, so serializing the class directly would
      // give `rc header --json` a different shape from `rc dump`'s `header` block for the same
      // document — and a `jq` query written against one would silently miss on the other.
      stdout(PRETTY.encodeToString(JsonObject.serializer(), header.toJsonObject()))
      return
    }
    stdout("version              ${header.version}")
    stdout("size                 ${header.width ?: "-"} x ${header.height ?: "-"}")
    stdout("contentDescription   ${header.contentDescription ?: "-"}")
    stdout("profiles             ${header.profiles?.toString() ?: "-"}${header.profileNote()}")
    stdout("desiredFPS           ${header.desiredFps?.toString() ?: "-"}")
    stdout("densityAtGeneration  ${header.densityAtGeneration?.toString() ?: "-"}")
    stdout("bytes                ${header.byteLength}")
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

  private fun readTextOrFail(path: Path, what: String): String =
    readBytesOrFail(path, what).decodeToString()

  private fun readBytesOrFail(path: Path, what: String): ByteArray {
    if (fileSystem.metadataOrNull(path)?.isRegularFile != true) fail("$what: no such file: $path")
    return fileSystem.read(path) { readByteArray() }
  }

  private fun fail(message: String): Nothing {
    stderr(message)
    exit(1)
  }

  private fun usage() {
    stdout(
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

    /** The replacement character a directory listing substitutes for a byte it cannot decode. */
    const val UNDECODABLE: Char = '\uFFFD'
  }
}
