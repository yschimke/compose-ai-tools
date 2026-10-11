package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.remotecompose.json.RemoteComposeJson
import ee.schimke.composeai.remotecompose.json.RemoteComposeJsonException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okio.FileMetadata
import okio.FileSystem
import okio.IOException as OkioIOException
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

/**
 * `compose-preview rc <compile|dump|header>` — the Remote Compose JSON codec at the command line,
 * for inspecting the otherwise opaque `.rc` documents previews capture to `renders/<stem>.rc`.
 *
 * - compile — authoring JSON (AndroidX `remote_compose_schema.json`) → `.rc`, or the JSON path the
 *   parser gave up on.
 * - dump — `.rc` → document JSON: the ordered operation stream, diffable and `jq`-able.
 * - header — `.rc` → declared size, content description, profile mask and version, cheaply.
 *
 * The two JSON dialects are not inverses (see `RemoteComposeJson`). Fully offline: no daemon,
 * Gradle or project.
 */
internal class RcCommand(
  private val args: List<String>,
  private val fileSystem: FileSystem = SystemFileSystem,
  private val stdout: (String) -> Unit = ::println,
  private val stderr: (String) -> Unit = System.err::println,
  /** Injectable so the non-zero exit paths are testable (as in `HistoryManifestCommand`). */
  private val exit: (Int) -> Nothing = { exitProcess(it) },
) {

  fun run() {
    // Find the subcommand by skipping leading flags (as `BundleCommand` does); the router may leave
    // an option like `--compact` at index 0. `--help` anywhere wins, and must be handled here
    // because the subcommand scan skips flags.
    if ("--help" in args || "-h" in args) {
      usage()
      return
    }
    val subIndex = CliFlags.firstPositionalIndex(args)
    val sub = if (subIndex >= 0) args[subIndex] else null
    val subArgs =
      if (subIndex >= 0) args.toMutableList().apply { removeAt(subIndex) } else emptyList()
    warnUnreadFlags(sub, subArgs)
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

  /**
   * Warn about flags the chosen subcommand doesn't read. `CliFlagValidation` only knows the union
   * for `rc`, so e.g. `rc header -o x` would otherwise be silently ignored. A warning, not a
   * refusal, as for unrecognised options elsewhere.
   */
  private fun warnUnreadFlags(sub: String?, args: List<String>) {
    val read = READS[sub] ?: return
    args
      .filter { it.startsWith("-") && it != "-" }
      .map { it.substringBefore("=") }
      .filter { it !in read && it !in HELP }
      .distinct()
      .forEach { stderr("compose-preview: warning: '$it' has no effect on 'rc $sub' (ignored)") }
  }

  private fun compile(args: List<String>) {
    val input = args.singleInput("rc compile", "a JSON file")
    val out = args.outputValue("rc compile")
    val bytes = guard { RemoteComposeJson.compile(readTextOrFail(input.toPath(), "rc compile")) }

    // A binary document must not go to a terminal. `-` is refused explicitly, since the caller
    // means stdout, not a file named `-`.
    if (out == null || out == "-") {
      fail(
        "rc compile: --output <file.rc> is required (a .rc document is binary; `-` is not stdout here)"
      )
    }
    // `compile` is one-way, so overwriting the authoring source with its output loses it.
    if (isSameFile(out.toPath(), input.toPath())) {
      fail(
        "rc compile: --output would overwrite the authoring JSON being compiled ($out). " +
          "Compiling is one-way — a .rc cannot be turned back into its source — so this would " +
          "destroy it. Name a different file."
      )
    }
    writing("rc compile", out) { path -> fileSystem.write(path) { write(bytes) } }
    stderr("compose-preview: wrote ${bytes.size} bytes to $out")
  }

  private fun dump(args: List<String>) {
    // Use `CliFlags.positionals`, or a leading `--output out.json` would be taken as the input.
    val input = args.singleInput("rc dump", "a .rc file or a directory")
    val compact = "--compact" in args
    val out = args.outputValue("rc dump")
    val file = input.toPath()

    // A directory dumps every `.rc` under it to a `.rc.json` twin, so a catalog can publish
    // diffable documents beside its PNGs. Deliberately not in the Gradle plugin, which would put
    // the codec and its Remote Compose dependencies on every consumer build's classpath.
    if (statOrFail(file, "rc dump")?.isDirectory == true) {
      // Directory mode writes twins in place, so `-o` is refused rather than silently ignored.
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
      // Text output, so `-` means stdout here (unlike `compile`).
      stdout(text)
    } else if (isSameFile(out.toPath(), file)) {
      // Writing the dump over its own `.rc` input would destroy the only copy irrecoverably. Paths
      // are canonicalised; an output that doesn't exist yet can't be the input.
      fail(
        "rc dump: --output would overwrite the document being dumped ($out). Document JSON " +
          "cannot be compiled back, so this would destroy it — name a different file."
      )
    } else {
      writing("rc dump", out) { path -> fileSystem.write(path) { writeUtf8(text + "\n") } }
    }
  }

  /**
   * The single operand this subcommand was given; more than one is refused rather than silently
   * using the first.
   */
  private fun List<String>.singleInput(what: String, expected: String): String {
    val positionals = CliFlags.positionals(this)
    return when (positionals.size) {
      0 -> fail("$what: expected $expected")
      1 -> positionals.single()
      else ->
        fail(
          "$what: expected $expected, got ${positionals.size} " +
            "(${positionals.joinToString(", ")}) — this command takes one at a time"
        )
    }
  }

  /**
   * `--output` / `-o`'s value, refusing the flag when it has none (`flagValue` can't tell that from
   * absent) or when the value is itself a flag. `-` is allowed as `dump`'s stdout sentinel.
   */
  private fun List<String>.outputValue(what: String): String? {
    // Every occurrence of both aliases, in order, so conflicts and a trailing valueless flag are
    // caught.
    val values = mutableListOf<String?>()
    var i = 0
    while (i < size) {
      val arg = this[i]
      when {
        arg == "--output" || arg == "-o" -> {
          values += getOrNull(i + 1)
          i++
        }
        arg.startsWith("--output=") -> values += arg.substringAfter("=")
        arg.startsWith("-o=") -> values += arg.substringAfter("=")
      }
      i++
    }
    if (values.isEmpty()) return null

    val bad = values.firstOrNull { it.isNullOrBlank() || (it.startsWith("-") && it != "-") }
    if (values.any { it.isNullOrBlank() || (it.startsWith("-") && it != "-") }) {
      fail("$what: --output needs a file after it (got ${bad?.let { "'$it'" } ?: "nothing"})")
    }
    // Two different destinations: refuse rather than pick one.
    val distinct = values.filterNotNull().distinct()
    if (distinct.size > 1) {
      fail("$what: --output given more than once (${distinct.joinToString(", ")}) — pick one")
    }
    return distinct.single()
  }

  /**
   * Whether [a] and [b] are the same file, compared canonically (`./`, `..`, symlinks). A path that
   * can't be canonicalised doesn't exist and so isn't the input.
   */
  private fun isSameFile(a: Path, b: Path): Boolean {
    val canonical = { p: Path ->
      try {
        fileSystem.canonicalize(p)
      } catch (_: OkioIOException) {
        null
      }
    }
    return canonical(a)?.let { it == canonical(b) } == true
  }

  /**
   * Create [out]'s parent and run [write], turning filesystem errors into a diagnostic rather than
   * a stack trace.
   */
  private fun writing(what: String, out: String, write: (Path) -> Unit) {
    val path = out.toPath()
    try {
      path.parent?.let(fileSystem::createDirectories)
      write(path)
    } catch (e: OkioIOException) {
      // On the JVM `okio.IOException` is `java.io.IOException`, so this covers both.
      fail("$what: cannot write $out: ${e.message}")
    }
  }

  private fun dumpTree(root: Path, compact: Boolean) {
    val walked = walk(root)
    refuseUndecodableNames(root, walked.entries)
    // `metadataOrNull` throws for entries it can't stat; treat each such entry as its own failure
    // instead of aborting the batch.
    val unstattable = mutableListOf<Path>()
    val documents =
      walked.entries
        .filter { entry ->
          if (!entry.name.endsWith(".rc")) return@filter false
          try {
            fileSystem.metadataOrNull(entry)?.isRegularFile == true
          } catch (e: OkioIOException) {
            unstattable += entry
            stderr("compose-preview: $entry: ${e.message}")
            false
          }
        }
        .sorted()
    if (documents.isEmpty() && unstattable.isEmpty()) {
      fail(
        walked.stoppedBy?.let { "rc dump: could not list $root: $it" }
          ?: "rc dump: no .rc documents under $root"
      )
    }
    walked.stoppedBy?.let {
      stderr(
        "compose-preview: $root: directory listing failed part-way ($it) — dumping the " +
          "${documents.size} document(s) enumerated before it, and exiting non-zero."
      )
    }

    var failed = unstattable.size
    for (document in documents) {
      val target = document.parent!! / "${document.name.removeSuffix(".rc")}.rc.json"
      try {
        if (!mayWrite(target)) {
          failed++
          stderr(
            if (fileSystem.metadataOrNull(target)?.symlinkTarget != null)
              "compose-preview: $target: refusing to overwrite — it is a symlink, and writing " +
                "through it would put a dump outside the tree this command was pointed at."
            else
              "compose-preview: $target: refusing to overwrite — it is not a document dump. " +
                "Document JSON cannot be compiled back, so replacing authoring JSON here would " +
                "destroy it."
          )
          continue
        }
        // Read separately: a read failure and a write failure treat the previous dump differently.
        val bytes =
          try {
            fileSystem.read(document) { readByteArray() }
          } catch (e: OkioIOException) {
            // The document can no longer be read, so its previous dump can't be trusted; remove it.
            failed++
            stderr("compose-preview: $document: ${e.message}")
            discardStaleDump(target)
            continue
          }
        val text = RemoteComposeJson.dump(bytes, pretty = !compact)
        replaceAtomically(target, text + "\n") { discardIfStale(target, text + "\n") }
      } catch (e: RemoteComposeJsonException) {
        // One unreadable document doesn't stop the batch; publish the rest and name it.
        failed++
        stderr("compose-preview: $document: ${e.message}")
        discardStaleDump(target)
      } catch (e: OkioIOException) {
        // The write failed after a successful projection. Keep an existing dump only if it matches
        // the new projection (via `replaceAtomically`'s failure hook). The batch continues.
        failed++
        stderr("compose-preview: $document: ${e.message}")
      }
    }
    val considered = documents.size + unstattable.size
    stderr("compose-preview: dumped ${considered - failed}/$considered documents under $root")
    if (failed > 0 || walked.stoppedBy != null) exit(1)
  }

  /**
   * Write [text] to [target] via a temporary sibling moved into place, so a failed write never
   * leaves a truncated dump that the publish step would copy. The `.tmp` suffix keeps leftovers out
   * of the publish glob.
   */
  private fun replaceAtomically(target: Path, text: String, onFailure: () -> Unit = {}) {
    // A freshly claimed temp name, never a fixed one, so it can't follow a symlink or truncate
    // someone else's file. Assigned inside the try because claiming can fail too.
    var temp: Path? = null
    try {
      temp = claimAndWrite(target, text)
      // Re-check right before the move: an authoring file may have appeared during the projection.
      // This narrows the race to one stat; the available primitives can't close it.
      if (!mayWrite(target)) {
        throw OkioIOException("$target changed after it was approved for overwriting; left alone")
      }
      fileSystem.atomicMove(temp, target)
    } catch (e: OkioIOException) {
      try {
        temp?.let { fileSystem.delete(it, mustExist = false) }
      } catch (_: OkioIOException) {
        // Reported via the caller's handler; an unremovable temp isn't worth a second message.
      }
      onFailure()
      throw e
    }
  }

  /**
   * After a failed replacement, remove [target] unless it matches the projection that couldn't be
   * written.
   */
  private fun discardIfStale(target: Path, projected: String) {
    val existing =
      try {
        if (fileSystem.metadataOrNull(target)?.symlinkTarget != null) return
        fileSystem.read(target) { readUtf8() }
      } catch (_: OkioIOException) {
        return
      }
    if (existing != projected) discardStaleDump(target)
  }

  /**
   * Claim the first `<target>.tmp`, `<target>.1.tmp`, … that can be created (`mustCreate` refuses
   * existing names, including symlinks). Claim then write, so a taken name and a real write failure
   * are handled differently. Bounded to avoid a hang.
   */
  private fun claimAndWrite(target: Path, text: String): Path {
    val parent = target.parent!!
    var lastFailure: OkioIOException? = null
    for (attempt in 0 until MAX_TEMP_ATTEMPTS) {
      val suffix = if (attempt == 0) ".tmp" else ".$attempt.tmp"
      val candidate = parent / "${target.name}$suffix"
      val sink =
        try {
          // Write through the handle from the claim, never reopening by name.
          fileSystem.sink(candidate, mustCreate = true)
        } catch (e: OkioIOException) {
          // Most likely the name is taken; try the next. Real problems fail every candidate.
          lastFailure = e
          continue
        }
      // Past the claim a failure is real: propagate it, cleaning up the claimed file first.
      try {
        sink.buffer().use { it.writeUtf8(text) }
      } catch (e: OkioIOException) {
        try {
          fileSystem.delete(candidate, mustExist = false)
        } catch (_: OkioIOException) {
          // Reported as part of the write failure.
        }
        throw e
      }
      return candidate
    }
    throw OkioIOException(
      "could not claim a temporary name beside $target after $MAX_TEMP_ATTEMPTS attempts" +
        (lastFailure?.message?.let { " (last: $it)" } ?: "")
    )
  }

  /**
   * Remove this command's previous dump once its document stops projecting, so a stale `.rc.json`
   * doesn't silently survive on a delivery branch. Only files [mayWrite] recognises as dumps;
   * failures are reported, not thrown.
   */
  private fun discardStaleDump(target: Path) {
    if (!fileSystem.exists(target) || !mayWrite(target)) return
    try {
      fileSystem.delete(target)
      stderr("compose-preview: $target: removed — it described a document that no longer projects")
    } catch (e: OkioIOException) {
      stderr("compose-preview: $target: stale, and could not be removed: ${e.message}")
    }
  }

  /**
   * Whether [target] may be written: it doesn't exist, or it is a previous dump (a `header` object
   * beside an `operations` array). `<stem>.rc.json` is also a plausible name for authoring JSON,
   * and overwriting that is unrecoverable.
   */
  private fun mayWrite(target: Path): Boolean {
    val metadata = fileSystem.metadataOrNull(target) ?: return true
    // Refuse symlinks outright: following one could approve and overwrite a dump outside the tree.
    if (metadata.symlinkTarget != null) return false
    val existing =
      try {
        Json.parseToJsonElement(fileSystem.read(target) { readUtf8() }) as? JsonObject
          ?: return false
      } catch (_: Exception) {
        return false
      }
    // `root` marks the authoring dialect and never appears in a dump, so check it first.
    if ("root" in existing) return false
    // Shapes, not just key names.
    return existing["header"] is JsonObject && existing["operations"] is JsonArray
  }

  /**
   * Walk [root] without following directory symlinks (explicitly, in case okio's default changes),
   * so the walk is bounded and never writes outside the tree.
   *
   * Drained lazily: an unlistable subtree throws mid-iteration, so the walk stops there, keeps what
   * was enumerated, and reports the reason in [Walk.stoppedBy].
   */
  private fun walk(root: Path): Walk {
    val entries = mutableListOf<Path>()
    val iterator = fileSystem.listRecursively(root, followSymlinks = false).iterator()
    while (true) {
      val next =
        try {
          if (!iterator.hasNext()) break
          iterator.next()
        } catch (e: OkioIOException) {
          return Walk(entries, e.message ?: e.toString())
        }
      entries += next
    }
    return Walk(entries, null)
  }

  /** What [walk] enumerated, and why it stopped early if it did. */
  private class Walk(val entries: List<Path>, val stoppedBy: String?)

  /**
   * Refuse a tree with filenames this JVM can't decode. Under a non-UTF-8 `sun.jnu.encoding` (e.g.
   * `LANG=C`), names with bytes above 0x7F come back mangled and unresolvable, and preview ids can
   * contain such bytes; a batch dump would otherwise report "no .rc documents". Only actual
   * undecodable entries trigger it.
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
    val input = args.singleInput("rc header", "a .rc file")
    val header = guard { RemoteComposeJson.header(readBytesOrFail(input.toPath(), "rc header")) }

    if ("--json" in args) {
      // `toJsonObject()` omits fields the document didn't declare, where the serializer would emit
      // `null`.
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
   * Name the two profile masks seen in practice; a player silently draws nothing for an unsupported
   * profile, so a bare number isn't enough.
   */
  private fun ee.schimke.composeai.remotecompose.json.RemoteComposeDocumentHeader.profileNote() =
    when (profiles) {
      512 -> "  (ANDROIDX)"
      513 -> "  (EXPERIMENTAL)"
      else -> ""
    }

  /** Report a codec failure as its message (which names the JSON path), not a stack trace. */
  private fun <T> guard(block: () -> T): T =
    try {
      block()
    } catch (e: RemoteComposeJsonException) {
      fail("compose-preview: ${e.message}")
    }

  /** `metadataOrNull` with its stat exception turned into this command's diagnostic. */
  private fun statOrFail(path: Path, what: String): FileMetadata? =
    try {
      fileSystem.metadataOrNull(path)
    } catch (e: OkioIOException) {
      fail("$what: cannot read $path: ${e.message}")
    }

  /**
   * Read [path] as strict UTF-8; lenient decoding would silently replace bad bytes and compile
   * wrong labels.
   */
  private fun readTextOrFail(path: Path, what: String): String {
    val bytes = readBytesOrFail(path, what)
    return try {
      Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    } catch (e: CharacterCodingException) {
      fail("$what: $path is not valid UTF-8 (${e.message ?: e::class.simpleName})")
    }
  }

  private fun readBytesOrFail(path: Path, what: String): ByteArray {
    // `metadataOrNull` can't tell missing from unreadable, so say both.
    if (statOrFail(path, what)?.isRegularFile != true) {
      fail("$what: no such file (or not readable): $path")
    }
    return try {
      fileSystem.read(path) { readByteArray() }
    } catch (e: OkioIOException) {
      // Existing but unreadable (permissions, stale NFS): report one line instead of a stack trace.
      fail("$what: cannot read $path: ${e.message}")
    }
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

    /** What each subcommand actually reads — the per-subcommand half of `rc`'s flag allowlist. */
    val READS =
      mapOf(
        "compile" to setOf("--output", "-o"),
        "dump" to setOf("--output", "-o", "--compact"),
        "header" to setOf("--json"),
      )

    val HELP = setOf("--help", "-h")

    /** Bound on the temporary-name scan — see [freeTempPath]. */
    const val MAX_TEMP_ATTEMPTS = 100

    /** The replacement character a directory listing substitutes for a byte it cannot decode. */
    const val UNDECODABLE: Char = '\uFFFD'
  }
}
