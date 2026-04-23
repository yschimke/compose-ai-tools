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
    /**
     * Modules within `androidx.wear.tiles` whose presence in a consumer's
     * declared deps signals "this project writes Tile previews." When any
     * match, [configure] injects `wear.tiles:tiles-renderer` into the
     * consumer's variant `implementation` so AGP generates R classes for
     * protolayout-renderer — the class TilePreviewRenderer reflectively
     * needs at render time. See the `afterEvaluate` block in
     * [registerAndroidTasks] for the full rationale.
     */
    private val tilesSignalNames = setOf(
        "tiles",
        "tiles-renderer",
        "tiles-tooling-preview",
        "tiles-tooling",
    )

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
        val artifactType = Attribute.of("artifactType", String::class.java)

        // `com.android.compose.screenshot` (Google's alpha Layoutlib-based
        // screenshot testing plugin) adds its own `screenshotTest` source set
        // alongside `main` / `test` / `androidTest`. We don't drive its
        // validate/update tasks — we keep using our Robolectric renderer — but
        // we DO want to discover and render any `@Preview` functions consumers
        // put under `src/screenshotTest/`, so modules that already adopted the
        // Google plugin (e.g. Confetti's `:androidApp`) surface those previews
        // in the CLI / VS Code grid without duplicating them in `main`.
        //
        // Detection is by plugin id rather than the
        // `android.experimental.enableScreenshotTest` gradle property, because
        // the property is a global flag while the plugin is applied per-module
        // — and only the latter actually causes AGP to register
        // `compile${Cap}ScreenshotTestKotlin` and the
        // `${variant}ScreenshotTestRuntimeClasspath` configuration we need.
        val screenshotTestEnabled = project.pluginManager.hasPlugin("com.android.compose.screenshot")

        val sourceClassDirs = project.files(
            project.layout.buildDirectory.dir("tmp/kotlin-classes/$variantName"),
            project.layout.buildDirectory.dir("intermediates/javac/$variantName/classes"),
            project.layout.buildDirectory.dir("intermediates/built_in_kotlinc/$variantName/compile${capVariant}Kotlin/classes"),
        )
        if (screenshotTestEnabled) {
            sourceClassDirs.from(
                project.layout.buildDirectory.dir(
                    "intermediates/built_in_kotlinc/${variantName}ScreenshotTest/compile${capVariant}ScreenshotTestKotlin/classes"
                ),
                project.layout.buildDirectory.dir(
                    "intermediates/javac/${variantName}ScreenshotTest/classes"
                ),
            )
        }

        val dependencyConfigName = "${variantName}RuntimeClasspath"
        val screenshotTestRuntimeConfig = if (screenshotTestEnabled) {
            project.configurations.findByName("${variantName}ScreenshotTestRuntimeClasspath")
        } else null

        val discoverTask = ComposePreviewTasks.registerDiscoverTask(
            project, sourceClassDirs, dependencyConfigName, previewOutputDir, extension,
        ) {
            dependsOn("compile${capVariant}Kotlin")
            if (screenshotTestEnabled) {
                dependsOn("compile${capVariant}ScreenshotTestKotlin")
                screenshotTestRuntimeConfig?.let { stConfig ->
                    dependencyJars.from(
                        stConfig.incoming.artifactView {
                            attributes.attribute(artifactType, "jar")
                        }.files,
                    )
                    dependencyJars.from(
                        stConfig.incoming.artifactView {
                            attributes.attribute(artifactType, "android-classes")
                        }.files,
                    )
                }
            }
        }

        // Writes the plugin-side compat findings (CompatRules) to
        // `build/compose-previews/doctor.json`. The CLI doesn't need this
        // file (it reads the same data via the ComposePreviewModel Tooling
        // API), but tools that invoke Gradle tasks rather than BuildActions
        // — specifically the VS Code extension — do. Same JSON schema as
        // `compose-preview doctor --json`'s per-module shape, so both
        // surfaces converge on one contract.
        // Resolve the runtime classpaths' root components at configuration
        // time so the task action stays config-cache safe (no `task.project`
        // access at execution). `findByName` may return null on variants that
        // don't have a paired unit-test classpath; the task tolerates an
        // unset Property as "no deps to inspect".
        val mainRuntimeRoot = project.configurations.findByName("${variantName}RuntimeClasspath")
            ?.incoming?.resolutionResult?.rootComponent
        val testRuntimeRoot = project.configurations.findByName("${variantName}UnitTestRuntimeClasspath")
            ?.incoming?.resolutionResult?.rootComponent

        // Capture the running Gradle version at configuration time so the
        // task action stays config-cache safe (GradleVersion.current() is a
        // static call but keeping the read out of `@TaskAction` avoids
        // surprises if Gradle ever namespaces it differently).
        val currentGradleVersion = org.gradle.util.GradleVersion.current().version
        project.tasks.register("composePreviewDoctor", ee.schimke.composeai.plugin.tooling.ComposePreviewDoctorTask::class.java) {
            group = "compose preview"
            description = "Write compose-preview doctor findings to build/compose-previews/doctor.json"
            this.variant.set(variantName)
            this.modulePath.set(project.path)
            this.gradleVersion.set(currentGradleVersion)
            this.outputFile.set(previewOutputDir.map { it.file("doctor.json") })
            mainRuntimeRoot?.let { this.mainRuntimeRoot.set(it) }
            testRuntimeRoot?.let { this.testRuntimeRoot.set(it) }
        }

        // Always inject `ui-test-manifest` + `ui-test-junit4` into the consumer's
        // `testImplementation`:
        //
        //  * `ui-test-manifest` contributes the `<activity android:name=
        //    "androidx.activity.ComponentActivity">` entry that has to land in
        //    the consumer's merged unit-test AndroidManifest before
        //    `createAndroidComposeRule<ComponentActivity>()` can launch its
        //    ActivityScenario. Our plugin bypasses the normal AGP dep graph
        //    (renderer classpath lives in our own resolvable config, not
        //    `testImplementation`), so the manifest merger never sees it
        //    otherwise.
        //  * `ui-test-junit4` is where `createAndroidComposeRule` /
        //    `ComposeTestRule` / `mainClock` live. The renderer test references
        //    these unconditionally from its default `renderDefault` path (we
        //    use `mainClock.autoAdvance = false` + explicit frame pumping to
        //    make infinite animations terminate deterministically — see
        //    RobolectricRenderTest.renderDefault), so the consumer's test
        //    classpath needs these classes too, not just the resource/manifest
        //    half of the story.
        //
        // No version: relies on the consumer's Compose BOM (or direct Compose
        // dep) to resolve these artifacts. Projects using
        // `implementation(platform(libs.compose.bom))` pick up the aligned
        // version automatically; projects without a BOM need to add one (a
        // reasonable ask — the plugin is for Compose apps).
        project.dependencies.add(
            "testImplementation",
            "androidx.compose.ui:ui-test-manifest",
        )
        project.dependencies.add(
            "testImplementation",
            "androidx.compose.ui:ui-test-junit4",
        )

        // Conditionally inject `androidx.wear.tiles:tiles-renderer` into the
        // consumer's variant `implementation` when the consumer signals they
        // want Tile previews. Detection is deferred to `afterEvaluate` so the
        // consumer's declared deps are complete.
        //
        // Why we inject at all: TilePreviewRenderer.renderTileInto calls
        // `TileRenderer(...)`, whose constructor builds `ProtoLayoutThemeImpl`
        // which holds a Java reference to
        // `androidx.wear.protolayout.renderer.R$style.ProtoLayoutBaseTheme`.
        // That R class is only compiled into the consumer's merged R.jar when
        // `wear.tiles:tiles-renderer` is on the MAIN compile classpath —
        // `testImplementation` and `compileOnly` don't participate in AGP's R
        // class generation. Consumer apps shouldn't have to restate a purely
        // preview-rendering dep in their main `implementation`.
        //
        // Why the signal is "tiles-tooling-preview / tiles-renderer / tiles":
        // these are the modules a consumer actually declares when they write
        // `@Preview`-annotated tile functions. Horologist projects go through
        // `horologist-tiles` so we include that too.
        //
        // No version — the consumer's wear.tiles atomic group constrains
        // `tiles-renderer` to their wear.tiles version. When the detection
        // misfires in a non-tiles project (shouldn't happen under the
        // heuristic above), Gradle fails with a clear "no version for
        // tiles-renderer" error.
        project.afterEvaluate {
            // Scan every configuration whose name ends in `Implementation` so
            // the detection works for ANY buildType / flavor / variant combo
            // (e.g. `uatImplementation`, `stagingImplementation`,
            // `uatStagingImplementation`). The earlier hardcoded list of
            // `debugImplementation` / `releaseImplementation` only fired on
            // the default AGP buildTypes, missing custom flavored layouts
            // like `uatDebug`. The group+name filter below is precise enough
            // that casting a wider net is safe — false positives require a
            // dep literally in the `androidx.wear.tiles` / horologist-tiles
            // groups, which is the signal we're looking for.
            val hasTilesSignal = project.configurations.asSequence()
                // Case-sensitive end-match misses the bare root `implementation`
                // configuration (lowercase i) — where plain
                // `implementation(libs.wear.tiles)` declarations land.
                // Include it explicitly alongside the camel-cased sourceSet-
                // scoped variants (`debugImplementation`,
                // `uatDebugImplementation`, `testImplementation`, …).
                .filter { it.name == "implementation" || it.name.endsWith("Implementation") }
                .flatMap { it.allDependencies.asSequence() }
                .any { dep ->
                    (dep.group == "androidx.wear.tiles" && dep.name in tilesSignalNames) ||
                        (dep.group == "com.google.android.horologist" && dep.name == "horologist-tiles")
                }
            if (hasTilesSignal) {
                project.dependencies.add(
                    "${variantName}Implementation",
                    "androidx.wear.tiles:tiles-renderer",
                )
            }
        }

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
        //
        // `extendsFrom(testConfig)` is load-bearing: it tells Gradle to resolve
        // renderer deps in the SAME graph as the consumer's test-runtime deps,
        // so version conflicts pick a single coherent max version instead of
        // two separate graphs that clash at class-load time. Without it, the
        // renderer's transitive `androidx.core:1.8.0` and consumer's
        // `androidx.core:1.16.0` both end up on the test classpath in different
        // JARs — whichever is listed first wins for each class, and the loaded
        // activity/lifecycle/compose-ui versions don't all agree. Symptoms:
        //   - `NoSuchFieldError: androidx.lifecycle.ReportFragment.Companion`
        //   - `NoSuchFieldError: … tag_compat_insets_dispatch`
        val rendererConfig = project.configurations.maybeCreate("composePreviewAndroidRenderer$capVariant").apply {
            isCanBeResolved = true
            isCanBeConsumed = false
            if (testConfig != null) {
                copyAttributes(attributes, testConfig.attributes)
                extendsFrom(testConfig)
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

        // Classes used for Gradle's test-class scanning. Local mode: the
        // renderer-android project's compiled output directories. External
        // mode: the AAR's `classes.jar`, expanded via `zipTree` so Gradle's
        // `Test.include("**/…Test.class")` filter can walk it — the include
        // filter traverses file trees but does NOT descend into JAR entries,
        // so feeding a raw JAR here silently produces `renderPreviews NO-SOURCE`
        // and every preview ends up with no PNG. `android-classes` is AGP's
        // `ArtifactType.CLASSES_JAR` (a JAR), not the extracted directory
        // (that would be `android-classes-directory`).
        val rendererClassDirs = if (useLocalRenderer) {
            project.files(
                rendererProjectDir.resolve("build/intermediates/built_in_kotlinc/$variantName/compile${capVariant}Kotlin/classes"),
                rendererProjectDir.resolve("build/tmp/kotlin-classes/$variantName"),
            )
        } else {
            val rendererJars = rendererConfig.incoming.artifactView {
                attributes.attribute(artifactType, "android-classes")
                componentFilter { id ->
                    id is org.gradle.api.artifacts.component.ModuleComponentIdentifier
                            && id.group == "ee.schimke.composeai"
                            && id.module == "renderer-android"
                }
            }.files
            // Callable defers `.files` resolution until the Test task queries
            // this FileCollection, keeping the configuration lazy.
            project.files(java.util.concurrent.Callable {
                rendererJars.files.map { project.zipTree(it) }
            })
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

        // Generates `ee/schimke/composeai/renderer/robolectric.properties`
        // onto the render classpath so Robolectric overrides the consumer's
        // `Application` with a stub by default — see
        // [GenerateRobolectricPropertiesTask] for rationale and the opt-out.
        val robolectricPropertiesDir = project.layout.buildDirectory
            .dir("generated/composeai/robolectric/$variantName")
        val generateRobolectricPropertiesTask = project.tasks.register(
            "generateRobolectricProperties",
            GenerateRobolectricPropertiesTask::class.java,
        ) {
            group = "compose preview"
            description = "Generate package-level robolectric.properties for renderPreviews"
            useConsumerApplication.set(extension.useConsumerApplication)
            outputDir.set(robolectricPropertiesDir)
        }

        // Renderer classpath FIRST — renderer depends on kotlinx-serialization
        // 1.11.x and Roborazzi 1.59+ while consumer apps may transitively drag
        // in older versions (Compose BOM, etc). Gradle's FileCollection.from()
        // doesn't do conflict resolution, so whichever JAR comes first wins at
        // classload time. Putting the renderer's dependencies first ensures the
        // test code gets the versions it was compiled against.
        val resolvedClasspath = project.files().apply {
            // Robolectric properties dir BEFORE consumer test resources so our
            // Application override wins when classloader.getResource walks the
            // classpath. Consumers with their own `robolectric.properties` at
            // the same package path are unusual — they'd need it specifically
            // for this renderer's test class.
            from(generateRobolectricPropertiesTask.flatMap { it.outputDir })
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
            // screenshotTest source set has its own runtime config — any
            // `screenshotTestImplementation(...)` dep the consumer declared is
            // only visible here, not via `testConfig`. Include it so previews
            // under `src/screenshotTest/` can reference those classes at
            // render time. No-op when the screenshot plugin isn't applied.
            screenshotTestRuntimeConfig?.let { stConfig ->
                from(stConfig.incoming.artifactView {
                    attributes.attribute(artifactType, "jar")
                }.files)
                from(stConfig.incoming.artifactView {
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
        val rendersDirectory = previewOutputDir.map { it.dir("renders") }
        val rendersDir = rendersDirectory.map { it.asFile.absolutePath }

        // Per-preview ATF findings land here. `verifyAccessibility` rolls them
        // up into a single `accessibility.json` next to `previews.json`. Kept
        // separate from renders/ so caching treats the two output trees
        // independently.
        val accessibilityPerPreviewDir = previewOutputDir.map { it.dir("accessibility-per-preview") }
        val accessibilityReportFile = previewOutputDir.map { it.file("accessibility.json") }

        val shardCount = resolveShardCount(project, extension, previewOutputDir.get().file("previews.json").asFile)
        val shardsEnabled = shardCount > 1

        // When sharded, generate N Java subclasses of RobolectricRenderTestBase, each with
        // its own static @Parameters method that loads only that shard's slice of the manifest.
        // Gradle distributes tests across forks at the class level, so a single parameterized
        // class can't be split — we give it N classes. Each shard subclass resolves its
        // Robolectric config via the generated package-level `robolectric.properties`
        // (sdk/graphicsMode/application/shadows), so every JVM's sandbox key matches and
        // each fork reuses its own cached sandbox across all previews in its slice.
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
            // Append AGP's own `test${Cap}UnitTest` classpath at the END so we
            // pick up files that only exist there: specifically, the unit-test
            // merged R.jar for library modules (`com.android.library` variants
            // publish their AAR-transitive R classes — e.g.
            // `androidx.customview.poolingcontainer.R$id`, pulled in by
            // `ViewCompositionStrategy` — via
            // `compile_and_runtime_r_class_jar/${variant}UnitTest/process${Cap}UnitTestResources/R.jar`,
            // which is added to `debugUnitTestRuntimeClasspath` as a raw file
            // dep without the `artifactType=jar` attribute, so our
            // attribute-filtered `artifactView` above silently drops it).
            // Ordering is load-bearing — putting it last means our renderer's
            // pinned versions still win classload lookups in the earlier
            // classpath entries. No-op on applications, since
            // `process${Cap}Resources` puts the merged R.jar on the main
            // runtime classpath where our existing `artifactView` already
            // picks it up. See issue #136.
            val agpTestClasspath = agpTestTask?.classpath ?: project.files()
            classpath = if (compileShardsTask != null) {
                resolvedClasspath + project.files(compileShardsTask.map { it.destinationDirectory }) + (agpTestTask?.testClassesDirs ?: project.files()) + agpTestClasspath
            } else {
                resolvedClasspath + (agpTestTask?.testClassesDirs ?: project.files()) + agpTestClasspath
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

            // Inherit AGP's unit-test javaLauncher so the forked test worker
            // runs on the same JDK as `test${capVariant}UnitTest` — which
            // AGP has already wired to the project's Java toolchain if the
            // consumer configured one (`java { toolchain { … } }` /
            // `kotlin { jvmToolchain(…) }`), or to the daemon JVM otherwise.
            //
            // Without this, a custom `Test` task's `javaLauncher` property
            // defaults to the first `java` on PATH, which on CI and in local
            // shells with `JAVA_HOME` overrides is NOT necessarily the same
            // JVM the Gradle daemon is running. That mismatch produces
            // `ClassNotFoundException: android.app.Application` during JUnit
            // discovery on some JVM/classloader combinations. See #142.
            agpTestTask?.javaLauncher?.orNull?.let { javaLauncher.set(it) }

            // Belt-and-braces for the graphics/looper modes. Config now
            // lives in `ee/schimke/composeai/renderer/robolectric.properties`
            // (see `RobolectricRenderTestBase` KDoc for why we can't use
            // `@GraphicsMode` directly). These system properties are a third
            // independent Robolectric config channel and cost nothing to
            // keep — survive both annotation and properties paths regressing.
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

            // GoogleFont interceptor cache — defaults to
            // `<project>/.compose-preview-history/fonts/`, same root the
            // history task uses, so committed TTFs sit beside committed PNGs.
            // The renderer class no-ops when this property is absent, so the
            // feature is fully additive for existing consumers.
            val fontsCacheDir = extension.historyDir
                .orElse(project.layout.projectDirectory.dir(".compose-preview-history"))
                .map { it.dir("fonts").asFile.absolutePath }
            systemProperty("composeai.fonts.cacheDir", fontsCacheDir.get())
            // `-PcomposePreview.fontsOffline=true` (or the same Gradle property
            // on a CI profile) skips network on cache miss so the render
            // shows the fallback font rather than silently fetching from
            // `fonts.googleapis.com`.
            val fontsOffline = project.providers.gradleProperty("composePreview.fontsOffline")
                .orElse("false")
            systemProperty("composeai.fonts.offline", fontsOffline.get())

            // ATF flags are routed through a CommandLineArgumentProvider
            // rather than `systemProperty(...)` so toggling the `-P` override
            // doesn't invalidate the Gradle configuration cache. `systemProperty`
            // evaluates its value eagerly at configuration time — the provider
            // we'd read there becomes part of the config-cache key, so flipping
            // `-PcomposePreview.accessibilityChecks.enabled` forces a ~5-10s
            // reconfigure. CommandLineArgumentProvider's `@Input` providers
            // are only evaluated at task execution, which is exactly the
            // lazy-input semantics we want for VSCode toggles.
            //
            // Renderer always inspects these sysprops at runtime; when
            // enabled=false, the a11y code paths are no-ops (see
            // [RobolectricRenderTest.renderWithA11y] / `renderDefault`).
            jvmArgumentProviders.add(
                AccessibilitySystemPropsProvider(
                    enabled = resolveA11yEnabled(project, extension),
                    annotate = resolveA11yAnnotate(project, extension),
                    outputDir = accessibilityPerPreviewDir.map { it.asFile.absolutePath },
                    debug = project.providers.gradleProperty("composeai.a11y.debug").orElse("false"),
                ),
            )
            // Per-preview dir is always an output — the feature being off just
            // means no files get written there. Declaring it unconditionally
            // lets the config cache key stay stable across toggles.
            outputs.dir(accessibilityPerPreviewDir).withPropertyName("a11yPerPreviewDir")

            // The PNG files are written to `rendersDirectory` via the
            // `composeai.render.outputDir` system property, not through any
            // Gradle-managed output. Declare the directory as an additional
            // output so the build cache round-trips the PNGs alongside the
            // test reports; without this the task gets a cache hit on a fresh
            // checkout but the renders are never restored, which is exactly
            // how previous modules silently vanished from `preview_main`.
            outputs.dir(rendersDirectory).withPropertyName("rendersDir")

            dependsOn(discoverTask)
            dependsOn(generateRobolectricPropertiesTask)
            if (useLocalRenderer) {
                dependsOn(":renderer-android:compile${capVariant}Kotlin")
            }
            if (screenshotTestEnabled) {
                dependsOn("compile${capVariant}ScreenshotTestKotlin")
            }
            // `process${Cap}Resources` only exists on `com.android.application`
            // variants — AGP 9.x libraries expose the resource pipeline through
            // `merge${Cap}Resources` / `generate${Cap}RFile` / the unit-test-
            // specific `process${Cap}UnitTestResources`. The unit-test resource
            // APK we actually consume is already routed via
            // `generate${Cap}UnitTestConfig` below, so the `processResources`
            // dep is just belt-and-suspenders; skip it when absent so library
            // modules configure cleanly. See issue #136.
            listOf(
                "process${capVariant}Resources",
                "generate${capVariant}UnitTestConfig",
            ).forEach { taskName ->
                if (project.tasks.findByName(taskName) != null) {
                    dependsOn(taskName)
                }
            }
            if (compileShardsTask != null) {
                dependsOn(compileShardsTask)
            }
        }

        // `verifyAccessibility` is ALWAYS registered so toggling
        // `-PcomposePreview.accessibilityChecks.enabled` doesn't change the
        // task graph — config cache stays valid across VSCode / CLI toggles.
        // An `onlyIf` gate backed by the lazy provider makes it a no-op when
        // the feature is off: the task configures but never executes, so the
        // JSON aggregation and failure thresholds only kick in when the user
        // actually opted in.
        val a11yEnabledProvider = resolveA11yEnabled(project, extension)
        val verifyA11yTask = project.tasks.register(
            "verifyAccessibility", VerifyAccessibilityTask::class.java,
        ) {
            group = "compose preview"
            description = "Aggregate ATF findings from renderPreviews and fail per configured thresholds"
            perPreviewDir.set(accessibilityPerPreviewDir)
            reportFile.set(accessibilityReportFile)
            moduleName.set(project.name)
            failOnErrors.set(extension.accessibilityChecks.failOnErrors)
            failOnWarnings.set(extension.accessibilityChecks.failOnWarnings)
            dependsOn(renderTask)
            onlyIf("composePreview.accessibilityChecks.enabled") { a11yEnabledProvider.get() }
        }

        ComposePreviewTasks.registerRenderAllPreviews(
            project, extension, renderTask, previewOutputDir, verifyA11yTask,
        )
    }

    /**
     * Lazy holder for ATF-related system properties on the `renderPreviews`
     * `Test` task. Using a CommandLineArgumentProvider — instead of
     * `test.systemProperty(...)` — means the values are resolved at task
     * execution time, so flipping the underlying Gradle property doesn't
     * invalidate the configuration cache. Each `@Input` participates in the
     * task's own up-to-date check, so toggling a11y correctly re-runs
     * rendering.
     */
    internal class AccessibilitySystemPropsProvider(
        @get:org.gradle.api.tasks.Input val enabled: org.gradle.api.provider.Provider<Boolean>,
        @get:org.gradle.api.tasks.Input val annotate: org.gradle.api.provider.Provider<Boolean>,
        @get:org.gradle.api.tasks.Input val outputDir: org.gradle.api.provider.Provider<String>,
        @get:org.gradle.api.tasks.Input val debug: org.gradle.api.provider.Provider<String>,
    ) : org.gradle.process.CommandLineArgumentProvider {
        override fun asArguments(): Iterable<String> = listOf(
            "-Dcomposeai.a11y.enabled=${enabled.get()}",
            "-Dcomposeai.a11y.annotate=${annotate.get()}",
            "-Dcomposeai.a11y.outputDir=${outputDir.get()}",
            "-Dcomposeai.a11y.debug=${debug.get()}",
        )
    }

    /**
     * Returns a lazy `Provider<Boolean>` for the effective
     * `accessibilityChecks.enabled` value. The
     * `-PcomposePreview.accessibilityChecks.enabled=<true|false>` Gradle
     * property WINS over the extension block — so VSCode (or a one-off CLI
     * invocation) can flip the feature on for a single run without touching
     * `build.gradle.kts`.
     *
     * **Deliberately returns a Provider, not a Boolean.** Reading `.get()` at
     * configuration time keys the configuration cache on the current property
     * value, which means every VSCode toggle would invalidate the cache and
     * pay a ~5-10s reconfigure cost. Consumers should pass this provider to
     * task `onlyIf`, `CommandLineArgumentProvider` inputs, etc. — those
     * evaluate it at task-graph-resolution time without cache invalidation.
     */
    internal fun resolveA11yEnabled(
        project: org.gradle.api.Project,
        extension: PreviewExtension,
    ): org.gradle.api.provider.Provider<Boolean> =
        project.providers
            .gradleProperty("composePreview.accessibilityChecks.enabled")
            .map { it.toBooleanStrictOrNull() ?: false }
            .orElse(extension.accessibilityChecks.enabled)

    /** Same config-cache-friendly treatment for `annotateScreenshots`. See [resolveA11yEnabled]. */
    internal fun resolveA11yAnnotate(
        project: org.gradle.api.Project,
        extension: PreviewExtension,
    ): org.gradle.api.provider.Provider<Boolean> =
        project.providers
            .gradleProperty("composePreview.accessibilityChecks.annotateScreenshots")
            .map { it.toBooleanStrictOrNull() ?: true }
            .orElse(extension.accessibilityChecks.annotateScreenshots)

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
