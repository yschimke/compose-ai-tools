package ee.schimke.composeai.guidelines

import ee.schimke.composeai.guidelines.protocol.CatalogGuidelinesV1
import ee.schimke.composeai.guidelines.protocol.GuidelineRuleV1
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads a catalog's `ui-builder.guidelines.json` from a file, a URL or bytes (a bundle entry).
 *
 * Fail-soft by design: a missing file is "this catalog has no guidelines", and a malformed one, or
 * one written for another catalog, is reported through [Loaded.problem] rather than thrown, so a
 * catalog run never fails because its guidance is broken.
 */
public object CatalogGuidelinesLoader {
  /** The outcome of a load: the guidelines, or why there are none. */
  public data class Loaded(val guidelines: CatalogGuidelinesV1?, val problem: String? = null)

  /** The guidelines in [text], checked against [expectedCatalog] when one is given. */
  public fun parse(text: String, expectedCatalog: String? = null): Loaded {
    val parsed =
      try {
        GUIDELINES_JSON.decodeFromString(CatalogGuidelinesV1.serializer(), text)
      } catch (e: Exception) {
        return Loaded(null, "not a readable ${CatalogGuidelinesV1.FILE_NAME}: ${parseFailure(e)}")
      }
    if (!parsed.schema.startsWith("compose-ui-builder/catalog-guidelines/")) {
      return Loaded(null, "unknown schema `${parsed.schema}`")
    }
    if (expectedCatalog != null && parsed.catalog != expectedCatalog) {
      return Loaded(null, "written for catalog `${parsed.catalog}`, not `$expectedCatalog`")
    }
    val bad = parsed.rules.filter { it.id.isBlank() || '?' !in it.check }
    if (bad.isNotEmpty()) {
      return Loaded(null, "rules without an id or a yes/no check: ${bad.joinToString { it.id }}")
    }
    return Loaded(parsed)
  }

  /**
   * Why [text] did not parse, without quoting it. A kotlinx decoding message carries the input
   * itself (`JSON input: …`, the whole text when it is short) and a JSON path whose map keys are
   * the input's, and a caller can point [load] at any local file, so the reason names only the
   * failure and its offset: a problem string must never echo the contents of a file that turned out
   * not to be guidelines.
   */
  internal fun parseFailure(e: Exception): String {
    if (e is kotlinx.serialization.MissingFieldException) {
      // Names the serializer's own fields, never the input's.
      return "missing ${e.missingFields.joinToString { "`$it`" }}"
    }
    val offset = OFFSET.find(e.message.orEmpty())?.groupValues?.get(1)
    return if (offset != null) "malformed JSON at offset $offset" else "malformed JSON"
  }

  private val OFFSET = Regex("""at offset (\d+)""")

  /** The guidelines in [file], or none when it does not exist. */
  public fun load(file: File, expectedCatalog: String? = null): Loaded =
    if (!file.isFile) Loaded(null) else parse(file.readText(), expectedCatalog)

  /**
   * The guidelines at [location]: an `http(s)` URL (a catalog's delivery branch) or a path. A URL
   * that answers 404 is a catalog with no guidelines, not an error.
   */
  public fun load(
    location: String,
    expectedCatalog: String? = null,
    http: OkHttpClient = DEFAULT_HTTP,
  ): Loaded {
    if (!location.startsWith("http://") && !location.startsWith("https://")) {
      return load(File(location), expectedCatalog)
    }
    return try {
      http.newCall(Request.Builder().url(location).build()).execute().use { response ->
        when {
          response.code == 404 -> Loaded(null)
          !response.isSuccessful -> Loaded(null, "$location answered ${response.code}")
          else -> parse(response.body.string(), expectedCatalog)
        }
      }
    } catch (e: java.io.IOException) {
      Loaded(null, "$location could not be read: ${e.message}")
    }
  }

  private val DEFAULT_HTTP: OkHttpClient =
    OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
}

/** The surfaces a subject can be: a whole screen, a widget, or a component shown on its own. */
public object GuidelineSurfaces {
  public const val SCREEN: String = GuidelineRuleV1.SURFACE_SCREEN
  public const val WIDGET: String = GuidelineRuleV1.SURFACE_WIDGET

  /**
   * A @Preview of one component (a button sticker, a card): the commonest subject in a
   * design-system catalog. A rule written for a whole screen ("time text at the top") is never
   * asked of one.
   */
  public const val COMPONENT: String = "component"

  /**
   * The profile a Wear widget's rules name: the `WEAR_WIDGETS` Remote Compose platform profile a
   * Glance Wear widget document is recorded under, in the guidelines' own spelling (the UI
   * builder's `RemoteProfileTargetV1`).
   */
  public const val WEAR_WIDGETS_PROFILE: String = "wear-widgets"

  /**
   * What a `previews.json` entry is to the guidelines: the surface its rules are chosen by, and the
   * profile it targets when the manifest says. One answer for every host — the CLI's live and
   * handoff runs, the MCP server — so a preview is judged against the same rules wherever it is
   * checked.
   *
   * A widget is what discovery records under `widget` (a Glance Wear widget preview, a launcher
   * widget), and, for a manifest written before discovery recorded that, what the manifest itself
   * shows: a Glance app-widget preview, a launcher-widget capture, or a `@PreviewParameter`
   * provider from `androidx.glance.wear`. Otherwise a preview naming a device is a [SCREEN] and one
   * without is a [COMPONENT].
   */
  public fun of(preview: JsonObject): GuidelineSubjectKind {
    val params = preview["params"] as? JsonObject
    (preview["widget"] as? JsonObject)?.let { widget ->
      return GuidelineSubjectKind(WIDGET, widget.text("profile"))
    }
    val launcherCapture =
      (preview["captures"] as? JsonArray).orEmpty().any {
        (it as? JsonObject)?.get("launcherWidget") is JsonObject
      }
    if (params?.text("kind") == GLANCE_APPWIDGET_KIND || launcherCapture) {
      return GuidelineSubjectKind(WIDGET)
    }
    if (params?.text("previewParameterProviderClassName")?.startsWith(GLANCE_WEAR_PREFIX) == true) {
      return GuidelineSubjectKind(WIDGET, WEAR_WIDGETS_PROFILE)
    }
    return GuidelineSubjectKind(if (params?.text("device") != null) SCREEN else COMPONENT)
  }

  private const val GLANCE_APPWIDGET_KIND = "GLANCE_APPWIDGET"
  private const val GLANCE_WEAR_PREFIX = "androidx.glance.wear."

  private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}

/**
 * What a preview is to the guidelines ([GuidelineSurfaces.of]): its [surface], and the Remote
 * Compose [profile] it targets when known — a profile-specific rule is asked only of a subject
 * naming that profile.
 */
public data class GuidelineSubjectKind(val surface: String, val profile: String? = null)

/**
 * Why [subject] is asked nothing, or null when some rule applies to it: no subject-scoped or
 * set-scoped rule of [this] names its surface and profile. Such a subject costs no request; its
 * result says so rather than reading as a pass.
 */
public fun CatalogGuidelinesV1.noRulesFor(subject: PreviewSubject): String? {
  val hasPicture = subject.pictures.isNotEmpty()
  if (subjectRules(subject.surface, subject.profile, hasPicture).isNotEmpty()) return null
  val set =
    setRules(hasPicture).filter {
      it.appliesToSurface(subject.surface) && it.appliesToProfile(subject.profile)
    }
  if (set.isNotEmpty()) return null
  return "no rule in the `$catalog` guidelines applies to surface `${subject.surface}`" +
    (subject.profile?.let { ", profile `$it`" } ?: " with no profile") +
    (if (hasPicture) "" else " without a picture")
}

/**
 * The rules worth asking about a subject of [surface] targeting Remote Compose [profile]:
 * subject-scoped (set-scoped rules are asked once per batch, see [setRules]), applying to that
 * surface — a rule naming no surface applies to every one — and, when [profile] is known, to that
 * profile. Visual rules are left out when [hasPicture] is false: the model would have nothing to
 * judge them on.
 */
public fun CatalogGuidelinesV1.subjectRules(
  surface: String,
  profile: String? = null,
  hasPicture: Boolean = true,
): List<GuidelineRuleV1> = rules.filter { rule ->
  rule.scope == GuidelineRuleV1.SCOPE_SUBJECT &&
    rule.appliesToSurface(surface) &&
    rule.appliesToProfile(profile) &&
    (hasPicture || rule.kind != GuidelineRuleV1.KIND_VISUAL)
}

/** The rules judged once across a whole batch, for consistency guidance. */
public fun CatalogGuidelinesV1.setRules(hasPicture: Boolean = true): List<GuidelineRuleV1> =
  rules.filter { rule ->
    rule.scope == GuidelineRuleV1.SCOPE_SET &&
      (hasPicture || rule.kind != GuidelineRuleV1.KIND_VISUAL)
  }

/** Whether [this] rule is asked of a subject of [surface]. */
public fun GuidelineRuleV1.appliesToSurface(surface: String): Boolean =
  surfaces.isEmpty() || surface in surfaces

/**
 * Whether [this] rule is asked of a design targeting [profile]. A rule naming no profile applies to
 * every design; a rule naming one applies to it and to its `+experimental` variant; one naming
 * `…+experimental` applies only to that. With no known [profile], a profile-specific rule is not
 * asked.
 */
public fun GuidelineRuleV1.appliesToProfile(profile: String?): Boolean =
  profiles.isEmpty() ||
    (profile != null && profiles.any { it == profile || it == profile.substringBefore('+') })

internal val GUIDELINES_JSON: Json = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
}
