package ee.schimke.composeai.plugin

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService

/**
 * Picks the JDK the render subprocess forks into so it can load the consumer's classes.
 *
 * Render JVMs inherit AGP's unit-test `javaLauncher`, which follows the consumer's toolchain, but
 * Kotlin can emit newer bytecode than that JDK (and the VS Code daemon may fall back to its bundled
 * JDK 17), so every preview fails with `UnsupportedClassVersionError`. Rather than making consumers
 * downgrade their bytecode, select a new-enough JVM and provision it through Gradle's toolchain
 * service.
 *
 * [selectMajor] is the pure decision: never below the inherited launcher, and an explicit
 * `composePreview.renderJavaVersion` wins outright.
 */
internal object RenderJvmSelection {
  /**
   * The JDK major the render subprocess should run on.
   *
   * @param inheritedMajor AGP's unit-test launcher major, or `null` when there is none (Gradle JVM
   *   fallback).
   * @param gradleDaemonMajor `JavaVersion.current()`, always available.
   * @param bytecodeMajor the highest detected bytecode target, or `null`.
   * @param explicitOverride `composePreview.renderJavaVersion`; honoured verbatim, even if lower.
   */
  fun selectMajor(
    inheritedMajor: Int?,
    gradleDaemonMajor: Int,
    bytecodeMajor: Int?,
    explicitOverride: Int?,
  ): Int {
    explicitOverride?.let {
      return it
    }
    // max of every signal: never below the inherited toolchain, never below the JVM Gradle runs on,
    // and always at least the consumer's bytecode target when we could detect it.
    return maxOf(inheritedMajor ?: 0, gradleDaemonMajor, bytecodeMajor ?: 0)
  }

  /**
   * The launcher provider for a render task. Lazy, so configuration-cache serialization and
   * toolchain resolution defer to execution and nothing captures the [Project]. Returns the
   * inherited provider unchanged when no upgrade is needed (#142); only an upgrade or override goes
   * through [JavaToolchainService].
   */
  fun launcherFor(
    toolchains: JavaToolchainService,
    inherited: Provider<JavaLauncher>?,
    gradleDaemonMajor: Int,
    bytecodeMajor: Int?,
    explicitOverride: Int?,
  ): Provider<JavaLauncher>? {
    if (explicitOverride != null) {
      return toolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(explicitOverride))
      }
    }
    if (inherited == null) {
      // No inherited launcher: only take over when the bytecode exceeds the Gradle JVM, else return
      // null and leave the convention alone.
      val target = selectMajor(null, gradleDaemonMajor, bytecodeMajor, null)
      return if (target > gradleDaemonMajor) {
        toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(target)) }
      } else {
        null
      }
    }
    return inherited.flatMap { launcher ->
      val inheritedMajor = launcher.metadata.languageVersion.asInt()
      val target = selectMajor(inheritedMajor, gradleDaemonMajor, bytecodeMajor, null)
      if (target <= inheritedMajor) {
        inherited
      } else {
        toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(target)) }
      }
    }
  }

  /**
   * `java` path for a render that would run on the Gradle JVM (desktop `javaexec`), or `null` when
   * no upgrade is needed. A lazy, config-cache-safe [Provider].
   */
  fun daemonJvmExecutable(
    toolchains: JavaToolchainService,
    gradleDaemonMajor: Int,
    bytecodeMajor: Int?,
    explicitOverride: Int?,
  ): Provider<String>? =
    launcherFor(toolchains, null, gradleDaemonMajor, bytecodeMajor, explicitOverride)?.map {
      it.executablePath.asFile.absolutePath
    }

  /**
   * `java` path baked into `daemon-launch.json`. Unlike [daemonJvmExecutable], always non-null: the
   * daemon is spawned by VS Code or the MCP server, where null means "let the editor guess" (often
   * an older JDK). Pins `max(Gradle JVM, bytecode target)` or the override; the Gradle JVM term
   * keeps it resolvable without provisioning.
   */
  fun daemonDescriptorExecutable(
    toolchains: JavaToolchainService,
    gradleDaemonMajor: Int,
    bytecodeMajor: Int?,
    explicitOverride: Int?,
  ): Provider<String> {
    val target = selectMajor(null, gradleDaemonMajor, bytecodeMajor, explicitOverride)
    return toolchains
      .launcherFor { languageVersion.set(JavaLanguageVersion.of(target)) }
      .map { it.executablePath.asFile.absolutePath }
  }
}

/**
 * Best-effort detection of the consumer's highest bytecode target. Every probe is defensive.
 * Under-reporting falls back safely; over-reporting only matters if no JDK can be provisioned,
 * which surfaces as a toolchain error with `renderJavaVersion` as the escape hatch.
 */
internal object BytecodeTargetDetector {
  /**
   * `"21"`/`"JVM_21"` → 21, `"1.8"`/`"VERSION_1_8"` → 8; `null` when no version token is present.
   */
  fun parseTargetMajor(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    // Strip any prefix (JVM_, VERSION_) down to the numeric tail; normalise the legacy "1.8" form.
    val digits = raw.substringAfterLast('_').substringAfterLast('=').trim()
    val normalised = if (digits.startsWith("1.")) digits.removePrefix("1.") else digits
    return normalised.takeWhile { it.isDigit() }.toIntOrNull()?.takeIf { it in 1..99 }
  }

  /**
   * `compilerOptions.jvmTarget` from the named Kotlin compile tasks, by reflection (no KGP compile
   * dependency). Highest major, or `null`.
   */
  fun detectKotlinJvmTarget(project: Project, candidateTaskNames: List<String>): Int? {
    var best: Int? = null
    for (name in candidateTaskNames) {
      val task = project.tasks.findByName(name) ?: continue
      val major = runCatching {
        val opts = task.javaClass.getMethod("getCompilerOptions").invoke(task)
        val prop =
          opts.javaClass.methods.firstOrNull { it.name == "getJvmTarget" }?.invoke(opts)
            ?: return@runCatching null
        val value = prop.javaClass.getMethod("getOrNull").invoke(prop) ?: return@runCatching null
        parseTargetMajor(value.toString())
      }
        .getOrNull()
      if (major != null && (best == null || major > best!!)) best = major
    }
    return best
  }
}
