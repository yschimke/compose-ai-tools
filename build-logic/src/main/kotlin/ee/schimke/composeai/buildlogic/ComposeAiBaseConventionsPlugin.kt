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
 * Conventions that previously lived in the root build's `allprojects {}` block. Isolated Projects
 * forbids a project configuring its siblings, so they're pushed down to each project: this plugin
 * is applied to every project from `settings.gradle.kts` via the IP-safe
 * `gradle.lifecycle.beforeProject` hook.
 *
 * Living in `build-logic` lets the ktfmt extension be configured with its real type
 * (`extensions.configure<KtfmtExtension>`) rather than reflectively — the settings script can't
 * import the type, but this convention plugin's classpath already carries both ktfmt and the Kotlin
 * Gradle plugin it links against.
 */
class ComposeAiBaseConventionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply("com.ncorti.ktfmt.gradle")
    project.extensions.configure<KtfmtExtension>("ktfmt") { googleStyle() }

    // History recording is on by default in the daemon (`HistoryFeature.ENABLED`); tests set it
    // explicitly so they stay pinned if that default ever changes. Git-provenance caching is
    // disabled in tests (`gitProvenanceTtlMs=0`) so per-render provenance stays fresh and
    // deterministic — production uses the default TTL to collapse render-burst git fetches.
    project.tasks.withType<Test>().configureEach {
      systemProperty("composeai.history.enabled", "true")
      systemProperty("composeai.history.gitProvenanceTtlMs", "0")
    }

    // Build-cache salt. A Gradle cache key is the hash of a task's declared inputs, so an extra
    // declared input property lets us move every Kotlin compilation to a fresh set of keys by
    // bumping one number in gradle.properties.
    //
    // This exists because a remote-cache entry can go bad at rest: in July 2026 the BuildFetch
    // entry for `:daemon:core:compileKotlin` was stored truncated, and every consumer that
    // resolved that key died in the *load* ("Failed to load cache entry cc7964dd…: Could not load
    // from remote cache: Unexpected end of ZLIB input stream") — before the task could execute,
    // so nothing ever pushed a replacement. PR runs are read-only (see settings.gradle.kts) and
    // main runs aborted at the same point, so it could not self-heal; it blocked every build that
    // touched `:daemon:core` until the key changed. BuildFetch documents no way to evict a single
    // entry (LRU under storage pressure is the only documented eviction), so a salt we control is
    // the escape hatch.
    //
    // Bumping it orphans the poisoned key rather than deleting it: the next pushing main run
    // executes the affected tasks and stores clean entries under the new keys, and everything else
    // reads those. Cost is one cold main build; the stale entries age out via LRU. Prefer asking
    // BuildFetch to evict the specific entry when that's an option — this is the lever for
    // when it isn't.
    //
    // Applied here rather than in `composeai.kotlin-conventions` deliberately: base-conventions
    // is the plugin *every* module applies, and `:daemon:core` — the module that was actually
    // poisoned — does not apply the Kotlin conventions plugin.
    val cacheSalt = project.providers.gradleProperty("composeai.cacheSalt").orElse("0")
    project.tasks.withType<KotlinCompilationTask<*>>().configureEach {
      inputs.property("composeai.cacheSalt", cacheSalt)
    }

    registerLayerBoundaryCheck(project)
    registerHttpServerFloorCheck(project)
    applyDaemonBom(project)
  }

  /**
   * Puts the compose-preview-daemon BOM on every module, so the daemon coordinates in the catalog
   * need not -- and must not -- name versions of their own.
   *
   * That repository publishes only the modules a release changes
   * (yschimke/compose-preview-daemon#123), so its coordinates no longer all sit at one version.
   * This catalog used to pin all nineteen of them to a single shared version ref, which resolves
   * right up until the first reduced release and then fails on whichever module did not publish at
   * the new version -- a build break arriving from a repository we did not change, on a version
   * bump that looks routine. The BOM is the published record of which versions belong together, so
   * it is the only coordinate that names one.
   *
   * `api` and `implementation` are the two buckets that declare these dependencies here, 67 of the
   * 69 declarations. `testImplementation` extends `implementation`, as do the Android variant
   * configurations, so both are carried. The two `daemonBench` configurations in the sample
   * benchmarks are resolvable configurations of their own and add the platform themselves.
   *
   * Kotlin Multiplatform modules do not use `api` and `implementation` at all: each source set
   * gets its own bucket, named `commonMainApi`, `desktopMainImplementation`, `jvmMainApi` and so
   * on. Matching only the two plain names left every KMP module without the platform, and CI found
   * it where a local check on a plain JVM module could not:
   * `:samples:design-catalog-m3-shared:desktopMainCompileClasspath` failed with
   * `Could not find ee.schimke.composeai:slot-preview-runtime:` -- note the empty version, which is
   * the signature of a catalog entry with no version and no platform to supply one.
   *
   * `configurations.all`, not a one-shot lookup: this plugin is applied from the `plugins {}` block
   * before the Java, Kotlin or Android plugin has created `api` and `implementation`, so checking
   * for them here finds nothing and silently adds nothing. The first attempt at this did exactly
   * that -- `:bundle-format:dependencies` then reported `preview-data-api FAILED` and
   * `daemon-client FAILED`, because the catalog entries carry no version and no platform had
   * arrived to supply one. `all` fires for configurations created later too, which is what makes
   * it work regardless of plugin ordering, and covers projects that have neither configuration by
   * simply never matching.
   */
  /**
   * A Kotlin source-set dependency bucket, such as `commonMainApi` or `desktopMainImplementation`.
   *
   * Matched by suffix because the source-set names are open-ended -- every target and every custom
   * source set adds a pair -- so enumerating them would go stale the moment a target is added. The
   * cost of matching too widely is only that a platform lands on a bucket with nothing from this
   * BOM in it, which constrains nothing and resolves to nothing.
   */
  private fun isKotlinSourceSetBucket(name: String): Boolean =
    name.endsWith("Api") || name.endsWith("Implementation")

  private fun applyDaemonBom(project: Project) {
    val bom =
      project.extensions
        .getByType<VersionCatalogsExtension>()
        .named("libs")
        .findLibrary("composeai-daemon-bom")
        .orElseThrow {
          IllegalStateException("libs.composeai.daemon.bom is missing from the version catalog")
        }

    // A `java-platform` rejects dependencies outright ("Adding dependencies to platforms is not
    // allowed by default"), and would have nothing to do with one anyway: a platform has no compile
    // classpath, so there is no resolution for the daemon BOM to constrain. Skipped rather than
    // worked around with `allowDependencies()`, which would let a real dependency slip into the BOM
    // unnoticed.
    //
    // This relies on `:bom` applying `composeai.maven-publishing-platform` BEFORE
    // `composeai.base-conventions`, which its `plugins {}` block does and says why. The check
    // cannot be moved inside `configurations.all` to avoid that ordering requirement: the callback
    // fires for `api` while `JavaPlatformPlugin.apply` is still running, and a plugin is not marked
    // applied until its `apply` returns — so `hasPlugin` reads false in exactly the window that
    // matters.
    if (project.pluginManager.hasPlugin("java-platform")) return

    project.configurations.all {
      if (name == "api" || name == "implementation" || isKotlinSourceSetBucket(name)) {
        project.dependencies.add(name, project.dependencies.platform(bom))
      }
    }
  }

  /**
   * Every component on this project's resolved runtime classpath as `<group>:<name>`.
   *
   * Resolved identity rather than declared dependencies: an artifact arriving through another POM
   * is the case that matters and the case a build-file scan misses. Shared by both boundary checks
   * so they cannot disagree about what is on the classpath.
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
   * Wires [CheckLayerBoundary] onto every project that has a `runtimeClasspath`, and onto `check`
   * so it runs where CI already looks rather than needing its own job.
   *
   * Registered from base-conventions for the same reason the cache salt is: this is the plugin
   * every module applies. `plugins.withId("org.gradle.java")` is the guard because the java plugin
   * is what creates `runtimeClasspath` — a project without one (the Android modules, the artwork
   * and fixture projects) silently gets no task rather than a configuration failure.
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
   * Wires [CheckHttpServerFloor] onto every project that has a `runtimeClasspath`, skipping any
   * project allowed to carry a server engine.
   *
   * That list is empty since the MCP server moved (#5176), so today this skips nothing and every
   * JVM module is checked. The branch stays because it is what makes an exemption *visible*: a
   * project on the list gets no task at all rather than a task that quietly passes, so adding one
   * is a diff in [CheckHttpServerFloor.httpServerProjects] that a reviewer reads against
   * `docs/design/REPOSITORY_LAYERS.md`.
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
