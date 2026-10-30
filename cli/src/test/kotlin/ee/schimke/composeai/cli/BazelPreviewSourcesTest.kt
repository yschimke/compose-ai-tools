package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/** Skipping Bazel packages whose sources hold no preview (#6162). */
class BazelPreviewSourcesTest {
  private val fs = FakeFileSystem()
  private val root = "/ws".toPath()

  private fun file(path: String, text: String = "") {
    val p = root / path
    fs.createDirectories(p.parent!!)
    fs.write(p) { writeUtf8(text) }
  }

  /** The workspace from #6162: wiring, data and view-model libraries over two leaf UI ones. */
  private fun workspace(): BazelPreviewSources {
    file("app/presentation/BUILD.bazel")
    file("app/presentation/WearApp.kt", "class WearApp")
    file("app/presentation/components/BUILD.bazel")
    file(
      "app/presentation/components/CustomTimeText.kt",
      "@Preview @Composable fun CustomTimeTextPreview() {}",
    )
    file("app/presentation/components/chip/BUILD")
    file("app/presentation/components/chip/WatchAppChip.kt", "@WearPreviewDevices fun Chip() {}")
    file("app/data/BUILD.bazel")
    file("app/data/Repository.kt", "class Repository")
    file("app/viewmodel/BUILD.bazel")
    file("app/viewmodel/src/main/Model.java", "class Model {}")
    file("app/bazel-out/k8/Generated.kt", "@Preview fun Generated() {}")
    return BazelPreviewSources(root, fs)
  }

  @Test
  fun `a package is judged by its own sources, not a sub-package's`() {
    val sources = workspace()
    assertFalse(sources.mayHavePreviews("//app/presentation:wear_app"))
    assertTrue(sources.mayHavePreviews("//app/presentation/components:custom_time_text"))
    // A multi-preview annotation spells Preview too.
    assertTrue(sources.mayHavePreviews("//app/presentation/components/chip:watch_app_chip"))
    assertFalse(sources.mayHavePreviews("//app/data:data"))
    // Sources nested below the package directory still count.
    assertFalse(sources.mayHavePreviews("//app/viewmodel"))
  }

  @Test
  fun `what this cannot judge is kept`() {
    val sources = workspace()
    assertTrue(sources.mayHavePreviews("@maps//lib:maps"), "an external repository")
    assertTrue(sources.mayHavePreviews("//app/missing:lib"), "a package not on disk")
  }

  @Test
  fun `a pattern's preview-less matches are skipped, named labels kept`() {
    val sources = workspace()
    val logs = mutableListOf<String>()
    val targets =
      listOf(
        "//app/data:data",
        "//app/presentation/components:custom_time_text",
        "//app/presentation:wear_app",
        "//app/viewmodel:viewmodel",
      )
    assertEquals(
      listOf("//app/presentation/components:custom_time_text", "//app/presentation:wear_app"),
      sources.filter(targets, kept = setOf("//app/presentation:wear_app"), log = logs::add),
    )
    assertTrue(logs.single().contains("//app/data, //app/viewmodel"), logs.single())
  }

  @Test
  fun `a filter that would leave nothing leaves everything`() {
    val sources = workspace()
    val logs = mutableListOf<String>()
    val targets = listOf("//app/data:data", "//app/viewmodel:viewmodel")
    assertEquals(targets, sources.filter(targets, kept = emptySet(), log = logs::add))
    assertEquals(1, logs.size)
  }

  @Test
  fun `a target with no previews points at the packages below it that have them`() {
    assertEquals(
      listOf("app/presentation/components", "app/presentation/components/chip"),
      workspace().previewPackagesBelow("//app/presentation:wear_app"),
    )
  }

  @Test
  fun `labels map to their main-repository package`() {
    assertEquals("a/b", BazelPreviewSources.mainRepoPackage("//a/b:c"))
    assertEquals("a/b", BazelPreviewSources.mainRepoPackage("//a/b"))
    assertEquals("a", BazelPreviewSources.mainRepoPackage("@//a:b"))
    assertEquals("a", BazelPreviewSources.mainRepoPackage("@@//a:b"))
    assertEquals("", BazelPreviewSources.mainRepoPackage("//:root"))
    assertEquals(null, BazelPreviewSources.mainRepoPackage("@maps//a:b"))
  }
}
