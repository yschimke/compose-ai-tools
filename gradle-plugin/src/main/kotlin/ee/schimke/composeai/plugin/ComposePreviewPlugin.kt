package ee.schimke.composeai.plugin

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.tasks.testing.Test

class ComposePreviewPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("composePreview", PreviewExtension::class.java)

        project.afterEvaluate {
            if (!extension.enabled.get()) return@afterEvaluate

            val isAndroid = project.plugins.hasPlugin("com.android.application")
                || project.plugins.hasPlugin("com.android.library")
            val hasComposeDesktop = project.plugins.hasPlugin("org.jetbrains.compose")

            if (isAndroid) {
                enableAndroidResources(project)
                registerAndroidTasks(project, extension)
            } else if (hasComposeDesktop) {
                registerDesktopTasks(project, extension)
            } else {
                project.logger.warn("compose-ai-tools: No Android or Compose Desktop target found")
            }
        }
    }

    private fun enableAndroidResources(project: Project) {
        val android = project.extensions.findByName("android") as? CommonExtension ?: return
        android.testOptions.unitTests.isIncludeAndroidResources = true
    }

    private fun registerAndroidTasks(project: Project, extension: PreviewExtension) {
        val variant = extension.variant.get()
        val capVariant = variant.cap()
        val previewOutputDir = project.layout.buildDirectory.dir("compose-previews")

        val sourceClassDirs = project.files(
            project.layout.buildDirectory.dir("tmp/kotlin-classes/$variant"),
            project.layout.buildDirectory.dir("intermediates/javac/$variant/classes"),
            project.layout.buildDirectory.dir("intermediates/built_in_kotlinc/$variant/compile${capVariant}Kotlin/classes"),
        )

        val dependencyConfigName = "${variant}RuntimeClasspath"

        val discoverTask = registerDiscoverTask(project, sourceClassDirs, dependencyConfigName, previewOutputDir, extension) {
            dependsOn("compile${capVariant}Kotlin")
        }

        val hasAndroidRenderer = project.rootProject.findProject(":renderer-android") != null

        if (hasAndroidRenderer) {
            val artifactType = Attribute.of("artifactType", String::class.java)
            val rendererProject = project.rootProject.project(":renderer-android")
            val rendererClassDirs = project.files(
                rendererProject.layout.buildDirectory.dir("intermediates/built_in_kotlinc/$variant/compile${capVariant}Kotlin/classes"),
                rendererProject.layout.buildDirectory.dir("tmp/kotlin-classes/$variant"),
            )

            // Build the classpath at configuration time using lazy file collections.
            // Use debugUnitTestRuntimeClasspath with artifact view filtering to get
            // extracted JARs from AARs (AGP registers the transforms).
            val testConfig = project.configurations.findByName("${variant}UnitTestRuntimeClasspath")
            val rendererConfig = rendererProject.configurations.findByName("${variant}RuntimeClasspath")

            // android.jar is needed on the classpath for Robolectric's runner classes
            // to load (they reference android.app.Application during initialization).
            // Robolectric's sandbox classloader takes priority during actual rendering.
            val android = project.extensions.findByName("android") as? CommonExtension
            val compileSdk = android?.compileSdk ?: 36
            val sdkDir = findAndroidSdkDir(project)

            val resolvedClasspath = project.files().apply {
                if (testConfig != null) {
                    from(testConfig.incoming.artifactView {
                        attributes.attribute(artifactType, "jar")
                    }.files)
                    from(testConfig.incoming.artifactView {
                        attributes.attribute(artifactType, "android-classes")
                    }.files)
                }
                if (rendererConfig != null) {
                    from(rendererConfig.incoming.artifactView {
                        attributes.attribute(artifactType, "jar")
                    }.files)
                }
                from(rendererClassDirs)
                from(sourceClassDirs)
                // android.jar for Robolectric runner bootstrap
                if (sdkDir != null) {
                    val androidJar = java.io.File("$sdkDir/platforms/android-$compileSdk/android.jar")
                    if (androidJar.exists()) from(androidJar)
                }
            }

            // Copy JVM args from AGP's test task (at configuration time, safe for config cache)
            val agpTestTask = project.tasks.findByName("test${capVariant}UnitTest") as? Test
            val agpJvmArgs = agpTestTask?.jvmArgs ?: emptyList()

            val manifestFile = previewOutputDir.map { it.file("previews.json").asFile.absolutePath }
            val rendersDir = previewOutputDir.map { it.dir("renders").asFile.absolutePath }

            val renderTask = project.tasks.register("renderPreviews", Test::class.java) {
                group = "compose preview"
                description = "Render Android previews via Robolectric"
                testClassesDirs = rendererClassDirs
                classpath = resolvedClasspath
                include("**/RobolectricRenderTest.class")
                useJUnit()

                jvmArgs(agpJvmArgs)
                jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
                )

                // Configure Robolectric via system properties instead of @Config/@GraphicsMode
                // annotations — annotations trigger eager android.app.Application resolution
                // before the sandbox classloader exists.
                systemProperty("robolectric.sdk", "35")
                systemProperty("robolectric.graphicsMode", "NATIVE")
                systemProperty("robolectric.looperMode", "PAUSED")
                systemProperty("robolectric.screenshot.hwrdr.native", "true")
                systemProperty("robolectric.pixelCopyRenderMode", "hardware")

                systemProperty("composeai.render.manifest", manifestFile.get())
                systemProperty("composeai.render.outputDir", rendersDir.get())

                dependsOn(discoverTask)
                dependsOn(":renderer-android:compile${capVariant}Kotlin")
                dependsOn("process${capVariant}Resources")
                val configTaskName = "generate${capVariant}UnitTestConfig"
                if (project.tasks.findByName(configTaskName) != null) {
                    dependsOn(configTaskName)
                }
            }

            project.tasks.register("renderAllPreviews", DefaultTask::class.java) {
                group = "compose preview"
                dependsOn(renderTask)
            }
        } else {
            registerStubRenderTask(project, previewOutputDir, sourceClassDirs, dependencyConfigName, discoverTask)
        }
    }

    private fun registerDesktopTasks(project: Project, extension: PreviewExtension) {
        val previewOutputDir = project.layout.buildDirectory.dir("compose-previews")

        val sourceClassDirs = project.files(
            project.layout.buildDirectory.dir("classes/kotlin/main"),
            project.layout.buildDirectory.dir("classes/kotlin/jvm/main"),
            project.layout.buildDirectory.dir("classes/kotlin/desktop/main"),
        )

        val dependencyConfigName = listOf("jvmRuntimeClasspath", "desktopRuntimeClasspath", "runtimeClasspath")
            .firstOrNull { project.configurations.findByName(it) != null }
            ?: "runtimeClasspath"

        val discoverTask = registerDiscoverTask(project, sourceClassDirs, dependencyConfigName, previewOutputDir, extension) {
            for (name in listOf("compileKotlinJvm", "compileKotlinDesktop", "compileKotlin")) {
                if (project.tasks.findByName(name) != null) {
                    dependsOn(name)
                    break
                }
            }
        }

        val rendererConfigName = "composePreviewRenderer"
        val rendererConfig = project.configurations.maybeCreate(rendererConfigName)
        rendererConfig.isCanBeResolved = true
        rendererConfig.isCanBeConsumed = false

        val hasDesktopRenderer = try {
            project.dependencies.add(rendererConfigName, project.dependencies.project(mapOf("path" to ":renderer-desktop")))
            true
        } catch (_: Exception) {
            false
        }

        if (hasDesktopRenderer) {
            val renderTask = project.tasks.register("renderPreviews", RenderPreviewsTask::class.java) {
                previewsJson.set(previewOutputDir.map { it.file("previews.json") })
                outputDir.set(previewOutputDir.map { it.dir("renders") })
                renderBackend.set("desktop")
                useComposeRenderer.set(true)
                renderClasspath.from(sourceClassDirs)
                project.configurations.findByName(dependencyConfigName)?.let { renderClasspath.from(it) }
                renderClasspath.from(rendererConfig)
                group = "compose preview"
                description = "Render all previews to PNG"
                dependsOn(discoverTask)
            }
            project.tasks.register("renderAllPreviews", DefaultTask::class.java) {
                group = "compose preview"
                dependsOn(renderTask)
            }
        } else {
            registerStubRenderTask(project, previewOutputDir, sourceClassDirs, dependencyConfigName, discoverTask)
        }
    }

    private fun registerDiscoverTask(
        project: Project,
        sourceClassDirs: org.gradle.api.file.FileCollection,
        dependencyConfigName: String,
        previewOutputDir: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
        extension: PreviewExtension,
        configureDeps: DiscoverPreviewsTask.() -> Unit,
    ): org.gradle.api.tasks.TaskProvider<DiscoverPreviewsTask> {
        return project.tasks.register("discoverPreviews", DiscoverPreviewsTask::class.java) {
            classDirs.from(sourceClassDirs)
            project.configurations.findByName(dependencyConfigName)?.let { config ->
                dependencyJars.from(config)
            }
            moduleName.set(project.name)
            variantName.set(extension.variant)
            outputFile.set(previewOutputDir.map { it.file("previews.json") })
            group = "compose preview"
            description = "Discover @Preview annotations in compiled classes"
            configureDeps()
        }
    }

    private fun registerStubRenderTask(
        project: Project,
        previewOutputDir: org.gradle.api.provider.Provider<org.gradle.api.file.Directory>,
        sourceClassDirs: org.gradle.api.file.FileCollection,
        dependencyConfigName: String,
        discoverTask: org.gradle.api.tasks.TaskProvider<DiscoverPreviewsTask>,
    ) {
        val renderTask = project.tasks.register("renderPreviews", RenderPreviewsTask::class.java) {
            previewsJson.set(previewOutputDir.map { it.file("previews.json") })
            outputDir.set(previewOutputDir.map { it.dir("renders") })
            renderBackend.set("stub")
            useComposeRenderer.set(false)
            renderClasspath.from(sourceClassDirs)
            project.configurations.findByName(dependencyConfigName)?.let { renderClasspath.from(it) }
            group = "compose preview"
            description = "Render all previews to PNG (stub)"
            dependsOn(discoverTask)
        }
        project.tasks.register("renderAllPreviews", DefaultTask::class.java) {
            group = "compose preview"
            dependsOn(renderTask)
        }
    }

    private fun findAndroidSdkDir(project: Project): String? {
        return project.providers.environmentVariable("ANDROID_HOME").orNull
            ?: project.providers.environmentVariable("ANDROID_SDK_ROOT").orNull
            ?: project.rootProject.file("local.properties").let { f ->
                if (f.exists()) f.readLines()
                    .find { it.startsWith("sdk.dir=") }
                    ?.substringAfter("sdk.dir=")
                else null
            }
    }

    private fun String.cap(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
