package ee.schimke.composeai.buildlogic

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.artifacts.dsl.LockMode
import java.io.File
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.configure

abstract class ComposeAiMavenPublishingExtension
@Inject
constructor(objects: ObjectFactory) {
  val artifactId: Property<String> = objects.property(String::class.java)
  val displayName: Property<String> = objects.property(String::class.java)
  val description: Property<String> = objects.property(String::class.java)
  val inceptionYear: Property<String> = objects.property(String::class.java).convention("2026")

  fun coordinates(artifactId: String, displayName: String, description: String) {
    this.artifactId.set(artifactId)
    this.displayName.set(displayName)
    this.description.set(description)
  }
}

class ComposeAiMavenPublishingPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply("composeai.android-conventions")
    project.pluginManager.apply("composeai.jvm-conventions")
    project.pluginManager.apply("composeai.kotlin-conventions")
    project.pluginManager.apply("maven-publish")
    project.pluginManager.apply("com.vanniktech.maven.publish")

    val extension =
      project.extensions.create(
        "composeAiMavenPublishing",
        ComposeAiMavenPublishingExtension::class.java,
      )

    project.group = "ee.schimke.composeai"
    project.version =
      project.providers.environmentVariable("PLUGIN_VERSION").orNull
        ?: project.nextPatchSnapshotVersion()

    project.configureAndroidLibraryPublication()
    project.configureDependencyLocking()

    project.afterEvaluate {
      val artifactId =
        extension.artifactId.orNull ?: error("composeAiMavenPublishing.artifactId is required")
      val displayName =
        extension.displayName.orNull ?: error("composeAiMavenPublishing.displayName is required")
      val artifactDescription =
        extension.description.orNull ?: error("composeAiMavenPublishing.description is required")

      project.extensions.configure<MavenPublishBaseExtension> {
        publishToMavenCentral(automaticRelease = true)
        if (!project.version.toString().endsWith("SNAPSHOT")) {
          signAllPublications()
        }
        coordinates("ee.schimke.composeai", artifactId, project.version.toString())
        pom {
          name.set(displayName)
          description.set(artifactDescription)
          url.set("https://github.com/yschimke/compose-ai-tools")
          inceptionYear.set(extension.inceptionYear)
          licenses {
            license {
              name.set("The Apache License, Version 2.0")
              url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
              distribution.set("repo")
            }
          }
          developers {
            developer {
              id.set("yschimke")
              name.set("Yuri Schimke")
              url.set("https://github.com/yschimke")
            }
          }
          scm {
            url.set("https://github.com/yschimke/compose-ai-tools")
            connection.set("scm:git:https://github.com/yschimke/compose-ai-tools.git")
            developerConnection.set(
              "scm:git:ssh://git@github.com/yschimke/compose-ai-tools.git"
            )
          }
        }
      }
    }
  }
}

/**
 * Publish an Android library as its single `release` variant, with real sources and an empty
 * javadoc jar — Maven Central requires *a* javadoc artifact but not a useful one for a Kotlin
 * library whose docs live in the repo.
 *
 * This used to be copy-pasted into all 25 Android modules that publish, each carrying the same
 * three imports, the same `@file:Suppress("DEPRECATION")` header, and the same nine-line
 * `mavenPublishing { configure(...) }` block. Twenty-five copies of one decision is twenty-five
 * places to miss when the plugin's API moves — which the suppression comment itself predicted
 * ("the replacement types vary between plugin versions"). Now it moves here, once.
 *
 * `withPlugin` rather than an `afterEvaluate` check so the JVM modules that share this convention
 * plugin (65 of the 90) are untouched — vanniktech's own default handles them correctly.
 */
@Suppress("DEPRECATION") // AndroidSingleVariantLibrary(Boolean, Boolean); replacement types
// (SourcesJar / JavadocJar) vary between plugin versions. Re-visit when bumping.
private fun Project.configureAndroidLibraryPublication() {
  pluginManager.withPlugin("com.android.library") {
    extensions.configure<MavenPublishBaseExtension> {
      configure(
        AndroidSingleVariantLibrary(
          javadocJar = JavadocJar.Empty(),
          sourcesJar = SourcesJar.Sources(),
          variant = "release",
        )
      )
    }
  }
}

private fun Project.nextPatchSnapshotVersion(): String {
  val manifest =
    generateSequence(rootDir) { it.parentFile }
      .map { it.resolve(".release-please-manifest.json") }
      .firstOrNull(File::isFile)
      ?: error("Could not find .release-please-manifest.json from $rootDir")
  val current = Regex(""""\.":\s*"([^"]+)"""").find(manifest.readText())!!.groupValues[1]
  val (major, minor, patch) = current.split(".").map { it.toInt() }
  return "$major.$minor.${patch + 1}-SNAPSHOT"
}

/**
 * Record each published module's resolved dependency graph in a committed `gradle.lockfile`.
 *
 * ## Why this module and not the whole build
 *
 * The release guard ([.github/scripts/maven-publish-needed.sh]) has to answer "could this
 * artifact's bytes differ from the last published release?". Its crudest rule is that ANY change
 * to `gradle/libs.versions.toml` dirties all 94 published modules, because a path diff cannot tell
 * which of them actually resolve the bumped coordinate. Measured over v1.57.0..v1.84.0, that one
 * rule accounts for ~97% of the publishing the guard cannot eliminate: 16 of 38 release windows
 * touched a shared build input, 9 of them the version catalog alone.
 *
 * A lock state answers the question exactly instead of approximating it. Gradle records what each
 * module actually resolved, the file is committed, and the guard diffs it — no parsing of catalog
 * aliases, no upward closure, no guessing. It also catches a case alias-matching would miss: the
 * POM carries *declared* versions (there is no `versionMapping` here), so a bump to a purely
 * transitive dependency does not change a consumer's POM — but Kotlin inlines `inline` functions
 * from the compile classpath into the caller, so the consumer's jar bytes can still move.
 *
 * ## LockMode.DEFAULT, deliberately
 *
 * DEFAULT does not fail a locked configuration that has no lock state; STRICT does. That is what
 * makes this safe to land before a single lockfile exists — every module resolves exactly as it
 * did, and the files arrive when `dependency-locks.yml` first writes them. STRICT is also a known
 * source of false failures on configurations that are not really resolvable (gradle#12010), which
 * is the second reason not to reach for it.
 *
 * ## Only the configurations that decide a published artifact
 *
 * NOT `lockAllConfigurations()`. Locking the test classpaths would mean a test-only dependency
 * bump rewrote a lockfile and so dirtied a module whose published bytes cannot have changed —
 * re-introducing, one layer down, exactly the over-reporting this exists to remove. The names
 * below are the resolvable compile/runtime classpaths of the variants we actually publish: the
 * plain JVM pair, the Android `release` pair (we publish `AndroidSingleVariantLibrary("release")`),
 * and the KMP `jvm` pair.
 *
 * Known gap, stated rather than discovered later: a KMP module publishing targets beyond `jvm`
 * has classpaths not named here, so its lock state is incomplete and the guard learns nothing
 * about those targets' dependencies. The guard fails open on a module with no lock state, so this
 * is safe — it just does not save anything for those modules yet.
 */
private fun Project.configureDependencyLocking() {
  dependencyLocking { lockMode.set(LockMode.DEFAULT) }

  configurations.configureEach {
    if (name in LOCKED_CONFIGURATIONS) {
      resolutionStrategy.activateDependencyLocking()
    }
  }

  // `./gradlew resolveAndLockAll --write-locks` regenerates every lockfile in one invocation.
  // Resolving inside the task (rather than relying on some other task to touch the configuration)
  // is what makes a module with no consumers still get a lockfile written.
  tasks.register("resolveAndLockAll") {
    group = "help"
    description = "Resolves the published configurations so --write-locks can record them."
    notCompatibleWithConfigurationCache("Resolves configurations at execution time")
    doFirst {
      require(project.gradle.startParameter.isWriteDependencyLocks) {
        "resolveAndLockAll must be run with --write-locks"
      }
    }
    doLast {
      configurations
        .filter { it.isCanBeResolved && it.name in LOCKED_CONFIGURATIONS }
        // GRAPH resolution, not `resolve()`. `resolve()` asks for the configuration's *files*,
        // which forces artifact-variant selection — and on an Android `releaseCompileClasspath`
        // that fails outright, because AGP normally supplies `artifactType` through its own
        // ArtifactViews and a raw request cannot choose between `android-classes-jar`,
        // `android-lint`, `android-manifest`, `jar`, `r-class-jar` and the rest
        // ("cannot choose between the following variants of project ':data-a11y-core'").
        //
        // Lock state records module versions, so the graph is all it needs and no file has to be
        // selected or downloaded. This is the same path Gradle's own `dependencies` report takes —
        // which is what the generated lockfile header tells you to run to regenerate it.
        .forEach { it.incoming.resolutionResult.root }
    }
  }
}

/**
 * The resolvable classpaths that determine a published artifact's POM and bytecode. Explicit names
 * rather than a pattern: `^(release)?(compile|runtime)Classpath$` would read as if it excluded the
 * test classpaths by luck of anchoring, and the cost of it one day not doing so is a lockfile that
 * churns on test-only bumps.
 */
private val LOCKED_CONFIGURATIONS =
  setOf(
    "compileClasspath",
    "runtimeClasspath",
    "releaseCompileClasspath",
    "releaseRuntimeClasspath",
    "jvmCompileClasspath",
    "jvmRuntimeClasspath",
  )
