package ee.schimke.composeai.preview.notification

import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Inflates a notification [factory] into the surrounding Compose tree. Stack `@Preview`s to fan out
 * variants over `uiMode`, `locale`, `widthDp`, `fontScale`; no notification-specific plumbing
 * needed.
 *
 * Inflation mirrors the renderer's `NotificationPreviewComposable`:
 * `Notification.Builder.recoverBuilder` → [surface]'s `createXxxContentView()` (falling back to
 * `createContentView()`) → `RemoteViews.apply(...)`. This is the AOSP visual; OEM chrome isn't
 * reproducible under Robolectric.
 *
 * @param previewId opts into the structured-fields sidecar: when `composeai.render.outputDir` is
 * set
 *   (under the Gradle render task), `<sanitized-id>.notification.json` is written under
 *   `<outputDir>/../data/notifications/`, same schema as `@NotificationPreview`. First so [factory]
 *   stays the trailing lambda.
 * @param surface which [NotificationSurface] to render (default [NotificationSurface.EXPANDED]).
 *   Recomposition-aware: changing it re-inflates.
 */
@Composable
fun NotificationContent(
  previewId: String? = null,
  surface: NotificationSurface = NotificationSurface.EXPANDED,
  widthDp: Int = DEFAULT_NOTIFICATION_WIDTH_DP,
  factory: (Context) -> Notification,
) {
  val context = LocalContext.current
  // `AndroidView`'s `factory` runs once per view instance, so key on [surface] to re-inflate when
  // it changes (e.g. bound to a runtime toggle). Surface changes are rare and inflation is cheap.
  key(surface) {
    AndroidView(
      // An exact width rather than `fillMaxWidth()`: under wrap-to-content measuring, the
      // RemoteViews' ~320dp intrinsic width would crop the PNG to a square. Matches the 400dp
      // canvas the `@NotificationPreview` path gets.
      modifier = Modifier.width(widthDp.dp).wrapContentHeight(),
      factory = { ctx ->
        val parent =
          FrameLayout(ctx).apply {
            layoutParams =
              ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
              )
            // Title rows resolve `?attr/textColorPrimary`, near-white at night, so paint a matching
            // surface behind the tree as SystemUI would on-device.
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
 * Notification surface the [NotificationContent] helper inflates, matching SystemUI's
 * `Notification.Builder` entry points:
 *
 * - [COLLAPSED] → `createContentView()`, the one-line shade row.
 * - [EXPANDED] → `createBigContentView()`, the expanded layout — where most styles differ, so the
 *   default.
 * - [HEADS_UP] → `createHeadsUpContentView()`, the high-importance popup. Usually identical to
 *   expanded on AOSP, but OEM skins and `MediaStyle` diverge.
 */
enum class NotificationSurface {
  COLLAPSED,
  EXPANDED,
  HEADS_UP,
}

/**
 * Default notification surface width in dp, matching the renderer's `SANDBOX_WIDTH_DP` so these
 * PNGs have the same shape as `@NotificationPreview`-routed ones.
 */
const val DEFAULT_NOTIFICATION_WIDTH_DP: Int = 400

/**
 * AOSP-approximate notification surface colours (≈ `#FFFFFF` day, `#1F1F1F` night) picked from
 * `Configuration.uiMode`. Not read from the theme: the sandbox activity's theme resolves the same
 * background for day and night, which would render white-on-white at night.
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
  // These `create*ContentView` methods are deprecated for posting but are the only way to get the
  // RemoteViews offline. Each falls back to `createContentView()` for unstyled notifications.
  val builder = Notification.Builder.recoverBuilder(context, notification)
  val remoteViews =
    when (surface) {
      NotificationSurface.COLLAPSED -> builder.createContentView()
      NotificationSurface.EXPANDED -> builder.createBigContentView() ?: builder.createContentView()
      NotificationSurface.HEADS_UP ->
        builder.createHeadsUpContentView() ?: builder.createContentView()
    } ?: return null
  return remoteViews.apply(context, parent)
}

/**
 * Per-preview structured-fields sidecar, same schema and location as `:renderer-android`'s
 * `NotificationSidecar`. Duplicated so this module stands alone; hand-rolled JSON to avoid
 * kotlinx-serialization on the runtime classpath. Best-effort: failures go to stderr.
 */
private object NotificationSidecar {

  fun write(
    previewId: String,
    notification: Notification,
    context: Context,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    try {
      val rendersDirPath = System.getProperty("composeai.render.outputDir") ?: return
      val rendersDir = File(rendersDirPath)
      val sidecar =
        File(
          File(rendersDir.parentFile ?: rendersDir, "data/notifications"),
          sanitize(previewId) + ".notification.json",
        )
      sidecar.parentFile?.mkdirs()
      fileSystem.write(sidecar.path.toPath()) {
        writeUtf8(buildJson(previewId, notification, context))
      }
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
    sb
      .append("\"autoCancel\":")
      .append((n.flags and Notification.FLAG_AUTO_CANCEL) != 0)
      .append(',')
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
    val resId =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        icon.resId
      } else {
        @Suppress("DEPRECATION") n.icon
      }
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
      val name = runCatching {
        person.javaClass.getMethod("getName").invoke(person) as? CharSequence
      }
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
