package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShareGistArgParsingTest {
  @Test
  fun `positional args are extracted past flags and their values`() {
    val args =
      listOf("report.md", "--secret", "--desc", "before/after for #123", "before.png", "after.png")
    assertEquals(
      listOf("report.md", "before.png", "after.png"),
      ShareGistCommand.parsePositional(args),
    )
  }

  @Test
  fun `unknown flags are skipped without consuming a following positional`() {
    // `--something` is treated as a value-less flag; the next token (`note.md`) stays positional.
    val args = listOf("--something", "note.md", "img.png")
    assertEquals(listOf("note.md", "img.png"), ShareGistCommand.parsePositional(args))
  }

  @Test
  fun `desc consumes the value after it even when the value contains spaces`() {
    val args = listOf("--desc", "PR #42 before/after", "doc.md", "a.png")
    assertEquals(listOf("doc.md", "a.png"), ShareGistCommand.parsePositional(args))
  }

  @Test
  fun `json and public secret flags drop out of positionals`() {
    val args = listOf("--json", "doc.md", "--public", "a.png", "b.png")
    assertEquals(listOf("doc.md", "a.png", "b.png"), ShareGistCommand.parsePositional(args))
  }
}

class ShareGistUrlParsingTest {
  @Test
  fun `extracts gist URL from gh stdout with surrounding whitespace`() {
    val out = "\nhttps://gist.github.com/octocat/abc123def456\n"
    assertEquals(
      "https://gist.github.com/octocat/abc123def456",
      ShareGistCommand.extractGistUrl(out),
    )
  }

  @Test
  fun `returns null when stdout has no gist URL`() {
    assertNull(ShareGistCommand.extractGistUrl("nothing useful here"))
  }

  @Test
  fun `gist id is the last path segment for authenticated URL`() {
    assertEquals(
      "abc123def456",
      ShareGistCommand.parseGistId("https://gist.github.com/octocat/abc123def456"),
    )
  }

  @Test
  fun `gist id is the only path segment for anonymous URL`() {
    assertEquals(
      "abc123def456",
      ShareGistCommand.parseGistId("https://gist.github.com/abc123def456"),
    )
  }

  @Test
  fun `raw base preserves username when present`() {
    // Authenticated gists serve raw assets under user/id, matching what GitHub's UI links to.
    assertEquals(
      "https://gist.githubusercontent.com/octocat/abc123def456/raw",
      ShareGistCommand.parseRawBase("https://gist.github.com/octocat/abc123def456"),
    )
  }

  @Test
  fun `raw base falls back to id-only for anonymous URL`() {
    assertEquals(
      "https://gist.githubusercontent.com/abc123def456/raw",
      ShareGistCommand.parseRawBase("https://gist.github.com/abc123def456"),
    )
  }
}
