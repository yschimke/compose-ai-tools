package ee.schimke.composeai.plugin.daemon

import ee.schimke.composeai.plugin.CheapSignalFiles
import java.io.File
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.SourceSetContainer

/**
 * Fills [DaemonBootstrapTask]'s source-watch inputs: where the variant the daemon serves is built
 * from, as the build itself declares it, so the descriptor's `sourceWatch` names custom `srcDirs`,
 * generated sources and KMP source sets that a reader's own conventions would miss. The Android
 * wiring, which reads AGP's variant API, lives with the rest of it in `AndroidPreviewSupport`; this
 * object stays free of AGP types so desktop-only builds load it.
 */
internal object SourceWatchInputs {

  /**
   * The desktop daemon's sources: the Kotlin compilation whose runtime classpath is
   * [runtimeConfigName] (the one the daemon runs against), and the Java source set of the same
   * classpath. Resolved late, since KGP and KMP targets configure after this plugin applies.
   */
  fun desktop(task: DaemonBootstrapTask, project: Project, runtimeConfigName: () -> String) {
    val dirs = project.provider { desktopDirs(project, runtimeConfigName()) }
    task.watchSourceDirs.set(dirs.map { it.first })
    task.watchResourceDirs.set(dirs.map { it.second })
    task.watchBuildFiles.set(buildFiles(project))
  }

  /** Build scripts and version catalogs whose edits change the module's classpath. */
  fun buildFiles(project: Project): List<String> =
    CheapSignalFiles.collect(project).map { it.absolutePath }

  private fun desktopDirs(
    project: Project,
    runtimeConfigName: String,
  ): Pair<List<String>, List<String>> {
    val sources = LinkedHashSet<File>()
    val resources = LinkedHashSet<File>()
    kotlinSourceSets(project, runtimeConfigName).forEach { sourceSet ->
      (call(sourceSet, "getKotlin") as? SourceDirectorySet)?.let { sources += it.srcDirs }
      (call(sourceSet, "getResources") as? SourceDirectorySet)?.let { resources += it.srcDirs }
    }
    project.extensions.findByType(SourceSetContainer::class.java)?.forEach { sourceSet ->
      if (sourceSet.runtimeClasspathConfigurationName != runtimeConfigName) return@forEach
      sources += sourceSet.java.srcDirs
      resources += sourceSet.resources.srcDirs
    }
    return sources.map { it.absolutePath } to resources.map { it.absolutePath }
  }

  /**
   * Every Kotlin source set compiled into the KGP compilation whose runtime classpath is
   * [runtimeConfigName], `dependsOn` parents included (`commonMain` for a KMP `desktopMain`). By
   * reflection, as elsewhere in this plugin: it has no compile dependency on KGP. Empty when KGP
   * isn't applied or its API moved.
   */
  private fun kotlinSourceSets(project: Project, runtimeConfigName: String): List<Any> {
    val kotlin = project.extensions.findByName("kotlin") ?: return emptyList()
    val targets =
      (call(kotlin, "getTargets") as? Iterable<*>)?.filterNotNull()
        ?: listOfNotNull(call(kotlin, "getTarget"))
    for (target in targets) {
      val compilations = call(target, "getCompilations") as? Iterable<*> ?: continue
      for (compilation in compilations.filterNotNull()) {
        if (call(compilation, "getRuntimeDependencyConfigurationName") != runtimeConfigName)
          continue
        return (call(compilation, "getAllKotlinSourceSets") as? Iterable<*>)?.filterNotNull()
          ?: emptyList()
      }
    }
    return emptyList()
  }

  private fun call(target: Any, method: String): Any? = runCatching {
    target.javaClass.getMethod(method).also { it.trySetAccessible() }.invoke(target)
  }
    .getOrNull()
}
