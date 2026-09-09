package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

/**
 * The one place both publishing lanes resolve a policy's `templates`.
 *
 * The prefix rule and the containment rule are separate claims and are tested separately: the first
 * says what a path may look like, the second where it is allowed to end up, and a textual prefix
 * check satisfied the first while `ui-builder/../catalog.spec.json` walked straight past the
 * second.
 */
class UiBuilderTemplateLookupTest {
  private fun root(): File =
    Files.createTempDirectory("ui-builder-lookup").toFile().also { it.deleteOnExit() }

  private fun write(root: File, path: String, text: String = "{}"): File =
    File(root, path).also {
      it.parentFile.mkdirs()
      it.writeText(text)
    }

  @Test
  fun `a design under ui-builder resolves`() {
    val root = root()
    val design = write(root, "ui-builder/designs/blank.json")

    val found =
      UiBuilderTemplateLookup.resolve(
        listOf(root),
        listOf("ui-builder/designs/blank.json"),
        moduleOwnsPolicy = true,
      )

    assertThat(found).containsExactly("ui-builder/designs/blank.json", design.canonicalFile)
  }

  @Test
  fun `a path that escapes the tree with dot-dot resolves to nothing`() {
    val root = root()
    write(root, "ui-builder/designs/blank.json")
    // A real file, outside the declared input tree, named by a path the prefix rule accepts.
    write(root, "catalog.spec.json", """{"system":"wear-m3"}""")

    val found =
      UiBuilderTemplateLookup.resolve(
        listOf(root),
        listOf("ui-builder/../catalog.spec.json"),
        moduleOwnsPolicy = true,
      )

    assertThat(found).isEmpty()
  }

  @Test
  fun `a path outside ui-builder is refused before anything is read`() {
    val root = root()
    write(root, "secrets.json")

    assertThat(
        UiBuilderTemplateLookup.resolve(
          listOf(root),
          listOf("secrets.json"),
          moduleOwnsPolicy = true,
        )
      )
      .isEmpty()
  }

  @Test
  fun `withdrawing the catalog withdraws the designs it advertised`() {
    // Removing a policy took an early return that deleted `ui-builder.json` and nothing else. The
    // designs sit in a declared output directory Gradle does not empty between runs, so they stayed
    // behind — listed by the local builder as designs no policy owns and nothing explains.
    val root = root()
    val out = write(root, "compose-previews/ui-builder.json")
    val templateDir = File(root, "compose-previews/ui-builder")
    val stale = write(root, "compose-previews/ui-builder/designs/blank.json")

    UiBuilderTemplateLookup.withdraw(out, templateDir)

    assertThat(out.exists()).isFalse()
    assertThat(stale.exists()).isFalse()
    // Still declared as an output, so it exists and is empty rather than missing.
    assertThat(templateDir.isDirectory).isTrue()
    assertThat(templateDir.listFiles()).isEmpty()
  }

  @Test
  fun `precedence follows the policy the caller chose`() {
    // A module-local design kept for some other catalog must not shadow the design the selected
    // policy owns when that policy came from the repository root.
    val module = root()
    val repo = root()
    write(module, "ui-builder/designs/blank.json", """{"from":"module"}""")
    val fromRepo = write(repo, "ui-builder/designs/blank.json", """{"from":"repo"}""")

    val found =
      UiBuilderTemplateLookup.resolve(
        listOf(module, repo),
        listOf("ui-builder/designs/blank.json"),
        moduleOwnsPolicy = false,
      )

    assertThat(found.values.single()).isEqualTo(fromRepo.canonicalFile)
  }
}
