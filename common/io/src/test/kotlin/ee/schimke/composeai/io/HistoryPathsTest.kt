package ee.schimke.composeai.io

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Pins the history-path layout.
 *
 * Three implementations must agree byte-for-byte: this one, the Gradle plugin's inlined copy
 * (`AndroidPreviewClasspath.composeAiHistoryDir` — the plugin can't depend on `:common-io`), and
 * the VS Code extension's TypeScript copy (`vscode-extension/src/historyPaths.ts`). The `GOLDEN_*`
 * vectors below are duplicated verbatim in `historyPaths.test.ts`; changing either side alone makes
 * the daemon write to one directory and the panel read from another, which surfaces as a silently
 * empty history drawer rather than a crash. Change the layout only with both suites updated
 * together.
 */
class HistoryPathsTest {

  @get:Rule val tmp: TemporaryFolder = TemporaryFolder()

  // ---- Golden vectors (mirrored in vscode-extension/src/test/historyPaths.test.ts) ----

  @Test
  fun `workspace slug is a readable prefix plus a 12-hex digest`() {
    assertEquals("app-671822104ddc", composeAiHistoryWorkspaceSlug(File("/home/dev/src/app")))
    assertEquals(
      "my-project-75d59f8a453b",
      composeAiHistoryWorkspaceSlug(File("/home/dev/src/my project")),
    )
  }

  @Test
  fun `workspace slug ignores a trailing separator`() {
    assertEquals(
      composeAiHistoryWorkspaceSlug(File("/home/dev/src/app")),
      composeAiHistoryWorkspaceSlug(File("/home/dev/src/app/")),
    )
  }

  @Test
  fun `same basename under different parents does not collide`() {
    assertNotEquals(
      composeAiHistoryWorkspaceSlug(File("/home/dev/a/app")),
      composeAiHistoryWorkspaceSlug(File("/home/dev/b/app")),
    )
  }

  @Test
  fun `module segment is the workspace-relative path`() {
    assertEquals(
      "auth/composables",
      composeAiHistoryModuleSegment(File("/w"), File("/w/auth/composables")),
    )
  }

  @Test
  fun `module segment for the root project is _root`() {
    assertEquals("_root", composeAiHistoryModuleSegment(File("/w"), File("/w")))
    assertEquals("_root", composeAiHistoryModuleSegment(File("/w"), File("/w/")))
  }

  @Test
  fun `module segment sanitises characters outside the safe set`() {
    assertEquals("a-b/c-d", composeAiHistoryModuleSegment(File("/w"), File("/w/a b/c:d")))
  }

  @Test
  fun `module segment keeps dots underscores and hyphens`() {
    assertEquals("my_mod.x-1", composeAiHistoryModuleSegment(File("/w"), File("/w/my_mod.x-1")))
  }

  @Test
  fun `module outside the workspace tree gets its own identity`() {
    // `settings.gradle.kts` may reassign a projectDir outside the root. No relative path exists,
    // so the segment keys on the module's own absolute path instead of silently colliding.
    val a = composeAiHistoryModuleSegment(File("/w"), File("/elsewhere/mod"))
    val b = composeAiHistoryModuleSegment(File("/w"), File("/other/mod"))
    assertTrue(a.startsWith("_external-"), "expected an _external- segment, got '$a'")
    assertNotEquals(a, b)
  }

  @Test
  fun `a workspace-root prefix match must be a real path boundary`() {
    // "/w-other" starts with "/w" as a string but is not inside it.
    val segment = composeAiHistoryModuleSegment(File("/w"), File("/w-other/mod"))
    assertTrue(segment.startsWith("_external-"), "expected an _external- segment, got '$segment'")
  }

  // ---- Resolution against the cache root + the legacy fallback ----

  @Test
  fun `history dir lands under the cache root`() {
    val workspace = tmp.newFolder("ws")
    val module = File(workspace, "auth/composables").apply { mkdirs() }

    val dir = composeAiHistoryDir(workspace, module)

    val expected =
      File(
        File(composeAiCacheDir("history"), composeAiHistoryWorkspaceSlug(workspace)),
        "auth/composables",
      )
    assertEquals(expected.absolutePath, dir.absolutePath)
  }

  @Test
  fun `history dir is not inside the workspace`() {
    val workspace = tmp.newFolder("ws")
    val module = File(workspace, "app").apply { mkdirs() }

    val dir = composeAiHistoryDir(workspace, module)

    assertTrue(
      !dir.absolutePath.startsWith(workspace.absolutePath + File.separator),
      "history must not be written inside the workspace, got ${dir.absolutePath}",
    )
  }

  @Test
  fun `an existing legacy directory wins so upgrades do not strand a timeline`() {
    val workspace = tmp.newFolder("ws")
    val module = File(workspace, "app").apply { mkdirs() }
    val legacy = File(module, LEGACY_HISTORY_DIRNAME).apply { mkdirs() }

    assertEquals(legacy.absolutePath, composeAiHistoryDir(workspace, module).absolutePath)
  }

  @Test
  fun `a legacy path that is a file not a directory is ignored`() {
    val workspace = tmp.newFolder("ws")
    val module = File(workspace, "app").apply { mkdirs() }
    File(module, LEGACY_HISTORY_DIRNAME).writeText("not a directory")

    val dir = composeAiHistoryDir(workspace, module)

    assertTrue(
      !dir.absolutePath.startsWith(module.absolutePath + File.separator),
      "a stray file must not divert history back into the workspace, got ${dir.absolutePath}",
    )
  }
}
