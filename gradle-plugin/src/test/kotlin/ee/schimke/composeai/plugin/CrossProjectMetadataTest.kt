package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Parser-level coverage for [CrossProjectMetadata] — the IP-safe replacement for the cross-project
 * `findProject` / `evaluationDependsOn` walk dropped in #1546. The service that wraps it
 * ([CrossProjectMetadataService]) is just a `Lazy` over [CrossProjectMetadata.build]; testing the
 * pure parser here covers the substantive behaviour without spinning up a BuildService.
 */
class CrossProjectMetadataTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `parseSettings enumerates included subprojects with conventional layout`() {
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

    val map = CrossProjectMetadata.parseSettings(tmp.root)

    assertThat(map.keys).containsExactly(":", ":app", ":lib", ":nested:child").inOrder()
    assertThat(map[":app"]).isEqualTo(File(tmp.root, "app"))
    assertThat(map[":nested:child"]).isEqualTo(File(tmp.root, "nested/child"))
  }

  @Test
  fun `parseSettings handles multi-arg include calls and groovy quoting`() {
    File(tmp.root, "settings.gradle")
      .writeText(
        """
        rootProject.name = 'demo'
        include ':app', ':lib'
        include(":a", ":b")
        """
          .trimIndent()
      )

    val map = CrossProjectMetadata.parseSettings(tmp.root)

    assertThat(map.keys).containsExactly(":", ":app", ":lib", ":a", ":b").inOrder()
  }

  @Test
  fun `parseSettings ignores commented include calls`() {
    File(tmp.root, "settings.gradle.kts")
      .writeText(
        """
        // include(":commented")
        /* include(":blockCommented") */
        include(":real")
        """
          .trimIndent()
      )

    val map = CrossProjectMetadata.parseSettings(tmp.root)

    assertThat(map.keys).containsExactly(":", ":real").inOrder()
  }

  @Test
  fun `declaresPreviewTooling matches each known coord name with boundary checks`() {
    assertThat(
        CrossProjectMetadata.declaresPreviewTooling(
          """implementation("androidx.compose.ui:ui-tooling-preview-android:1.9.5")"""
        )
      )
      .isTrue()
    assertThat(
        CrossProjectMetadata.declaresPreviewTooling(
          """api("org.jetbrains.compose.components:components-ui-tooling-preview:1.7.5")"""
        )
      )
      .isTrue()
    assertThat(
        CrossProjectMetadata.declaresPreviewTooling(
          """api("androidx.compose.ui:ui-tooling-preview:1.9.5")"""
        )
      )
      .isTrue()
    assertThat(CrossProjectMetadata.declaresPreviewTooling("""api("com.example:ui-tooling:1.0")"""))
      .isFalse()
    assertThat(CrossProjectMetadata.declaresPreviewTooling("""api("com.example:some-other-dep")"""))
      .isFalse()
  }

  @Test
  fun `parseProjectDeps captures declared project paths only`() {
    val deps =
      CrossProjectMetadata.parseProjectDeps(
        """
        dependencies {
          implementation(project(":shared"))
          api(project(":lib:core"))
          implementation(project(path = ":lib:other"))
          implementation("com.example:lib:1.0")
        }
        """
          .trimIndent()
      )

    assertThat(deps).containsExactly(":shared", ":lib:core", ":lib:other").inOrder()
  }

  @Test
  fun `hasPreviewToolingDeep follows transitive project deps`() {
    // :app -> :shared (declares preview tooling); :app should now resolve true.
    val rootDir = tmp.root
    File(rootDir, "settings.gradle.kts")
      .writeText(
        """
        rootProject.name = "demo"
        include(":app")
        include(":shared")
        """
          .trimIndent()
      )
    File(rootDir, "app").mkdirs()
    File(rootDir, "shared").mkdirs()
    File(rootDir, "app/build.gradle.kts")
      .writeText(
        """
        dependencies {
          implementation(project(":shared"))
        }
        """
          .trimIndent()
      )
    File(rootDir, "shared/build.gradle.kts")
      .writeText(
        """
        dependencies {
          api("org.jetbrains.compose.components:components-ui-tooling-preview:1.7.5")
        }
        """
          .trimIndent()
      )

    val metadata = CrossProjectMetadata.build(rootDir)

    assertThat(metadata.hasPreviewToolingDeep(":app")).isTrue()
    assertThat(metadata.hasPreviewToolingDeep(":shared")).isTrue()
  }

  @Test
  fun `hasPreviewToolingDeep returns false when no transitive dep declares tooling`() {
    val rootDir = tmp.root
    File(rootDir, "settings.gradle.kts")
      .writeText(
        """
        include(":app")
        include(":shared")
        """
          .trimIndent()
      )
    File(rootDir, "app").mkdirs()
    File(rootDir, "shared").mkdirs()
    File(rootDir, "app/build.gradle.kts")
      .writeText("""dependencies { implementation(project(":shared")) }""")
    File(rootDir, "shared/build.gradle.kts").writeText("""dependencies { }""")

    val metadata = CrossProjectMetadata.build(rootDir)

    assertThat(metadata.hasPreviewToolingDeep(":app")).isFalse()
    assertThat(metadata.hasPreviewToolingDeep(":shared")).isFalse()
  }

  @Test
  fun `hasPreviewToolingDeep tolerates dependency cycles without stack overflow`() {
    val rootDir = tmp.root
    File(rootDir, "settings.gradle.kts").writeText("""include(":a"); include(":b")""")
    File(rootDir, "a").mkdirs()
    File(rootDir, "b").mkdirs()
    File(rootDir, "a/build.gradle.kts")
      .writeText("""dependencies { implementation(project(":b")) }""")
    File(rootDir, "b/build.gradle.kts")
      .writeText("""dependencies { implementation(project(":a")) }""")

    val metadata = CrossProjectMetadata.build(rootDir)

    // Doesn't loop forever; doesn't find tooling either.
    assertThat(metadata.hasPreviewToolingDeep(":a")).isFalse()
  }

  @Test
  fun `allBuildFiles returns every existing subproject build file`() {
    val rootDir = tmp.root
    File(rootDir, "settings.gradle.kts").writeText("""include(":app"); include(":lib")""")
    File(rootDir, "app").mkdirs()
    File(rootDir, "lib").mkdirs()
    File(rootDir, "build.gradle.kts").writeText("")
    File(rootDir, "app/build.gradle.kts").writeText("")
    File(rootDir, "lib/build.gradle").writeText("")
    // Subproject without a build file at all — silently skipped (not all included projects need
    // one; rare but the parser shouldn't NPE).
    File(rootDir, "settings.gradle.kts")
      .writeText("""include(":app"); include(":lib"); include(":empty")""")
    File(rootDir, "empty").mkdirs()

    val metadata = CrossProjectMetadata.build(rootDir)

    assertThat(metadata.allBuildFiles().map { it.relativeTo(rootDir).path })
      .containsExactly("build.gradle.kts", "app/build.gradle.kts", "lib/build.gradle")
  }

  @Test
  fun `stripGradleComments strips line and block comments`() {
    val stripped =
      CrossProjectMetadata.stripGradleComments(
        """
        // line
        include(":a")
        /* block */ include(":b")
        /* multi
           line */ include(":c")
        """
          .trimIndent()
      )

    assertThat(stripped).doesNotContain("line")
    assertThat(stripped).doesNotContain("block")
    assertThat(stripped).contains(":a")
    assertThat(stripped).contains(":b")
    assertThat(stripped).contains(":c")
  }
}
