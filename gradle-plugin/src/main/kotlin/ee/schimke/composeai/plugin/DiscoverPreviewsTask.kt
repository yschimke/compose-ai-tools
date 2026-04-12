package ee.schimke.composeai.plugin

import io.github.classgraph.AnnotationInfo
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import io.github.classgraph.MethodInfo
import io.github.classgraph.ScanResult
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class DiscoverPreviewsTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirs: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencyJars: ConfigurableFileCollection

    @get:Input
    abstract val moduleName: Property<String>

    @get:Input
    abstract val variantName: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    companion object {
        private val PREVIEW_FQNS = setOf(
            "androidx.compose.ui.tooling.preview.Preview",
            "androidx.compose.desktop.ui.tooling.preview.Preview",
        )
        private val CONTAINER_FQNS = setOf(
            "androidx.compose.ui.tooling.preview.Preview\$Container",
            "androidx.compose.ui.tooling.preview.Preview.Container",
        )
    }

    @TaskAction
    fun discover() {
        val existingClassDirs = classDirs.files.filter { it.exists() && it.isDirectory }
        val classpath = existingClassDirs + dependencyJars.files.filter { file ->
            val name = file.name.lowercase()
            file.exists() && name.endsWith(".jar") &&
                (name.contains("preview") || name.contains("tooling") ||
                    name.contains("compose") || name.contains("annotation"))
        }

        val previews = mutableListOf<PreviewInfo>()

        if (classpath.isNotEmpty()) {
            ClassGraph()
                .enableMethodInfo()
                .enableAnnotationInfo()
                .overrideClasspath(classpath.map { it.absolutePath })
                .ignoreParentClassLoaders()
                .scan().use { scanResult ->
                    for (classInfo in scanResult.allClasses) {
                        for (method in classInfo.methodInfo) {
                            val annotations = method.annotationInfo ?: continue
                            discoverFromMethod(classInfo, method, annotations.toList(), scanResult, previews)
                        }
                    }
                }
        }

        val deduped = previews.distinctBy {
            "${it.id}_${it.params.name}_${it.params.device}_${it.params.widthDp}_${it.params.heightDp}"
        }

        val manifest = PreviewManifest(
            module = moduleName.get(),
            variant = variantName.get(),
            previews = deduped,
        )

        val outFile = outputFile.get().asFile
        outFile.parentFile.mkdirs()
        outFile.writeText(json.encodeToString(manifest))

        logger.lifecycle("Discovered ${deduped.size} preview(s) in module '${moduleName.get()}':")
        for (preview in deduped) {
            val dims = if (preview.params.widthDp > 0 && preview.params.heightDp > 0) {
                " ${preview.params.widthDp}x${preview.params.heightDp}dp"
            } else ""
            val label = preview.params.name?.let { " ($it)" } ?: ""
            logger.lifecycle("  ${preview.className}.${preview.functionName}$label$dims")
        }
    }

    private fun discoverFromMethod(
        classInfo: ClassInfo,
        method: MethodInfo,
        annotations: List<AnnotationInfo>,
        scanResult: ScanResult,
        previews: MutableList<PreviewInfo>,
    ) {
        val directPreviews = collectDirectPreviews(annotations)
        if (directPreviews.isNotEmpty()) {
            for (ann in directPreviews) {
                previews.add(makePreview(classInfo, method, ann))
            }
            return
        }

        for (ann in annotations) {
            val resolved = resolveMultiPreview(ann, scanResult, mutableSetOf())
            for (resolvedAnn in resolved) {
                previews.add(makePreview(classInfo, method, resolvedAnn))
            }
        }
    }

    private fun isDirectPreview(ann: AnnotationInfo): Boolean =
        ann.name in PREVIEW_FQNS

    private fun isPreviewContainer(ann: AnnotationInfo): Boolean =
        ann.name in CONTAINER_FQNS

    private fun collectDirectPreviews(annotations: List<AnnotationInfo>): List<AnnotationInfo> {
        val result = mutableListOf<AnnotationInfo>()
        for (ann in annotations) {
            when {
                isDirectPreview(ann) -> result.add(ann)
                isPreviewContainer(ann) -> {
                    val value = ann.parameterValues.getValue("value")
                    when (value) {
                        is Array<*> -> value.filterIsInstance<AnnotationInfo>().forEach { result.add(it) }
                        is AnnotationInfo -> result.add(value)
                        else -> {
                            val len = java.lang.reflect.Array.getLength(value)
                            for (i in 0 until len) {
                                val elem = java.lang.reflect.Array.get(value, i)
                                if (elem is AnnotationInfo) result.add(elem)
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    private fun resolveMultiPreview(
        ann: AnnotationInfo,
        scanResult: ScanResult,
        visited: MutableSet<String>,
    ): List<AnnotationInfo> {
        if (ann.name in visited) return emptyList()
        if (isDirectPreview(ann) || isPreviewContainer(ann)) return emptyList()
        visited.add(ann.name)

        val annClassInfo = scanResult.getClassInfo(ann.name) ?: return emptyList()
        val directPreviews = collectDirectPreviews(annClassInfo.annotationInfo.toList())
        if (directPreviews.isNotEmpty()) return directPreviews

        val result = mutableListOf<AnnotationInfo>()
        for (metaAnn in annClassInfo.annotationInfo) {
            result.addAll(resolveMultiPreview(metaAnn, scanResult, visited))
        }
        return result
    }

    private fun makePreview(classInfo: ClassInfo, method: MethodInfo, ann: AnnotationInfo): PreviewInfo {
        val params = extractPreviewParams(ann)
        val fqn = "${classInfo.name}.${method.name}"
        val suffix = if (!params.name.isNullOrBlank()) "_${params.name}" else ""
        return PreviewInfo(
            id = fqn + suffix,
            functionName = method.name,
            className = classInfo.name,
            sourceFile = classInfo.sourceFile,
            params = params,
            renderOutput = "renders/${fqn}${suffix}.png",
        )
    }

    private fun extractPreviewParams(ann: AnnotationInfo): PreviewParams {
        val pv = ann.parameterValues
        return PreviewParams(
            name = (pv.getValue("name") as? String)?.ifBlank { null },
            device = (pv.getValue("device") as? String)?.ifBlank { null },
            widthDp = (pv.getValue("widthDp") as? Int)?.takeIf { it > 0 } ?: 0,
            heightDp = (pv.getValue("heightDp") as? Int)?.takeIf { it > 0 } ?: 0,
            fontScale = (pv.getValue("fontScale") as? Float)?.takeIf { it > 0 } ?: 1.0f,
            showSystemUi = (pv.getValue("showSystemUi") as? Boolean) ?: false,
            showBackground = (pv.getValue("showBackground") as? Boolean) ?: false,
            backgroundColor = (pv.getValue("backgroundColor") as? Long) ?: 0L,
            uiMode = (pv.getValue("uiMode") as? Int)?.takeIf { it > 0 } ?: 0,
            locale = (pv.getValue("locale") as? String)?.ifBlank { null },
            group = (pv.getValue("group") as? String)?.ifBlank { null },
        )
    }
}
