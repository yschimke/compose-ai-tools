package ee.schimke.composeai.buildlogic

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import java.io.File
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
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
    project.rejectSnapshotDependenciesInPom()

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

/**
 * Fail the build if a module that publishes to Maven Central declares a `-SNAPSHOT` dependency in a
 * scope that reaches its POM.
 *
 * Central does not warn about this, it *rejects the whole deployment*: "Dependencies to SNAPSHOT
 * versions not allowed for dependency: <coordinates>". Because every publishable module deploys in
 * one bundle, a single offender fails `publish-gradle-plugin` for the entire release — and
 * `finalize-release` then leaves the GitHub Release as an un-drafted draft, so the CLI tarball
 * consumers download 404s and nothing of that version ships at all. 1.34.0 and 1.35.0 were stranded
 * exactly this way after `:third-party-rc-embedded-player` re-applied `composeai.maven-publishing`
 * while `api`-exposing `androidx.compose.remote:*:1.0.0-SNAPSHOT` (#4490).
 *
 * The check runs on every build, not only releases: the mistake is a *declaration*, so catching it
 * on the PR that makes it is the whole point. Only POM-visible scopes are inspected — `compileOnly`
 * and `testImplementation` never reach the POM, which is how `:runtimes:wear-preview` and
 * `:data-remotecompose-connector` legitimately compile against the same androidx.dev snapshots.
 *
 * Declarations rather than a resolved graph, deliberately: this needs no resolution (so it costs
 * nothing at configuration time) and it names the line a human has to edit.
 */
private fun Project.rejectSnapshotDependenciesInPom() {
  afterEvaluate {
    val offenders =
      configurations
        .filter { isPomVisibleConfiguration(it.name) }
        .flatMap { configuration ->
          // Constraints as well as dependencies: Gradle serializes an `api`/`implementation`
          // constraint into the POM's `dependencyManagement`, so a constraint pinning a SNAPSHOT
          // puts that coordinate in the uploaded POM just as a dependency does.
          val declared =
            configuration.dependencies
              .withType(ExternalModuleDependency::class.java)
              .map { Triple(it.group, it.name, it.version) } +
              configuration.dependencyConstraints.map {
                Triple(it.group, it.name, it.version)
              }
          declared.mapNotNull { (group, name, version) ->
            version
              ?.takeIf { it.endsWith("-SNAPSHOT") }
              ?.let { "  ${configuration.name}(\"$group:$name:$it\")" }
          }
        }
        .distinct()
        .sorted()
    if (offenders.isNotEmpty()) {
      error(
        buildString {
          appendLine(
            "$path publishes to Maven Central but declares SNAPSHOT dependencies that would " +
              "reach its POM:"
          )
          offenders.forEach(::appendLine)
          append(
            "Central rejects the whole deployment for these, which strands every artifact of the " +
              "release. Either pin them to a released version, move them to compileOnly, or stop " +
              "applying composeai.maven-publishing to this module."
          )
        }
      )
    }
  }
}

/**
 * The declaration buckets whose contents Gradle writes into a published POM.
 *
 * Matched by *suffix* rather than by an explicit list of names, because the same four kinds are
 * prefixed differently per plugin: plain `api` for a JVM library, `releaseApi` for the single
 * Android variant vanniktech publishes, and `commonMainApi` / `jvmMainImplementation` for the
 * Kotlin Multiplatform modules (`:rc-player-runtime`, `:rc-player-compose`, …) whose source-set
 * dependencies land in their per-target publications. An explicit list silently stopped covering
 * whichever convention was added last, which is exactly the hole this guard exists to close.
 *
 * `compileOnlyApi` is in — unlike `compileOnly`, it *is* published, in compile scope. Test and
 * test-fixture source sets are out: nothing they declare reaches a POM. So are the non-published
 * Android variants, since only `release` is published and a debug-only SNAPSHOT is legitimate.
 */
private val POM_SCOPE_KINDS = listOf("compileOnlyApi", "implementation", "runtimeOnly", "api")

private fun isPomVisibleConfiguration(name: String): Boolean {
  val kind =
    POM_SCOPE_KINDS.firstOrNull {
      name == it || name.endsWith(it.replaceFirstChar(Char::uppercaseChar))
    } ?: return false
  val prefix = name.dropLast(kind.length)
  return !prefix.contains("test", ignoreCase = true) &&
    !prefix.contains("fixtures", ignoreCase = true) &&
    !prefix.startsWith("debug")
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
