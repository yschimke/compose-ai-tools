package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.gradle.api.tasks.testing.Test as TestTask
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

/**
 * Pins [configureRenderTaskReporting], the guard against a render failing for reasons that have
 * nothing to do with the preview.
 *
 * Under a non-UTF-8 platform locale (`LC_CTYPE=POSIX` — the default in agent sandboxes and minimal
 * CI images) Gradle's HTML test reporter cannot write its per-test-method output directories when a
 * preview's display name contains a non-ASCII character, e.g. `@Preview(name = "Play Store — 10
 * inch tablet")`. The build then fails with "Malformed input or input contains unmappable
 * characters" once per affected file, printed twice, drowning the real result. Turning the HTML
 * report off removes the failure class; the sidecar `.error.json` files remain the diagnostic
 * channel.
 *
 * The JUnit XML report must stay ON — its filenames come from the test *class*
 * (`RobolectricRenderTest_Shard0`), which is always ASCII, so it is unaffected and CI test-result
 * collection depends on it.
 */
class RenderTaskReportingTest {

  private fun renderTask(): TestTask {
    val project = ProjectBuilder.builder().build()
    return project.tasks.create("render", TestTask::class.java).also {
      configureRenderTaskReporting(it)
    }
  }

  @Test
  fun `html report is disabled so non-ascii preview names cannot fail report generation`() {
    assertThat(renderTask().reports.html.required.get()).isFalse()
  }

  @Test
  fun `junit xml report stays enabled for CI test-result collection`() {
    assertThat(renderTask().reports.junitXml.required.get()).isTrue()
  }

  @Test
  fun `render jvm encoding is pinned to UTF-8 regardless of ambient locale`() {
    assertThat(renderTask().defaultCharacterEncoding).isEqualTo("UTF-8")
  }
}
