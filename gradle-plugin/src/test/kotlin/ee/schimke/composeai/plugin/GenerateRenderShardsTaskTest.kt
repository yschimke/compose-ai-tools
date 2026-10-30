package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the shard classes [GenerateRenderShardsTask] writes: one per shard, each run by
 * renderer-android's LVT-deduping runner rather than Robolectric's own (#6230), while keeping
 * Robolectric's `@Parameters` annotation that runner reads.
 */
class GenerateRenderShardsTaskTest {

  @get:Rule val tempDir = TemporaryFolder()

  private fun generate(shards: Int): Map<String, String> {
    val project = ProjectBuilder.builder().withProjectDir(tempDir.newFolder("project")).build()
    val out = tempDir.newFolder("out")
    val task =
      project.tasks.create("generateRenderShards", GenerateRenderShardsTask::class.java).also {
        it.shards.set(shards)
        it.outputDir.set(out)
      }
    task.generate()
    return out.resolve("ee/schimke/composeai/renderer").listFiles().orEmpty().associate {
      it.name to it.readText()
    }
  }

  @Test
  fun `writes one shard class per shard`() {
    assertThat(generate(shards = 3).keys)
      .containsExactly(
        "RobolectricRenderTest_Shard0.java",
        "RobolectricRenderTest_Shard1.java",
        "RobolectricRenderTest_Shard2.java",
      )
  }

  @Test
  fun `shards run under the deduping runner, not Robolectric's`() {
    val source = generate(shards = 2).getValue("RobolectricRenderTest_Shard1.java")
    assertThat(source).contains("package ee.schimke.composeai.renderer;")
    assertThat(source).contains("@RunWith(DedupingParameterizedRobolectricTestRunner.class)")
    assertThat(source).doesNotContain("@RunWith(ParameterizedRobolectricTestRunner.class)")
  }

  @Test
  fun `shards keep Robolectric's Parameters annotation and load their own slice`() {
    val source = generate(shards = 2).getValue("RobolectricRenderTest_Shard1.java")
    assertThat(source).contains("import org.robolectric.ParameterizedRobolectricTestRunner;")
    assertThat(source).contains("@ParameterizedRobolectricTestRunner.Parameters(name = \"{0}\")")
    assertThat(source).contains("PreviewManifestLoader.loadShard(1, 2)")
    assertThat(source).contains("extends RobolectricRenderTestBase")
  }
}
