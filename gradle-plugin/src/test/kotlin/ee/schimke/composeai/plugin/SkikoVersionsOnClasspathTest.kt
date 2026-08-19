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
