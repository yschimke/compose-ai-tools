package ee.schimke.composeai.plugin

import org.gradle.api.Project
import org.gradle.api.provider.Provider

/**
 * The subset of [names] registered on [project] by the time the task graph is built, as task-name
 * strings for `dependsOn`.
 *
 * Use this instead of `project.tasks.matching { it.name in names }`. A predicate-based
 * `TaskCollection` must realize every registered task in the project to evaluate the predicate when
 * Gradle resolves it as a dependency, and realizing a task runs its configuration action. AGP 9.5
 * registers `generate<Variant>ComposePreviewRunfiles` for every variant and its configuration
 * throws when the variant has unit tests disabled (`enableUnitTest = false`, AGP's default for
 * `release` in many builds), so realizing it failed every compose-preview task with "Could not
 * create task ':app:generateReleaseComposePreviewRunfiles'".
 *
 * `TaskContainer.names` lists registered names without realizing anything, and a name string in
 * `dependsOn` realizes only that task.
 */
internal fun optionalTaskDependencies(
  project: Project,
  names: Collection<String>,
): Provider<List<String>> {
  val tasks = project.tasks
  val wanted = names.toList()
  return project.provider {
    val registered = tasks.names
    wanted.filter { it in registered }
  }
}
