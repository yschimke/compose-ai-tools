package ee.schimke.composeai.cli.simulator

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryRelocationsTest {

  private val dir: File = Files.createTempDirectory("relocations").toFile()
  private val classesJar = File(dir, "classes.jar").apply { writeText("jar") }

  @Test
  fun aDecisionIsReusedForTheSameRuntime() {
    var runs = 0
    val relocate: (File) -> String = { out ->
      runs++
      out.writeText("relocated")
      """{"relocated":["com/google/common/base"],"relocatedClasses":3}"""
    }

    val first = LibraryRelocations.ensure(classesJar, "aaaa", relocate)
    val second = LibraryRelocations.ensure(classesJar, "aaaa", relocate)

    assertEquals(1, runs)
    assertEquals(listOf("com/google/common/base"), first.packages)
    assertEquals(File(dir, "classes-relocated-aaaa.jar"), second.jar)
    assertEquals("relocated", second.jar!!.readText())
  }

  @Test
  fun nothingRelocatedRunsFromTheConvertedJarAndIsRemembered() {
    var runs = 0
    val outcome =
      LibraryRelocations.ensure(classesJar, "bbbb") {
        runs++
        """{"relocated":[],"relocatedClasses":0}"""
      }
    LibraryRelocations.ensure(classesJar, "bbbb") {
      runs++
      "{}"
    }

    assertNull(outcome.jar)
    assertTrue(outcome.packages.isEmpty())
    assertEquals(1, runs)
    assertFalse(File(dir, "classes-relocated-bbbb.jar").exists())
  }

  @Test
  fun anotherRuntimeDecidesAgainAndDropsTheOldDecision() {
    LibraryRelocations.ensure(classesJar, "old0") { out ->
      out.writeText("x")
      """{"relocated":["lib"]}"""
    }
    LibraryRelocations.ensure(classesJar, "new0") { """{"relocated":[]}""" }

    assertFalse(File(dir, "relocation-old0.json").exists())
    assertFalse(File(dir, "classes-relocated-old0.jar").exists())
    assertTrue(File(dir, "relocation-new0.json").exists())
    assertTrue(classesJar.exists())
  }

  @Test
  fun theStampFollowsTheRuntimeJarsAndForcedPackages() {
    val a = File(dir, "guava.jar").apply { writeText("12345") }
    val b = File(dir, "kotlin-stdlib.jar").apply { writeText("1") }
    val base = LibraryRelocations.stamp(listOf(a, b), null)

    assertEquals(base, LibraryRelocations.stamp(listOf(a, b), null))
    b.writeText("12")
    assertNotEquals(base, LibraryRelocations.stamp(listOf(a, b), null))
    assertNotEquals(base, LibraryRelocations.stamp(listOf(a, b), listOf("lib")))
  }

  @Test
  fun theCommandPassesTheRuntimeBoundaryAndForcedPackages() {
    val command =
      LibraryRelocations.command(
        java = File("/jdk/bin/java"),
        classpath = listOf(File("core.jar"), File("asm.jar")),
        classesJar = File("classes.jar"),
        out = File("out.jar"),
        androidJar = File("android.jar"),
        runtime = listOf(File("guava.jar")),
        boundary = listOf(File("simulator-core.jar")),
        forced = listOf("com/google/common/base"),
      )

    assertTrue("-D${LibraryRelocations.RUNTIME_PROPERTY}=guava.jar" in command)
    assertTrue("-D${LibraryRelocations.BOUNDARY_PROPERTY}=simulator-core.jar" in command)
    assertTrue("-D${LibraryRelocations.PACKAGES_PROPERTY}=com/google/common/base" in command)
    assertEquals(
      listOf(LibraryRelocations.MAIN, "classes.jar", "out.jar", "android.jar"),
      command.takeLast(4),
    )
  }

  @Test
  fun anUnreadableSummaryRelocatesNothing() {
    assertTrue(LibraryRelocations.parse("not json").isEmpty())
    assertTrue(LibraryRelocations.parse("""{"relocated":"x"}""").isEmpty())
  }
}
