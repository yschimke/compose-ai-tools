package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.SharePreviewCommand.Mechanism
import ee.schimke.composeai.cli.SharePreviewCommand.MechanismResult
import ee.schimke.composeai.cli.SharePreviewCommand.Mode
import ee.schimke.composeai.cli.SharePreviewCommand.TargetResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharePreviewArgParsingTest {
  @Test
  fun `positional args extracted past flags and their values`() {
    val args =
      listOf(
        "report.md",
        "--mechanism",
        "branch",
        "--desc",
        "before/after for #123",
        "before.png",
        "after.png",
      )
    assertEquals(
      listOf("report.md", "before.png", "after.png"),
      SharePreviewCommand.parsePositional(args),
    )
  }

  @Test
  fun `value-less flags drop out of positionals`() {
    val args =
      listOf("--json", "doc.md", "--public", "a.png", "--allow-non-preview-branch", "b.png")
    assertEquals(listOf("doc.md", "a.png", "b.png"), SharePreviewCommand.parsePositional(args))
  }

  @Test
  fun `unknown flag is skipped without consuming a following positional`() {
    val args = listOf("--something", "note.md", "img.png")
    assertEquals(listOf("note.md", "img.png"), SharePreviewCommand.parsePositional(args))
  }
}

class SharePreviewMechanismTest {
  @Test
  fun `auto prefers gist when the gh CLI is available`() {
    val r =
      SharePreviewCommand.resolveMechanism(
        Mode.REPORT,
        forced = null,
        gistAvailable = { true },
        branchAvailable = { true },
      )
    assertEquals(Mechanism.GIST, (r as MechanismResult.Ok).mechanism)
  }

  @Test
  fun `auto falls back to branch when gh is unavailable`() {
    val r =
      SharePreviewCommand.resolveMechanism(
        Mode.REPORT,
        forced = null,
        gistAvailable = { false },
        branchAvailable = { true },
      )
    assertEquals(Mechanism.BRANCH, (r as MechanismResult.Ok).mechanism)
  }

  @Test
  fun `auto errors when neither mechanism is available`() {
    val r =
      SharePreviewCommand.resolveMechanism(
        Mode.REPORT,
        forced = null,
        gistAvailable = { false },
        branchAvailable = { false },
      )
    assertTrue(r is MechanismResult.Err)
  }

  @Test
  fun `forced gist errors when gh is unavailable`() {
    val r =
      SharePreviewCommand.resolveMechanism(
        Mode.REPORT,
        forced = Mechanism.GIST,
        gistAvailable = { false },
        branchAvailable = { true },
      )
    assertTrue(r is MechanismResult.Err && "gist" in r.message)
  }

  @Test
  fun `bulk directory can never be a gist`() {
    val r =
      SharePreviewCommand.resolveMechanism(
        Mode.BULK,
        forced = Mechanism.GIST,
        gistAvailable = { true },
        branchAvailable = { true },
      )
    assertTrue(r is MechanismResult.Err)
  }

  @Test
  fun `bulk directory uses the branch mechanism`() {
    val r =
      SharePreviewCommand.resolveMechanism(
        Mode.BULK,
        forced = null,
        gistAvailable = { true },
        branchAvailable = { true },
      )
    assertEquals(Mechanism.BRANCH, (r as MechanismResult.Ok).mechanism)
  }

  @Test
  fun `auto does not probe gh when forced to branch`() {
    var gistProbed = false
    SharePreviewCommand.resolveMechanism(
      Mode.REPORT,
      forced = Mechanism.BRANCH,
      gistAvailable = {
        gistProbed = true
        true
      },
      branchAvailable = { true },
    )
    assertTrue(!gistProbed, "forced branch should not shell out to gh auth status")
  }
}

class SharePreviewTargetBranchTest {
  @Test
  fun `target derived from current feature branch`() {
    val r =
      SharePreviewCommand.resolveTargetBranch(null, "agent/my-change", allowNonPreview = false)
    assertEquals("compose-preview/share/agent/my-change", (r as TargetResult.Ok).branch)
  }

  @Test
  fun `override is used verbatim when valid`() {
    val r =
      SharePreviewCommand.resolveTargetBranch(
        "compose-preview/pr",
        "agent/x",
        allowNonPreview = false,
      )
    assertEquals("compose-preview/pr", (r as TargetResult.Ok).branch)
  }

  @Test
  fun `refuses to snapshot from main`() {
    val r = SharePreviewCommand.resolveTargetBranch(null, "main", allowNonPreview = false)
    assertTrue(r is TargetResult.Err && "refusing to snapshot" in r.message)
  }

  @Test
  fun `refuses to snapshot from a release branch`() {
    val r = SharePreviewCommand.resolveTargetBranch(null, "release/v1.0", allowNonPreview = false)
    assertTrue(r is TargetResult.Err)
  }

  @Test
  fun `detached HEAD with no override errors`() {
    val r = SharePreviewCommand.resolveTargetBranch(null, null, allowNonPreview = false)
    assertTrue(r is TargetResult.Err && "--branch" in r.message)
  }

  @Test
  fun `override outside the allowlist needs the escape hatch`() {
    val rejected = SharePreviewCommand.resolveTargetBranch("screenshots", "agent/x", false)
    assertTrue(rejected is TargetResult.Err && "--allow-non-preview-branch" in rejected.message)
    val accepted = SharePreviewCommand.resolveTargetBranch("screenshots", "agent/x", true)
    assertTrue(accepted is TargetResult.Ok)
  }

  @Test
  fun `override of main is hard-blocked even with the escape hatch`() {
    val r = SharePreviewCommand.resolveTargetBranch("main", "agent/x", allowNonPreview = true)
    assertTrue(r is TargetResult.Err)
  }
}

class SharePreviewBranchValidationTest {
  @Test
  fun `compose-preview prefixes pass by default`() {
    assertNull(SharePreviewCommand.validateBranch("compose-preview/share/x", false))
    assertNull(SharePreviewCommand.validateBranch("preview_pr", false))
  }

  @Test
  fun `path traversal and refspec syntax rejected`() {
    assertNotNull(SharePreviewCommand.validateBranch("../etc", allowNonPreview = true))
    assertNotNull(SharePreviewCommand.validateBranch("compose-preview/pr:main", false))
    assertNotNull(SharePreviewCommand.validateBranch("-force", allowNonPreview = true))
    assertNotNull(SharePreviewCommand.validateBranch("compose-preview/pr@{1}", false))
  }
}

class SharePreviewRawUrlBaseTest {
  @Test
  fun `https and ssh github remotes map to raw url base`() {
    assertEquals(
      "https://raw.githubusercontent.com/yschimke/compose-ai-tools",
      SharePreviewCommand.githubRawUrlBase("https://github.com/yschimke/compose-ai-tools.git"),
    )
    assertEquals(
      "https://raw.githubusercontent.com/owner/repo",
      SharePreviewCommand.githubRawUrlBase("git@github.com:owner/repo.git"),
    )
  }

  @Test
  fun `loopback web proxy remote maps to raw url base`() {
    // Claude Code hosted sessions rewrite `origin` to a loopback proxy that fronts github.com.
    assertEquals(
      "https://raw.githubusercontent.com/yschimke/compose-ai-tools",
      SharePreviewCommand.githubRawUrlBase(
        "http://local_proxy@127.0.0.1:38695/git/yschimke/compose-ai-tools"
      ),
    )
    assertEquals(
      "https://raw.githubusercontent.com/owner/repo",
      SharePreviewCommand.githubRawUrlBase("http://localhost:8080/git/owner/repo.git"),
    )
  }

  @Test
  fun `non-loopback host on a git path is not assumed to be github`() {
    assertNull(SharePreviewCommand.githubRawUrlBase("https://ghe.example.com/git/owner/repo"))
  }

  @Test
  fun `non-github and malformed remotes return null`() {
    assertNull(SharePreviewCommand.githubRawUrlBase("git@gitlab.com:owner/repo.git"))
    assertNull(SharePreviewCommand.githubRawUrlBase("https://github.com/repo.git"))
    assertNull(SharePreviewCommand.githubRawUrlBase("http://127.0.0.1:38695/git/repo"))
  }
}

class SharePreviewGistUrlTest {
  @Test
  fun `extracts gist URL from gh stdout`() {
    assertEquals(
      "https://gist.github.com/octocat/abc123def456",
      SharePreviewCommand.extractGistUrl("\nhttps://gist.github.com/octocat/abc123def456\n"),
    )
    assertNull(SharePreviewCommand.extractGistUrl("nothing useful here"))
  }

  @Test
  fun `gist id is the last path segment`() {
    assertEquals(
      "abc123def456",
      SharePreviewCommand.parseGistId("https://gist.github.com/octocat/abc123def456"),
    )
    assertEquals(
      "abc123def456",
      SharePreviewCommand.parseGistId("https://gist.github.com/abc123def456"),
    )
  }

  @Test
  fun `raw base preserves username when present`() {
    assertEquals(
      "https://gist.githubusercontent.com/octocat/abc123def456/raw",
      SharePreviewCommand.parseRawBase("https://gist.github.com/octocat/abc123def456"),
    )
  }
}

class SharePreviewMessageTest {
  @Test
  fun `default message mirrors the CI shape`() {
    assertEquals(
      "Preview renders for PR #42 (a1b2c3d4)",
      SharePreviewCommand.defaultMessage("42", "a1b2c3d4e5f6789"),
    )
    assertEquals("Preview renders for PR #42", SharePreviewCommand.defaultMessage("42", null))
    assertEquals("Preview renders", SharePreviewCommand.defaultMessage(null, null))
  }
}
