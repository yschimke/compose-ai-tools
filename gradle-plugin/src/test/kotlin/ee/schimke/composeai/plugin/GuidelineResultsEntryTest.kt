package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuidelineResultsEntryTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `a guidelines run's results are carried when present and readable`() {
    val report = """{"module":":catalog","catalog":"wear-m3","results":[]}"""
    val file = tmp.newFile("guidelines.json").apply { writeText(report) }
    assertThat(guidelineResultsEntry(listOf(file))).isEqualTo(report.toByteArray())
  }

  @Test
  fun `no results file, or one that is not a report, carries nothing`() {
    assertThat(guidelineResultsEntry(listOf(File(tmp.root, "guidelines.json")))).isNull()
    val stray = tmp.newFile("guidelines.json").apply { writeText("""{"results": "nope"}""") }
    val told = mutableListOf<File>()
    assertThat(guidelineResultsEntry(listOf(stray)) { told += it }).isNull()
    assertThat(told).containsExactly(stray)
    stray.writeText("{truncated")
    assertThat(guidelineResultsEntry(listOf(stray))).isNull()
  }

  @Test
  fun `results are keyed by the bundle's preview ids, and previews it does not carry are dropped`() {
    val report =
      """{"module":":catalog","results":[""" +
        """{"previewId":"x.A B","record":{"previewId":"x.A B","model":"m"}},""" +
        """{"previewId":"x.Gone","record":{"previewId":"x.Gone","model":"m"}}]}"""
    val file = tmp.newFile("guidelines.json").apply { writeText(report) }
    val bytes = guidelineResultsEntry(listOf(file), mapOf("x.A B" to "x_a_b"))!!
    val text = bytes.toString(Charsets.UTF_8)
    assertThat(text).contains("\"previewId\":\"x_a_b\"")
    assertThat(text).doesNotContain("x.A B")
    assertThat(text).doesNotContain("x.Gone")
    assertThat(text).contains("\"module\":\":catalog\"")
  }
}
