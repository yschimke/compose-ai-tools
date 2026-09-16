package ee.schimke.composeai.buildlogic

import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

abstract class ComposeAiPlatformPublishingExtension
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

/**
 * Publishes a Gradle `java-platform` — a BOM — to the same namespace, and on the same version, as
 * the modules it constrains.
 *
 * Deliberately NOT [ComposeAiMavenPublishingPlugin]. That plugin applies the Android, JVM and
 * Kotlin convention plugins, because every other published artifact here is compiled code; a
 * platform has no sources, no compile classpath and no variants to configure, and applying the
 * Kotlin plugin to one produces an empty jar alongside the POM that is the only artifact anyone
 * wants. What the two do share — coordinates, signing and the POM metadata block — is
 * [configureComposeAiPublication], so the BOM cannot drift from the modules it describes.
 *
 * `version` comes from [platformPublishedVersion], NOT the module path: the BOM is the index of a
 * release rather than a member of it, so it always carries the tag and is never skipped.
 */
class ComposeAiPlatformPublishingPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.pluginManager.apply("java-platform")
    project.pluginManager.apply("maven-publish")
    project.pluginManager.apply("com.vanniktech.maven.publish")

    val extension =
      project.extensions.create(
        "composeAiPlatformPublishing",
        ComposeAiPlatformPublishingExtension::class.java,
      )

    project.group = "ee.schimke.composeai"
    project.version = project.platformPublishedVersion()

    project.afterEvaluate {
      project.configureComposeAiPublication(
        artifactId =
          extension.artifactId.orNull
            ?: error("composeAiPlatformPublishing.artifactId is required"),
        displayName =
          extension.displayName.orNull
            ?: error("composeAiPlatformPublishing.displayName is required"),
        artifactDescription =
          extension.description.orNull
            ?: error("composeAiPlatformPublishing.description is required"),
        inceptionYear = extension.inceptionYear,
      )
    }
  }
}
