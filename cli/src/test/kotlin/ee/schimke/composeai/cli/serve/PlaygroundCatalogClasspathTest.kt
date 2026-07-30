package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure classpath-assembly half of the catalog liveBundle → playground compile classpath
 * resolver.
 */
class PlaygroundCatalogClasspathTest {

  private val root: File = Files.createTempDirectory("pg-catalog-cp").toFile()

  @Test
  fun `catalog classes come first, then libs, then resolved jars`() {
    val classes = File(root, "classes")
    val embeddedLib = File(root, "app-project.jar")
    val material3 = File(root, "material3.jar")
    val foundation = File(root, "foundation.jar")

    val cp =
      PlaygroundCatalogClasspath.assemble(
        system = "compose-m3",
        classesDir = classes,
        libJars = listOf(embeddedLib),
        resolvedJars = listOf(material3, foundation),
      )

    assertEquals("playground-compose-m3", cp.moduleName)
    assertEquals(
      listOf(classes, embeddedLib, material3, foundation).map { it.absolutePath },
      cp.entries.map { it.toString() },
      "the snippet compiles against the catalog classes first, then its resolved dependencies",
    )
  }

  @Test
  fun `duplicate jars are collapsed while preserving first-seen order`() {
    val classes = File(root, "classes")
    val material3 = File(root, "material3.jar")

    val cp =
      PlaygroundCatalogClasspath.assemble(
        system = "compose-m3",
        classesDir = classes,
        libJars = listOf(material3),
        resolvedJars = listOf(material3, File(root, "runtime.jar")),
      )

    assertEquals(
      listOf(classes, material3, File(root, "runtime.jar")).map { it.absolutePath },
      cp.entries.map { it.toString() },
      "a jar that appears as both an embedded lib and a resolved dep is listed once",
    )
  }

  @Test
  fun `an empty dependency set still yields the catalog classes`() {
    val classes = File(root, "classes")
    val cp = PlaygroundCatalogClasspath.assemble("bespoke", classes, emptyList(), emptyList())
    assertEquals(listOf(classes.absolutePath), cp.entries.map { it.toString() })
  }
}
