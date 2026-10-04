package ee.schimke.composeai.plugin

import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLauncher

/**
 * AGP's `test<Variant>UnitTest` task, bound whenever it becomes available.
 *
 * AGP registers that task after `onVariants`, so a render task configured early — an upstream
 * `tasks.withType<Test>().all {}` realizes every Test task as soon as it is registered — sees
 * `findByName` return null and used to drop AGP's test classes, JVM args and toolchain launcher for
 * good. This holder looks the task up when asked and, when it is not there yet, replays the request
 * after evaluation, still at configuration time, so no Project reference reaches a task action or
 * the configuration cache. It only ever realizes AGP's own unit-test task, exactly as the eager
 * lookup did.
 *
 * Must be constructed while the project is still configuring (it registers an `afterEvaluate`).
 */
internal class LateAgpTestTask(private val project: Project, private val unitTestTaskName: String) {
  /** AGP's test classes dirs; empty until (and unless) the task exists. */
  val testClassesDirs: ConfigurableFileCollection = project.files()

  /** AGP's full unit-test classpath; empty until (and unless) the task exists. */
  val classpath: ConfigurableFileCollection = project.files()

  private val pending = mutableListOf<(Test) -> Unit>()
  private var evaluated = false

  init {
    pending += { agp ->
      testClassesDirs.from(agp.testClassesDirs)
      classpath.from(agp.classpath)
    }
    project.afterEvaluate {
      evaluated = true
      val agp = find()
      val actions = pending.toList()
      pending.clear()
      if (agp != null) actions.forEach { it(agp) }
    }
  }

  /**
   * Runs [action] with AGP's unit-test task now when it is registered, else after evaluation if it
   * is registered by then. Returns true only when [action] ran immediately, so a caller can apply
   * its no-AGP-task fallback otherwise (a deferred [action] then overrides that fallback).
   */
  fun whenAvailable(action: (Test) -> Unit): Boolean {
    val agp = find()
    if (agp != null) {
      action(agp)
      return true
    }
    if (!evaluated) pending += action
    return false
  }

  /**
   * Copies AGP's unit-test JVM args and forks [task] on [launcherFor]'s choice for AGP's launcher,
   * or, while/when there is no AGP task, on [launcherFor]`(null)`.
   */
  fun inheritJvmSettings(task: Test, launcherFor: (Test?) -> Provider<JavaLauncher>?) {
    val immediate = whenAvailable { agp ->
      task.jvmArgs(agp.jvmArgs ?: emptyList<String>())
      launcherFor(agp)?.let { task.javaLauncher.set(it) }
    }
    if (!immediate) launcherFor(null)?.let { task.javaLauncher.set(it) }
  }

  private fun find(): Test? = project.tasks.findByName(unitTestTaskName) as? Test
}
