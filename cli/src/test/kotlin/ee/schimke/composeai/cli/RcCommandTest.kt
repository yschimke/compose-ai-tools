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
 * `rc dump <dir>` in memory: the batch mode's interesting behaviour is filesystem behaviour, so
 * each case is a `FakeFileSystem` fixture. Argument dispatch and messages are tested against the
 * built distribution instead, since `exitProcess` is what's under test there.
 */
class RcCommandTest {

  // `createSymlink` throws unless `allowSymlinks` is on; catching that would pass the symlink test
  // for the wrong reason.
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

    // Idempotence is pinned: the delivery lane re-runs on every publish.
    assertEquals(first, fs.read(dir / "a.rc.json") { readUtf8() })
  }

  @Test
  fun `refuses to overwrite authoring json`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }
    // `<stem>.rc.json` is also a plausible authoring-JSON name, and overwriting it is
    // unrecoverable.
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

    // Publish the readable documents and name the failed one, rather than none.
    assertTrue(fs.exists(dir / "good.rc.json"))
    assertFalse(fs.exists(dir / "bad.rc.json"))
    assertTrue(err.any { "bad.rc" in it }, "names the document that failed: $err")
  }

  @Test
  fun `an unlistable subtree does not stop the batch`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }
    fs.createDirectories(dir / "locked")

    // `listRecursively` is lazy, so an unlistable subtree throws mid-iteration; draining entry by
    // entry keeps the per-document handlers in charge. Built here rather than by overriding `list`,
    // which `ForwardingFileSystem.listRecursively` never calls.
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

    // A regular file whose read fails: the metadata check passes and the failure lands on the read.
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

    // Replaced by a document this CLI can't project (e.g. a newer alpha's opcodes).
    fs.write(dir / "a.rc") { writeUtf8("not a remote compose document") }

    assertEquals(1, runExpectingExit("dump", dir.toString()))

    // A gap is visible; a stale dump that still parses and diffs clean is not.
    assertFalse(fs.exists(dir / "a.rc.json"), "the stale dump is gone: ${fs.list(dir)}")
  }

  @Test
  fun `refuses to write a dump through a symlink`() {
    fs.createDirectories(dir)
    fs.createDirectories("/elsewhere".toPath())
    fs.write(dir / "a.rc") { write(document) }
    // A real dump outside the tree that the target links to; reading through the link would approve
    // it and the write would follow it.
    val outside = "/elsewhere/other.rc.json".toPath()
    fs.write(outside) { writeUtf8("""{"header":{},"operations":[]}""") }
    fs.createSymlink(dir / "a.rc.json", outside)

    assertEquals(1, runExpectingExit("dump", dir.toString()))

    // The target is checked on its own metadata, not the link destination's.
    assertEquals("""{"header":{},"operations":[]}""", fs.read(outside) { readUtf8() })
    assertTrue(err.any { "it is a symlink" in it }, "names the refusal: $err")
  }

  @Test
  fun `refuses to dump a document over itself`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }

    // `-o` naming the input would truncate the only binary copy; canonical comparison catches
    // indirect spellings.
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

    // Compiling over the authoring JSON would lose the only copy of what a person wrote.
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

    // A sink that opens (truncating) and then fails part-way, like a full disk. Writing directly
    // left a partial dump the publish step would count; the atomic write must not. Modelled as
    // open-then-fail, since a sink that threw on open would never truncate.
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

    // Read failure: nothing is known about the document any more, so its dump goes.
    val unreadable =
      object : ForwardingFileSystem(fs) {
        override fun source(file: Path): Source =
          if (file.name.endsWith(".rc")) throw OkioIOException("Permission denied")
          else super.source(file)
      }
    assertEquals(1, runExpectingExit("dump", dir.toString(), fileSystem = unreadable))
    assertFalse(fs.exists(dir / "a.rc.json"), "gone: ${fs.list(dir)}")

    // Write failure: the projection is in hand, so a matching existing dump is kept…
    run("dump", dir.toString())
    val unwritable =
      object : ForwardingFileSystem(fs) {
        override fun sink(file: Path, mustCreate: Boolean): Sink =
          if (file.name.endsWith(".tmp")) throw OkioIOException("No space left on device")
          else super.sink(file, mustCreate)
      }
    assertEquals(1, runExpectingExit("dump", dir.toString(), fileSystem = unwritable))
    assertEquals(good, fs.read(dir / "a.rc.json") { readUtf8() }, "kept")

    // …and a non-matching one goes: it may describe a document that no longer exists.
    fs.write(dir / "a.rc.json") { writeUtf8("""{"header":{},"operations":[{"type":"Stale"}]}""") }
    assertEquals(1, runExpectingExit("dump", dir.toString(), fileSystem = unwritable))
    assertFalse(fs.exists(dir / "a.rc.json"), "the stale one goes: ${fs.list(dir)}")
  }

  @Test
  fun `will not truncate a temporary path it did not create`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }
    // Someone else's file at the first temp name must not be overwritten.
    fs.write(dir / "a.rc.json.tmp") { writeUtf8("not mine") }

    run("dump", dir.toString())

    assertEquals("not mine", fs.read(dir / "a.rc.json.tmp") { readUtf8() })
    assertContains(fs.read(dir / "a.rc.json") { readUtf8() }, "RootLayoutComponent")
  }

  @Test
  fun `refuses an authoring file that is not valid utf-8`() {
    fs.createDirectories(dir)
    // Valid JSON with one invalid UTF-8 byte inside a string; lenient decoding would compile the
    // wrong text and report success.
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

    // `CliFlagValidation` only knows the union of `rc`'s flags, so `header -o` must warn here.
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
    // A symlink at the first temp name would let the write escape the tree.
    fs.createSymlink(dir / "a.rc.json.tmp", outside)

    run("dump", dir.toString())

    // Stepped over rather than refused: the name reads as occupied, the next candidate is used, and
    // nothing outside is touched.
    assertEquals("not mine", fs.read(outside) { readUtf8() })
    assertContains(fs.read(dir / "a.rc.json") { readUtf8() }, "RootLayoutComponent")
  }

  @Test
  fun `refuses a second input operand`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }
    fs.write(dir / "b.rc") { write(document) }

    // Using the first of several operands would silently process a different input.
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

    // A valid earlier `-o` must not mask a trailing valueless `--output`.
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
    // Both key names but neither shape: not a dump, so it must not be overwritten.
    val lookalike = """{"header":null,"operations":null}"""
    fs.write(dir / "a.rc.json") { writeUtf8(lookalike) }

    assertEquals(1, runExpectingExit("dump", dir.toString()))

    assertEquals(lookalike, fs.read(dir / "a.rc.json") { readUtf8() })
    assertTrue(err.any { "refusing to overwrite" in it }, "names the refusal: $err")
  }

  @Test
  fun `a named input that cannot be stat-ed is a message and not a stack trace`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }

    // `metadataOrNull` throws for unstattable paths at these input boundaries.
    val unstattable =
      object : ForwardingFileSystem(fs) {
        override fun metadataOrNull(path: Path) = throw OkioIOException("Permission denied")
      }

    assertEquals(1, runExpectingExit("dump", (dir / "a.rc").toString(), fileSystem = unstattable))

    assertTrue(err.any { "cannot read" in it && "Permission denied" in it }, "names it: $err")
  }

  @Test
  fun `an entry that cannot be stat-ed does not stop the batch`() {
    fs.createDirectories(dir)
    fs.write(dir / "good.rc") { write(document) }
    fs.write(dir / "locked.rc") { write(document) }

    // An unstattable entry must fail on its own, not abort the batch.
    val unstattable =
      object : ForwardingFileSystem(fs) {
        override fun metadataOrNull(path: Path) =
          if (path.name == "locked.rc") throw OkioIOException("Permission denied")
          else super.metadataOrNull(path)
      }

    assertEquals(1, runExpectingExit("dump", dir.toString(), fileSystem = unstattable))

    assertTrue(fs.exists(dir / "good.rc.json"), "dumped what it could: ${fs.list(dir)}")
    assertTrue(err.any { "locked.rc" in it && "Permission denied" in it }, "names it: $err")
    assertTrue(err.any { "dumped 1/2 documents" in it }, "counts it as a failure: $err")
  }

  @Test
  fun `refuses an authoring document even in a dump's shape`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }
    // Dump shapes present but `root` marks it as authoring JSON.
    val source = """{"header":{"width":10},"root":[{"box":{}}],"operations":[]}"""
    fs.write(dir / "a.rc.json") { writeUtf8(source) }

    assertEquals(1, runExpectingExit("dump", dir.toString()))

    assertEquals(source, fs.read(dir / "a.rc.json") { readUtf8() })
  }

  @Test
  fun `claims a temporary name rather than checking then writing`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }
    fs.createDirectories("/elsewhere".toPath())
    val outside = "/elsewhere/precious".toPath()
    fs.write(outside) { writeUtf8("not mine") }

    // Something appears at the candidate path between the look and the write; only `mustCreate`
    // closes that window.
    var looked = false
    val racing =
      object : ForwardingFileSystem(fs) {
        override fun sink(file: Path, mustCreate: Boolean): Sink {
          if (file.name == "a.rc.json.tmp" && !looked) {
            looked = true
            fs.createSymlink(file, outside)
          }
          return super.sink(file, mustCreate)
        }
      }

    run("dump", dir.toString(), fileSystem = racing)

    assertEquals("not mine", fs.read(outside) { readUtf8() })
    assertContains(fs.read(dir / "a.rc.json") { readUtf8() }, "RootLayoutComponent")
  }

  @Test
  fun `refuses an output flag with nothing after it`() {
    fs.createDirectories(dir)
    fs.write(dir / "a.rc") { write(document) }

    // A valueless `-o` must be refused, not read as absent.
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

    // The ordinary case: a not-yet-existing output can't be canonicalised and isn't the input.
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

    // Following directory symlinks would write outside the tree, or loop.
    assertTrue(fs.exists(dir / "a.rc.json"))
    assertFalse(fs.exists("/elsewhere/outside.rc.json".toPath()))
  }
}
