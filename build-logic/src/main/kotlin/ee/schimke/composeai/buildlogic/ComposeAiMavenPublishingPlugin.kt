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
 * Shared by [ComposeAiMavenPublishingPlugin] and [ComposeAiPlatformPublishingPlugin] so the BOM and
 * the modules it indexes never disagree.
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
 * Publish an Android library as its single `release` variant, with sources and an empty javadoc jar
 * (Maven Central requires one). `withPlugin` so JVM modules sharing this convention keep
 * vanniktech's default.
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
 * Outside a release (`PLUGIN_VERSION` unset), the next-patch snapshot. During a release, the tag's
 * version if the module is in `-Pcomposeai.publishSet` (from
 * `.github/scripts/maven-publish-plan.sh`; absent means publish everything), otherwise the version
 * it last published at, from `publishing-manifest.json`.
 *
 * A skipped module must keep its recorded version: POMs name project dependencies at their
 * `project.version`, so stamping the tag on an unpublished module would make consumers require an
 * artifact that was never uploaded.
 */
private fun Project.publishedVersion(): String {
  val pluginVersion =
    providers.environmentVariable("PLUGIN_VERSION").orNull?.takeIf { it.isNotBlank() }
      ?: return nextPatchSnapshotVersion()

  // The `gradle-plugin` included build always publishes at the tag (the plan always includes it).
  // It must bypass the lookup anyway: [publishedArtifactId] can't name included-build projects (its
  // root flattens to the empty string), so the `error(...)` below would fail configuration.
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
 * The artifact id this project publishes as: its path with separators flattened. Pinned against the
 * build files by `PublishedArtifactIdTest`, since `:bom` and the publish set address modules this
 * way.
 */
internal fun Project.publishedArtifactId(): String = path.removePrefix(":").replace(':', '-')

/**
 * `publishing-manifest.json`, or an empty document when absent. Not committed: the release plan
 * writes each coordinate's published version here before Gradle runs; only read when
 * `PLUGIN_VERSION` is set.
 */
internal fun Project.publishingManifestText(): String =
  generateSequence(rootDir) { it.parentFile }
    .map { it.resolve("publishing-manifest.json") }
    .firstOrNull(File::isFile)
    ?.readText() ?: "{}"

/**
 * The version a platform publishes at: always the tag. `:bom` indexes a release, so it must exist
 * at the tag whatever else published. Kept separate from [publishedVersion] because its derived
 * artifact id is in neither the publish set nor the manifest.
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
