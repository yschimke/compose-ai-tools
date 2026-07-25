package ee.schimke.composeai.discovery

import java.io.File
import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * App-level preview discovery: the real activities an app declares and the scripted tours a module
 * commits. Two producers, one theme — put the *app itself* (not just isolated composables) in front
 * of agents and reviewers:
 * - [parseManifestActivities] reads `<activity>` entries + intent-filters out of the merged
 *   `AndroidManifest.xml`; [buildActivityPreviews] turns the enabled ones into synthetic
 *   [PreviewKind.ACTIVITY] previews. The launcher activity's capture is the app's hero image.
 * - [parseTourSpec] reads a committed `compose-previews/tours/<name>.json` script;
 *   [buildTourPreviews] turns each into a synthetic [PreviewKind.APP_TOUR] preview whose captures
 *   are the tour's steps (launch → click/intent/back → …), one PNG per step.
 *
 * Pure JVM, no Gradle/AGP types — same contract as the rest of [PreviewDiscovery] so non-Gradle
 * build systems can drive it.
 */
object AppTourDiscovery {

  /** Filename-stem sanitizer — mirrors `PreviewDiscovery.SANITIZE_RENDER_STEM`. */
  private val SANITIZE_STEM = Regex("[^A-Za-z0-9._-]")

  private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

  /**
   * Class-name prefixes of activities that manifest-merger injects from libraries (Glance's action
   * trampoline, ui-tooling's `PreviewActivity`, ui-test-manifest's `ComponentActivity`, Play
   * Services dialogs, …). They're not part of the *app* — not previewable entry points, not tour
   * material — so they're dropped at parse time.
   */
  private val LIBRARY_ACTIVITY_PREFIXES =
    listOf("android.", "androidx.", "com.android.", "com.google.android.")

  private val xmlFactory: XMLInputFactory =
    XMLInputFactory.newFactory().apply {
      setProperty(XMLInputFactory.SUPPORT_DTD, false)
      setProperty("javax.xml.stream.isSupportingExternalEntities", false)
    }

  private val TOUR_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  // -------------------------------------------------------------------------
  // Manifest activities
  // -------------------------------------------------------------------------

  /**
   * Parses the merged manifest's `<activity>` declarations. Best-effort: a missing / malformed
   * manifest yields an empty list rather than an error (mirrors `ManifestReferenceExtractor`).
   * Activities with `android:enabled="false"` are skipped — they can't be launched. Short
   * `.RelativeName` forms are resolved against the manifest `package` attribute when present
   * (merged manifests produced by AGP carry the full applicationId there).
   */
  fun parseManifestActivities(file: File): List<ManifestActivity> =
    if (file.isFile) file.inputStream().use { parseManifestActivities(it) } else emptyList()

  fun parseManifestActivities(input: InputStream): List<ManifestActivity> {
    val reader =
      try {
        xmlFactory.createXMLStreamReader(input)
      } catch (_: XMLStreamException) {
        return emptyList()
      }
    val out = mutableListOf<ManifestActivity>()
    var packageName: String? = null
    // Parser state for the <activity> currently being read (null between activities).
    var current: ActivityBuilder? = null
    var currentFilter: FilterBuilder? = null
    try {
      while (reader.hasNext()) {
        when (reader.next()) {
          XMLStreamConstants.START_ELEMENT ->
            when (reader.localName) {
              "manifest" ->
                packageName =
                  packageName ?: readAttr(reader, namespaceUri = "", localName = "package")
              "activity" -> {
                val name = readAttr(reader, ANDROID_NS, "name")
                val enabled = readAttr(reader, ANDROID_NS, "enabled")?.toBooleanStrictOrNull()
                current =
                  if (name.isNullOrEmpty() || enabled == false) {
                    null
                  } else {
                    ActivityBuilder(
                      rawName = name,
                      exported = readAttr(reader, ANDROID_NS, "exported") == "true",
                      label = readAttr(reader, ANDROID_NS, "label"),
                    )
                  }
              }
              "intent-filter" -> if (current != null) currentFilter = FilterBuilder()
              "action" ->
                currentFilter?.let { f ->
                  readAttr(reader, ANDROID_NS, "name")?.let(f.actions::add)
                }
              "category" ->
                currentFilter?.let { f ->
                  readAttr(reader, ANDROID_NS, "name")?.let(f.categories::add)
                }
              "data" ->
                currentFilter?.let { f ->
                  readAttr(reader, ANDROID_NS, "scheme")?.let(f.schemes::add)
                  readAttr(reader, ANDROID_NS, "host")?.let(f.hosts::add)
                }
            }
          XMLStreamConstants.END_ELEMENT ->
            when (reader.localName) {
              "intent-filter" -> {
                val f = currentFilter
                if (f != null) current?.filters?.add(f)
                currentFilter = null
              }
              "activity" -> {
                current
                  ?.build(packageName)
                  ?.takeIf { built ->
                    LIBRARY_ACTIVITY_PREFIXES.none { built.className.startsWith(it) }
                  }
                  ?.let(out::add)
                current = null
              }
            }
        }
      }
    } catch (_: XMLStreamException) {
      // Truncated / malformed manifest — keep what we collected so far.
    } finally {
      try {
        reader.close()
      } catch (_: XMLStreamException) {}
    }
    return out
  }

  private class ActivityBuilder(
    val rawName: String,
    val exported: Boolean,
    val label: String?,
    val filters: MutableList<FilterBuilder> = mutableListOf(),
  ) {
    fun build(packageName: String?): ManifestActivity {
      val fqcn =
        when {
          rawName.startsWith(".") -> packageName?.let { it + rawName } ?: rawName
          !rawName.contains(".") -> packageName?.let { "$it.$rawName" } ?: rawName
          else -> rawName
        }
      val intentFilters = filters.map { it.build() }
      return ManifestActivity(
        className = fqcn,
        exported = exported,
        launcher =
          intentFilters.any {
            "android.intent.action.MAIN" in it.actions &&
              "android.intent.category.LAUNCHER" in it.categories
          },
        label = label,
        intentFilters = intentFilters,
      )
    }
  }

  private class FilterBuilder(
    val actions: MutableList<String> = mutableListOf(),
    val categories: MutableList<String> = mutableListOf(),
    val schemes: MutableList<String> = mutableListOf(),
    val hosts: MutableList<String> = mutableListOf(),
  ) {
    fun build() =
      ActivityIntentFilter(
        actions = actions.toList(),
        categories = categories.toList(),
        dataSchemes = schemes.toList(),
        dataHosts = hosts.toList(),
      )
  }

  private fun readAttr(
    reader: javax.xml.stream.XMLStreamReader,
    namespaceUri: String,
    localName: String,
  ): String? {
    for (i in 0 until reader.attributeCount) {
      val ns = reader.getAttributeNamespace(i) ?: ""
      if (ns == namespaceUri && reader.getAttributeLocalName(i) == localName) {
        return reader.getAttributeValue(i)
      }
    }
    return null
  }

  /**
   * Turns [activities] into one synthetic [PreviewKind.ACTIVITY] preview each. The launcher
   * activity's capture is required (the hero must render); every other activity is `optional` —
   * real screens often need intent extras or session state discovery can't guess, and a best-effort
   * `.error.json` beside a missing PNG beats failing the whole render for it. Sized to the standard
   * phone canvas ([DeviceDimensions.DEFAULT]) or the Wear default on watch modules.
   */
  fun buildActivityPreviews(
    activities: List<ManifestActivity>,
    isWear: Boolean,
  ): List<PreviewInfo> {
    val device = if (isWear) DeviceDimensions.DEFAULT_WEAR else DeviceDimensions.DEFAULT
    return activities.map { activity ->
      val simpleName = activity.className.substringAfterLast('.')
      val stem = "activity__" + simpleName.replace(SANITIZE_STEM, "_")
      PreviewInfo(
        id = stem,
        functionName = simpleName,
        className = activity.className,
        params =
          PreviewParams(
            name = simpleName,
            kind = PreviewKind.ACTIVITY,
            widthDp = device.widthDp,
            heightDp = device.heightDp,
            density = device.density,
            // The hero should look like the app on a device: draw system bars for context.
            showSystemUi = !isWear,
          ),
        captures =
          listOf(
            Capture(
              renderOutput = "renders/$stem.png",
              optional = !activity.launcher,
              cost = ACTIVITY_LAUNCH_COST,
            )
          ),
      )
    }
  }

  // -------------------------------------------------------------------------
  // Tour specs
  // -------------------------------------------------------------------------

  /** One committed tour script — the JSON shape of `compose-previews/tours/<name>.json`. */
  @Serializable
  data class TourSpec(
    /** Tour id; also the render-file stem. Falls back to the spec's filename when absent. */
    val name: String? = null,
    val description: String? = null,
    /**
     * The Intent that starts the tour. `null` → the manifest's launcher activity, so the common
     * "tour starts at the home screen" spec needs no `start` block at all.
     */
    val start: TourIntentSpec? = null,
    val steps: List<TourSpecStep> = emptyList(),
  )

  /** One authored step: at most one action, plus the label that names its capture. */
  @Serializable
  data class TourSpecStep(
    val label: String,
    val click: TourClickSpec? = null,
    val intent: TourIntentSpec? = null,
    val back: Boolean = false,
  )

  /** Parses one tour spec file. Returns `null` (best-effort) when unreadable or not a tour. */
  fun parseTourSpec(file: File): TourSpec? =
    runCatching { TOUR_JSON.decodeFromString(TourSpec.serializer(), file.readText()) }
      .getOrNull()
      ?.takeIf { it.steps.isNotEmpty() || it.start != null }

  /**
   * Turns committed tour spec files into one synthetic [PreviewKind.APP_TOUR] preview each. Every
   * tour gets a synthesized step-0 "launch" capture of its start state, then one capture per
   * authored step; the renderer performs each step's action and captures the currently-resumed
   * activity. A spec without a `start` intent falls back to [launcherActivity]'s component — specs
   * in modules with no launcher must name their start explicitly (the ones that don't are skipped
   * with a warning added to [warnings]).
   */
  fun buildTourPreviews(
    tourSpecFiles: List<File>,
    launcherActivity: ManifestActivity?,
    isWear: Boolean,
    warnings: MutableList<String>,
  ): List<PreviewInfo> {
    val device = if (isWear) DeviceDimensions.DEFAULT_WEAR else DeviceDimensions.DEFAULT
    val found = LinkedHashMap<String, PreviewInfo>()
    for (file in tourSpecFiles.sortedBy { it.name }) {
      val spec = parseTourSpec(file)
      if (spec == null) {
        warnings +=
          "composePreview: skipping tour spec ${file.name} — not parseable as a tour " +
            "(expected JSON with a steps array and optionally a start intent)."
        continue
      }
      val name = (spec.name ?: file.nameWithoutExtension).replace(SANITIZE_STEM, "_")
      val start =
        spec.start ?: launcherActivity?.let { TourIntentSpec(activityClassName = it.className) }
      if (start == null) {
        warnings +=
          "composePreview: skipping tour spec ${file.name} — no start intent and the " +
            "manifest declares no launcher activity."
        continue
      }
      val stem = "apptour__$name"
      if (found.containsKey(stem)) {
        warnings += "composePreview: skipping tour spec ${file.name} — duplicate tour name '$name'."
        continue
      }
      val steps =
        listOf(TourStepCapture(index = 0, label = "launch")) +
          spec.steps.mapIndexed { i, step ->
            TourStepCapture(
              index = i + 1,
              label = step.label,
              click = step.click,
              intent = step.intent,
              back = step.back,
            )
          }
      found[stem] =
        PreviewInfo(
          id = stem,
          functionName = name,
          className = start.activityClassName ?: "",
          params =
            PreviewParams(
              name = name,
              kind = PreviewKind.APP_TOUR,
              widthDp = device.widthDp,
              heightDp = device.heightDp,
              density = device.density,
              showSystemUi = !isWear,
              launchIntent = start,
            ),
          captures =
            steps.map { step ->
              val label = step.label.replace(SANITIZE_STEM, "_")
              Capture(
                tourStep = step,
                renderOutput = "renders/${stem}_step%02d_%s.png".format(step.index, label),
                cost = TOUR_STEP_COST,
              )
            },
        )
    }
    return found.values.toList()
  }
}
