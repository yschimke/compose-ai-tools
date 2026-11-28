package ee.schimke.composeai.plugin

import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewKind
import ee.schimke.composeai.discovery.PreviewManifest
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import javax.imageio.ImageIO
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.problems.Problems
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.TestEventReporterFactory

/**
 * Interop between Compose Preview's Robolectric renderer and AGP's Compose Preview Screenshot
 * Testing plugin (`com.android.compose.screenshot`).
 *
 * Uses Google's `src/screenshotTest<Variant>/reference/<package>/<Class>/<fn>_*.png` layout so a
 * module's `@PreviewTest` suite and golden references can be validated or refreshed with
 * `composePreviewValidateScreenshotTest` / `composePreviewUpdateScreenshotTest` without requiring
 * Layoutlib or Android Studio.
 */
internal object ScreenshotTestInterop {
  const val VALIDATE_TASK_NAME: String = "composePreviewValidateScreenshotTest"
  const val UPDATE_TASK_NAME: String = "composePreviewUpdateScreenshotTest"
  const val DEFAULT_DIFF_THRESHOLD: Double = 0.01
  const val DEFAULT_CHANNEL_TOLERANCE: Int = 2

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }

  /**
   * Registers `composePreviewValidateScreenshotTest` and `composePreviewUpdateScreenshotTest` when
   * `com.android.compose.screenshot` is applied, a `src/screenshotTest<Variant>/reference/`
   * directory exists, or `composePreview.screenshotTest.referenceDir` is configured.
   */
  fun registerTasks(
    project: Project,
    extension: PreviewExtension,
    variantName: String,
    capVariant: String,
    screenshotTestPluginApplied: Boolean,
    manifestFileProvider: Provider<RegularFile>,
    rendersDirProvider: Provider<Directory>,
    renderTask: TaskProvider<*>,
    partialRenderReasonProvider: Provider<String> = project.providers.provider { "" },
    renderTestResultsDirProvider: Provider<Directory>? = null,
  ) {
    if (!extension.enabled.get() || !extension.screenshotTest.enabled.get()) return

    val defaultRefDir =
      project.layout.projectDirectory.dir("src/screenshotTest${capVariant}/reference")
    val hasExplicitRefDir = extension.screenshotTest.referenceDir.isPresent
    if (!screenshotTestPluginApplied && !hasExplicitRefDir && !defaultRefDir.asFile.isDirectory) {
      return
    }

    val providers = project.providers
    val agpThreshold =
      if (screenshotTestPluginApplied) readAgpScreenshotThreshold(project) else null
    val propThreshold =
      providers.gradleProperty("composePreview.screenshotDiffThreshold").map {
        it.trim().toDouble()
      }
    val effectiveThreshold =
      extension.screenshotTest.imageDifferenceThreshold
        .orElse(propThreshold)
        .orElse(agpThreshold ?: DEFAULT_DIFF_THRESHOLD)

    val propChannelTolerance =
      providers.gradleProperty("composePreview.screenshotChannelTolerance").map {
        it.trim().toInt()
      }
    val effectiveChannelTolerance =
      extension.screenshotTest.channelTolerance
        .orElse(propChannelTolerance)
        .orElse(DEFAULT_CHANNEL_TOLERANCE)

    val effectiveReferenceDir = extension.screenshotTest.referenceDir.orElse(defaultRefDir)

    val reportsRoot = project.layout.buildDirectory.dir("reports/compose-previews/screenshot-test")
    val testBinaryDir = project.layout.buildDirectory.dir("test-results/$VALIDATE_TASK_NAME/binary")
    val testHtmlDir = project.layout.buildDirectory.dir("reports/tests/$VALIDATE_TASK_NAME")

    val validateTask =
      project.tasks.register(VALIDATE_TASK_NAME, ValidateScreenshotTestTask::class.java) {
        group = "verification"
        description =
          "Validate @PreviewTest renders against AGP screenshotTest reference PNGs under " +
            "src/screenshotTest${capVariant}/reference"
        dependsOn(renderTask)
        manifestFile.set(manifestFileProvider)
        rendersDir.set(rendersDirProvider)
        if (renderTestResultsDirProvider != null) {
          renderTestResultsDir.set(renderTestResultsDirProvider)
        }
        referenceDir.set(
          providers.provider { effectiveReferenceDir.get().takeIf { it.asFile.isDirectory } },
        )
        referenceDirFallbackPath.set(effectiveReferenceDir.map { it.asFile.absolutePath })
        imageDifferenceThreshold.set(effectiveThreshold)
        channelTolerance.set(effectiveChannelTolerance)
        failOnMissingReferences.set(extension.screenshotTest.failOnMissingReferences)
        requirePreviewTestAnnotation.set(extension.screenshotTest.requirePreviewTestAnnotation)
        partialRenderReason.set(partialRenderReasonProvider)
        modulePath.set(project.path)
        this.variantName.set(variantName)
        reportFile.set(reportsRoot.map { it.file("report.json") })
        diffsDir.set(reportsRoot.map { it.dir("diffs") })
        testBinaryResultsDir.set(testBinaryDir)
        testHtmlReportDir.set(testHtmlDir)
      }

    project.tasks.register(UPDATE_TASK_NAME, UpdateScreenshotTestTask::class.java) {
      group = "compose preview"
      description =
        "Update AGP screenshotTest reference PNGs under src/screenshotTest${capVariant}/reference " +
          "from Compose Preview renders"
      dependsOn(renderTask)
      manifestFile.set(manifestFileProvider)
      rendersDir.set(rendersDirProvider)
      if (renderTestResultsDirProvider != null) {
        renderTestResultsDir.set(renderTestResultsDirProvider)
      }
      referenceDirPath.set(effectiveReferenceDir.map { it.asFile.absolutePath })
      requirePreviewTestAnnotation.set(extension.screenshotTest.requirePreviewTestAnnotation)
      partialRenderReason.set(partialRenderReasonProvider)
      outputs.upToDateWhen { false }
    }

    if (extension.screenshotTest.validateInCheck.get()) {
      project.tasks.matching { it.name == "check" }.configureEach { dependsOn(validateTask) }
    }
  }

  /**
   * Reflectively reads `imageDifferenceThreshold` from AGP's `android.testOptions` /
   * `screenshotTests` block when `com.android.compose.screenshot` is applied, without taking a
   * compile-time dependency on Google's alpha plugin.
   */
  internal fun readAgpScreenshotThreshold(project: Project): Double? = runCatching {
    val androidExt = project.extensions.findByName("android") ?: return@runCatching null
    val testOptions =
      androidExt.javaClass.methods
        .firstOrNull { it.name == "getTestOptions" && it.parameterCount == 0 }
        ?.invoke(androidExt) ?: return@runCatching null
    val screenshotTests =
      testOptions.javaClass.methods
        .firstOrNull { it.name == "getScreenshotTests" && it.parameterCount == 0 }
        ?.invoke(testOptions)
        ?: (testOptions as? org.gradle.api.plugins.ExtensionAware)
          ?.extensions
          ?.findByName("screenshotTests")
        ?: return@runCatching null
    val raw =
      screenshotTests.javaClass.methods
        .firstOrNull { it.name == "getImageDifferenceThreshold" && it.parameterCount == 0 }
        ?.invoke(screenshotTests)
    when (raw) {
      is Number -> raw.toDouble()
      is Provider<*> -> (raw.orNull as? Number)?.toDouble()
      else -> null
    }
  }
    .getOrNull()

  internal fun parseManifest(manifestFile: File): PreviewManifest =
    json.decodeFromString(PreviewManifest.serializer(), manifestFile.readText())

  internal fun writeReport(reportFile: File, report: ScreenshotTestReport) {
    reportFile.parentFile?.mkdirs()
    reportFile.writeText(json.encodeToString(report))
  }

  /**
   * Selects previews participating in screenshot-test validation/update.
   *
   * When any `@PreviewTest`-annotated preview exists (or [requirePreviewTestAnnotation] is true),
   * only those previews are selected — matching `com.android.compose.screenshot` alpha15+.
   * Otherwise, previews whose `sourceFile` lives under `src/screenshotTest/` are selected.
   */
  internal fun selectScreenshotTestPreviews(
    previews: List<PreviewInfo>,
    requirePreviewTestAnnotation: Boolean,
  ): List<PreviewInfo> {
    val renderable = previews.filter {
      it.params.kind == PreviewKind.COMPOSE ||
        it.params.kind == PreviewKind.TILE ||
        it.params.kind == PreviewKind.GLANCE_APPWIDGET ||
        it.params.kind == PreviewKind.NOTIFICATION
    }
    val annotated = renderable.filter { it.previewTest }
    if (requirePreviewTestAnnotation || annotated.isNotEmpty()) {
      return annotated
    }
    return renderable.filter { preview ->
      val source = preview.sourceFile?.replace('\\', '/') ?: return@filter false
      source.startsWith("src/screenshotTest/") || source.contains("/src/screenshotTest/")
    }
  }

  internal data class RenderedCapture(
    val index: Int,
    val rowToken: String?,
    val file: File,
    val captureKey: String? = null,
    val providerIndex: Int? = index,
  )

  private const val PARAM_INDEX_PREFIX = "PARAM_"
  private const val UNSHARDED_RENDER_XML =
    "TEST-ee.schimke.composeai.renderer.RobolectricRenderTest.xml"

  private val rowTokenOrder: Comparator<String> = Comparator { a, b ->
    val ia =
      a.removePrefix(PARAM_INDEX_PREFIX).toIntOrNull()?.takeIf { a.startsWith(PARAM_INDEX_PREFIX) }
    val ib =
      b.removePrefix(PARAM_INDEX_PREFIX).toIntOrNull()?.takeIf { b.startsWith(PARAM_INDEX_PREFIX) }
    when {
      ia != null && ib != null -> ia.compareTo(ib)
      ia != null -> -1
      ib != null -> 1
      else -> a.compareTo(b)
    }
  }

  /**
   * Recovers exact 0-based `PreviewParameterProvider` indices for labelled fan-out rows from the
   * unsharded `composePreviewRender` JUnit XML (`RobolectricRenderTest`).
   *
   * When `shards == 1`, `PreviewManifestLoader.loadShard` emits parameterized rows in exact
   * provider iteration order (`0, 1, 2, ...`). When `shards > 1`, `assignToShard` re-sorts rows by
   * cost and id across shard XMLs, so provider order cannot be inferred from JUnit XML — returns
   * empty in that case so callers never silently pair swapped labels with Google numeric goldens.
   */
  internal fun recoverProviderIndicesFromJunitXml(
    renderTestResultsDir: File?,
    previews: List<PreviewInfo>,
  ): Map<String, Map<String, Int>> {
    if (renderTestResultsDir == null || !renderTestResultsDir.isDirectory) return emptyMap()
    val xmlFiles =
      renderTestResultsDir.listFiles()?.filter { it.isFile && it.extension.equals("xml", true) }
        ?: return emptyMap()
    if (xmlFiles.any { it.name.contains("RobolectricRenderTest_Shard") }) {
      return emptyMap()
    }
    val unshardedXml = xmlFiles.firstOrNull { it.name == UNSHARDED_RENDER_XML } ?: return emptyMap()

    val testcaseIds = runCatching {
      val factory =
        javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
          setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
          setFeature("http://xml.org/sax/features/external-general-entities", false)
          setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
      val doc = factory.newDocumentBuilder().parse(unshardedXml)
      val nodes = doc.getElementsByTagName("testcase")
      buildList {
        for (i in 0 until nodes.length) {
          val nameAttr = nodes.item(i).attributes?.getNamedItem("name")?.nodeValue ?: continue
          val start = nameAttr.indexOf('[')
          val end = nameAttr.lastIndexOf(']')
          if (start >= 0 && end > start + 1) {
            add(nameAttr.substring(start + 1, end))
          }
        }
      }
    }
      .getOrElse {
        return emptyMap()
      }

    val allIds = previews.map { it.id }.toSet()
    val paramPreviews =
      previews
        .filter { !it.params.previewParameterProviderClassName.isNullOrBlank() }
        .sortedByDescending { it.id.length }
    if (paramPreviews.isEmpty()) return emptyMap()

    val counters = mutableMapOf<String, Int>()
    val result = mutableMapOf<String, MutableMap<String, Int>>()
    for (tcId in testcaseIds) {
      if (tcId in allIds) continue
      // Longest-prefix match prevents `Foo` from claiming `Foo_Dark`'s row `Foo_Dark_Alpha`.
      val owner =
        paramPreviews.firstOrNull { p ->
          tcId.startsWith("${p.id}_") && tcId.length > p.id.length + 1
        } ?: continue
      val token = tcId.substring(owner.id.length + 1)
      val nextIdx = counters[owner.id] ?: 0
      counters[owner.id] = nextIdx + 1
      val map = result.getOrPut(owner.id) { linkedMapOf() }
      map.putIfAbsent(token, nextIdx)
    }
    return result
  }

  /**
   * Resolves rendered PNG files for [preview]. Handles single-capture previews (`<stem>.png`),
   * multi-capture manifests (resolving `@PreviewParameter` fan-outs per capture), and
   * `@PreviewParameter` fan-outs (`<stem>_PARAM_<idx>.png` and labelled `<stem>_<label>.png`),
   * excluding files claimed by sibling previews or other captures.
   */
  internal fun resolveRenderedCaptures(
    preview: PreviewInfo,
    rendersDir: File,
    siblingOutputs: Set<String> = emptySet(),
    providerIndices: Map<String, Int> = emptyMap(),
  ): List<RenderedCapture> {
    val pngCaptures = preview.captures.filter { it.renderOutput.endsWith(".png") }
    if (pngCaptures.isEmpty()) return emptyList()

    val multiCapture = pngCaptures.size > 1
    val captureKeys =
      if (multiCapture) deriveCaptureKeys(pngCaptures.map { it.renderOutput })
      else listOf<String?>(null)

    val resolved = mutableListOf<RenderedCapture>()
    for ((captureIdx, capture) in pngCaptures.withIndex()) {
      val templateOutput = capture.renderOutput
      val captureKey = captureKeys[captureIdx]
      val captureSiblings =
        if (multiCapture) {
          siblingOutputs +
            pngCaptures.filterIndexed { i, _ -> i != captureIdx }.map { it.renderOutput }
        } else {
          siblingOutputs
        }
      resolved +=
        resolveSingleCaptureFanout(
          templateOutput = templateOutput,
          rendersDir = rendersDir,
          siblingOutputs = captureSiblings,
          captureKey = captureKey,
          providerIndices = providerIndices,
        )
    }
    return resolved
  }

  private fun deriveCaptureKeys(renderOutputs: List<String>): List<String> {
    val stems = renderOutputs.map { File(it.removePrefix("renders/")).nameWithoutExtension }
    val rawCommon = stems.reduce { acc, s -> acc.commonPrefixWith(s) }
    val boundaryIdx = rawCommon.lastIndexOfAny(charArrayOf('_', '-'))
    val commonPrefix = if (boundaryIdx >= 0) rawCommon.substring(0, boundaryIdx + 1) else ""
    val rawKeys = stems.mapIndexed { idx, stem ->
      val suffix =
        if (commonPrefix.isNotEmpty() && stem.length > commonPrefix.length) {
          stem.removePrefix(commonPrefix).trimStart('_', '-')
        } else {
          ""
        }
      sanitizeSegment(suffix).ifEmpty { "cap$idx" }
    }
    return if (rawKeys.distinct().size == rawKeys.size) {
      rawKeys
    } else {
      rawKeys.mapIndexed { idx, key -> "${key}_$idx" }
    }
  }

  private fun resolveSingleCaptureFanout(
    templateOutput: String,
    rendersDir: File,
    siblingOutputs: Set<String>,
    captureKey: String?,
    providerIndices: Map<String, Int>,
  ): List<RenderedCapture> {
    val rel = templateOutput.removePrefix("renders/")
    val exact = File(rendersDir, rel)
    if (exact.isFile) {
      return listOf(
        RenderedCapture(
          index = 0,
          rowToken = null,
          file = exact,
          captureKey = captureKey,
          providerIndex = 0,
        ),
      )
    }

    val stem = exact.nameWithoutExtension
    val parent = exact.parentFile ?: rendersDir
    val dirPrefix =
      templateOutput.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
    val prefix = "${stem}_"
    val ext = ".png"

    val rawRows =
      parent
        .listFiles()
        .orEmpty()
        .asSequence()
        .filter { file ->
          file.isFile &&
            file.name.startsWith(prefix) &&
            file.name.endsWith(ext) &&
            file.name.length > prefix.length + ext.length
        }
        .map { file ->
          val token = file.name.substring(prefix.length, file.name.length - ext.length)
          val candidateOutput = dirPrefix + file.name
          Triple(token, candidateOutput, file)
        }
        .filter { (_, candidateOutput, _) ->
          candidateOutput !in siblingOutputs &&
            siblingOutputs.none { sibling ->
              isOwnedByMoreSpecificSibling(
                templateOutput = templateOutput,
                siblingOutput = sibling,
                candidateOutput = candidateOutput,
              )
            }
        }
        .distinctBy { (token, _, _) -> token }
        .sortedWith(compareBy(rowTokenOrder) { (token, _, _) -> token })
        .toList()

    if (rawRows.isEmpty()) {
      return listOf(
        RenderedCapture(
          index = 0,
          rowToken = null,
          file = exact,
          captureKey = captureKey,
          providerIndex = 0,
        ),
      )
    }

    // Reserve explicit PARAM_<idx> and recovered JUnit XML provider indices.
    val usedIndices = mutableSetOf<Int>()
    for ((token, _, _) in rawRows) {
      val paramIdx =
        token.removePrefix(PARAM_INDEX_PREFIX).toIntOrNull()?.takeIf {
          token.startsWith(PARAM_INDEX_PREFIX)
        }
      if (paramIdx != null) usedIndices += paramIdx
      providerIndices[token]?.let { usedIndices += it }
    }

    var nextFallbackIndex = 0
    val captures = rawRows.map { (token, _, file) ->
      val paramIdx =
        token.removePrefix(PARAM_INDEX_PREFIX).toIntOrNull()?.takeIf {
          token.startsWith(PARAM_INDEX_PREFIX)
        }
      if (paramIdx != null) {
        RenderedCapture(
          index = paramIdx,
          rowToken = null,
          file = file,
          captureKey = captureKey,
          providerIndex = paramIdx,
        )
      } else {
        val authoritativeIdx = providerIndices[token] ?: if (rawRows.size == 1) 0 else null
        val displayIdx =
          if (authoritativeIdx != null) {
            authoritativeIdx
          } else {
            while (nextFallbackIndex in usedIndices) nextFallbackIndex++
            val idx = nextFallbackIndex++
            usedIndices += idx
            idx
          }
        RenderedCapture(
          index = displayIdx,
          rowToken = token,
          file = file,
          captureKey = captureKey,
          providerIndex = authoritativeIdx,
        )
      }
    }

    return if (captures.all { it.providerIndex != null }) {
      captures.sortedBy { it.providerIndex }
    } else {
      captures
    }
  }

  private fun isOwnedByMoreSpecificSibling(
    templateOutput: String,
    siblingOutput: String,
    candidateOutput: String,
  ): Boolean {
    if (siblingOutput == templateOutput) return false
    val templateDir = templateOutput.substringBeforeLast('/', "")
    val siblingDir = siblingOutput.substringBeforeLast('/', "")
    if (templateDir != siblingDir) return false
    val templateLeaf = templateOutput.substringAfterLast('/')
    val siblingLeaf = siblingOutput.substringAfterLast('/')
    val candidateLeaf = candidateOutput.substringAfterLast('/')
    val templateDot = templateLeaf.lastIndexOf('.')
    val siblingDot = siblingLeaf.lastIndexOf('.')
    if (templateDot <= 0 || siblingDot <= 0) return false
    val templateStem = templateLeaf.substring(0, templateDot)
    val siblingStem = siblingLeaf.substring(0, siblingDot)
    return siblingStem.startsWith("${templateStem}_") && candidateLeaf.startsWith("${siblingStem}_")
  }

  /**
   * Deterministic 8-hex-character configuration digest distinguishing multiple `@Preview`
   * configurations on the same function (e.g. multiple unnamed previews or same-named previews
   * differing by `uiMode`, `device`, `fontScale`, `locale`, etc.).
   */
  internal fun configHash(preview: PreviewInfo): String {
    val canonical =
      listOf(
          preview.id,
          preview.className,
          preview.functionName,
          json.encodeToString(
            ee.schimke.composeai.discovery.PreviewParams.serializer(),
            preview.params,
          ),
        )
        .joinToString("\u0000")
    val digest =
      java.security.MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.take(4).joinToString("") { "%02x".format(it) }
  }

  internal fun shortHash(raw: String): String {
    val digest =
      java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.take(4).joinToString("") { "%02x".format(it) }
  }

  /**
   * Resolves the reference PNG path under [referenceDir] matching `com.android.compose.screenshot`
   * directory conventions (`<package/path>/<ClassName>/...`).
   *
   * When [ambiguousName] is `false` (unique `(className, functionName, sanitizedName)` among
   * selected previews), existing Layoutlib references (`<fn>_<previewName>_<hex8>_<index>.png` or
   * `<fn>_<hex8>_<index>.png`) and unhashed legacy files are matched in place so teams can share
   * one golden directory across Google's screenshot plugin and Compose Preview. For labelled
   * `@PreviewParameter` rows, Google's numeric goldens are matched ONLY when [providerIndex] is
   * authoritatively known (recovered from unsharded render metadata or single-row fan-out) — never
   * from alphabetical filename order — and generated labelled reference filenames include a digest
   * of the raw [rowToken] so case-distinct labels (e.g. `Dark` vs `dark`) never collide.
   */
  internal fun resolveReferenceFile(
    referenceDir: File,
    className: String,
    functionName: String,
    previewName: String,
    index: Int,
    configHash: String = "",
    rowToken: String? = null,
    captureKey: String? = null,
    ambiguousName: Boolean = false,
    providerIndex: Int? = if (rowToken == null) index else null,
  ): File {
    val classDir = File(referenceDir, className.replace('.', File.separatorChar))
    val sanitizedName = sanitizeSegment(previewName)
    val sanitizedRow = rowToken?.let(::sanitizeSegment)?.takeIf { it.isNotEmpty() }
    val rowDigest = rowToken?.takeIf { it.isNotEmpty() }?.let(::shortHash)
    val sanitizedCapture = captureKey?.let(::sanitizeSegment)?.takeIf { it.isNotEmpty() }
    val normalizedHash = configHash.trim().lowercase()

    val tail =
      when {
        sanitizedRow != null && rowDigest != null -> "${sanitizedRow}_${rowDigest}.png"
        rowDigest != null -> "${rowDigest}.png"
        else -> "${index}.png"
      }
    val hashedParts = buildList {
      add(functionName)
      if (sanitizedName.isNotEmpty()) add(sanitizedName)
      if (sanitizedCapture != null) add(sanitizedCapture)
      if (normalizedHash.isNotEmpty()) add(normalizedHash)
    }
    val hashedFile = File(classDir, "${hashedParts.joinToString("_")}_$tail")
    if (hashedFile.isFile) return hashedFile

    val unhashedParts = buildList {
      add(functionName)
      if (sanitizedName.isNotEmpty()) add(sanitizedName)
      if (sanitizedCapture != null) add(sanitizedCapture)
    }
    val unhashedFile = File(classDir, "${unhashedParts.joinToString("_")}_$tail")

    // When multiple preview configurations on the same function share this name (or are both
    // unnamed), require the configHash-qualified filename so distinct configs never collapse.
    if (ambiguousName) {
      return hashedFile
    }

    if (unhashedFile.isFile) return unhashedFile

    val fallbackFile = if (normalizedHash.isNotEmpty()) hashedFile else unhashedFile
    if (sanitizedCapture != null) {
      // Multi-capture previews don't have a Google Layoutlib single-capture equivalent; keep
      // each capture isolated by its captureKey.
      return fallbackFile
    }

    val existing =
      classDir.listFiles()?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
        ?: return fallbackFile

    matchLayoutlibGolden(existing, functionName, sanitizedName, "_$tail")?.let {
      return it
    }

    // For labelled `@PreviewParameter` rows, match Google Layoutlib's numeric golden ONLY when the
    // exact provider index is known (never guessed from alphabetical filename order).
    if (rowToken != null && providerIndex != null) {
      matchLayoutlibGolden(existing, functionName, sanitizedName, "_${providerIndex}.png")?.let {
        return it
      }
    }

    return fallbackFile
  }

  /**
   * Returns true if [referenceDir] contains Google Layoutlib numeric golden PNGs
   * (`<fn>[_<previewName>]_<hexHash>_<digits>.png`) for the given preview. Used to fail loudly
   * instead of silently swapping/duplicating goldens when a sharded render drops provider order for
   * labelled `@PreviewParameter` rows.
   */
  internal fun hasNumericLayoutlibGoldens(
    referenceDir: File,
    className: String,
    functionName: String,
    previewName: String,
  ): Boolean {
    val classDir = File(referenceDir, className.replace('.', File.separatorChar))
    val existing =
      classDir.listFiles()?.filter { it.isFile && it.extension.equals("png", ignoreCase = true) }
        ?: return false
    val sanitizedName = sanitizeSegment(previewName)
    val numericSuffixRegex = Regex("^(.*)_(\\d+)\\.png$")
    val hashSuffixRegex = Regex("^(.*?)_[0-9a-fA-F]{6,12}$")
    val bareHashRegex = Regex("^[0-9a-fA-F]{6,12}$")

    return existing.any { file ->
      if (!file.name.startsWith("${functionName}_")) return@any false
      val match = numericSuffixRegex.matchEntire(file.name) ?: return@any false
      val beforeIndex = match.groupValues[1].removePrefix("${functionName}_")
      if (sanitizedName.isNotEmpty()) {
        val stripped =
          hashSuffixRegex.matchEntire(beforeIndex)?.groupValues?.get(1) ?: return@any false
        sanitizeSegment(stripped) == sanitizedName
      } else {
        bareHashRegex.matches(beforeIndex)
      }
    }
  }

  private fun matchLayoutlibGolden(
    existing: List<File>,
    functionName: String,
    sanitizedName: String,
    suffix: String,
  ): File? {
    val candidates = existing.filter { file ->
      file.name.startsWith("${functionName}_") && file.name.endsWith(suffix)
    }
    if (candidates.isEmpty()) return null

    return if (sanitizedName.isNotEmpty()) {
      // Match `<functionName>_<previewName>_<hexHash><suffix>` (Google Layoutlib convention).
      val hashSuffixRegex = Regex("^(.*?)_[0-9a-fA-F]{6,12}$")
      val byPreviewName = candidates.filter { file ->
        val middle = file.name.removePrefix("${functionName}_").removeSuffix(suffix)
        val stripped = hashSuffixRegex.matchEntire(middle)?.groupValues?.get(1) ?: middle
        sanitizeSegment(stripped) == sanitizedName
      }
      byPreviewName.singleOrNull()
    } else {
      // Unnamed @Preview: `<functionName>_<hexHash><suffix>`.
      val bareHashRegex = Regex("^[0-9a-fA-F]{6,12}$")
      val byBareHash = candidates.filter { file ->
        val middle = file.name.removePrefix("${functionName}_").removeSuffix(suffix)
        bareHashRegex.matches(middle)
      }
      byBareHash.singleOrNull()
    }
  }

  internal fun sanitizeSegment(raw: String): String =
    raw.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

  /** Compares two PNG images using per-channel tolerance and differing pixel fraction. */
  internal fun compareImages(
    reference: BufferedImage,
    actual: BufferedImage,
    threshold: Double,
    channelTolerance: Int,
  ): ImageComparison {
    if (reference.width != actual.width || reference.height != actual.height) {
      return ImageComparison(
        matched = false,
        referenceWidth = reference.width,
        referenceHeight = reference.height,
        actualWidth = actual.width,
        actualHeight = actual.height,
        diffFraction = 1.0,
        sizeMismatch = true,
      )
    }
    val w = reference.width
    val h = reference.height
    val total = w.toLong() * h.toLong()
    if (total == 0L) {
      return ImageComparison(
        matched = true,
        referenceWidth = w,
        referenceHeight = h,
        actualWidth = w,
        actualHeight = h,
        diffFraction = 0.0,
        sizeMismatch = false,
      )
    }
    var diffPixels = 0L
    for (y in 0 until h) {
      for (x in 0 until w) {
        if (pixelsDiffer(reference.getRGB(x, y), actual.getRGB(x, y), channelTolerance)) {
          diffPixels++
        }
      }
    }
    val fraction = diffPixels.toDouble() / total.toDouble()
    return ImageComparison(
      matched = fraction <= threshold,
      referenceWidth = w,
      referenceHeight = h,
      actualWidth = w,
      actualHeight = h,
      diffFraction = fraction,
      sizeMismatch = false,
    )
  }

  internal fun pixelsDiffer(argbA: Int, argbB: Int, tolerance: Int): Boolean {
    if (argbA == argbB) return false
    val aA = (argbA ushr 24) and 0xFF
    val aB = (argbB ushr 24) and 0xFF
    if (aA == 0 && aB == 0) return false
    val rA = (argbA ushr 16) and 0xFF
    val gA = (argbA ushr 8) and 0xFF
    val bA = argbA and 0xFF
    val rB = (argbB ushr 16) and 0xFF
    val gB = (argbB ushr 8) and 0xFF
    val bB = argbB and 0xFF
    return abs(aA - aB) > tolerance ||
      abs(rA - rB) > tolerance ||
      abs(gA - gB) > tolerance ||
      abs(bA - bB) > tolerance
  }

  /** Writes a side-by-side composite (`Reference | Actual` or `Reference | Actual | Diff`). */
  internal fun writeDiffComposite(
    outputFile: File,
    reference: BufferedImage,
    actual: BufferedImage,
    channelTolerance: Int,
  ) {
    val gap = 8
    val sameSize = reference.width == actual.width && reference.height == actual.height
    val width =
      if (sameSize) reference.width * 3 + gap * 2 else reference.width + actual.width + gap
    val height = maxOf(reference.height, actual.height)
    val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    try {
      g.color = Color(0x20, 0x20, 0x20, 0xFF)
      g.fillRect(0, 0, width, height)
      g.drawImage(reference, 0, 0, null)
      g.drawImage(actual, reference.width + gap, 0, null)
      if (sameSize) {
        val diffImg = BufferedImage(reference.width, reference.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until reference.height) {
          for (x in 0 until reference.width) {
            val a = reference.getRGB(x, y)
            val b = actual.getRGB(x, y)
            if (pixelsDiffer(a, b, channelTolerance)) {
              diffImg.setRGB(x, y, 0xFFFF00C8.toInt())
            } else {
              // Dim unchanged pixels so differences pop visually.
              val alpha = (a ushr 24) and 0xFF
              val r = ((a ushr 16) and 0xFF) / 3
              val gr = ((a ushr 8) and 0xFF) / 3
              val bl = (a and 0xFF) / 3
              diffImg.setRGB(x, y, (alpha shl 24) or (r shl 16) or (gr shl 8) or bl)
            }
          }
        }
        g.drawImage(diffImg, reference.width * 2 + gap * 2, 0, null)
      }
    } finally {
      g.dispose()
    }
    outputFile.parentFile?.mkdirs()
    ImageIO.write(out, "PNG", outputFile)
  }
}

internal data class ImageComparison(
  val matched: Boolean,
  val referenceWidth: Int,
  val referenceHeight: Int,
  val actualWidth: Int,
  val actualHeight: Int,
  val diffFraction: Double,
  val sizeMismatch: Boolean,
)

@Serializable
internal data class ScreenshotTestCaseReport(
  val id: String,
  val className: String,
  val functionName: String,
  val previewName: String,
  val index: Int,
  val status: String,
  val diffFraction: Double? = null,
  val message: String? = null,
  val referencePath: String? = null,
  val actualPath: String? = null,
  val diffPath: String? = null,
)

@Serializable
internal data class ScreenshotTestReport(
  val module: String,
  val variant: String,
  val threshold: Double,
  val channelTolerance: Int,
  val total: Int,
  val passed: Int,
  val failed: Int,
  val missingReferences: Int,
  val cases: List<ScreenshotTestCaseReport>,
)

@CacheableTask
abstract class ValidateScreenshotTestTask
@Inject
constructor(
  private val testEventReporterFactory: TestEventReporterFactory,
  private val problems: Problems,
) : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val manifestFile: RegularFileProperty

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val rendersDir: DirectoryProperty

  @get:InputDirectory
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val renderTestResultsDir: DirectoryProperty

  @get:InputDirectory
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val referenceDir: DirectoryProperty

  @get:Internal abstract val referenceDirFallbackPath: Property<String>

  @get:Input abstract val imageDifferenceThreshold: Property<Double>

  @get:Input abstract val channelTolerance: Property<Int>

  @get:Input abstract val failOnMissingReferences: Property<Boolean>

  @get:Input abstract val requirePreviewTestAnnotation: Property<Boolean>

  @get:Input @get:Optional abstract val partialRenderReason: Property<String>

  @get:Input abstract val modulePath: Property<String>

  @get:Input abstract val variantName: Property<String>

  @get:OutputFile abstract val reportFile: RegularFileProperty

  @get:OutputDirectory abstract val diffsDir: DirectoryProperty

  @get:OutputDirectory abstract val testBinaryResultsDir: DirectoryProperty

  @get:OutputDirectory abstract val testHtmlReportDir: DirectoryProperty

  @TaskAction
  fun validate() {
    val partialReason = partialRenderReason.orNull.orEmpty()
    if (partialReason.isNotBlank()) {
      val exception =
        GradleException(
          "Screenshot test validation for ${modulePath.get()} (${variantName.get()}) requires a " +
            "full, unfiltered render (partial render active: $partialReason). Re-run without " +
            "-PcomposePreview.tier=fast / filter / exclude flags.",
        )
      ComposePreviewProblems.reportValidationFailure(
        problems = problems,
        id = "screenshot-test-partial-render",
        displayName = "Compose screenshot test requires full render",
        contextualLabel =
          "${modulePath.get()} (${variantName.get()}): partial render ($partialReason)",
        details = exception.message ?: "",
        solutions = listOf("Re-run without -PcomposePreview.tier=fast, filter, or exclude flags"),
        exception = exception,
      )
      throw exception
    }

    val manifest = ScreenshotTestInterop.parseManifest(manifestFile.get().asFile)
    val rendersRoot = rendersDir.get().asFile
    val refRoot =
      referenceDir.orNull?.asFile
        ?: referenceDirFallbackPath.orNull?.let(::File)
        ?: File(rendersRoot.parentFile, "missing-reference")
    val threshold = imageDifferenceThreshold.get()
    val tolerance = channelTolerance.get()
    val requireRef = failOnMissingReferences.get()
    val diffsRoot = diffsDir.get().asFile
    diffsRoot.deleteRecursively()
    diffsRoot.mkdirs()

    val selected =
      ScreenshotTestInterop.selectScreenshotTestPreviews(
        previews = manifest.previews,
        requirePreviewTestAnnotation = requirePreviewTestAnnotation.get(),
      )

    if (selected.isEmpty() && refRoot.isDirectory) {
      val existingRefs =
        refRoot.walkTopDown().count { it.isFile && it.extension.equals("png", ignoreCase = true) }
      if (existingRefs > 0) {
        val message =
          "Found $existingRefs committed screenshot reference PNG(s) under ${refRoot.path}, " +
            "but 0 screenshot-test previews were discovered for ${modulePath.get()} " +
            "(${variantName.get()}). Ensure com.android.compose.screenshot is enabled " +
            "(e.g. -Pandroid.experimental.enableScreenshotTest=true) and that previews in " +
            "src/screenshotTest/ are annotated with @PreviewTest."
        val exception = GradleException(message)
        ComposePreviewProblems.reportValidationFailure(
          problems = problems,
          id = "screenshot-test-undiscovered-suite",
          displayName = "Compose screenshot test suite not discovered",
          contextualLabel =
            "${modulePath.get()} (${variantName.get()}): $existingRefs reference(s), 0 discovered",
          details = message,
          solutions =
            listOf(
              "Enable com.android.compose.screenshot (for example, pass -Pandroid.experimental.enableScreenshotTest=true)",
              "Verify screenshot-test previews under src/screenshotTest/ carry @com.android.tools.screenshot.PreviewTest",
            ),
          exception = exception,
        )
        throw exception
      }
    }

    val cases = mutableListOf<ScreenshotTestCaseReport>()
    val failures = mutableListOf<String>()
    val updateCmd =
      if (modulePath.get() == ":") {
        "./gradlew ${ScreenshotTestInterop.UPDATE_TASK_NAME}"
      } else {
        "./gradlew ${modulePath.get()}:${ScreenshotTestInterop.UPDATE_TASK_NAME}"
      }

    val testSession =
      PreviewTestEventSession.open(
        factory = testEventReporterFactory,
        binaryResultsDir = testBinaryResultsDir.orNull,
        htmlReportDir = testHtmlReportDir.orNull,
        rootName = ScreenshotTestInterop.VALIDATE_TASK_NAME,
      )

    var passedCount = 0
    var failedCount = 0
    var missingCount = 0

    val providerIndicesByPreview =
      ScreenshotTestInterop.recoverProviderIndicesFromJunitXml(
        renderTestResultsDir = renderTestResultsDir.orNull?.asFile,
        previews = manifest.previews,
      )

    val nameCounts =
      selected
        .groupingBy { preview ->
          Triple(
            preview.className,
            preview.functionName,
            ScreenshotTestInterop.sanitizeSegment(preview.params.name.orEmpty()),
          )
        }
        .eachCount()

    try {
      for (preview in selected) {
        val previewName = preview.params.name.orEmpty()
        val nameKey =
          Triple(
            preview.className,
            preview.functionName,
            ScreenshotTestInterop.sanitizeSegment(previewName),
          )
        val ambiguousName = (nameCounts[nameKey] ?: 0) > 1
        val hash = ScreenshotTestInterop.configHash(preview)
        val siblingOutputs =
          manifest.previews
            .asSequence()
            .filter { it.id != preview.id }
            .flatMap { it.captures.asSequence().map { capture -> capture.renderOutput } }
            .toSet()
        val captures =
          ScreenshotTestInterop.resolveRenderedCaptures(
            preview = preview,
            rendersDir = rendersRoot,
            siblingOutputs = siblingOutputs,
            providerIndices = providerIndicesByPreview[preview.id].orEmpty(),
          )
        for (capture in captures) {
          val index = capture.index
          val rowToken = capture.rowToken
          val captureKey = capture.captureKey
          val providerIndex = capture.providerIndex
          val actualFile = capture.file
          val startedAt = Instant.now()
          val refFile =
            ScreenshotTestInterop.resolveReferenceFile(
              referenceDir = refRoot,
              className = preview.className,
              functionName = preview.functionName,
              previewName = previewName,
              index = index,
              configHash = hash,
              rowToken = rowToken,
              captureKey = captureKey,
              ambiguousName = ambiguousName,
              providerIndex = providerIndex,
            )
          val testDisplayName = buildString {
            append(preview.functionName)
            if (previewName.isNotBlank()) append("[$previewName]")
            if (captureKey != null) append("[$captureKey]")
            if (ambiguousName) append("[$hash]")
            if (rowToken != null) {
              append("#$rowToken")
            } else if (index > 0 || captures.size > 1) {
              append("#$index")
            }
          }

          if (
            rowToken != null &&
              providerIndex == null &&
              captureKey == null &&
              !ambiguousName &&
              !refFile.isFile &&
              ScreenshotTestInterop.hasNumericLayoutlibGoldens(
                referenceDir = refRoot,
                className = preview.className,
                functionName = preview.functionName,
                previewName = previewName,
              )
          ) {
            failedCount++
            val msg =
              "Cannot map labelled @PreviewParameter row '$rowToken' to existing Google numeric " +
                "reference PNG(s) because provider order is unavailable from a sharded render; " +
                "set composePreview.shards = 1 to preserve provider order."
            failures += "${preview.className}.$testDisplayName: $msg"
            cases +=
              ScreenshotTestCaseReport(
                id = preview.id,
                className = preview.className,
                functionName = preview.functionName,
                previewName = previewName,
                index = index,
                status = "failed",
                message = msg,
                referencePath = refFile.path,
                actualPath = actualFile.path,
              )
            testSession?.recordCapture(
              className = preview.className,
              testName = testDisplayName,
              displayName = testDisplayName,
              startTime = startedAt,
              endTime = Instant.now(),
              outcome = PreviewTestEventSession.CaptureOutcome.Failed(msg),
            )
            continue
          }

          if (!actualFile.isFile) {
            failedCount++
            val msg = "Rendered PNG missing at ${actualFile.name}"
            failures += "${preview.className}.$testDisplayName: $msg"
            cases +=
              ScreenshotTestCaseReport(
                id = preview.id,
                className = preview.className,
                functionName = preview.functionName,
                previewName = previewName,
                index = index,
                status = "failed",
                message = msg,
                referencePath = refFile.path,
                actualPath = actualFile.path,
              )
            testSession?.recordCapture(
              className = preview.className,
              testName = testDisplayName,
              displayName = testDisplayName,
              startTime = startedAt,
              endTime = Instant.now(),
              outcome = PreviewTestEventSession.CaptureOutcome.Failed(msg),
            )
            continue
          }

          if (!refFile.isFile) {
            missingCount++
            val msg = "Missing reference PNG (${refFile.name}); run `$updateCmd` to generate it."
            if (requireRef) {
              failedCount++
              failures += "${preview.className}.$testDisplayName: $msg"
              cases +=
                ScreenshotTestCaseReport(
                  id = preview.id,
                  className = preview.className,
                  functionName = preview.functionName,
                  previewName = previewName,
                  index = index,
                  status = "missing",
                  message = msg,
                  referencePath = refFile.path,
                  actualPath = actualFile.path,
                )
              testSession?.recordCapture(
                className = preview.className,
                testName = testDisplayName,
                displayName = testDisplayName,
                startTime = startedAt,
                endTime = Instant.now(),
                outcome = PreviewTestEventSession.CaptureOutcome.Failed(msg),
              )
            } else {
              cases +=
                ScreenshotTestCaseReport(
                  id = preview.id,
                  className = preview.className,
                  functionName = preview.functionName,
                  previewName = previewName,
                  index = index,
                  status = "skipped",
                  message = msg,
                  referencePath = refFile.path,
                  actualPath = actualFile.path,
                )
              testSession?.recordCapture(
                className = preview.className,
                testName = testDisplayName,
                displayName = testDisplayName,
                startTime = startedAt,
                endTime = Instant.now(),
                outcome = PreviewTestEventSession.CaptureOutcome.Skipped,
              )
            }
            continue
          }

          val refImg = ImageIO.read(refFile)
          val actualImg = ImageIO.read(actualFile)
          if (refImg == null || actualImg == null) {
            failedCount++
            val msg =
              "Could not decode PNG (${if (refImg == null) refFile.name else actualFile.name})"
            failures += "${preview.className}.$testDisplayName: $msg"
            cases +=
              ScreenshotTestCaseReport(
                id = preview.id,
                className = preview.className,
                functionName = preview.functionName,
                previewName = previewName,
                index = index,
                status = "failed",
                message = msg,
                referencePath = refFile.path,
                actualPath = actualFile.path,
              )
            testSession?.recordCapture(
              className = preview.className,
              testName = testDisplayName,
              displayName = testDisplayName,
              startTime = startedAt,
              endTime = Instant.now(),
              outcome = PreviewTestEventSession.CaptureOutcome.Failed(msg),
            )
            continue
          }

          val cmp =
            ScreenshotTestInterop.compareImages(
              reference = refImg,
              actual = actualImg,
              threshold = threshold,
              channelTolerance = tolerance,
            )
          if (cmp.matched) {
            passedCount++
            cases +=
              ScreenshotTestCaseReport(
                id = preview.id,
                className = preview.className,
                functionName = preview.functionName,
                previewName = previewName,
                index = index,
                status = "passed",
                diffFraction = cmp.diffFraction,
                referencePath = refFile.path,
                actualPath = actualFile.path,
              )
            testSession?.recordCapture(
              className = preview.className,
              testName = testDisplayName,
              displayName = testDisplayName,
              startTime = startedAt,
              endTime = Instant.now(),
              outcome = PreviewTestEventSession.CaptureOutcome.Succeeded,
            )
          } else {
            failedCount++
            val diffName =
              buildList {
                  add(ScreenshotTestInterop.sanitizeSegment(preview.id))
                  captureKey
                    ?.let(ScreenshotTestInterop::sanitizeSegment)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::add)
                  if (!rowToken.isNullOrEmpty()) {
                    ScreenshotTestInterop.sanitizeSegment(rowToken)
                      .takeIf { it.isNotEmpty() }
                      ?.let(::add)
                    add(ScreenshotTestInterop.shortHash(rowToken))
                  }
                  add(index.toString())
                  add("diff")
                }
                .joinToString("_") + ".png"
            val diffFile = File(diffsRoot, diffName)
            ScreenshotTestInterop.writeDiffComposite(diffFile, refImg, actualImg, tolerance)
            val msg =
              if (cmp.sizeMismatch) {
                "Size mismatch: reference ${cmp.referenceWidth}x${cmp.referenceHeight} vs " +
                  "actual ${cmp.actualWidth}x${cmp.actualHeight}"
              } else {
                String.format(
                  "%.2f%% pixels differ (threshold %.2f%%)",
                  cmp.diffFraction * 100.0,
                  threshold * 100.0,
                )
              }
            failures += "${preview.className}.$testDisplayName: $msg (diff: ${diffFile.path})"
            cases +=
              ScreenshotTestCaseReport(
                id = preview.id,
                className = preview.className,
                functionName = preview.functionName,
                previewName = previewName,
                index = index,
                status = "failed",
                diffFraction = cmp.diffFraction,
                message = msg,
                referencePath = refFile.path,
                actualPath = actualFile.path,
                diffPath = diffFile.path,
              )
            testSession?.recordCapture(
              className = preview.className,
              testName = testDisplayName,
              displayName = testDisplayName,
              startTime = startedAt,
              endTime = Instant.now(),
              outcome = PreviewTestEventSession.CaptureOutcome.Failed(msg),
            )
          }
        }
      }
      testSession?.finishRoot(
        failedMessage = failures.takeIf { it.isNotEmpty() }?.joinToString("\n"),
      )
    } finally {
      testSession?.close()
    }

    val report =
      ScreenshotTestReport(
        module = modulePath.get(),
        variant = variantName.get(),
        threshold = threshold,
        channelTolerance = tolerance,
        total = cases.size,
        passed = passedCount,
        failed = failedCount,
        missingReferences = missingCount,
        cases = cases,
      )
    ScreenshotTestInterop.writeReport(reportFile.get().asFile, report)

    if (failures.isNotEmpty()) {
      val summary = buildString {
        append(
          "Screenshot test validation failed for ${modulePath.get()} (${variantName.get()}): " +
            "$failedCount of ${cases.size} failed",
        )
        if (missingCount > 0) append(" ($missingCount missing reference(s))")
        append(".\n")
        failures.take(20).forEach { append("  - ").append(it).append("\n") }
        if (failures.size > 20) append("  ... and ${failures.size - 20} more\n")
        append("Report: ").append(reportFile.get().asFile.absolutePath).append("\n")
        append("To update references: ").append(updateCmd)
      }
      val exception = GradleException(summary)
      ComposePreviewProblems.reportValidationFailure(
        problems = problems,
        id = "screenshot-test-mismatch",
        displayName = "Compose screenshot test mismatch",
        contextualLabel =
          "${modulePath.get()} (${variantName.get()}): $failedCount screenshot test failure(s)",
        details = summary,
        solutions =
          listOf(
            "Inspect diff composites under ${diffsRoot.absolutePath}",
            "Run `$updateCmd` if the visual change is intentional",
          ),
        exception = exception,
      )
      throw exception
    }

    logger.lifecycle(
      "Screenshot tests passed for ${modulePath.get()} (${variantName.get()}): " +
        "$passedCount/${cases.size} matched (threshold ${String.format("%.2f%%", threshold * 100.0)}).",
    )
  }
}

abstract class UpdateScreenshotTestTask : DefaultTask() {

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val manifestFile: RegularFileProperty

  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val rendersDir: DirectoryProperty

  @get:InputDirectory
  @get:Optional
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val renderTestResultsDir: DirectoryProperty

  @get:Internal abstract val referenceDirPath: Property<String>

  @get:Input abstract val requirePreviewTestAnnotation: Property<Boolean>

  @get:Input @get:Optional abstract val partialRenderReason: Property<String>

  @TaskAction
  fun update() {
    val partialReason = partialRenderReason.orNull.orEmpty()
    if (partialReason.isNotBlank()) {
      throw GradleException(
        "Screenshot reference update requires a full, unfiltered render (partial render active: " +
          "$partialReason). Re-run without -PcomposePreview.tier=fast / filter / exclude flags " +
          "so stale PNGs are not copied into references.",
      )
    }

    val manifest = ScreenshotTestInterop.parseManifest(manifestFile.get().asFile)
    val rendersRoot = rendersDir.get().asFile
    val refRoot = File(referenceDirPath.get())
    val selected =
      ScreenshotTestInterop.selectScreenshotTestPreviews(
        previews = manifest.previews,
        requirePreviewTestAnnotation = requirePreviewTestAnnotation.get(),
      )

    if (selected.isEmpty() && refRoot.isDirectory) {
      val existingRefs =
        refRoot.walkTopDown().count { it.isFile && it.extension.equals("png", ignoreCase = true) }
      if (existingRefs > 0) {
        throw GradleException(
          "Found $existingRefs committed screenshot reference PNG(s) under ${refRoot.path}, " +
            "but 0 screenshot-test previews were discovered. Ensure " +
            "com.android.compose.screenshot is enabled (e.g. " +
            "-Pandroid.experimental.enableScreenshotTest=true) and that previews in " +
            "src/screenshotTest/ are annotated with @PreviewTest.",
        )
      }
    }

    val providerIndicesByPreview =
      ScreenshotTestInterop.recoverProviderIndicesFromJunitXml(
        renderTestResultsDir = renderTestResultsDir.orNull?.asFile,
        previews = manifest.previews,
      )

    val nameCounts =
      selected
        .groupingBy { preview ->
          Triple(
            preview.className,
            preview.functionName,
            ScreenshotTestInterop.sanitizeSegment(preview.params.name.orEmpty()),
          )
        }
        .eachCount()

    var created = 0
    var updated = 0
    var skipped = 0
    for (preview in selected) {
      val previewName = preview.params.name.orEmpty()
      val nameKey =
        Triple(
          preview.className,
          preview.functionName,
          ScreenshotTestInterop.sanitizeSegment(previewName),
        )
      val ambiguousName = (nameCounts[nameKey] ?: 0) > 1
      val hash = ScreenshotTestInterop.configHash(preview)
      val siblingOutputs =
        manifest.previews
          .asSequence()
          .filter { it.id != preview.id }
          .flatMap { it.captures.asSequence().map { capture -> capture.renderOutput } }
          .toSet()
      val captures =
        ScreenshotTestInterop.resolveRenderedCaptures(
          preview = preview,
          rendersDir = rendersRoot,
          siblingOutputs = siblingOutputs,
          providerIndices = providerIndicesByPreview[preview.id].orEmpty(),
        )
      for (capture in captures) {
        val index = capture.index
        val rowToken = capture.rowToken
        val captureKey = capture.captureKey
        val providerIndex = capture.providerIndex
        val actualFile = capture.file
        if (!actualFile.isFile) {
          val suffixLabel =
            listOfNotNull(captureKey, rowToken ?: index.toString()).joinToString("#")
          logger.warn(
            "Skipping ${preview.className}.${preview.functionName}#$suffixLabel: rendered PNG missing (${actualFile.name})",
          )
          skipped++
          continue
        }
        val target =
          ScreenshotTestInterop.resolveReferenceFile(
            referenceDir = refRoot,
            className = preview.className,
            functionName = preview.functionName,
            previewName = previewName,
            index = index,
            configHash = hash,
            rowToken = rowToken,
            captureKey = captureKey,
            ambiguousName = ambiguousName,
            providerIndex = providerIndex,
          )
        if (
          rowToken != null &&
            providerIndex == null &&
            captureKey == null &&
            !ambiguousName &&
            !target.isFile &&
            ScreenshotTestInterop.hasNumericLayoutlibGoldens(
              referenceDir = refRoot,
              className = preview.className,
              functionName = preview.functionName,
              previewName = previewName,
            )
        ) {
          throw GradleException(
            "Cannot map labelled @PreviewParameter row '$rowToken' of " +
              "${preview.className}.${preview.functionName} to existing Google numeric reference " +
              "PNG(s) because provider order is unavailable from a sharded render; set " +
              "composePreview.shards = 1 to preserve provider order.",
          )
        }
        val existed = target.isFile
        target.parentFile?.mkdirs()
        Files.copy(actualFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        if (existed) updated++ else created++
      }
    }

    logger.lifecycle(
      "Updated screenshot references in ${refRoot.path}: $created created, $updated updated" +
        (if (skipped > 0) ", $skipped skipped" else "") +
        ".",
    )
  }
}
