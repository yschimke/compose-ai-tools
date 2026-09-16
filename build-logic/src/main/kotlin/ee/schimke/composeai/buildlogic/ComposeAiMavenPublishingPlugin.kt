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
 * `CORE_LINE_VERSION` when the release sets it — the release tag when this release publishes, and
 * the last version the modules actually reached Central at when the guard skipped the publish —
 * else `PLUGIN_VERSION`. Kept apart from the tag so a skipped release never stamps a never-uploaded
 * version onto a POM: v2.2.1 shipped `data-remotecompose-connector:2.2.1` requiring
 * `daemon-core:2.2.1` with the core line held at 2.2.0, which resolved for nobody
 * (yschimke/wear-m3-catalog#350).
 *
 * That failure came from the two-train split, where `data/…` versioned on a line of its own and
 * each module took its train's version. The data modules publish from compose-preview-daemon since
 * #5336 and the split went with them; one line is left, and the variable keeps its name.
 *
 * **The line version is ignored unless `PLUGIN_VERSION` is set.** Outside a release both are
 * absent and everything falls to the snapshot version; a stray `CORE_LINE_VERSION` in a developer
 * shell must not silently version the build.
 */
private fun Project.publishedVersion(): String {
  val pluginVersion =
    providers.environmentVariable("PLUGIN_VERSION").orNull?.takeIf { it.isNotBlank() }
      ?: return nextPatchSnapshotVersion()
  return providers.environmentVariable("CORE_LINE_VERSION").orNull?.takeIf { it.isNotBlank() }
    ?: pluginVersion
}

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
