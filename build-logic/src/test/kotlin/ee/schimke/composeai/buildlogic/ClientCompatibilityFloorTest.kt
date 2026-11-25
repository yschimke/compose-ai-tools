package ee.schimke.composeai.buildlogic

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ClientCompatibilityFloorTest {

  @Test
  fun `flags versions above ceiling and lockstep drift`() {
    val ceilings =
      listOf(
        ClientCompatibilityFloor.Ceiling("androidx.compose.ui:ui", 1, 9, "compose-bom-compat"),
        ClientCompatibilityFloor.Ceiling("io.github.classgraph:classgraph", 5, 999, "Java 17 floor"),
      )
    val pairs =
      listOf(
        ClientCompatibilityFloor.LockstepPair(
          "org.slf4j:slf4j-nop",
          "org.slf4j:slf4j-api",
          "gradle-tooling-api strict slf4j-api constraint",
        )
      )
    val resolved =
      listOf(
        ClientCompatibilityFloor.ResolvedModule("debugCompileClasspath", "androidx.compose.ui:ui", "1.10.0"),
        ClientCompatibilityFloor.ResolvedModule("runtimeClasspath", "io.github.classgraph:classgraph", "6.0.0"),
        ClientCompatibilityFloor.ResolvedModule("runtimeClasspath", "org.slf4j:slf4j-nop", "2.0.18"),
        ClientCompatibilityFloor.ResolvedModule("runtimeClasspath", "org.slf4j:slf4j-api", "2.0.17"),
      )

    val versionOffenders = ClientCompatibilityFloor.findVersionOffenders(resolved, ceilings)
    assertEquals(2, versionOffenders.size)
    assertTrue(versionOffenders.any { it.contains("androidx.compose.ui:ui:1.10.0") })
    assertTrue(versionOffenders.any { it.contains("io.github.classgraph:classgraph:6.0.0") })

    val lockstepOffenders = ClientCompatibilityFloor.findLockstepOffenders(resolved, pairs)
    assertEquals(1, lockstepOffenders.size)
    assertTrue(lockstepOffenders.single().contains("org.slf4j:slf4j-nop:2.0.18 != org.slf4j:slf4j-api:2.0.17"))
  }

  @Test
  fun `flags class files above Java 17 while ignoring multi-release entries`(@TempDir tempDir: File) {
    val jar = File(tempDir, "sample.jar")
    ZipOutputStream(jar.outputStream()).use { zos ->
      zos.putNextEntry(ZipEntry("com/example/Java17.class"))
      zos.write(classHeader(61))
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("META-INF/versions/21/com/example/Java21Mr.class"))
      zos.write(classHeader(65))
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("com/example/Java25.class"))
      zos.write(classHeader(69))
      zos.closeEntry()
    }

    val offenders = ClientCompatibilityFloor.findBytecodeOffenders(listOf(jar), maxClassMajor = 61)
    assertEquals(listOf("sample.jar!com/example/Java25.class (major 69)"), offenders)
  }

  private fun classHeader(major: Int): ByteArray =
    byteArrayOf(
      0xCA.toByte(),
      0xFE.toByte(),
      0xBA.toByte(),
      0xBE.toByte(),
      0x00,
      0x00,
      ((major shr 8) and 0xFF).toByte(),
      (major and 0xFF).toByte(),
    )
}
