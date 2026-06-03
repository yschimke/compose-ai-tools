package ee.schimke.composeai.renderer

import ee.schimke.composeai.io.SystemFileSystem
import okio.Path.Companion.toPath
import android.app.Notification
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import java.io.File

/**
 * Per-preview structured-fields sidecar for `@NotificationPreview` renders. Sibling of the PNG,
 * placed under `<renders-parent>/data/notifications/<sanitized-id>.notification.json`. Lets
 * agents / CI / tests assert on the *fields* of a built notification (channel, importance,
 * style, actions, EXTRA_*) without pixel-diffing the rendered PNG, and pairs structurally with
 * the existing `RenderErrorSidecar` per-PNG `.error.json` sibling.
 *
 * Hand-rolled JSON. Same reason `RenderErrorSidecar` does it: the renderer-android runtime
 * classpath deliberately doesn't pull `kotlinx-serialization` (renderer-vs-consumer alignment;
 * see `docs/RENDERER_COMPATIBILITY.md`). The schema is shallow and stable.
 *
 * Best-effort. Failures here print to stderr but don't propagate — the goal is to keep notification
 * structured-field capture from derailing the PNG render path on a per-preview issue.
 */
internal object NotificationSidecar {

  /**
   * Resolve where to write `<id>.notification.json`. Layout mirrors the project's data-product
   * convention: PNGs land in `<outputDir>/renders/`, structured data lands in
   * `<outputDir>/data/<kind>/`. `composeai.render.outputDir` points at the renders dir; the
   * sidecar goes one level up + `data/notifications`.
   */
  fun pathFor(rendersDir: File, previewId: String): File =
    File(File(rendersDir.parentFile ?: rendersDir, "data/notifications"), sanitize(previewId) + ".notification.json")

  /**
   * Write the structured-fields sidecar for [notification], keyed by [previewId]. Resolves the
   * output dir from the same `composeai.render.outputDir` system property the PNG path uses;
   * silently no-ops when the property isn't set (e.g. unit-test invocations that don't go through
   * the gradle plugin's render task).
   */
  fun write(previewId: String, notification: Notification, context: Context) {
    try {
      val rendersDirPath = System.getProperty("composeai.render.outputDir") ?: return
      val sidecar = pathFor(File(rendersDirPath), previewId)
      sidecar.parentFile?.mkdirs()
      SystemFileSystem.write(sidecar.path.toPath()) {
        writeUtf8(buildJson(previewId, notification, context))
      }
    } catch (e: Throwable) {
      System.err.println(
        "Failed to write notification sidecar for $previewId: ${e.message}"
      )
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
    // Trim trailing comma if present (every appender writes a trailing comma for tidiness).
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
    val name =
      runCatching { context.resources.getResourceName(resId) }.getOrNull()
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
      // `Action.icon` is deprecated in favour of `Action.getIcon().resId` but the int form is
      // still the easy-to-serialise one; we want the resId either way.
      @Suppress("DEPRECATION") val iconResId = action.icon
      if (iconResId != 0) sb.append(",\"iconResId\":").append(iconResId)
      sb.append('}')
    }
    sb.append("],")
  }

  /**
   * `MessagingStyle` notifications park each `Message` as a `Bundle` inside
   * `notification.extras[EXTRA_MESSAGES]`. Each bundle carries `KEY_TEXT`, `KEY_TIMESTAMP`, and
   * `KEY_SENDER_PERSON` (a `Person` parcelable; we surface its display name only). Walks the
   * array best-effort — missing or malformed entries skip silently.
   */
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

  /**
   * Best-effort read of a Message's sender display name. `Person` ships as a Parcelable under
   * `sender_person`; older builders also put the raw name under `sender`. Cast through `Any?`
   * since we don't want a compile-time dep on `androidx.core.app.Person` here (the renderer
   * stays Compose- and AndroidX-light on its main classpath).
   */
  private fun readSenderName(bundle: Bundle): String? {
    val person: Parcelable? =
      @Suppress("DEPRECATION") bundle.getParcelable("sender_person")
    if (person != null) {
      // Reflectively read `getName()` — both `android.app.Person` and
      // `androidx.core.app.Person` expose it. Avoids the hard dep.
      val name =
        runCatching { person.javaClass.getMethod("getName").invoke(person) as? CharSequence }
          .getOrNull()
      if (name != null) return name.toString()
    }
    return bundle.getCharSequence("sender")?.toString()
  }

  private fun sanitize(s: String): String = s.replace(Regex("""[/\\:*?"<>|\s]"""), "_")

  private fun jsonStringOrNull(s: String?): String =
    if (s == null) "null" else jsonString(s)

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
