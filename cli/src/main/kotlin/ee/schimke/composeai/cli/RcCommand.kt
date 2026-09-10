package ee.schimke.composeai.cli

import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.remotecompose.json.RemoteComposeJson
import ee.schimke.composeai.remotecompose.json.RemoteComposeJsonException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
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
   * Name a flag the chosen subcommand does not read.
   *
   * `CliFlagValidation`'s entry for `rc` is necessarily the **union** of what `compile`, `dump` and
   * `header` read — it validates at the routed-command boundary, where the subcommand has not been
   * resolved yet — so it cannot tell that `rc header doc.rc -o report.json` names an output nothing
   * will write, or that `rc compile x.json --compact` asks for formatting of a binary. Each is
   * silently ignored, which is precisely the failure that validator exists to warn about; only this
   * class knows enough to say so.
   *
   * A warning rather than a refusal, matching what the CLI does with an unrecognised option
   * everywhere else: the invocation still means something, and nothing downstream breaks.
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
    val input = CliFlags.positionals(args).firstOrNull() ?: fail("rc compile: expected a JSON file")
    val out = args.outputValue("rc compile")
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
    // The mirror of `dump`'s guard, and the more expensive mistake of the two: the authoring JSON
    // is the source, and `compile` is one-way — a `.rc` cannot be turned back into the document
    // that produced it, so overwriting the source with its own output loses the only copy of the
    // thing a person actually wrote.
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
    // `CliFlags.positionals`, not "the first token without a dash". `rc dump --output out.json
    // input.rc` puts `out.json` first by that reading, so the command would dump the file it was
    // asked to write — and `rc compile -o out.rc in.json` would compile its own output path.
    val input = CliFlags.positionals(args).firstOrNull() ?: fail("rc dump: expected a .rc file")
    val compact = "--compact" in args
    val out = args.outputValue("rc dump")
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
    } else if (isSameFile(out.toPath(), file)) {
      // `rc dump doc.rc -o doc.rc` would truncate the only copy of the document and leave the
      // lossy projection in its place. There is no undo: document JSON has no parser, so the `.rc`
      // it replaced cannot be recovered from it. Same one-way harm the directory mode's overwrite
      // guard exists for, arrived at by a different typo.
      //
      // Canonicalised on both sides so `-o ./doc.rc` and a symlink pointing back at the input are
      // caught too; a path that cannot be canonicalised (the output does not exist yet, which is
      // the ordinary case) simply is not the input.
      fail(
        "rc dump: --output would overwrite the document being dumped ($out). Document JSON " +
          "cannot be compiled back, so this would destroy it — name a different file."
      )
    } else {
      writing("rc dump", out) { path -> fileSystem.write(path) { writeUtf8(text + "\n") } }
    }
  }

  /**
   * `--output` / `-o`'s value, refusing the flag when it has none.
   *
   * `flagValue` answers `null` both for "not given" and for "given with nothing after it", and
   * every branch downstream reads that null as *absent*. So `rc dump doc.rc -o` printed the dump to
   * stdout and `rc dump renders -o` wrote beside the sources — each doing something the caller
   * plainly did not ask for, and silently, because the flag is on `rc`'s allowlist and so draws no
   * "unrecognised option" warning either.
   *
   * A value that is itself a flag is refused for the same reason: `rc dump doc.rc -o --compact`
   * would otherwise write a file named `--compact`. `-` is the exception, since `dump` honours it
   * as the stdout sentinel.
   */
  private fun List<String>.outputValue(what: String): String? {
    val given = any {
      it == "--output" || it == "-o" || it.startsWith("--output=") || it.startsWith("-o=")
    }
    if (!given) return null
    val value = flagValue("--output") ?: flagValue("-o")
    if (value.isNullOrBlank() || (value.startsWith("-") && value != "-")) {
      fail("$what: --output needs a file after it (got ${value?.let { "'$it'" } ?: "nothing"})")
    }
    return value
  }

  /**
   * Whether [a] and [b] name the same file on disk.
   *
   * Canonicalised rather than compared as strings, so `./doc.rc`, `docs/../docs/doc.rc` and a
   * symlink pointing back at the input are all the same file. `canonicalize` throws for a path that
   * does not exist — the ordinary case for an output — and a path that is not there cannot be the
   * input that just was, so that failure answers `false` rather than propagating.
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
    val walked = walk(root)
    refuseUndecodableNames(root, walked.entries)
    val documents =
      walked.entries
        .filter { fileSystem.metadataOrNull(it)?.isRegularFile == true && it.name.endsWith(".rc") }
        .sorted()
    if (documents.isEmpty()) {
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

    var failed = 0
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
        // Read on its own, because a failure HERE means something different from a failure on the
        // write below, and the difference decides the fate of the previous dump.
        val bytes =
          try {
            fileSystem.read(document) { readByteArray() }
          } catch (e: OkioIOException) {
            // Nothing is known about this document any more — it may be the same file with its
            // permissions changed, or a wholly different one written by a render that has moved
            // on. The dump beside it still reads like a current projection and cannot be checked
            // against anything, so it goes, the same as one whose document stopped projecting.
            // It costs a re-run; keeping it costs a reader believing a file that may describe a
            // document that no longer exists.
            failed++
            stderr("compose-preview: $document: ${e.message}")
            discardStaleDump(target)
            continue
          }
        val text = RemoteComposeJson.dump(bytes, pretty = !compact)
        replaceAtomically(target, text + "\n") { discardIfStale(target, text + "\n") }
      } catch (e: RemoteComposeJsonException) {
        // One unreadable document does not stop the batch. A catalog with a single sticker captured
        // from a newer alpha than this CLI links would otherwise publish NO documents at all, which
        // is a strictly worse outcome than publishing the other thirty-nine and naming the one.
        failed++
        stderr("compose-preview: $document: ${e.message}")
        discardStaleDump(target)
      } catch (e: OkioIOException) {
        // Reaching here means the WRITE failed — the read and the projection both succeeded. What
        // that does NOT establish is that the file already there says the same thing: the document
        // may have changed since the run that wrote it, so the dump beside it can be a projection
        // of a document that no longer exists. `replaceAtomically`'s failure hook settles it by
        // comparison rather than by assumption — the projected text is in hand, so an existing
        // dump that matches it is kept and one that does not is removed.
        //
        // The batch survives it either way. Without this handler the loop aborts on the first
        // full disk, having neither processed the rest nor printed the count a caller checks.
        failed++
        stderr("compose-preview: $document: ${e.message}")
      }
    }
    stderr(
      "compose-preview: dumped ${documents.size - failed}/${documents.size} documents under $root"
    )
    if (failed > 0 || walked.stoppedBy != null) exit(1)
  }

  /**
   * Write [text] to [target] by writing a sibling and moving it into place.
   *
   * Writing straight to the target truncates it first, so a sink that fails part-way — a full
   * filesystem is the ordinary way — leaves a **partial** dump where a complete one used to be.
   * That file is worse than either outcome the batch is built around: it is not a valid projection,
   * and it is not absent either, so [discardStaleDump] cannot recognise it as one of this command's
   * dumps and leaves it, and the publish step downstream copies every `*.rc.json` it finds into
   * `out/documents/` and counts it as projected. Truncated JSON on a delivery branch, from a run
   * that reported the failure and exited non-zero.
   *
   * A temporary sibling never has that window: it is either moved into place whole or deleted. The
   * `.tmp` suffix keeps it out of the publish glob even if the process dies between the two.
   */
  private fun replaceAtomically(target: Path, text: String, onFailure: () -> Unit = {}) {
    // A path that does not exist, rather than a fixed `<target>.tmp`. The fixed name was a second
    // way to destroy a file this command did not write: a symlink there would be followed out of
    // the tree, and an ordinary `a.rc.json.tmp` a person happens to keep beside `a.rc` would be
    // truncated — the exact thing `mayWrite` exists to refuse, reintroduced by the guard meant to
    // make writing safer. Claiming an unused name instead means the write can only ever land on
    // something this command made, and a leftover from a crashed run neither blocks the dump nor
    // gets clobbered.
    val temp = freeTempPath(target)
    try {
      fileSystem.write(temp) { writeUtf8(text) }
      fileSystem.atomicMove(temp, target)
    } catch (e: OkioIOException) {
      try {
        fileSystem.delete(temp, mustExist = false)
      } catch (_: OkioIOException) {
        // Reported by the caller's handler as part of the write failure; a temp file that cannot be
        // removed is not worth a second message, and it cannot be published.
      }
      onFailure()
      throw e
    }
  }

  /**
   * Remove [target] if it disagrees with the projection that could not be written.
   *
   * Called when the replacement fails. The previous dump is only sound if it says what the new one
   * would have said, and that is a question with an answer rather than a judgement call: the
   * projected text is right here. Matching means nothing was lost and the file stays. Differing
   * means it is a projection of a document that has since changed — the same stale file a codec
   * failure removes — and it goes.
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
   * The first `<target>.tmp`, `<target>.1.tmp`, … that nothing occupies.
   *
   * `metadataOrNull` answers for the path itself rather than what it resolves to, so a symlink
   * counts as occupied and is stepped over rather than followed. The scan is bounded because an
   * unbounded one is a hang: a directory that somehow holds every candidate is a filesystem
   * problem, and saying so beats spinning.
   */
  private fun freeTempPath(target: Path): Path {
    val parent = target.parent!!
    for (attempt in 0 until MAX_TEMP_ATTEMPTS) {
      val suffix = if (attempt == 0) ".tmp" else ".$attempt.tmp"
      val candidate = parent / "${target.name}$suffix"
      if (fileSystem.metadataOrNull(candidate) == null) return candidate
    }
    throw OkioIOException(
      "no free temporary name beside $target after $MAX_TEMP_ATTEMPTS attempts; " +
        "remove the leftover ${target.name}*.tmp files"
    )
  }

  /**
   * Remove a dump this command wrote earlier, once the document it described stops projecting.
   *
   * Leaving it is the failure mode that actually costs something. The batch is fail-soft by design,
   * so a document that starts failing — replaced, or captured from a newer alpha than this CLI
   * links — drops out of the run with a message and a non-zero exit. But its `.rc.json` from the
   * *previous* run stays on disk, still parses, still reads like a projection of the file beside
   * it, and now describes a document that no longer exists. On a delivery branch that is worse than
   * a gap: a gap is visible, and `git diff` on a stale file shows nothing at all.
   *
   * Deliberately narrow. Only a target [mayWrite] recognises as a prior dump is removed — never an
   * authoring document, never a file this command did not write — and a failure to remove it is
   * reported rather than thrown, since the batch is mid-flight and the other documents still have
   * to finish.
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
    val metadata = fileSystem.metadataOrNull(target) ?: return true
    // A symlink is refused on its own metadata, before anything reads through it. The walk already
    // declines to *descend* into a symlinked directory, but that boundary was one-sided: a
    // `<stem>.rc.json` that is itself a link resolves elsewhere, and both the structural check
    // below and the write follow it — so a link pointing at a real dump outside the tree would be
    // approved as "a previous dump" and then overwritten, which is exactly the writing-outside-the-
    // tree the walk exists to prevent. Refusing rather than resolving-and-comparing, because a
    // symlink here is not a shape this command produces and a publish step has no use for one.
    if (metadata.symlinkTarget != null) return false
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
   *
   * Drained one entry at a time rather than with `.toList()`, because `listRecursively` is **lazy**
   * and the listing of each descendant directory happens during iteration. A subtree the process
   * cannot list — a mode-000 directory in a published tree, an NFS mount that went away — throws
   * from `hasNext()`/`next()`, and a `.toList()` let that escape past every per-document handler
   * below: the command died with a stack trace having dumped nothing, which is precisely the
   * fail-soft the batch mode promises. Now the walk stops where the filesystem stopped it, the
   * entries already enumerated are still dumped, and [Walk.stoppedBy] carries the reason so the
   * caller can name it and exit non-zero.
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
      // `toJsonObject()`, not the data class's own serializer. The two agree on field names now
      // (`@SerialName("desiredFPS")` settled the one that did not), so this is no longer a
      // workaround — it is the omit-what-the-document-did-not-say behaviour: a header with no
      // declared FPS has no `desiredFPS` key here, where the generated serializer would emit
      // `null`, and "the document did not say" is the distinction this whole type is built on.
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

  /**
   * Read [path] as **strict** UTF-8.
   *
   * `decodeToString()` substitutes U+FFFD for a malformed byte sequence rather than failing, and
   * the JSON parser then accepts the repaired text — so an authoring file with one bad byte inside
   * a string compiles, reports success, and ships a document whose label or resource name is not
   * what the file says. Silently wrong, which is the same shape as the directory-listing hazard
   * `refuseUndecodableNames` refuses: a wrong answer that reads exactly like a right one.
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
    // "or not readable", because `metadataOrNull` cannot tell those apart and this was measured
    // rather than guessed: a directory the process may traverse but not read comes back null here,
    // and reporting that one as "no such file" sends the reader looking for a path that is right
    // in front of them.
    if (fileSystem.metadataOrNull(path)?.isRegularFile != true) {
      fail("$what: no such file (or not readable): $path")
    }
    return try {
      fileSystem.read(path) { readByteArray() }
    } catch (e: OkioIOException) {
      // Existing and readable are different questions, and the gap between them is where a real
      // tree lives: a mode-000 file, a stale NFS handle, a symlink to a device that went away.
      // `guard` catches codec failures and `writing` catches output failures; without this the
      // INPUT side of `compile`, single-file `dump` and `header` was the one path left printing a
      // stack trace, for a problem whose whole diagnosis is one line.
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
