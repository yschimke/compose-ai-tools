package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * skiko is the artifact a Compose Multiplatform bump reaches the renderer's own call sites through
 * — 0.150.0 changed `Image.encodeToData`'s parameter list — and it resolves to a single version
 * across the render classpath, so the consumer decides which API the tool runs against
 * (compose-ai-tools#4190). Reading it off the resolved filenames is what puts the version in the
 * log, and what catches an API-jar / native-runtime skew before every render dies at draw time.
 */
class SkikoVersionsOnClasspathTest {

  private fun cached(artifact: String) =
    "/home/u/.gradle/caches/modules-2/files-2.1/org.jetbrains.skiko/x/deadbeef/$artifact"

  @Test
  fun `reads the version off the api jar and its platform native runtime`() {
    val versions =
      ValidateComposePreviewClasspathTask.skikoVersionsOnClasspath(
        listOf(
          cached("skiko-awt-0.150.1.jar"),
          cached("skiko-awt-runtime-macos-arm64-0.150.1.jar"),
          cached("skiko-0.150.1.jar"),
          "/home/u/.gradle/caches/x/annotations-23.0.0.jar",
        )
      )
    assertThat(versions).containsExactly("0.150.1")
  }

  @Test
  fun `an api jar and native runtime at different versions come back as two`() {
    // The pair that loads a libskiko whose exports the API does not declare — every render then
    // fails at draw time with UnsatisfiedLinkError, far from the cause.
    val versions =
      ValidateComposePreviewClasspathTask.skikoVersionsOnClasspath(
        listOf(cached("skiko-awt-0.150.1.jar"), cached("skiko-awt-runtime-linux-x64-0.144.6.jar"))
      )
    assertThat(versions).containsExactly("0.144.6", "0.150.1").inOrder()
  }

  @Test
  fun `two coherent pairs from the two scopes are not a skew`() {
    // The shape of this plugin's own bundle E2E fixture, which #4200's check hard-failed: a
    // consumer on
    // org.jetbrains.compose 1.10.3 (skiko 0.9.37.4) against a tool on 1.11.1 (skiko 0.144.6). Each
    // side resolved a matched API/native pair; the JVM loads whichever leads the classpath and
    // shadows the other. Reading the concatenation as one classpath called this a skew and hard
    // -failed every consumer off the tool's Compose line.
    val tool =
      setOf(cached("skiko-awt-0.144.6.jar"), cached("skiko-awt-runtime-linux-x64-0.144.6.jar"))
    val consumer =
      listOf(cached("skiko-awt-0.9.37.4.jar"), cached("skiko-awt-runtime-linux-x64-0.9.37.4.jar"))
    val scopes =
      ValidateComposePreviewClasspathTask.skikoScopes(
        toolPaths = tool,
        allPaths = tool.toList() + consumer,
      )
    assertThat(scopes.map { it.label })
      .containsExactly("compose-preview renderer", "consumer runtime")
      .inOrder()
    assertThat(scopes.map { it.versions }).containsExactly(listOf("0.144.6"), listOf("0.9.37.4"))
    // What the task acts on: no scope resolved more than one version, so nothing fails.
    assertThat(scopes.filter { it.versions.size > 1 }).isEmpty()
  }

  @Test
  fun `a mismatched pair inside one scope is still a skew`() {
    // The case the guard exists for, unchanged: within a SINGLE resolved classpath the API jar and
    // the platform native runtime disagree, so the loaded libskiko does not export what the API
    // declares and every render dies at draw time.
    val tool =
      setOf(cached("skiko-awt-0.150.1.jar"), cached("skiko-awt-runtime-linux-x64-0.144.6.jar"))
    val scopes =
      ValidateComposePreviewClasspathTask.skikoScopes(toolPaths = tool, allPaths = tool.toList())
    val skewed = scopes.filter { it.versions.size > 1 }
    assertThat(skewed).hasSize(1)
    assertThat(skewed.single().label).isEqualTo("compose-preview renderer")
    assertThat(skewed.single().versions).containsExactly("0.144.6", "0.150.1").inOrder()
  }

  @Test
  fun `a skew on the consumer side is attributed to the consumer`() {
    val tool = setOf(cached("skiko-awt-0.144.6.jar"))
    val consumer =
      listOf(cached("skiko-awt-0.150.1.jar"), cached("skiko-awt-runtime-linux-x64-0.144.6.jar"))
    val skewed =
      ValidateComposePreviewClasspathTask.skikoScopes(
          toolPaths = tool,
          allPaths = tool.toList() + consumer,
        )
        .filter { it.versions.size > 1 }
    assertThat(skewed.map { it.label }).containsExactly("consumer runtime")
  }

  @Test
  fun `with no tool classpath set the whole thing is one scope`() {
    // Nothing registers the guard without one, but degrading to a single scope keeps a caller that
    // does from having the consumer's jars reported as the tool's.
    val scopes =
      ValidateComposePreviewClasspathTask.skikoScopes(
        toolPaths = emptySet(),
        allPaths = listOf(cached("skiko-awt-0.150.1.jar")),
      )
    assertThat(scopes.map { it.label }).containsExactly("render")
    assertThat(scopes.single().versions).containsExactly("0.150.1")
  }

  @Test
  fun `a classpath with no skiko names none, and windows paths still parse`() {
    assertThat(
        ValidateComposePreviewClasspathTask.skikoVersionsOnClasspath(listOf("/a/kotlin.jar"))
      )
      .isEmpty()
    assertThat(
        ValidateComposePreviewClasspathTask.skikoVersionsOnClasspath(
          listOf("C:\\Users\\u\\.gradle\\caches\\x\\skiko-awt-0.148.2.jar")
        )
      )
      .containsExactly("0.148.2")
  }
}
