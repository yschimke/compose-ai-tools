package ee.schimke.composeai.daemon.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the two invariants that make [DaemonProperties] a registry rather than a list that drifts:
 * it is **complete** (every `composeai.daemon.*` literal in the tree is declared) and it is the
 * **only** place those names are spelled inside `daemon/`.
 *
 * Both checks read the source tree, so a stray literal fails on the pull request that introduces it
 * rather than months later, when a rename silently stops honouring somebody's `-D` flag.
 */
class DaemonPropertyRegistryTest {

  @Test
  fun namesAreUnique() {
    val duplicates =
      DaemonProperties.ALL.groupBy { it.name }.filterValues { it.size > 1 }.keys.sorted()
    assertEquals("duplicate names in DaemonProperties.ALL", emptyList<String>(), duplicates)
    assertEquals(DaemonProperties.ALL.size, DaemonProperties.BY_NAME.size)
  }

  @Test
  fun everyNameIsUnderTheDaemonPrefix() {
    assertEquals(
      "DaemonProperties declares $PREFIX* knobs only",
      emptyList<String>(),
      DaemonProperties.ALL.map { it.name }.filterNot { it.startsWith(PREFIX) },
    )
  }

  @Test
  fun everyGroupIsRendered() {
    assertEquals(
      "group missing from DaemonProperties.GROUPS — the generated table would drop it",
      emptySet<String>(),
      DaemonProperties.ALL.map { it.group }.toSet() - DaemonProperties.GROUPS.toSet(),
    )
  }

  @Test
  fun theSourceScanSeesTheDaemonModules() {
    // Guards the two scanning tests below against being vacuously green.
    assertTrue(kotlinSources(File(repoRoot(), "daemon")).size > 100)
  }

  @Test
  fun registryDeclaresEveryLiteralInTheTree() {
    val undeclared =
      kotlinSources(repoRoot())
        .flatMap { file -> literalsIn(file).map { it to file.relativeTo(repoRoot()) } }
        .filterNot { (name, _) -> name in DaemonProperties.BY_NAME }
        .sortedBy { it.first }
    if (undeclared.isNotEmpty()) {
      fail(
        "these $PREFIX* names are used in the tree but not declared in DaemonProperties:\n" +
          undeclared.joinToString("\n") { (name, file) -> "  $name  ($file)" }
      )
    }
  }

  @Test
  fun daemonModulesSpellNoDaemonPropertyLiteral() {
    val offenders =
      kotlinSources(File(repoRoot(), "daemon"))
        .filterNot { it.path.endsWith(REGISTRY_FILE) }
        .flatMap { file -> literalsIn(file).map { "  $it  (${file.relativeTo(repoRoot())})" } }
        .sorted()
    if (offenders.isNotEmpty()) {
      fail(
        "daemon/ must reach $PREFIX* knobs through DaemonProperties — a typed entry, or " +
          "DaemonProperties.Names.* in a const context — never a string literal:\n" +
          offenders.joinToString("\n")
      )
    }
  }

  private companion object {
    const val PREFIX = "composeai.daemon."
    const val REGISTRY_FILE = "config/DaemonProperties.kt"

    /** Matches a *string literal* only, so KDoc prose like `-Dcomposeai.daemon.foo=1` is fine. */
    val LITERAL = Regex("\"(composeai\\.daemon\\.[A-Za-z.]+)\"")

    fun literalsIn(file: File): List<String> =
      LITERAL.findAll(file.readText()).map { it.groupValues[1] }.distinct().toList()

    fun kotlinSources(root: File): List<File> =
      root
        .walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" }
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    fun repoRoot(): File {
      var dir: File? = File("").absoluteFile
      while (dir != null) {
        if (File(dir, "settings.gradle.kts").isFile) return dir
        dir = dir.parentFile
      }
      error("could not locate the repository root from ${File("").absolutePath}")
    }
  }
}
