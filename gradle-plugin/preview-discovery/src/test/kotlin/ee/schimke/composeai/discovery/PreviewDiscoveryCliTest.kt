package ee.schimke.composeai.discovery

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Test

class PreviewDiscoveryCliTest {

  @Test
  fun `parse fills every Input field from the canonical arg shape`() {
    val parsed =
      PreviewDiscoveryCli.parse(
        arrayOf(
          "--classes",
          "/tmp/a${File.pathSeparator}/tmp/b",
          "--dependency-jars",
          "/tmp/c.jar",
          "--source-files",
          "/tmp/x.kt${File.pathSeparator}/tmp/y.kt",
          "--module",
          ":app",
          "--variant",
          "debug",
          "--project-directory",
          "/tmp/project",
          "--fail-on-empty",
          "--out",
          "/tmp/previews.json",
        )
      )

    assertThat(parsed.input.classDirs.map { it.path }).containsExactly("/tmp/a", "/tmp/b").inOrder()
    assertThat(parsed.input.dependencyJars.map { it.path }).containsExactly("/tmp/c.jar")
    assertThat(parsed.input.sourceFiles.map { it.path })
      .containsExactly("/tmp/x.kt", "/tmp/y.kt")
      .inOrder()
    assertThat(parsed.input.moduleName).isEqualTo(":app")
    assertThat(parsed.input.variantName).isEqualTo("debug")
    assertThat(parsed.input.projectDirectory.path).isEqualTo("/tmp/project")
    assertThat(parsed.input.failOnEmpty).isTrue()
    assertThat(parsed.outFile.path).isEqualTo("/tmp/previews.json")
  }

  @Test
  fun `repeated --classes accumulates`() {
    val parsed =
      PreviewDiscoveryCli.parse(
        arrayOf(
          "--classes",
          "/a",
          "--classes",
          "/b",
          "--module",
          "m",
          "--variant",
          "v",
          "--project-directory",
          "/proj",
          "--out",
          "/out",
        )
      )

    assertThat(parsed.input.classDirs.map { it.path }).containsExactly("/a", "/b").inOrder()
  }

  @Test
  fun `--fail-on-empty defaults to false when absent`() {
    val parsed =
      PreviewDiscoveryCli.parse(
        arrayOf(
          "--module",
          "m",
          "--variant",
          "v",
          "--project-directory",
          "/proj",
          "--out",
          "/out",
        )
      )
    assertThat(parsed.input.failOnEmpty).isFalse()
  }

  @Test
  fun `missing --module errors with a clear message`() {
    val error =
      assertThrows(PreviewDiscoveryCli.ArgError::class.java) {
        PreviewDiscoveryCli.parse(
          arrayOf("--variant", "v", "--project-directory", "/p", "--out", "/o")
        )
      }
    assertThat(error.message).contains("--module is required")
  }

  @Test
  fun `unknown argument errors`() {
    val error =
      assertThrows(PreviewDiscoveryCli.ArgError::class.java) {
        PreviewDiscoveryCli.parse(arrayOf("--bogus", "v"))
      }
    assertThat(error.message).contains("--bogus")
  }

  @Test
  fun `flag without value errors`() {
    val error =
      assertThrows(PreviewDiscoveryCli.ArgError::class.java) {
        PreviewDiscoveryCli.parse(arrayOf("--module"))
      }
    assertThat(error.message).contains("--module requires a value")
  }

  @Test
  fun `splitPathList drops empties so build-system-passed empty inputs are harmless`() {
    val parsed =
      PreviewDiscoveryCli.parse(
        arrayOf(
          // Bazel `$(locations …)` substitutions can produce an empty leading segment when the
          // input set is empty — the CLI must tolerate that without spurious File("") entries.
          "--classes",
          "${File.pathSeparator}/a${File.pathSeparator}${File.pathSeparator}/b",
          "--module",
          "m",
          "--variant",
          "v",
          "--project-directory",
          "/p",
          "--out",
          "/o",
        )
      )
    assertThat(parsed.input.classDirs.map { it.path }).containsExactly("/a", "/b").inOrder()
  }
}
