package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Behavioural tests for the render-JVM selection policy. These pin *decisions* about which JDK the
 * render subprocess forks into — never render on an older JVM than the consumer's bytecode, prefer
 * an upgrade, honour an explicit override verbatim — independent of any single call site's wiring.
 */
class RenderJvmSelectionTest {

  // --- selectMajor ---------------------------------------------------------

  @Test
  fun `keeps the inherited toolchain when it already covers everything`() {
    // Toolchain 21, Gradle on 21, bytecode 21 -> no upgrade needed.
    assertThat(RenderJvmSelection.selectMajor(21, 21, 21, null)).isEqualTo(21)
  }

  @Test
  fun `raises the render JVM to the bytecode target above the toolchain`() {
    // The meshcore case: toolchain/test-launcher is 17 but classes are Java-21 bytecode.
    assertThat(RenderJvmSelection.selectMajor(17, 17, 21, null)).isEqualTo(21)
  }

  @Test
  fun `raises to the Gradle daemon JVM when it is newer than the inherited launcher`() {
    // VS Code daemon fell back to JDK 17 but Gradle runs on 21 and bytecode is unknown.
    assertThat(RenderJvmSelection.selectMajor(17, 21, null, null)).isEqualTo(21)
  }

  @Test
  fun `falls back to the Gradle daemon JVM when no launcher is inherited`() {
    assertThat(RenderJvmSelection.selectMajor(null, 21, null, null)).isEqualTo(21)
  }

  @Test
  fun `never selects below the inherited toolchain even if bytecode reads lower`() {
    // A stray low bytecode reading must not drag a genuinely-21 toolchain render down to 17.
    assertThat(RenderJvmSelection.selectMajor(21, 17, 8, null)).isEqualTo(21)
  }

  @Test
  fun `explicit override wins outright, including deliberately lower values`() {
    // The SDK matrix pins a specific JDK; honour it exactly even below the other signals.
    assertThat(RenderJvmSelection.selectMajor(21, 21, 21, 17)).isEqualTo(17)
    assertThat(RenderJvmSelection.selectMajor(17, 17, 17, 25)).isEqualTo(25)
  }

  @Test
  fun `picks the highest of the bytecode and daemon signals`() {
    assertThat(RenderJvmSelection.selectMajor(17, 23, 21, null)).isEqualTo(23)
    assertThat(RenderJvmSelection.selectMajor(17, 21, 25, null)).isEqualTo(25)
  }

  // --- parseTargetMajor ----------------------------------------------------

  @Test
  fun `parses Kotlin JvmTarget and Java target forms`() {
    assertThat(BytecodeTargetDetector.parseTargetMajor("21")).isEqualTo(21)
    assertThat(BytecodeTargetDetector.parseTargetMajor("JVM_21")).isEqualTo(21)
    assertThat(BytecodeTargetDetector.parseTargetMajor("VERSION_17")).isEqualTo(17)
    assertThat(BytecodeTargetDetector.parseTargetMajor("1.8")).isEqualTo(8)
    assertThat(BytecodeTargetDetector.parseTargetMajor("VERSION_1_8")).isEqualTo(8)
  }

  @Test
  fun `parseTargetMajor tolerates junk`() {
    assertThat(BytecodeTargetDetector.parseTargetMajor(null)).isNull()
    assertThat(BytecodeTargetDetector.parseTargetMajor("")).isNull()
    assertThat(BytecodeTargetDetector.parseTargetMajor("nope")).isNull()
  }
}
