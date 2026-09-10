package ee.schimke.composeai.cli

import ee.schimke.composeai.remotecompose.json.RemoteComposeJson
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
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

  private fun run(vararg args: String) =
    RcCommand(args.toList(), fs, stdout = {}, stderr = { err += it }, exit = { throw Exited(it) })
      .run()

  /** Run expecting the command to refuse, and return the exit code it asked for. */
  private fun runExpectingExit(vararg args: String): Int =
    try {
      run(*args)
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
