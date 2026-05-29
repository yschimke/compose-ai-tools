package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Parser-level coverage for [SubprojectBuildFilesValueSource] — the IP-safe replacement for the
 * cross-project `findProject` / `evaluationDependsOn` walk dropped in #1546.
 *
 * The wrapping [org.gradle.api.provider.ValueSource] is just a thin Gradle-managed registration
 * around [SubprojectBuildFilesValueSource.parseSettingsForSubprojectBuildFiles]; the parser does
 * the substantive work and is what gets unit-tested here. Functional-level CC tracking is exercised
 * via Test Kit in [CrossProjectMetadataFunctionalTest].
 */
class SubprojectBuildFilesValueSourceTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `enumerates included subprojects from settings dot gradle dot kts`() {
    File(tmp.root, "settings.gradle.kts")
      .writeText(
        """
        rootProject.name = "demo"
        include(":app")
        include(":lib")
        include(":nested:child")
        """
          .trimIndent()
      )
    File(tmp.root, "app").mkdirs()
    File(tmp.root, "lib").mkdirs()
    File(tmp.root, "nested/child").mkdirs()
    File(tmp.root, "app/build.gradle.kts").writeText("")
    File(tmp.root, "lib/build.gradle.kts").writeText("")
    File(tmp.root, "nested/child/build.gradle.kts").writeText("")

    val files = SubprojectBuildFilesValueSource.parseSettingsForSubprojectBuildFiles(tmp.root)

    assertThat(files.map { it.relativeTo(tmp.root).path })
      .containsExactly(
        "app/build.gradle.kts",
        "lib/build.gradle.kts",
        "nested/child/build.gradle.kts",
      )
  }

  @Test
  fun `handles multi-arg include calls and groovy quoting`() {
    File(tmp.root, "settings.gradle")
      .writeText(
        """
        rootProject.name = 'demo'
        include ':app', ':lib'
        include(":a", ":b")
        """
          .trimIndent()
      )
    listOf("app", "lib", "a", "b").forEach {
      File(tmp.root, it).mkdirs()
      File(tmp.root, "$it/build.gradle").writeText("")
    }

    val files = SubprojectBuildFilesValueSource.parseSettingsForSubprojectBuildFiles(tmp.root)

    assertThat(files.map { it.relativeTo(tmp.root).path })
      .containsExactly("a/build.gradle", "app/build.gradle", "b/build.gradle", "lib/build.gradle")
  }

  @Test
  fun `ignores commented include calls`() {
    File(tmp.root, "settings.gradle.kts")
      .writeText(
        """
        // include(":commented")
        /* include(":blockCommented") */
        include(":real")
        """
          .trimIndent()
      )
    File(tmp.root, "real").mkdirs()
    File(tmp.root, "real/build.gradle.kts").writeText("")

    val files = SubprojectBuildFilesValueSource.parseSettingsForSubprojectBuildFiles(tmp.root)

    assertThat(files.map { it.relativeTo(tmp.root).path }).containsExactly("real/build.gradle.kts")
  }

  @Test
  fun `silently skips subprojects without a build file on disk`() {
    File(tmp.root, "settings.gradle.kts").writeText("""include(":app"); include(":empty")""")
    File(tmp.root, "app").mkdirs()
    File(tmp.root, "empty").mkdirs()
    File(tmp.root, "app/build.gradle.kts").writeText("")

    val files = SubprojectBuildFilesValueSource.parseSettingsForSubprojectBuildFiles(tmp.root)

    assertThat(files.map { it.relativeTo(tmp.root).path }).containsExactly("app/build.gradle.kts")
  }

  @Test
  fun `returns empty list when no settings file exists`() {
    val files = SubprojectBuildFilesValueSource.parseSettingsForSubprojectBuildFiles(tmp.root)
    assertThat(files).isEmpty()
  }
}
