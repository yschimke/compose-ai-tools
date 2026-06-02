package ee.schimke.composeai.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompareSemverTest {
  @Test
  fun `equal versions compare zero`() {
    assertEquals(0, compareSemver("0.8.10", "0.8.10"))
  }

  @Test
  fun `patch bump compares as older`() {
    assertTrue(compareSemver("0.8.10", "0.8.11") < 0)
    assertTrue(compareSemver("0.8.11", "0.8.10") > 0)
  }

  @Test
  fun `minor bump beats patch component`() {
    assertTrue(compareSemver("0.8.99", "0.9.0") < 0)
  }

  @Test
  fun `major bump dominates`() {
    assertTrue(compareSemver("0.99.99", "1.0.0") < 0)
  }

  @Test
  fun `snapshot suffix is older than the same numeric base`() {
    // The build derives `<next>.<patch+1>-SNAPSHOT` from the manifest; doctor's update check
    // shouldn't shout "you're behind" when a developer is running their own snapshot ahead of
    // the published latest, but it should also recognise that 0.8.11-SNAPSHOT < 0.8.11 once the
    // tag actually ships.
    assertTrue(compareSemver("0.8.11-SNAPSHOT", "0.8.11") < 0)
    assertTrue(compareSemver("0.8.11", "0.8.11-SNAPSHOT") > 0)
  }

  @Test
  fun `snapshot ahead of published latest sorts as newer`() {
    assertTrue(compareSemver("0.8.11-SNAPSHOT", "0.8.10") > 0)
  }

  @Test
  fun `unparseable input falls back to string compare without throwing`() {
    // Doesn't matter what the answer is — only that it's deterministic and total.
    val result = compareSemver("not-a-version", "0.8.10")
    assertTrue(result != 0 || "not-a-version" == "0.8.10")
  }
}

class VersionCompatibilityTest {
  @Test
  fun `major version is the first numeric segment`() {
    assertEquals(0, majorVersionOf("0.12.5"))
    assertEquals(1, majorVersionOf("1.2.3"))
    assertEquals(2, majorVersionOf("v2.0.0-SNAPSHOT"))
    assertEquals(12, majorVersionOf("12.0.0"))
  }

  @Test
  fun `unparseable major returns null`() {
    assertEquals(null, majorVersionOf("main"))
    assertEquals(null, majorVersionOf(""))
    assertEquals(null, majorVersionOf("-SNAPSHOT"))
  }

  @Test
  fun `same major is compatible regardless of minor or patch`() {
    assertTrue(!versionsIncompatible("1.2.3", "1.9.0"))
    assertTrue(!versionsIncompatible("0.12.5", "0.8.0"))
    assertTrue(!versionsIncompatible("2.0.0-SNAPSHOT", "2.3.1"))
  }

  @Test
  fun `different major is incompatible`() {
    assertTrue(versionsIncompatible("1.0.0", "2.0.0"))
    assertTrue(versionsIncompatible("0.12.5", "1.0.0"))
  }

  @Test
  fun `unparseable version is treated as compatible (no false alarm)`() {
    // We can't reason about non-semver strings (a dev building from `main`), so don't warn.
    assertTrue(!versionsIncompatible("main", "1.0.0"))
    assertTrue(!versionsIncompatible("1.0.0", ""))
  }
}

class UpdateCommandPipelineTest {
  @Test
  fun `no version pins to latest via plain pipe`() {
    val pipeline = UpdateCommand.buildPipeline(null)
    assertEquals(
      "curl -fsSL https://raw.githubusercontent.com/yschimke/skills/main/scripts/install.sh | bash",
      pipeline,
    )
  }

  @Test
  fun `semver-like version is appended unquoted`() {
    val pipeline = UpdateCommand.buildPipeline("0.8.10")
    assertTrue(pipeline.endsWith(" | bash -s -- 0.8.10"), pipeline)
  }

  @Test
  fun `exotic version arg is single-quoted to defuse shell metacharacters`() {
    // Belt-and-braces: install.sh refuses non-semver input anyway, but `compose-preview update`
    // shouldn't allow `;rm -rf ~` to escape the curl-pipe-bash pipeline if a user pastes
    // something weird.
    val pipeline = UpdateCommand.buildPipeline("0.8.10; echo pwned")
    assertTrue(pipeline.endsWith(" | bash -s -- '0.8.10; echo pwned'"), pipeline)
  }

  @Test
  fun `embedded single quotes are escaped inside single-quoted form`() {
    val quoted = UpdateCommand.shellQuote("a'b")
    assertEquals("'a'\\''b'", quoted)
  }
}
