package ee.schimke.composeai.plugin

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test

/**
 * All AGP-touching code lives here, segregated from [ComposePreviewPlugin] so
 * the plugin class stays loadable on classpaths without AGP (functional tests,
 * Compose-Multiplatform-only consumers). Gradle decorates the plugin class at
 * apply time — and decoration resolves referenced classes eagerly. Keeping
 * every `com.android.build.api.*` reference out of [ComposePreviewPlugin]'s
 * bytecode means AGP only gets loaded when this helper's static methods are
 * actually invoked, which happens inside
 * `pluginManager.withPlugin("com.android.application" / "com.android.library")`.
 */
internal object AndroidPreviewSupport {
    fun configure(project: Project, extension: PreviewExtension) {
        val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)

        androidComponents.finalizeDsl { android: Any ->
            if (extension.enabled.get()) {
                (android as CommonExtension).testOptions.unitTests.isIncludeAndroidResources = true
            }
        }

        // Register render tasks once, for the variant the user picked. onVariants
        // fires after AGP has created variant-specific configurations like
        // `${variant}UnitTestRuntimeClasspath`, so everything we need is there.
        // Fetching `sdkComponents.bootClasspath` eagerly (at apply time) forces
        // AGP to read `compileOptions.targetCompatibility` before it's finalized
        // and crashes — grab it inside onVariants instead.
        var registered = false
        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            if (registered) return@onVariants
            if (!extension.enabled.get()) return@onVariants
            if (variant.name != extension.variant.get()) return@onVariants
            registered = true
            registerAndroidTasks(project, extension, variant.name, androidComponents.sdkComponents.bootClasspath)
        }
    }

    private fun registerAndroidTasks(
        project: Project,
        extension: PreviewExtension,
        variantName: String,
        bootClasspath: org.gradle.api.provider.Provider<List<org.gradle.api.file.RegularFile>>,
    ) {
        val capVariant = variantName.cap()
        val previewOutputDir = project.layout.buildDirectory.dir("compose-previews")

        val sourceClassDirs = project.files(
            project.layout.buildDirectory.dir("tmp/kotlin-classes/$variantName"),
            project.layout.buildDirectory.dir("intermediates/javac/$variantName/classes"),
            project.layout.buildDirectory.dir("intermediates/built_in_kotlinc/$variantName/compile${capVariant}Kotlin/classes"),
        )

        val dependencyConfigName = "${variantName}RuntimeClasspath"

        val discoverTask = ComposePreviewTasks.registerDiscoverTask(
            project, sourceClassDirs, dependencyConfigName, previewOutputDir, extension,
        ) {
            dependsOn("compile${capVariant}Kotlin")
        }

        val artifactType = Attribute.of("artifactType", String::class.java)
        val testConfig = project.configurations.findByName("${variantName}UnitTestRuntimeClasspath")

        // The default path for external consumers: resolve
        // `ee.schimke.composeai:renderer-android:<plugin-version>` from Maven.
        // The plugin's own version is baked into the jar at build time so the
        // matching renderer AAR is chosen automatically — see [PluginVersion].
        //
        // Dev-mode shortcut: when the plugin runs *inside* the compose-ai-tools
        // build itself (in-repo samples), bypass Maven and depend on the sibling
        // `:renderer-android` Gradle project directly. That way live renderer
        // edits show up without a publish step. The signal is the presence of
        // the sibling build script on disk; we deliberately avoid calling
        // `rootProject.findProject(...)` here because reading the sibling's
        // model under Isolated Projects is disallowed — a filesystem check is
        // IP-safe, and only the in-repo layout matches it.
        val rendererProjectDir = project.rootDir.resolve("renderer-android")
        val useLocalRenderer = rendererProjectDir.resolve("build.gradle.kts").exists()
                || rendererProjectDir.resolve("build.gradle").exists()

        // Renderer's transitive runtime dependencies come through a dedicated
        // resolvable configuration in *this* project. Attributes are copied
        // from the sample's unit-test runtime classpath so Gradle picks the
        // right Android variant without us declaring them by hand.
        val rendererConfig = project.configurations.maybeCreate("composePreviewAndroidRenderer$capVariant").apply {
            isCanBeResolved = true
            isCanBeConsumed = false
            if (testConfig != null) {
                copyAttributes(attributes, testConfig.attributes)
            }
        }

        if (useLocalRenderer) {
            try {
                project.dependencies.add(rendererConfig.name, project.dependencies.project(mapOf("path" to ":renderer-android")))
            } catch (e: org.gradle.api.UnknownProjectException) {
                project.logger.debug("compose-ai-tools: :renderer-android project not found, skipping", e)
            }
        } else {
            project.dependencies.add(
                rendererConfig.name,
                "ee.schimke.composeai:renderer-android:${PluginVersion.value}",
            )
        }

        // Classes directory used for Gradle's test-class scanning. Local mode:
        // the renderer-android project's compiled output. External mode: the
        // extracted android-classes artifact (AGP's transform unpacks the AAR's
        // classes.jar). Either way, Gradle scans this for `@RunWith(…)` classes
        // to run and finds `RobolectricRenderTest`.
        val rendererClassDirs = if (useLocalRenderer) {
            project.files(
                rendererProjectDir.resolve("build/intermediates/built_in_kotlinc/$variantName/compile${capVariant}Kotlin/classes"),
                rendererProjectDir.resolve("build/tmp/kotlin-classes/$variantName"),
            )
        } else {
            project.files(rendererConfig.incoming.artifactView {
                attributes.attribute(artifactType, "android-classes")
                componentFilter { id ->
                    id is org.gradle.api.artifacts.component.ModuleComponentIdentifier
                            && id.group == "ee.schimke.composeai"
                            && id.module == "renderer-android"
                }
            }.files)
        }

        // AGP's `generate${Variant}UnitTestConfig` task emits
        // `com/android/tools/test_config.properties` under
        // `intermediates/unit_test_config_directory/<variant>UnitTest/.../out/`.
        // Robolectric loads it from the classpath and uses it to find the merged
        // resource APK (`apk-for-local-test.ap_`) — the one that contains every
        // AAR's merged resources (protolayout-renderer's `ProtoLayoutBaseTheme`
        // etc.). Without this directory on the classpath, `getIdentifier` returns
        // 0 for any library-provided style and TileRenderer's theme construction
        // explodes on `Unknown resource value type 0`. Compose-only previews
        // don't read AAR resources, which is why this only surfaced with tiles.
        val unitTestConfigDir = project.layout.buildDirectory.dir(
            "intermediates/unit_test_config_directory/${variantName}UnitTest/generate${capVariant}UnitTestConfig/out"
        )

        // Renderer classpath FIRST — renderer depends on kotlinx-serialization
        // 1.11.x and Roborazzi 1.59+ while consumer apps may transitively drag
        // in older versions (Compose BOM, etc). Gradle's FileCollection.from()
        // doesn't do conflict resolution, so whichever JAR comes first wins at
        // classload time. Putting the renderer's dependencies first ensures the
        // test code gets the versions it was compiled against.
        val resolvedClasspath = project.files().apply {
            from(rendererConfig.incoming.artifactView {
                attributes.attribute(artifactType, "jar")
            }.files)
            from(rendererClassDirs)
            if (testConfig != null) {
                from(testConfig.incoming.artifactView {
                    attributes.attribute(artifactType, "jar")
                }.files)
                from(testConfig.incoming.artifactView {
                    attributes.attribute(artifactType, "android-classes")
                }.files)
            }
            from(sourceClassDirs)
            from(unitTestConfigDir)
            // SDK stub android.jar on the OUTER classpath so JUnit can introspect
            // the test class (RobolectricRenderTest.kt references android.graphics.Bitmap,
            // android.view.PixelCopy, etc. in method signatures). Without it, JUnit fails
            // with `NoClassDefFoundError: android/graphics/Bitmap` during test discovery,
            // before Robolectric's sandbox classloader is even created.
            //
            // Inside the sandbox, `ParameterizedRobolectricTestRunner` loads the test class
            // through Robolectric's InstrumentingClassLoader, which delegates `android.*`
            // resolution to its own `android-all` artifact (real framework classes, with
            // shadows applied). The outer stub does NOT shadow the sandboxed PixelCopy.
            //
            // Sourced from AGP's SdkComponents so we don't have to parse local.properties
            // or read rootProject.file(...).
            from(project.files(bootClasspath))
        }

        val manifestFile = previewOutputDir.map { it.file("previews.json").asFile.absolutePath }
        val rendersDir = previewOutputDir.map { it.dir("renders").asFile.absolutePath }

        val shardCount = resolveShardCount(project, extension, previewOutputDir.get().file("previews.json").asFile)
        val shardsEnabled = shardCount > 1

        // When sharded, generate N Java subclasses of RobolectricRenderTestBase, each with
        // its own static @Parameters method that loads only that shard's slice of the manifest.
        // Gradle distributes tests across forks at the class level, so a single parameterized
        // class can't be split — we give it N classes. Each shard subclass inherits @Config
        // and @GraphicsMode from the base, so every JVM's sandbox key matches and each fork
        // reuses its own cached sandbox across all previews in its slice.
        val shardSourcesDir = project.layout.buildDirectory.dir("generated/composeai/render-shards/java")
        val shardClassesDir = project.layout.buildDirectory.dir("generated/composeai/render-shards/classes")

        val generateShardsTask = if (shardsEnabled) {
            project.tasks.register("generateRenderShards", GenerateRenderShardsTask::class.java) {
                group = "compose preview"
                description = "Generate $shardCount RobolectricRenderTest_Shard subclasses"
                shards.set(shardCount)
                outputDir.set(shardSourcesDir)
            }
        } else null

        val compileShardsTask = if (generateShardsTask != null) {
            project.tasks.register("compileRenderShards", JavaCompile::class.java) {
                group = "compose preview"
                description = "Compile generated shard test subclasses"
                source(generateShardsTask.map { it.outputDir.asFileTree })
                classpath = resolvedClasspath
                destinationDirectory.set(shardClassesDir)
                options.release.set(21)
                dependsOn(generateShardsTask)
                if (useLocalRenderer) {
                    dependsOn(":renderer-android:compile${capVariant}Kotlin")
                }
            }
        } else null

        val renderTask = project.tasks.register("renderPreviews", Test::class.java) {
            group = "compose preview"
            description = "Render Android previews via Robolectric"
            val agpTestTask = project.tasks.findByName("test${capVariant}UnitTest") as? Test
            testClassesDirs = if (compileShardsTask != null) {
                rendererClassDirs + project.files(compileShardsTask.map { it.destinationDirectory }) + (agpTestTask?.testClassesDirs ?: project.files())
            } else {
                rendererClassDirs + (agpTestTask?.testClassesDirs ?: project.files())
            }
            classpath = if (compileShardsTask != null) {
                resolvedClasspath + project.files(compileShardsTask.map { it.destinationDirectory }) + (agpTestTask?.testClassesDirs ?: project.files())
            } else {
                resolvedClasspath + (agpTestTask?.testClassesDirs ?: project.files())
            }
            if (shardsEnabled) {
                include("**/RobolectricRenderTest_Shard*.class")
                maxParallelForks = shardCount
            } else {
                include("**/RobolectricRenderTest.class")
            }
            useJUnit()

            // Copy JVM args from AGP's test task. Deferred to the configuration
            // lambda (rather than called at registration time) so AGP has had
            // a chance to register `test${capVariant}UnitTest` by the time this
            // runs — onVariants fires before unit-test tasks are wired.
            jvmArgs(agpTestTask?.jvmArgs ?: emptyList<String>())
            jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
                // Robolectric's `ShadowVMRuntime.getAddressOfDirectByteBuffer`
                // reflectively invokes `DirectByteBuffer.address()`; under JDK 17+
                // module rules this fails with IllegalAccessException without this
                // opens. Reached via `PathIterator` — triggered here by Wear Compose's
                // curved text renderer.
                "--add-opens=java.base/java.nio=ALL-UNNAMED",
            )

            // Belt-and-suspenders for the graphics/looper modes — the test class
            // already pins `@GraphicsMode(NATIVE)`, but if those annotations ever
            // regress to eager resolution we still want NATIVE/PAUSED.
            systemProperty("robolectric.graphicsMode", "NATIVE")
            systemProperty("robolectric.looperMode", "PAUSED")
            // Conscrypt isn't needed for preview rendering (no TLS/HTTP paths
            // execute) and its native library is flaky on some Linux sandboxes
            // — e.g. missing/ABI-mismatched `libstdc++.so.6`. Telling Robolectric
            // to skip the install avoids those failures without shipping our
            // own Conscrypt stubs. See `ConscryptMode` /
            // `ConscryptModeConfigurer` in Robolectric.
            systemProperty("robolectric.conscryptMode", "OFF")
            // Routes ShadowPixelCopy through HardwareRenderingScreenshot →
            // ImageReader + HardwareRenderer.syncAndDraw, the only path that
            // replays Compose's RenderNodes correctly.
            systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            // Roborazzi defaults to "compare" mode (which doesn't write pixels
            // unless the expected baseline exists). Force "record" so every run
            // writes fresh PNGs.
            systemProperty("roborazzi.test.record", "true")

            systemProperty("composeai.render.manifest", manifestFile.get())
            systemProperty("composeai.render.outputDir", rendersDir.get())

            dependsOn(discoverTask)
            if (useLocalRenderer) {
                dependsOn(":renderer-android:compile${capVariant}Kotlin")
            }
            dependsOn("process${capVariant}Resources")
            val configTaskName = "generate${capVariant}UnitTestConfig"
            if (project.tasks.findByName(configTaskName) != null) {
                dependsOn(configTaskName)
            }
            if (compileShardsTask != null) {
                dependsOn(compileShardsTask)
            }
        }

        ComposePreviewTasks.registerRenderAllPreviews(project, extension, renderTask, previewOutputDir)
    }

    private fun copyAttributes(target: AttributeContainer, source: AttributeContainer) {
        source.keySet().forEach { key ->
            @Suppress("UNCHECKED_CAST")
            val attr = key as Attribute<Any>
            source.getAttribute(attr)?.let { target.attribute(attr, it) }
        }
    }

    /**
     * Resolves the effective shard count from [PreviewExtension.shards]:
     *
     *  - `≥1`: use the value as-is.
     *  - `0` (auto): read [previewsJson] if it exists from a previous discover run and
     *    hand the count to [ShardTuning.autoShards]. If the file is missing (very first
     *    build), fall back to 1 — the next run will have better data and can
     *    pick a higher count then.
     */
    private fun resolveShardCount(
        project: Project,
        extension: PreviewExtension,
        previewsJson: java.io.File,
    ): Int {
        val requested = extension.shards.get()
        if (requested > 0) return requested
        if (!previewsJson.exists()) {
            project.logger.info("compose-ai-tools: shards=auto but previews.json missing; defaulting to 1 for this run")
            return 1
        }
        // Simple count of `"id"` entries — cheap and avoids pulling kotlinx.serialization into the plugin classpath.
        val previewCount = Regex("\"id\"\\s*:").findAll(previewsJson.readText()).count()
        val resolved = ShardTuning.autoShards(previewCount)
        project.logger.lifecycle("compose-ai-tools: shards=auto → $resolved (previewCount=$previewCount, cores=${Runtime.getRuntime().availableProcessors()})")
        return resolved
    }

    private fun String.cap(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
