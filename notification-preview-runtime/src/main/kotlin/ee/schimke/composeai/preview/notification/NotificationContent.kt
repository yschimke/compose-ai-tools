package ee.schimke.composeai.preview.notification

import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * Composable helper that inflates a notification factory into the surrounding Compose tree.
 *
 * Pairs with stacked `@Preview` (multi-preview meta-annotations) to fan out variants of the same
 * notification across the existing knobs `@Preview` already owns — `uiMode`, `locale`, `widthDp`,
 * `fontScale`. The fan-out is driven by Compose tooling: discovery + the renderer's COMPOSE path
 * pick each `@Preview` up as a separate entry, so no notification-specific plumbing is required.
 *
 * Inflation path mirrors the renderer-side `NotificationPreviewComposable`:
 * `Notification.Builder.recoverBuilder(context, notification)` → [surface]'s matching
 * `createXxxContentView()` (with `createContentView()` as the collapsed fallback) →
 * `RemoteViews.apply(...)`. This is the AOSP visual; OEM chrome (Pixel rounded corners, Samsung
 * tinting) is drawn by SystemUI on-device and isn't reproducible under Robolectric.
 *
 * Pass [previewId] to opt into the structured-fields JSON sidecar — when the renderer's
 * `composeai.render.outputDir` system property is set (i.e. running under the
 * compose-preview Gradle plugin's render task), a `<sanitized-id>.notification.json` is written
 * alongside the PNG under `<outputDir>/../data/notifications/`. Same schema and convention as the
 * FQN-discovered `@NotificationPreview` strategy in `:renderer-android`. Helper-based call sites
 * that don't know their preview id at compile time can leave it `null`; sidecar emission is opt-in.
 *
 * Pass [surface] to render a specific notification surface: [NotificationSurface.EXPANDED]
 * (default, the shade-expanded `createBigContentView()` layout — the most informative variant and
 * what the rest of the gallery uses), [NotificationSurface.COLLAPSED]
 * (`createContentView()` — the one-line shade row), or [NotificationSurface.HEADS_UP]
 * (`createHeadsUpContentView()` — the popup variant shown for high-importance channels). On AOSP
 * heads-up returns the same `RemoteViews` as the expanded layout for most styles; the parameter
 * is still distinct in the API so multi-preview meta-annotations can author 3-way surface
 * fan-outs. [surface] is recomposition-aware: the composable is internally wrapped in
 * `key(surface)`, so binding it to state (e.g. a runtime toggle in an interactive session) causes
 * the inflated tree to re-render on change rather than sticking on the first-composition value.
 *
 * [previewId] is the first parameter so [factory] stays the trailing-lambda slot — `NotificationContent
 * { ctx -> ... }` is the common shape and shouldn't require named arguments. Pass `previewId` only
 * when you actually want the sidecar: `NotificationContent(previewId = "Foo") { ctx -> ... }`.
 */
@Composable
fun NotificationContent(
  previewId: String? = null,
  surface: NotificationSurface = NotificationSurface.EXPANDED,
  factory: (Context) -> Notification,
) {
  val context = LocalContext.current
  // `AndroidView`'s `factory` block runs once per view instance — Compose recompositions do not
  // rerun it. Without keying, callers that bind [surface] to state (e.g. a runtime toggle between
  // collapsed / expanded / heads-up) would see the rendered notification stay on whichever
  // surface was active at first composition. Wrapping the `AndroidView` in `key(surface)` forces
  // Compose to throw away the previous view instance and re-run `factory` whenever [surface]
  // changes, so the inflated RemoteViews tree tracks the parameter. The factory body is cheap
  // (a `FrameLayout` plus a one-shot RemoteViews inflation) so the recreation cost is fine for
  // a surface change — these are infrequent compared to per-frame recompositions, and the only
  // mutable state inside the tree is the inflated RemoteViews itself, which is regenerated from
  // scratch on every surface anyway.
  key(surface) {
    AndroidView(
      modifier = Modifier.fillMaxWidth().wrapContentHeight(),
      factory = { ctx ->
        val parent =
          FrameLayout(ctx).apply {
            layoutParams =
              ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
              )
            // RemoteViews title rows resolve `?attr/textColorPrimary` against the activity theme,
            // which is near-white under `uiMode = NIGHT_YES`. Without a matching dark surface
            // behind the inflated tree, the title text renders white-on-white. SystemUI on-device
            // paints the dark notification surface for us; here we have to do it ourselves. Read
            // `android.R.attr.colorBackground` from the theme so the colour tracks the active
            // night-mode configuration without us hard-coding light / dark values.
            setBackgroundColor(resolveBackgroundColor(ctx))
          }
        val notification = factory(context)
        if (previewId != null) {
          NotificationSidecar.write(previewId, notification, context)
        }
        val view =
          inflateNotificationView(context, notification, parent, surface)
            ?: error("NotificationContent produced no inflatable RemoteViews")
        parent.addView(
          view,
          FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
          ),
        )
        parent
      },
    )
  }
}

/**
 * Notification surface the [NotificationContent] helper inflates. The three values map to the
 * three `Notification.Builder.createXxxContentView()` entry points SystemUI uses on-device:
 *
 *  - [COLLAPSED] → `createContentView()`, the one-line row that appears when the shade lists
 *    the notification alongside others. Wide-and-short.
 *  - [EXPANDED] → `createBigContentView()`, the layout shown when the user taps to expand. Most
 *    style classes (`BigTextStyle`, `MessagingStyle`, `InboxStyle`, …) only differ from
 *    collapsed in this layout, so it's the most informative variant and the default.
 *  - [HEADS_UP] → `createHeadsUpContentView()`, the popup variant shown for high-importance
 *    channels (or `setPriority(PRIORITY_HIGH)` pre-O). On stock AOSP this returns the same
 *    `RemoteViews` tree as the expanded layout for most styles — we still surface it as a
 *    distinct value because OEM skins (and the platform's `MediaStyle`) do diverge.
 */
enum class NotificationSurface {
  COLLAPSED,
  EXPANDED,
  HEADS_UP,
}

/**
 * AOSP-derived notification surface colours, picked off the active `Configuration.uiMode`. We
 * deliberately don't read `?android:attr/colorBackground` from the activity theme: the renderer's
 * sandbox activity uses a generic theme that resolves the same lavender for both day and night
 * modes, so the title row's `?attr/textColorPrimary` (near-white under NIGHT_YES) renders
 * white-on-white. Hard-coding the two surface values keeps each variant's contrast correct.
 *
 * Values approximate `Theme.DeviceDefault.Notification` / `…Notification.Dark` (≈ `#FFFFFF`
 * day, `#1F1F1F` night) — close enough to AOSP that the rendered PNG reads like the shade
 * surface a stock device would draw.
 */
private fun resolveBackgroundColor(context: Context): Int {
  val night =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
      Configuration.UI_MODE_NIGHT_YES
  return if (night) 0xFF1F1F1F.toInt() else 0xFFFFFFFF.toInt()
}

@Suppress("DEPRECATION")
private fun inflateNotificationView(
  context: Context,
  notification: Notification,
  parent: ViewGroup,
  surface: NotificationSurface,
): android.view.View? {
  // `createBigContentView` / `createContentView` / `createHeadsUpContentView` are marked
  // deprecated for production posting paths (where the system inflates them for you) but there's
  // no non-deprecated alternative when you specifically want the RemoteViews tree for offline
  // rendering. Each surface falls back to `createContentView()` so notifications without a
  // `setStyle(...)` (which produce no big / heads-up layout) still render at least the collapsed
  // row instead of erroring out.
  val builder = Notification.Builder.recoverBuilder(context, notification)
  val remoteViews =
    when (surface) {
      NotificationSurface.COLLAPSED -> builder.createContentView()
      NotificationSurface.EXPANDED ->
        builder.createBigContentView() ?: builder.createContentView()
      NotificationSurface.HEADS_UP ->
        builder.createHeadsUpContentView() ?: builder.createContentView()
    } ?: return null
  return remoteViews.apply(context, parent)
}

/**
 * Per-preview structured-fields sidecar. Same schema and same on-disk convention as
 * `:renderer-android`'s `NotificationSidecar` — duplicated here on purpose so this module can
 * stand alone (no compile dep on `:renderer-android` for consumers in Bazel modules or JVM unit
 * tests that don't carry the renderer).
 *
 * Hand-rolled JSON for the same reason the renderer-side version is: the runtime classpath
 * deliberately doesn't pull `kotlinx-serialization`, and the schema is shallow and stable.
 * Best-effort — failures here print to stderr but don't propagate.
 */
private object NotificationSidecar {

  fun write(previewId: String, notification: Notification, context: Context) {
    try {
      val rendersDirPath = System.getProperty("composeai.render.outputDir") ?: return
      val rendersDir = File(rendersDirPath)
      val sidecar =
        File(
          File(rendersDir.parentFile ?: rendersDir, "data/notifications"),
          sanitize(previewId) + ".notification.json",
        )
      sidecar.parentFile?.mkdirs()
      sidecar.writeText(buildJson(previewId, notification, context))
    } catch (e: Throwable) {
      System.err.println("Failed to write notification sidecar for $previewId: ${e.message}")
    }
  }

  private fun buildJson(previewId: String, n: Notification, context: Context): String {
    val sb = StringBuilder()
    sb.append('{')
    sb.append("\"schema\":\"compose-preview-notification/v1\",")
    sb.append("\"previewId\":").append(jsonString(previewId)).append(',')
    appendChannel(sb, n)
    appendCategory(sb, n)
    appendGroup(sb, n)
    sb.append("\"ongoing\":").append((n.flags and Notification.FLAG_ONGOING_EVENT) != 0).append(',')
    sb.append("\"autoCancel\":").append((n.flags and Notification.FLAG_AUTO_CANCEL) != 0).append(',')
    appendColor(sb, n)
    appendSmallIcon(sb, n, context)
    appendExtras(sb, n)
    appendActions(sb, n)
    appendMessages(sb, n)
    if (sb.last() == ',') sb.setLength(sb.length - 1)
    sb.append('}')
    return sb.toString()
  }

  private fun appendChannel(sb: StringBuilder, n: Notification) {
    val id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.channelId else null
    sb.append("\"channelId\":").append(jsonStringOrNull(id)).append(',')
  }

  private fun appendCategory(sb: StringBuilder, n: Notification) {
    sb.append("\"category\":").append(jsonStringOrNull(n.category)).append(',')
  }

  private fun appendGroup(sb: StringBuilder, n: Notification) {
    sb.append("\"group\":").append(jsonStringOrNull(n.group)).append(',')
  }

  private fun appendColor(sb: StringBuilder, n: Notification) {
    if (n.color != 0) sb.append("\"color\":").append(n.color).append(',')
  }

  private fun appendSmallIcon(sb: StringBuilder, n: Notification, context: Context) {
    val icon = n.smallIcon ?: return
    val resId = icon.resId
    val name = runCatching { context.resources.getResourceName(resId) }.getOrNull()
    sb.append("\"smallIcon\":{")
    sb.append("\"resourceId\":").append(resId)
    if (name != null) sb.append(",\"resourceName\":").append(jsonString(name))
    sb.append("},")
  }

  private fun appendExtras(sb: StringBuilder, n: Notification) {
    val extras = n.extras ?: return
    sb.append("\"extras\":{")
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
    val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
    val template = extras.getString(Notification.EXTRA_TEMPLATE)
    var first = true
    fun field(name: String, value: String?) {
      if (value == null) return
      if (!first) sb.append(',')
      sb.append(jsonString(name)).append(':').append(jsonString(value))
      first = false
    }
    field("title", title)
    field("text", text)
    field("bigText", bigText)
    field("subText", subText)
    field("template", template)
    sb.append("},")
  }

  private fun appendActions(sb: StringBuilder, n: Notification) {
    val actions = n.actions ?: return
    sb.append("\"actions\":[")
    actions.forEachIndexed { i, action ->
      if (i > 0) sb.append(',')
      sb.append('{')
      sb.append("\"title\":").append(jsonString(action.title?.toString() ?: ""))
      @Suppress("DEPRECATION") val iconResId = action.icon
      if (iconResId != 0) sb.append(",\"iconResId\":").append(iconResId)
      sb.append('}')
    }
    sb.append("],")
  }

  private fun appendMessages(sb: StringBuilder, n: Notification) {
    val extras = n.extras ?: return
    val array =
      @Suppress("DEPRECATION") extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return
    if (array.isEmpty()) return
    sb.append("\"messages\":[")
    var i = 0
    for (parcelable in array) {
      val bundle = parcelable as? Bundle ?: continue
      if (i > 0) sb.append(',')
      sb.append('{')
      val text = bundle.getCharSequence("text")?.toString()
      val timestamp = bundle.getLong("time", -1L).takeIf { it >= 0 }
      val senderName = readSenderName(bundle)
      var first = true
      fun field(name: String, value: String?) {
        if (value == null) return
        if (!first) sb.append(',')
        sb.append(jsonString(name)).append(':').append(jsonString(value))
        first = false
      }
      field("text", text)
      field("sender", senderName)
      if (timestamp != null) {
        if (!first) sb.append(',')
        sb.append("\"timestamp\":").append(timestamp)
      }
      sb.append('}')
      i++
    }
    sb.append("],")
  }

  private fun readSenderName(bundle: Bundle): String? {
    val person: Parcelable? = @Suppress("DEPRECATION") bundle.getParcelable("sender_person")
    if (person != null) {
      val name =
        runCatching { person.javaClass.getMethod("getName").invoke(person) as? CharSequence }
          .getOrNull()
      if (name != null) return name.toString()
    }
    return bundle.getCharSequence("sender")?.toString()
  }

  private fun sanitize(s: String): String = s.replace(Regex("""[/\\:*?"<>|\s]"""), "_")

  private fun jsonStringOrNull(s: String?): String = if (s == null) "null" else jsonString(s)

  private fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
      when (c) {
        '"' -> sb.append("\\\"")
        '\\' -> sb.append("\\\\")
        '\b' -> sb.append("\\b")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
      }
    }
    sb.append('"')
    return sb.toString()
  }
}
