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
}
