package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.junit.jupiter.api.Test

class ServeParityIssuesStoreTest {
  private val fs = FakeFileSystem()
  private val root = "/bundle".toPath()
  private val json = Json { prettyPrint = true }

  private fun issue(
    number: Int = 40,
    state: String = "open",
    url: String = "https://github.com/yschimke/m3-catalog/issues/$number",
  ) =
    ParityIssue(
      repository = "yschimke/m3-catalog",
      number = number,
      title = "IconButton glyph colour",
      url = url,
      state = state,
      area = "component",
      parity = "known-difference",
      system = "m3",
      component = "IconButton/Tonal",
      previewIds = listOf("iconbutton-tonal__ideal__default__light"),
      referenceIds = listOf("iconbutton-tonal-figma"),
    )

  private fun write(raw: String) {
    fs.createDirectories(root / ParityIssues.DIRECTORY)
    fs.write(root / ParityIssues.DIRECTORY / ParityIssues.FILE) { writeUtf8(raw) }
  }

  private fun write(value: ParityIssues) = write(json.encodeToString(value))

  private fun load() = ServeParityIssuesStore.load(File("/bundle"), fs)

  @Test
  fun `an umbrella issue keeps a row per component, and a true duplicate still collapses`() {
    // One issue may name several components — the producer emits a row each so the report reaches
    // every component page it is about. Deduping by repository + number alone kept an arbitrary one
    // of them, which is the silent half of the failure: the issue still appeared, just not where
    // the rest of it belonged.
    write(
      ParityIssues(
        generatedAt = "2026-08-15T10:00:00Z",
        issues =
          listOf(
            issue().copy(component = "Button/Elevated"),
            issue().copy(component = "Card/Elevated"),
            issue().copy(component = "ToggleButton/Elevated"),
            // Same repository, number AND component: a genuine duplicate, and still one row.
            issue().copy(component = "Card/Elevated"),
          ),
      )
    )
    val rows = assertNotNull(load()).issues
    assertEquals(
      listOf("Button/Elevated", "Card/Elevated", "ToggleButton/Elevated"),
      rows.map { it.component },
    )
    assertEquals(listOf(40, 40, 40), rows.map { it.number }, "the rows are one issue")
  }

  @Test
  fun `loads valid open and closed rows`() {
    write(
      ParityIssues(
        generatedAt = "2026-08-15T10:00:00Z",
        issues = listOf(issue(), issue(41, "closed")),
      )
    )
    val rows = assertNotNull(load()).issues
    assertEquals(listOf("open", "closed"), rows.map { it.state })
    assertEquals("https://github.com/yschimke/m3-catalog/issues/40", rows.first().url)
  }

  @Test
  fun `loads the JavaScript producer fixture without schema drift`() {
    write(File(repoRoot(), "scripts/design-artifacts/fixtures/parity-issues.json").readText())
    assertEquals(listOf("open", "closed"), assertNotNull(load()).issues.map { it.state })
  }

  @Test
  fun `missing wrong-schema and truncated indexes fail soft`() {
    assertNull(load())
    write(ParityIssues(schema = "future/v2", issues = listOf(issue())))
    assertNull(load())
    write("{\"schema\":\"compose-preview-issues/v1\",\"issues\":[")
    assertNull(load())
  }

  @Test
  fun `oversized index drops wholesale`() {
    write(ParityIssues(issues = List(ServeParityIssuesStore.MAX_ISSUES + 1) { issue(it + 1) }))
    assertNull(load())
  }

  @Test
  fun `unparseable or mismatched urls are dropped and never forwarded`() {
    val sanitized =
      assertNotNull(
        ServeParityIssuesStore.sanitize(
          ParityIssues(
            issues =
              listOf(
                issue(url = "javascript:alert(1)"),
                issue(41, url = "https://github.com/other/repo/issues/41"),
                issue(42, url = "https://WWW.GITHUB.COM/YSCHIMKE/M3-CATALOG/issues/42/"),
              )
          )
        )
      )
    assertEquals(listOf(42), sanitized.issues.map { it.number })
    assertEquals("https://github.com/yschimke/m3-catalog/issues/42", sanitized.issues.single().url)
  }

  @Test
  fun `unknown labels are discarded without discarding the issue`() {
    val row =
      assertNotNull(
          ServeParityIssuesStore.sanitize(
            ParityIssues(issues = listOf(issue().copy(area = "admin", parity = "magic")))
          )
        )
        .issues
        .single()
    assertNull(row.area)
    assertNull(row.parity)
  }
}
