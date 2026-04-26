package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

class PublishImagesBranchValidationTest {
  @Test
  fun `preview_pr passes by default`() {
    assertNull(PublishImagesCommand.validateBranch("preview_pr", allowNonPreview = false))
  }

  @Test
  fun `preview_main passes by default`() {
    assertNull(PublishImagesCommand.validateBranch("preview_main", allowNonPreview = false))
  }

  @Test
  fun `non-preview branch rejected without escape hatch`() {
    val message = PublishImagesCommand.validateBranch("screenshots", allowNonPreview = false)
    assertTrue(
      message != null && "preview_*" in message && "--allow-non-preview-branch" in message,
      "expected reject mentioning the allowlist and escape flag, got $message",
    )
  }

  @Test
  fun `non-preview branch passes with escape hatch`() {
    assertNull(PublishImagesCommand.validateBranch("screenshots", allowNonPreview = true))
  }

  @Test
  fun `main is hard-blocked even with escape hatch`() {
    val message = PublishImagesCommand.validateBranch("main", allowNonPreview = true)
    assertTrue(
      message != null && "mainline" in message,
      "expected hard-block on main, got $message",
    )
  }

  @Test
  fun `master develop trunk HEAD all hard-blocked`() {
    for (b in listOf("master", "develop", "trunk", "HEAD")) {
      val message = PublishImagesCommand.validateBranch(b, allowNonPreview = true)
      assertTrue(message != null, "expected hard-block on '$b', got null")
    }
  }

  @Test
  fun `release prefix hard-blocked even with escape hatch`() {
    assertNotNull(PublishImagesCommand.validateBranch("release/v1.0", allowNonPreview = true))
  }

  @Test
  fun `path traversal rejected`() {
    val message = PublishImagesCommand.validateBranch("../etc", allowNonPreview = true)
    assertTrue(
      message != null && "path-injection" in message,
      "expected refname rejection, got $message",
    )
  }

  @Test
  fun `refspec colon rejected`() {
    assertNotNull(PublishImagesCommand.validateBranch("preview_pr:main", allowNonPreview = false))
  }

  @Test
  fun `leading dash rejected to prevent flag injection`() {
    assertNotNull(PublishImagesCommand.validateBranch("-force", allowNonPreview = true))
  }

  @Test
  fun `at-brace rejected`() {
    // `branch@{1}` is a reflog selector; pushing to it is meaningless and we don't want exotic
    // git syntax leaking through.
    assertNotNull(PublishImagesCommand.validateBranch("preview_pr@{1}", allowNonPreview = false))
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
