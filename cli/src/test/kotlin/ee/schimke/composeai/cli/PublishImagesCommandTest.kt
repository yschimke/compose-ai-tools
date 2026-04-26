package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PublishImagesArgParsingTest {
  @Test
  fun `single positional past flags`() {
    val args =
      listOf(
        "_pr_renders",
        "--branch",
        "preview_pr",
        "--remote",
        "origin",
        "--pr-number",
        "42",
        "--json",
      )
    assertEquals(listOf("_pr_renders"), PublishImagesCommand.parsePositional(args))
  }

  @Test
  fun `message flag with spaces in value`() {
    val args = listOf("--message", "Preview renders for issue #99", "/tmp/staging")
    assertEquals(listOf("/tmp/staging"), PublishImagesCommand.parsePositional(args))
  }

  @Test
  fun `unknown flag is dropped without consuming next positional`() {
    val args = listOf("--something", "/tmp/staging")
    assertEquals(listOf("/tmp/staging"), PublishImagesCommand.parsePositional(args))
  }
}

class PublishImagesMessageTest {
  @Test
  fun `message with both pr number and head sha matches GHA shape`() {
    assertEquals(
      "Preview renders for PR #42 (a1b2c3d4)",
      PublishImagesCommand.defaultMessage("42", "a1b2c3d4e5f6789"),
    )
  }

  @Test
  fun `message with only pr number`() {
    assertEquals("Preview renders for PR #42", PublishImagesCommand.defaultMessage("42", null))
  }

  @Test
  fun `message with only head sha`() {
    assertEquals(
      "Preview renders (a1b2c3d4)",
      PublishImagesCommand.defaultMessage(null, "a1b2c3d4e5f6789"),
    )
  }

  @Test
  fun `message with neither falls back to bare`() {
    assertEquals("Preview renders", PublishImagesCommand.defaultMessage(null, null))
  }
}

class PublishImagesRawUrlBaseTest {
  @Test
  fun `https github remote maps to raw url base`() {
    assertEquals(
      "https://raw.githubusercontent.com/yschimke/compose-ai-tools",
      PublishImagesCommand.githubRawUrlBase("https://github.com/yschimke/compose-ai-tools.git"),
    )
  }

  @Test
  fun `ssh github remote maps to raw url base`() {
    assertEquals(
      "https://raw.githubusercontent.com/yschimke/compose-ai-tools",
      PublishImagesCommand.githubRawUrlBase("git@github.com:yschimke/compose-ai-tools.git"),
    )
  }

  @Test
  fun `https remote without git suffix still maps`() {
    assertEquals(
      "https://raw.githubusercontent.com/owner/repo",
      PublishImagesCommand.githubRawUrlBase("https://github.com/owner/repo"),
    )
  }

  @Test
  fun `non-github remote returns null`() {
    assertNull(PublishImagesCommand.githubRawUrlBase("git@gitlab.com:owner/repo.git"))
    assertNull(PublishImagesCommand.githubRawUrlBase("https://example.com/owner/repo.git"))
  }

  @Test
  fun `malformed remote returns null`() {
    // Missing the `owner/` segment — defensive against weird remote URLs.
    assertNull(PublishImagesCommand.githubRawUrlBase("https://github.com/repo.git"))
    assertNull(PublishImagesCommand.githubRawUrlBase("git@github.com:repo.git"))
  }
}
