package ee.schimke.composeai.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** Preparing a staged `bazel prepare --out` directory again from its origin (#6160). */
class StagedPrepareOriginTest {
  private val workspace = File("/ws")
  private val resolve = { path: String ->
    File(path).let { if (it.isAbsolute) it else File(workspace, path) }
  }

  @Test
  fun `a build-backed prepare records its invocation with absolute paths`() {
    val origin =
      StagedPrepareOrigin.of(
        listOf(
          "prepare",
          "--project",
          "/elsewhere/../ws",
          "--target",
          "//pkg:my_target",
          "--config=conf/bazel.json",
          "--out",
          "/tmp/staged/my_target",
          "--bazel-arg=--config=ci",
        ),
        workspace,
        resolve,
      )!!
    assertEquals("/ws", origin.workspace)
    assertEquals(
      listOf(
        "prepare",
        "--target",
        "//pkg:my_target",
        "--config",
        "/ws/conf/bazel.json",
        "--out",
        "/tmp/staged/my_target",
        "--bazel-arg=--config=ci",
        "--project",
        "/ws",
      ),
      origin.args,
    )
  }

  @Test
  fun `a spaced pass-through value is copied verbatim, not read as a path flag`() {
    val origin =
      StagedPrepareOrigin.of(
        listOf(
          "prepare",
          "--bazel-arg",
          "--config=ci",
          "--buck-arg",
          "--out",
          "--out",
          "staged",
        ),
        workspace,
        resolve,
      )!!
    assertEquals(
      listOf(
        "prepare",
        "--bazel-arg",
        "--config=ci",
        "--buck-arg",
        "--out",
        "--out",
        "/ws/staged",
        "--project",
        "/ws",
      ),
      origin.args,
    )
  }

  @Test
  fun `clearing removes an earlier record`() {
    val fs = FakeFileSystem()
    val staged = "/tmp/staged".toPath()
    StagedPrepareOrigin.write(staged, StagedPrepareOrigin("/ws", listOf("prepare")), fs)
    StagedPrepareOrigin.clear(staged, fs)
    assertNull(StagedPrepareOrigin.replayFor(listOf("prepare"), staged, fs))
    StagedPrepareOrigin.clear(staged, fs) // nothing there: still fine
  }

  @Test
  fun `nothing is recorded without --out, or from imported artifacts`() {
    assertNull(StagedPrepareOrigin.of(listOf("prepare", "--target", "//a:b"), workspace, resolve))
    assertNull(
      StagedPrepareOrigin.of(
        listOf("prepare", "--artifacts", "m.json", "--out", "/tmp/o"),
        workspace,
        resolve,
      )
    )
  }

  @Test
  fun `a bare re-prepare of the staged directory replays it, keeping this run's timeout`() {
    val fs = FakeFileSystem()
    val staged = "/tmp/staged/my_target".toPath()
    val origin =
      StagedPrepareOrigin(
        "/ws",
        listOf("prepare", "--target", "//pkg:t", "--timeout", "300", "--project", "/ws"),
      )
    StagedPrepareOrigin.write(staged, origin, fs)

    val replay =
      StagedPrepareOrigin.replayFor(
        listOf("prepare", "--project", staged.toString(), "--timeout", "1800"),
        staged,
        fs,
      )!!
    assertEquals(
      listOf("prepare", "--target", "//pkg:t", "--project", "/ws", "--timeout", "1800"),
      replay.args,
    )
    // One target of a multi-target --out sits a level below the recorded directory.
    assertEquals(origin, StagedPrepareOrigin.replayFor(listOf("prepare"), staged / "pkg_t", fs))
  }

  @Test
  fun `a --target the record selected still replays the whole recorded set`() {
    val fs = FakeFileSystem()
    val staged = "/tmp/staged".toPath()
    val origin =
      StagedPrepareOrigin(
        "/ws",
        listOf(
          "prepare",
          "--target",
          "//a:lib //b:lib",
          "--config",
          "/ws/conf/bazel.json",
          "--out",
          "/tmp/staged",
          "--project",
          "/ws",
        ),
      )
    StagedPrepareOrigin.write(staged, origin, fs)
    // As compose-preview-mcp re-prepares one module of a multi-target --out (#6231).
    val replay =
      StagedPrepareOrigin.replayFor(
        listOf(
          "prepare",
          "--project",
          (staged / "a_lib").toString(),
          "--timeout",
          "1800",
          "--target",
          "//a:lib",
        ),
        staged / "a_lib",
        fs,
      )!!
    assertEquals("/ws", replay.workspace)
    assertEquals(origin.args + listOf("--timeout", "1800"), replay.args)
    assertEquals(
      origin,
      StagedPrepareOrigin.replayFor(listOf("prepare", "--target=//b:lib"), staged, fs),
    )
    // `//a` is `//a:a`, which the record never built.
    assertNull(StagedPrepareOrigin.replayFor(listOf("prepare", "--target", "//a"), staged, fs))
  }

  @Test
  fun `a --target inside a recorded pattern replays`() {
    val fs = FakeFileSystem()
    val staged = "/tmp/staged".toPath()
    fun replays(recorded: String, requested: String): Boolean {
      StagedPrepareOrigin.write(
        staged,
        StagedPrepareOrigin("/ws", listOf("prepare", "--target", recorded)),
        fs,
      )
      return StagedPrepareOrigin.replayFor(listOf("prepare", "--target", requested), staged, fs) !=
        null
    }
    assertTrue(replays("//app/...", "//app/ui:lib"))
    assertTrue(replays("//app/...", "//app:app"))
    assertTrue(replays("//...", "//x/y:z"))
    assertTrue(replays("//app/ui:all", "//app/ui:lib"))
    assertTrue(replays("//app/ui:*", "//app/ui:lib"))
    assertTrue(replays("//app/ui", "//app/ui:ui"))
    assertTrue(!replays("//app/...", "//application:lib"))
    assertTrue(!replays("//app/ui:all", "//app/ui/sub:lib"))
    assertTrue(!replays("-//app/...", "//app:lib"))
  }

  @Test
  fun `an explicit selection is not redirected`() {
    val fs = FakeFileSystem()
    val staged = "/tmp/staged".toPath()
    StagedPrepareOrigin.write(
      staged,
      StagedPrepareOrigin("/ws", listOf("prepare", "--target", "//a:lib")),
      fs,
    )
    // A target the record did not select is this run's own choice.
    assertNull(StagedPrepareOrigin.replayFor(listOf("prepare", "--target", "//x:y"), staged, fs))
    assertNull(
      StagedPrepareOrigin.replayFor(listOf("prepare", "--target", "//a:lib //x:y"), staged, fs)
    )
    assertNull(StagedPrepareOrigin.replayFor(listOf("prepare", "--target=//x:y"), staged, fs))
    assertNull(StagedPrepareOrigin.replayFor(listOf("prepare", "--config", "c.json"), staged, fs))
    assertNull(StagedPrepareOrigin.replayFor(listOf("prepare"), "/tmp/other".toPath(), fs))
  }
}
