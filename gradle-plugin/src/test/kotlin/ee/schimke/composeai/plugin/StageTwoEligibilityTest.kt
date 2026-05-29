package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pins the contract of [ComposePreviewTasks.detectStageTwoIneligibilityFor] — the daemon-warm-time
 * predicate that keeps modules off in-process compile (stage 2,
 * `composePreview.daemon.compileInProcess`) when BTA can't safely drive their build. Mirrors
 * `docs/daemon/COMPILE-IN-PROCESS.md` § "Eligibility".
 *
 * The KSP / KAPT / KMP branches are plain `hasPlugin(<id>)` string checks — exercised end-to-end by
 * the functional tests where the real plugins are on the TestKit classpath. These unit tests cover
 * the branch with actual logic: the `annotationProcessor`-dependency probe, which must read
 * declared dependencies (not mere configuration existence) and stay configuration-cache-safe.
 */
class StageTwoEligibilityTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `plain module with no processors is eligible`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    assertThat(ComposePreviewTasks.detectStageTwoIneligibilityFor(project)).isNull()
  }

  @Test
  fun `an empty annotationProcessor configuration does not disqualify`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    // The config exists (java/AGP create it) but carries no declared deps — the module isn't
    // actually annotation-processed, so it stays eligible.
    project.configurations.create("annotationProcessor")
    assertThat(ComposePreviewTasks.detectStageTwoIneligibilityFor(project)).isNull()
  }

  @Test
  fun `a declared annotationProcessor dependency falls back to stage 1`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    project.configurations.create("annotationProcessor")
    project.dependencies.add("annotationProcessor", "com.example:some-processor:1.0")

    val reason = ComposePreviewTasks.detectStageTwoIneligibilityFor(project)
    assertThat(reason).isNotNull()
    assertThat(reason).contains("annotationProcessor")
  }

  @Test
  fun `a per-variant annotationProcessor bucket is matched case-insensitively`() {
    val project = ProjectBuilder.builder().withProjectDir(tmp.root).build()
    // AGP creates `<variant>AnnotationProcessor` buckets; the probe matches the substring
    // regardless of the camelCase prefix.
    project.configurations.create("debugAnnotationProcessor")
    project.dependencies.add("debugAnnotationProcessor", "com.example:some-processor:1.0")

    assertThat(ComposePreviewTasks.detectStageTwoIneligibilityFor(project)).isNotNull()
  }
}
