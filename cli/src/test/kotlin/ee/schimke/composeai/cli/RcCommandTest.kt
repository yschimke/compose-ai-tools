package ee.schimke.composeai.cli

import ee.schimke.composeai.remotecompose.json.RemoteComposeJson
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException as OkioIOException
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import okio.fakefilesystem.FakeFileSystem

/**
 * `rc dump <dir>` in memory.
 *
 * This is what injecting the `FileSystem` buys, and the reason `docs/AGENT_GUIDE.md` asks for it:
 * the batch mode's interesting behaviour is all filesystem behaviour — which targets it will
 * overwrite, which it refuses, what it does with a document it cannot read — and every one of those
 * cases is a `FakeFileSystem` fixture rather than a temp directory and a cleanup.
 *
 * The command's other half — argument dispatch and the messages it prints — is exercised against
 * the built distribution instead, because `exitProcess` is the thing under test there and a test
 * JVM has no answer to that.
 */
class RcCommandTest {

  // `allowSymlinks` is off by default and `createSymlink` throws without it — which would leave
  // the symlink test passing for the wrong reason if it were caught rather than enabled.
  private val fs = FakeFileSystem().apply { allowSymlinks = true }
  private val dir = "/docs".toPath()

  private val document: ByteArray =
    RemoteComposeJson.compile(
      """{"header":{"width":10,"height":10},"root":[{"box":{"modifiers":[{"size":10.0}]}}]}"""
    )

  /** Thrown by the injected exit seam, so a refusal path ends the command and not the JVM. */
  private class Exited(val code: Int) : RuntimeException()

  private val err = mutableListOf<String>()

  private fun run(vararg args: String, fileSystem: FileSystem = fs) =
    RcCommand(
        args.toList(),
        fileSystem,
        stdout = {},
        stderr = { err += it },
        exit = { throw Exited(it) },
      )
      .run()

  /** Run expecting the command to refuse, and return the exit code it asked for. */
  private fun runExpectingExit(vararg args: String, fileSystem: FileSystem = fs): Int =
    try {
      run(*args, fileSystem = fileSystem)
      error("expected a non-zero exit")
    } catch (e: Exited) {
      e.code
    }

  @Test
  fun `dumps every document under a directory`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }
    fs.write(dir / "b.rc") { write(document) }

    run("dump", dir.toString())

    assertTrue(fs.exists(dir / "a.rc.json"))
    assertTrue(fs.exists(dir / "b.rc.json"))
    assertContains(fs.read(dir / "a.rc.json") { readUtf8() }, "RootLayoutComponent")
  }

  @Test
  fun `re-dumping overwrites its own output`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }

    run("dump", dir.toString())
    val first = fs.read(dir / "a.rc.json") { readUtf8() }
    run("dump", dir.toString())

    // Idempotence is what the delivery lane needs — it re-runs on every publish — so it is pinned
    // rather than assumed from the overwrite guard's shape.
    assertEquals(first, fs.read(dir / "a.rc.json") { readUtf8() })
  }

  @Test
  fun `refuses to overwrite authoring json`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }
    // The collision: `<stem>.rc` dumps to `<stem>.rc.json`, which is also an ordinary name for the
    // AUTHORING json that produced it. Document JSON has no parser, so overwriting is one-way harm.
    val source = """{"header":{"width":10},"root":[{"box":{}}]}"""
    fs.write(dir / "a.rc.json") { writeUtf8(source) }

    assertEquals(1, runExpectingExit("dump", dir.toString()))

    assertEquals(source, fs.read(dir / "a.rc.json") { readUtf8() })
    assertTrue(err.any { "refusing to overwrite" in it }, "names the refusal: $err")
  }

  @Test
  fun `one unreadable document does not stop the batch`() {
    fs.createDirectories(dir)
    fs.write(dir / "good.rc") { write(document) }
    fs.write(dir / "bad.rc") { writeUtf8("not a remote compose document") }

    // Non-zero so a workflow notices, having still written what it could.
    assertEquals(1, runExpectingExit("dump", dir.toString()))

    // Publishing the readable documents and naming the one that failed beats publishing none — the
    // delivery lane is fail-soft for exactly this, and it is the CLI that has to make it so.
    assertTrue(fs.exists(dir / "good.rc.json"))
    assertFalse(fs.exists(dir / "bad.rc.json"))
    assertTrue(err.any { "bad.rc" in it }, "names the document that failed: $err")
  }

  @Test
  fun `an unlistable subtree does not stop the batch`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }
    fs.createDirectories(dir / "locked")

    // `listRecursively` is lazy: on a real filesystem an unlistable subtree throws from
    // `hasNext()`/`next()` part-way through the walk, not from the call that returned the
    // sequence — which is exactly why draining it entry-by-entry matters, since `.toList()` let it
    // past every per-document handler and killed the command with a stack trace having dumped
    // nothing.
    //
    // The sequence is built here rather than by overriding `list`, because
    // `ForwardingFileSystem.listRecursively` delegates to the DELEGATE's own `listRecursively` and
    // never calls this class's `list` at all. Overriding `list` produced a passing command and a
    // test that proved nothing.
    val unlistable =
      object : ForwardingFileSystem(fs) {
        override fun listRecursively(dir: Path, followSymlinks: Boolean): Sequence<Path> =
          sequence {
            yieldAll(fs.list(dir))
            throw OkioIOException("Permission denied")
          }
      }

    assertEquals(1, runExpectingExit("dump", dir.toString(), fileSystem = unlistable))

    assertTrue(fs.exists(dir / "a.rc.json"), "dumps what it enumerated before the failure")
    assertTrue(err.any { "Permission denied" in it }, "names why the walk stopped: $err")
  }

  @Test
  fun `an unreadable input is a message and not a stack trace`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }

    // Existing and readable are different questions: the file is a regular file, so the metadata
    // check passes and the failure lands on the read itself.
    val unreadable =
      object : ForwardingFileSystem(fs) {
        override fun source(file: Path): Source = throw OkioIOException("Permission denied")
      }

    assertEquals(1, runExpectingExit("dump", (dir / "a.rc").toString(), fileSystem = unreadable))

    assertTrue(err.any { "cannot read" in it && "Permission denied" in it }, "names it: $err")
  }

  @Test
  fun `a stale dump is removed when its document stops projecting`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }
    run("dump", dir.toString())
    assertTrue(fs.exists(dir / "a.rc.json"))

    // The document is replaced by one this CLI cannot project — a newer alpha's opcodes, in the
    // case the delivery lane actually hits.
    fs.write(dir / "a.rc") { writeUtf8("not a remote compose document") }

    assertEquals(1, runExpectingExit("dump", dir.toString()))

    // A gap is visible; a stale file is not. Left in place it still parses, still reads like a
    // projection of the `.rc` beside it, and `git diff` on the delivery branch shows nothing at
    // all — which is worse than publishing no document for that sticker.
    assertFalse(fs.exists(dir / "a.rc.json"), "the stale dump is gone: ${fs.list(dir)}")
  }

  @Test
  fun `refuses to write a dump through a symlink`() {
    fs.createDirectories(dir)
    fs.createDirectories("/elsewhere".toPath())
    fs.write(dir / "a.rc") { write(document) }
    // A real dump, outside the tree, that the target links to. `mayWrite` would read *through* the
    // link, recognise a previous dump and approve it — and the write would follow the same link.
    val outside = "/elsewhere/other.rc.json".toPath()
    fs.write(outside) { writeUtf8("""{"header":{},"operations":[]}""") }
    fs.createSymlink(dir / "a.rc.json", outside)

    assertEquals(1, runExpectingExit("dump", dir.toString()))

    // The walk already refuses to descend into a symlinked directory; that boundary was one-sided
    // until the target got checked on its own metadata rather than the metadata of what it
    // resolves to.
    assertEquals("""{"header":{},"operations":[]}""", fs.read(outside) { readUtf8() })
    assertTrue(err.any { "it is a symlink" in it }, "names the refusal: $err")
  }

  @Test
  fun `refuses to dump a document over itself`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }

    // `-o` naming the input truncates the only binary copy and leaves the lossy projection there.
    // Same one-way harm the directory mode's overwrite guard exists for, reached by a different
    // typo — and the canonical comparison is what makes the indirect spelling refuse too.
    assertEquals(1, runExpectingExit("dump", (dir / "a.rc").toString(), "-o", "/docs/./a.rc"))

    assertContentEquals(document, fs.read(dir / "a.rc") { readByteArray() })
    assertTrue(err.any { "would overwrite the document being dumped" in it }, "names it: $err")
  }

  @Test
  fun `refuses to compile a source over itself`() {
    fs.createDirectories(dir)
    val source =
      """{"header":{"width":10,"height":10},"root":[{"box":{"modifiers":[{"size":10.0}]}}]}"""
    fs.write(dir / "s.json") { writeUtf8(source) }

    // The mirror of the dump guard and the costlier of the two: compiling is one-way, so the
    // authoring JSON — the only copy of what a person wrote — cannot be recovered from the `.rc`
    // that replaced it.
    assertEquals(1, runExpectingExit("compile", (dir / "s.json").toString(), "-o", "/docs/s.json"))

    assertEquals(source, fs.read(dir / "s.json") { readUtf8() })
    assertTrue(err.any { "would overwrite the authoring JSON" in it }, "names it: $err")
  }

  @Test
  fun `a failed write leaves the previous dump intact`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }
    run("dump", dir.toString())
    val good = fs.read(dir / "a.rc.json") { readUtf8() }

    // A sink that dies part-way — a full filesystem, in practice. Writing straight to the target
    // truncates it first, so the old code left a PARTIAL dump: not a valid projection, and not
    // absent either, so `discardStaleDump` could not recognise it and the publish step downstream
    // would copy it into `documents/` and count it as projected.
    //
    // Modelled faithfully: the sink OPENS (truncating whatever it points at) and then fails on the
    // write. A `sink()` that simply threw would never truncate, and the old code would have passed
    // this test while leaving the very file it is about.
    val failing =
      object : ForwardingFileSystem(fs) {
        override fun sink(file: Path, mustCreate: Boolean): Sink {
          val delegate = super.sink(file, mustCreate)
          if (!file.name.startsWith("a.rc.json")) return delegate
          return object : Sink by delegate {
            override fun write(source: Buffer, byteCount: Long) =
              throw OkioIOException("No space left on device")
          }
        }
      }

    assertEquals(1, runExpectingExit("dump", dir.toString(), fileSystem = failing))

    assertEquals(good, fs.read(dir / "a.rc.json") { readUtf8() }, "the good dump is untouched")
    assertFalse(fs.exists(dir / "a.rc.json.tmp"), "no temp left behind: ${fs.list(dir)}")
  }

  @Test
  fun `a stale dump goes when the document becomes unreadable, and stays when the write fails`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }
    run("dump", dir.toString())
    val good = fs.read(dir / "a.rc.json") { readUtf8() }

    // Read failure: nothing is known about the document any more — same file with changed
    // permissions, or a different one a render has since written. The dump cannot be checked
    // against anything and still reads like a current projection, so it goes.
    val unreadable =
      object : ForwardingFileSystem(fs) {
        override fun source(file: Path): Source =
          if (file.name.endsWith(".rc")) throw OkioIOException("Permission denied")
          else super.source(file)
      }
    assertEquals(1, runExpectingExit("dump", dir.toString(), fileSystem = unreadable))
    assertFalse(fs.exists(dir / "a.rc.json"), "gone: ${fs.list(dir)}")

    // Write failure is the opposite case, and the asymmetry is the whole point: the read and the
    // projection both succeeded, so what the existing dump says can be CHECKED rather than
    // assumed — the projected text is in hand. Matching, it is kept.
    run("dump", dir.toString())
    val unwritable =
      object : ForwardingFileSystem(fs) {
        override fun sink(file: Path, mustCreate: Boolean): Sink =
          if (file.name.endsWith(".tmp")) throw OkioIOException("No space left on device")
          else super.sink(file, mustCreate)
      }
    assertEquals(1, runExpectingExit("dump", dir.toString(), fileSystem = unwritable))
    assertEquals(good, fs.read(dir / "a.rc.json") { readUtf8() }, "kept")

    // …and not matching, it goes. "The projection succeeded" says nothing about what the file
    // already there describes: the document may have changed since the run that wrote it, leaving
    // a valid-looking projection of a document that no longer exists.
    fs.write(dir / "a.rc.json") { writeUtf8("""{"header":{},"operations":[{"type":"Stale"}]}""") }
    assertEquals(1, runExpectingExit("dump", dir.toString(), fileSystem = unwritable))
    assertFalse(fs.exists(dir / "a.rc.json"), "the stale one goes: ${fs.list(dir)}")
  }

  @Test
  fun `will not truncate a temporary path it did not create`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }
    // Somebody's file that happens to sit at the name the atomic write would have claimed. The
    // guard added for symlinks made this case worse, not better: it declared every plain file
    // there command-owned and opened it with overwrite semantics.
    fs.write(dir / "a.rc.json.tmp") { writeUtf8("not mine") }

    run("dump", dir.toString())

    assertEquals("not mine", fs.read(dir / "a.rc.json.tmp") { readUtf8() })
    assertContains(fs.read(dir / "a.rc.json") { readUtf8() }, "RootLayoutComponent")
  }

  @Test
  fun `refuses an authoring file that is not valid utf-8`() {
    fs.createDirectories(dir)
    // Valid JSON structurally; one byte inside a string is not valid UTF-8. `decodeToString()`
    // would substitute U+FFFD, the parser would accept the repaired text, and `compile` would
    // report success over a document whose content is not what the file says.
    val source = """{"header":{"width":10,"height":10},"root":[{"text":{"value":"x"""
    fs.write(dir / "s.json") {
      writeUtf8(source)
      write(byteArrayOf(0xFF.toByte()))
      writeUtf8(""""}}]}""")
    }

    assertEquals(1, runExpectingExit("compile", (dir / "s.json").toString(), "-o", "/docs/out.rc"))

    assertTrue(err.any { "not valid UTF-8" in it }, "names it: $err")
    assertFalse(fs.exists(dir / "out.rc"))
  }

  @Test
  fun `names a flag the chosen subcommand does not read`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }

    // `CliFlagValidation`'s `rc` entry is the union of the three subcommands' flags — it runs
    // before the subcommand is resolved — so it cannot see that `header -o` names an output
    // nothing will write. Silently ignored is the failure that validator exists to warn about.
    run("header", (dir / "a.rc").toString(), "-o", "report.json")

    assertTrue(err.any { "'-o' has no effect on 'rc header'" in it }, "warns: $err")
    assertFalse(fs.exists("report.json".toPath()), "and wrote nothing")
  }

  @Test
  fun `will not write a dump through a symlinked temporary`() {
    fs.createDirectories(dir)
    fs.createDirectories("/elsewhere".toPath())
    fs.write(dir / "a.rc") { write(document) }
    val outside = "/elsewhere/precious".toPath()
    fs.write(outside) { writeUtf8("not mine") }
    // The first candidate temp name is a path something else can have made into a symlink first,
    // and writing through it would truncate a file outside the tree — the escape `mayWrite`
    // refuses for the target, reintroduced by the guard meant to make writing safer.
    fs.createSymlink(dir / "a.rc.json.tmp", outside)

    run("dump", dir.toString())

    // Stepped over rather than refused: `metadataOrNull` answers for the link itself, so the name
    // reads as occupied and the next candidate is claimed. Nothing outside is touched and the
    // document still gets its dump, which beats failing the batch over someone else's symlink.
    assertEquals("not mine", fs.read(outside) { readUtf8() })
    assertContains(fs.read(dir / "a.rc.json") { readUtf8() }, "RootLayoutComponent")
  }

  @Test
  fun `refuses a second input operand`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }
    fs.write(dir / "b.rc") { write(document) }

    // Taking the first of several silently produces output for a different input than the caller
    // named — a typo that reads as a success.
    assertEquals(
      1,
      runExpectingExit(
        "dump",
        (dir / "a.rc").toString(),
        (dir / "b.rc").toString(),
        "-o",
        "/o.json",
      ),
    )

    assertTrue(err.any { "this command takes one at a time" in it }, "names it: $err")
    assertFalse(fs.exists("/o.json".toPath()))
  }

  @Test
  fun `refuses a repeated output flag`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }
    val doc = (dir / "a.rc").toString()

    // A valid earlier occurrence used to mask a malformed later one: `flagValue` found
    // `report.json` and never looked at the trailing `--output`.
    assertEquals(1, runExpectingExit("dump", doc, "-o", "/r.json", "--output"))
    assertFalse(fs.exists("/r.json".toPath()), "wrote nothing")

    // And two destinations is a caller who believes something untrue about what this will write.
    err.clear()
    assertEquals(1, runExpectingExit("dump", doc, "--output", "/first.json", "-o", "/second.json"))
    assertTrue(err.any { "given more than once" in it }, "names it: $err")
    assertFalse(fs.exists("/first.json".toPath()))
    assertFalse(fs.exists("/second.json".toPath()))
  }

  @Test
  fun `does not mistake a look-alike for one of its own dumps`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }
    // Both key names, neither shape. A membership-only check called this a previous dump and let
    // the write destroy it — the exact guarantee the check exists to make.
    val lookalike = """{"header":null,"operations":null}"""
    fs.write(dir / "a.rc.json") { writeUtf8(lookalike) }

    assertEquals(1, runExpectingExit("dump", dir.toString()))

    assertEquals(lookalike, fs.read(dir / "a.rc.json") { readUtf8() })
    assertTrue(err.any { "refusing to overwrite" in it }, "names the refusal: $err")
  }

  @Test
  fun `refuses an output flag with nothing after it`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }

    // `flagValue` answers null both for "not given" and for "given with nothing after it", and the
    // branches downstream read that null as absent — so this printed the dump to stdout, and the
    // directory form wrote beside the sources, each doing something nobody asked for and drawing
    // no "unrecognised option" warning either, because `-o` is on `rc`'s allowlist.
    assertEquals(1, runExpectingExit("dump", (dir / "a.rc").toString(), "-o"))
    assertEquals(1, runExpectingExit("dump", dir.toString(), "-o"))
    // A value that is itself a flag would have created a file named `--compact`.
    assertEquals(1, runExpectingExit("dump", (dir / "a.rc").toString(), "-o", "--compact"))

    assertFalse(fs.exists(dir / "a.rc.json"), "wrote nothing: ${fs.list(dir)}")
    assertTrue(err.all { "--output needs a file after it" in it }, "names it: $err")
  }

  @Test
  fun `dumps to a different file happily`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }

    // The guard must not catch the ordinary case: the output does not exist yet, so canonicalising
    // it fails, and a path that is not there cannot be the input that is.
    run("dump", (dir / "a.rc").toString(), "-o", (dir / "out.json").toString())

    assertContains(fs.read(dir / "out.json") { readUtf8() }, "RootLayoutComponent")
  }

  @Test
  fun `does not descend into a symlinked directory`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }
    fs.createDirectories("/elsewhere".toPath())
    fs.write("/elsewhere/outside.rc".toPath()) { write(document) }
    fs.createSymlink(dir / "link", "/elsewhere".toPath())

    run("dump", dir.toString())

    // Following it would write `.rc.json` files outside the tree the command was pointed at, and a
    // link to an ancestor would make the walk unbounded.
    assertTrue(fs.exists(dir / "a.rc.json"))
    assertFalse(fs.exists("/elsewhere/outside.rc.json".toPath()))
  }
}
