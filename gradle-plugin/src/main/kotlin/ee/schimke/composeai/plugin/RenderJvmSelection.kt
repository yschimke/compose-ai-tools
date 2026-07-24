package ee.schimke.composeai.plugin

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService

/**
 * Picks the JDK the preview *render subprocess* forks into so it can load the consumer's compiled
 * classes, instead of asking the consumer to downgrade their bytecode target.
 *
 * ## The problem
 *
 * Every render path (`composePreviewRender`, the resource/XR render tasks, the VS Code daemon) runs
 * the consumer's `.class` files in a forked JVM, and that JVM is chosen by inheriting AGP's
 * unit-test `javaLauncher` — which follows the consumer's *toolchain*. But Kotlin's `jvmTarget` can
 * emit **newer** bytecode than the toolchain JDK (kotlinc on JDK 17 happily emits Java-21 class
 * files), and the VS Code daemon frequently falls back to its own bundled JDK 17. When the render
 * JVM is older than the bytecode it must load, the class loader throws
 * `UnsupportedClassVersionError` and *every* preview fails — with no message that points at the
 * JDK.
 *
 * The historical workaround (see meshcore-mobile#271) was for the app to pin every module's
 * bytecode back down to Java 17. That's invasive — a dozen `build.gradle.kts` edits — and it fights
 * the direction consumers actually want to move. Instead the plugin selects a render JVM new enough
 * for the consumer's bytecode and provisions it through Gradle's toolchain service.
 *
 * ## Selection
 *
 * [selectMajor] is the pure decision. It never returns *below* the inherited launcher (so an
 * upgrade is the only automatic move — we never silently render on an older JVM than the consumer's
 * own toolchain), and an explicit `composePreview.renderJavaVersion` override wins outright so the
 * SDK matrix (and anyone deliberately pinning a JDK) keeps working.
 */
internal object RenderJvmSelection {
  /**
   * The JDK major the render subprocess should run on.
   *
   * @param inheritedMajor the major of AGP's unit-test `javaLauncher` (the consumer toolchain), or
   *   `null` when AGP exposes none and the render would fall through to the Gradle daemon JVM.
   * @param gradleDaemonMajor `JavaVersion.current()` — the JVM Gradle itself runs on, always
   *   available without provisioning.
   * @param bytecodeMajor the highest bytecode target detected across the module's Kotlin/Java
   *   compilation, or `null` when it can't be determined.
   * @param explicitOverride `composePreview.renderJavaVersion` (or the matching `-P` property);
   *   when set it is honoured verbatim, including deliberately *lower* values.
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
   * Build the launcher provider for a render task, given AGP's inherited launcher. Stays lazy — the
   * inherited launcher is only resolved inside the returned provider — so config-cache
   * serialization and toolchain resolution both defer to execution time and nothing captures the
   * [Project].
   *
   * When the selected major matches (or is below) what was inherited, the *original* inherited
   * launcher provider is returned unchanged, preserving the exact JVM AGP wired (issue #142). Only
   * a genuine upgrade — or an explicit override — routes through [JavaToolchainService], which then
   * finds an installed JDK of that version or provisions one.
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
      // No inherited launcher: the render would fall through to the Gradle daemon JVM. Only take
      // over (routing through the toolchain service) when the bytecode target genuinely exceeds it;
      // otherwise return null so the caller leaves the convention untouched — no toolchain
      // resolution, byte-for-byte the prior behaviour.
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
}

/**
 * Best-effort detection of the highest JVM bytecode target the consumer's classes are compiled to,
 * so [RenderJvmSelection] can raise the render JVM to match. Every probe is defensive: a missing
 * Kotlin Gradle plugin, a renamed KGP method, or an unrealised task yields `null`/skips rather than
 * failing configuration. Detection under-reporting is safe (we fall back to the toolchain/daemon
 * JVM); over-reporting only matters if a matching JDK can't be provisioned, which surfaces as a
 * clear toolchain error plus the `composePreview.renderJavaVersion` escape hatch.
 */
internal object BytecodeTargetDetector {
  /**
   * Parse a Kotlin `JvmTarget` / Java `targetCompatibility` string to its class-file major-version
   * "feature" number: `"21"`/`"JVM_21"` -> 21, `"1.8"`/`"VERSION_1_8"` -> 8. Returns `null` when no
   * version-shaped token is present.
   */
  fun parseTargetMajor(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    // Strip any prefix (JVM_, VERSION_) down to the numeric tail; normalise the legacy "1.8" form.
    val digits = raw.substringAfterLast('_').substringAfterLast('=').trim()
    val normalised = if (digits.startsWith("1.")) digits.removePrefix("1.") else digits
    return normalised.takeWhile { it.isDigit() }.toIntOrNull()?.takeIf { it in 1..99 }
  }

  /**
   * Read `compilerOptions.jvmTarget` off the named Kotlin compile tasks by reflection, so the
   * plugin needn't declare a compile dependency on the Kotlin Gradle plugin. Returns the highest
   * major found, or `null`.
   */
  fun detectKotlinJvmTarget(project: Project, candidateTaskNames: List<String>): Int? {
    var best: Int? = null
    for (name in candidateTaskNames) {
      val task = project.tasks.findByName(name) ?: continue
      val major =
        runCatching {
            val opts = task.javaClass.getMethod("getCompilerOptions").invoke(task)
            val prop =
              opts.javaClass.methods.firstOrNull { it.name == "getJvmTarget" }?.invoke(opts)
                ?: return@runCatching null
            val value =
              prop.javaClass.getMethod("getOrNull").invoke(prop) ?: return@runCatching null
            parseTargetMajor(value.toString())
          }
          .getOrNull()
      if (major != null && (best == null || major > best!!)) best = major
    }
    return best
  }
}
