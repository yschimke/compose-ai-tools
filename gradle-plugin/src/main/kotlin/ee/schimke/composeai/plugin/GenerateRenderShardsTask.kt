package ee.schimke.composeai.plugin

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Generates `RobolectricRenderTest_ShardN` subclasses (one per shard) in Java. Each
 * subclass declares its own static `@Parameters` method that calls
 * `PreviewManifestLoader.loadShard(N, shardCount)`, so the `ParameterizedRobolectricTestRunner`
 * sees a distinct parameter set per class. Gradle's test fork distribution happens at the
 * class level, so N classes → N parallel JVMs when `maxParallelForks = N`.
 *
 * Java (not Kotlin) deliberately: avoids pulling a Kotlin compiler into the plugin's
 * generated-source path. `javac --release 21` is enough since the base class is a plain
 * JVM class from the plugin's perspective.
 */
@CacheableTask
abstract class GenerateRenderShardsTask : DefaultTask() {
    @get:Input
    abstract val shards: Property<Int>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val count = shards.get()
        val dir = outputDir.get().asFile.resolve("ee/schimke/composeai/renderer")
        dir.deleteRecursively()
        dir.mkdirs()

        for (i in 0 until count) {
            val className = "RobolectricRenderTest_Shard$i"
            val source = """
                |package ee.schimke.composeai.renderer;
                |
                |import java.util.List;
                |import org.junit.runner.RunWith;
                |import org.robolectric.ParameterizedRobolectricTestRunner;
                |
                |/**
                | * Auto-generated shard $i of $count. Do not edit.
                | * Inherits @Config(sdk = 34) and @GraphicsMode(NATIVE) from
                | * RobolectricRenderTestBase so every shard's sandbox key matches
                | * — each fork reuses its cached sandbox across its slice of previews.
                | */
                |@RunWith(ParameterizedRobolectricTestRunner.class)
                |public class $className extends RobolectricRenderTestBase {
                |    public $className(RenderPreviewEntry preview) { super(preview); }
                |
                |    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
                |    public static List<Object[]> previews() {
                |        return PreviewManifestLoader.loadShard($i, $count);
                |    }
                |}
                |""".trimMargin()
            dir.resolve("$className.java").writeText(source)
        }
        logger.lifecycle("Generated $count shard test subclasses in $dir")
    }
}
