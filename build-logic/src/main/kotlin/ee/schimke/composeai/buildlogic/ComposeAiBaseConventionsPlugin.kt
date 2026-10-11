package ee.schimke.composeai.buildlogic

import com.ncorti.ktfmt.gradle.KtfmtExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

/**
 * Per-project conventions, applied by each module via `plugins { id("composeai.base-conventions")
 * }` because Isolated Projects forbids the root configuring its siblings. Living in `build-logic`
 * lets ktfmt be configured with its real `KtfmtExtension` type.
 */
class ComposeAiBaseConventionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply("com.ncorti.ktfmt.gradle")
    project.extensions.configure<KtfmtExtension>("ktfmt") { googleStyle() }

    // History recording is on by default in the daemon; tests pin it explicitly. Git-provenance
    // caching is disabled in tests (`gitProvenanceTtlMs=0`) so per-render provenance stays
    // deterministic.
    project.tasks.withType<Test>().configureEach {
      systemProperty("composeai.history.enabled", "true")
      systemProperty("composeai.history.gitProvenanceTtlMs", "0")
    }

    // Build-cache salt: an extra declared input, so bumping `composeai.cacheSalt` in
    // gradle.properties moves every Kotlin compilation to fresh cache keys.
    //
    // The escape hatch for a remote-cache entry that goes bad at rest (e.g. stored truncated,
    // failing every load before the task can run and push a replacement). BuildFetch can't evict a
    // single entry; bumping orphans the poisoned key at the cost of one cold main build. Applied
    // here because every module applies base-conventions, including ones without the Kotlin
    // conventions plugin.
    val cacheSalt = project.providers.gradleProperty("composeai.cacheSalt").orElse("0")
    project.tasks.withType<KotlinCompilationTask<*>>().configureEach {
      inputs.property("composeai.cacheSalt", cacheSalt)
    }

    registerLayerBoundaryCheck(project)
    registerHttpServerFloorCheck(project)
    applyDaemonBom(project)
    applyContractsBom(project)
  }

  /**
   * A Kotlin source-set dependency bucket, such as `commonMainApi` or `desktopMainImplementation`.
   * Matched by suffix since source-set names are open-ended; over-matching is harmless.
   */
  private fun isKotlinSourceSetBucket(name: String): Boolean =
    name.endsWith("Api") || name.endsWith("Implementation")

  private fun applyDaemonBom(project: Project) =
    applyPlatformBom(project, "composeai-daemon-bom", "compose-preview-daemon")

  private fun applyContractsBom(project: Project) =
    applyPlatformBom(project, "composeai-contracts-bom", "compose-preview-contracts")

  /**
   * Puts a published BOM on every module, so the coordinates it constrains need not — and must not
   * — name versions of their own. Both upstream lines publish only the modules a release changes,
   * so their coordinates don't share one version; the BOM is the record of which versions go
   * together.
   *
   * Applied to `api` and `implementation` (which `testImplementation` and Android variants extend)
   * and to KMP source-set buckets ([isKotlinSourceSetBucket]); without that, KMP modules fail with
   * an empty version (`slot-preview-runtime:`). The `daemonBench` configurations add the platform
   * themselves.
   *
   * `configurations.all`, not a one-shot lookup: this plugin is applied before the
   * Java/Kotlin/Android plugins create those configurations.
   */
  private fun applyPlatformBom(project: Project, alias: String, line: String) {
    val bom =
      project.extensions
        .getByType<VersionCatalogsExtension>()
        .named("libs")
        .findLibrary(alias)
        .orElseThrow {
          IllegalStateException(
            "libs.${alias.replace('-', '.')} is missing from the version catalog"
          )
        }

    // A `java-platform` rejects dependencies and has no classpath to constrain; `:bom` imports both
    // BOMs by hand. Relies on `:bom` applying `composeai.maven-publishing-platform` before
    // base-conventions: the check can't move into `configurations.all` because `hasPlugin` reads
    // false while `JavaPlatformPlugin.apply` is still running.
    if (project.pluginManager.hasPlugin("java-platform")) return

    project.configurations.all {
      if (name == "api" || name == "implementation" || isKotlinSourceSetBucket(name)) {
        project.dependencies.add(name, project.dependencies.platform(bom))
      }
    }
  }

  /**
   * Every component on this project's resolved runtime classpath as `<group>:<name>`. Resolved
   * rather than declared, to catch artifacts arriving transitively; shared by both boundary checks.
   */
  private fun resolvedRuntimeModules(project: Project) =
    project.configurations.named("runtimeClasspath").flatMap { configuration ->
      configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
        artifacts
          .mapNotNull { artifact ->
            (artifact.id.componentIdentifier as? ModuleComponentIdentifier)?.let {
              "${it.group}:${it.module}"
            }
          }
          .toSet()
      }
    }

  /**
   * Wires [CheckLayerBoundary] onto every project with a `runtimeClasspath`, and onto `check`.
   * Guarded by `plugins.withId("org.gradle.java")`, so projects without one (Android, artwork,
   * fixtures) get no task rather than a failure.
   */
  private fun registerLayerBoundaryCheck(project: Project) {
    project.plugins.withId("org.gradle.java") {
      val task =
        project.tasks.register<CheckLayerBoundary>("checkLayerBoundary") {
          description =
            "Fails if a compose-preview-server artifact reaches this project's runtime classpath."
          group = "verification"

          resolvedModules.set(resolvedRuntimeModules(project))

          allowedPreviewModules.set(
            CheckLayerBoundary.ownPreviewModules + CheckLayerBoundary.knownLayerTwoEdges
          )
        }

      project.tasks.named("check") { dependsOn(task) }
    }
  }

  /**
   * Wires [CheckHttpServerFloor] onto every project with a `runtimeClasspath`, skipping projects
   * allowed a server engine. That list is empty today; an exempt project gets no task at all, so an
   * exemption is a visible diff in [CheckHttpServerFloor.httpServerProjects].
   */
  private fun registerHttpServerFloorCheck(project: Project) {
    if (project.path in CheckHttpServerFloor.httpServerProjects) return

    project.plugins.withId("org.gradle.java") {
      val task =
        project.tasks.register<CheckHttpServerFloor>("checkHttpServerFloor") {
          description =
            "Fails if an HTTP server engine reaches this project's runtime classpath. " +
              "Preview-serving behaviour is compose-preview-server's."
          group = "verification"

          resolvedModules.set(resolvedRuntimeModules(project))
          serverPrefixes.set(CheckHttpServerFloor.serverPrefixes)
        }

      project.tasks.named("check") { dependsOn(task) }
    }
  }
}
