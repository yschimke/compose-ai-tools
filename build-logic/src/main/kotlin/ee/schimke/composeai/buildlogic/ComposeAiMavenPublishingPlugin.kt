package ee.schimke.composeai.buildlogic

import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
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
    project.version = project.publishedVersion()

    project.configureAndroidLibraryPublication()

    project.afterEvaluate {
      project.configureComposeAiPublication(
        artifactId =
          extension.artifactId.orNull ?: error("composeAiMavenPublishing.artifactId is required"),
        displayName =
          extension.displayName.orNull
            ?: error("composeAiMavenPublishing.displayName is required"),
        artifactDescription =
          extension.description.orNull
            ?: error("composeAiMavenPublishing.description is required"),
        inceptionYear = extension.inceptionYear,
      )
    }
  }
}

/**
 * The coordinates, signing and POM metadata every artifact this repository publishes carries.
 *
 * Shared by [ComposeAiMavenPublishingPlugin] and [ComposeAiPlatformPublishingPlugin] rather than
 * duplicated: the BOM describes the same release as the modules it constrains, so if the two ever
 * disagreed about the group, the licence or the SCM block, the index and the things it indexes
 * would be published under different metadata.
 */
internal fun Project.configureComposeAiPublication(
  artifactId: String,
  displayName: String,
  artifactDescription: String,
  inceptionYear: Property<String>,
) {
  extensions.configure<MavenPublishBaseExtension> {
    publishToMavenCentral(automaticRelease = true)
    if (!version.toString().endsWith("SNAPSHOT")) {
      signAllPublications()
    }
    coordinates("ee.schimke.composeai", artifactId, version.toString())
    pom {
      name.set(displayName)
      description.set(artifactDescription)
      url.set("https://github.com/yschimke/compose-ai-tools")
      this.inceptionYear.set(inceptionYear)
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
        developerConnection.set("scm:git:ssh://git@github.com/yschimke/compose-ai-tools.git")
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
 * The version this module publishes at.
 *
 * Outside a release (`PLUGIN_VERSION` unset) everything is the next-patch snapshot.
 *
 * During a release the module takes the tag's version **if it is in the publish set**, and
 * otherwise the version it last published at, read from `publishing-manifest.json`. The set is
 * computed by `.github/scripts/maven-publish-plan.sh` and handed over as
 * `-Pcomposeai.publishSet=<comma separated artifact ids>`; a release that omits the property
 * publishes everything at the tag, which is the old behaviour and the safe default for a
 * `workflow_dispatch` recovery run.
 *
 * Giving a skipped module its *recorded* version rather than the tag is the whole mechanism. A
 * published POM names its project dependencies at their `project.version`, so a module built at
 * the tag names its skipped dependencies at the versions those are actually on Central, and a
 * consumer resolving it gets artifacts that exist. Stamping the tag onto a module that did not
 * publish is precisely the break this repository shipped in v2.2.1 —
 * `data-remotecompose-connector:2.2.1` requiring a `daemon-core:2.2.1` that was never uploaded
 * (yschimke/wear-m3-catalog#350).
 *
 * This replaces `CORE_LINE_VERSION`, which was the two-train split's answer to the same problem at
 * a much coarser grain: one held-back version for a whole train. The trains went to
 * compose-preview-daemon with the data modules (#5336); the per-module manifest is what is left.
 */
private fun Project.publishedVersion(): String {
  val pluginVersion =
    providers.environmentVariable("PLUGIN_VERSION").orNull?.takeIf { it.isNotBlank() }
      ?: return nextPatchSnapshotVersion()

  // The `gradle-plugin` included build always publishes, so its four coordinates always carry the
  // tag and never consult the publish set. `maven-publish-plan.sh` does `dirty.update(
  // INCLUDED_BUILD_IDS)` unconditionally — the CLI bakes the plugin coordinate for the version it
  // ships at, so a skipped plugin is a user-facing break on the first command anyone runs.
  //
  // Bypassing the lookup is not a shortcut around a rule; it is the only correct answer, because
  // [publishedArtifactId] CANNOT name these projects. An included build's paths are its own: its
  // root project is `:`, which flattens to the EMPTY STRING, and `:gradle-plugin-config` flattens
  // to `gradle-plugin-config` while it publishes as `compose-preview-config`. Neither is in the
  // publish set or the manifest, so the `error(...)` below fired while the plugin was being
  // applied — killing v2.18.0's release job during configuration, before anything was uploaded.
  if (gradle.parent != null) return pluginVersion

  return PublishedVersions.resolve(
    artifactId = publishedArtifactId(),
    tagVersion = pluginVersion,
    publishSet =
      PublishedVersions.parsePublishSet(providers.gradleProperty("composeai.publishSet").orNull),
    manifestText = publishingManifestText(),
  )
}

/**
 * The artifact id this project publishes as: its path with the separators flattened.
 *
 * Pinned against the build files by `PublishedArtifactIdTest`, because `:bom` and the publish set
 * both address modules this way while the modules themselves declare an id in their build script.
 */
internal fun Project.publishedArtifactId(): String = path.removePrefix(":").replace(':', '-')

/**
 * `publishing-manifest.json`, or an empty document when there is none.
 *
 * NOT a committed file. The release job's publish plan resolves each coordinate's published version
 * from Maven Central and writes it here (`--write-manifest`) before Gradle runs, so a module the
 * release skips can name the version it is already published at. Outside a release the file is
 * absent and nothing reads it: `publishedVersion` only consults it when `PLUGIN_VERSION` is set.
 */
internal fun Project.publishingManifestText(): String =
  generateSequence(rootDir) { it.parentFile }
    .map { it.resolve("publishing-manifest.json") }
    .firstOrNull(File::isFile)
    ?.readText() ?: "{}"

/**
 * The version a *platform* publishes at: always the tag, never a line version.
 *
 * `:bom` is the index of a release, not a member of it. A consumer resolving the BOM at the tag has
 * to find it there whether or not any given module published, so it never takes a recorded or
 * held-back version.
 *
 * Kept apart from [publishedVersion] rather than special-cased inside it — routing the BOM through
 * the module path is what broke the equivalent change in compose-preview-daemon, where its derived
 * artifact id was absent from both the publish set and the manifest and every reduced-publish
 * release died during Gradle configuration.
 */
internal fun Project.platformPublishedVersion(): String =
  providers.environmentVariable("PLUGIN_VERSION").orNull?.takeIf { it.isNotBlank() }
    ?: nextPatchSnapshotVersion()

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
