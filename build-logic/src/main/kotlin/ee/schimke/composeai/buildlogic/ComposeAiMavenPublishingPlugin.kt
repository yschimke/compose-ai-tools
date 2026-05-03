package ee.schimke.composeai.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import java.io.File
import javax.inject.Inject
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.model.ObjectFactory
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask
import tapmoc.TapmocExtension
import tapmoc.configureKotlinCompatibility

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
    project.pluginManager.apply("composeai.tapmoc-conventions")
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

    project.extensions.configure<PublishingExtension> {
      repositories.maven {
        name = "GitHubPackages"
        url =
          project.uri(
            project.providers
              .environmentVariable("GITHUB_REPOSITORY")
              .map { "https://maven.pkg.github.com/$it" }
              .orElse("https://maven.pkg.github.com/yschimke/compose-ai-tools")
          )
        credentials {
          username = project.providers.environmentVariable("GITHUB_ACTOR").orNull
          password = project.providers.environmentVariable("GITHUB_TOKEN").orNull
        }
      }
    }

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

class ComposeAiAndroidConventionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.configureCommonAndroid()
  }
}

class ComposeAiJvmConventionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.configureCommonJvm()
  }
}

class ComposeAiTapmocConventionsPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.configureTapmocCompatibility()
  }
}

private fun Project.configureCommonAndroid() {
  pluginManager.withPlugin("com.android.library") {
    extensions.configure<LibraryExtension> { configureLibraryDefaults() }
  }
  pluginManager.withPlugin("com.android.application") {
    extensions.configure<ApplicationExtension> { configureApplicationDefaults() }
  }
}

private fun LibraryExtension.configureLibraryDefaults() {
  compileSdk = 36
  defaultConfig { minSdk = 24 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

private fun ApplicationExtension.configureApplicationDefaults() {
  compileSdk = 36
  defaultConfig { minSdk = 24 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

private fun Project.configureCommonJvm() {
  pluginManager.withPlugin("java") {
    extensions.configure<JavaPluginExtension> {
      toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
    }
    tasks.withType<Test>().configureEach { useJUnit() }
  }
}

private fun Project.configureTapmocCompatibility() {
  pluginManager.withPlugin("com.gradleup.tapmoc") {
    val kotlinCoreLibraries =
      extensions
        .getByType<VersionCatalogsExtension>()
        .named("libs")
        .findVersion("kotlinCoreLibraries")
        .get()
        .requiredVersion

    configureKotlinCompatibility(version = kotlinCoreLibraries)
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
      compilerOptions.freeCompilerArgs.add("-Xsuppress-version-warnings")
    }
    extensions.configure<TapmocExtension> { checkDependencies() }
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
