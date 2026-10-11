package ee.schimke.composeai.cli

import ee.schimke.composeai.daemon.client.WorkspaceId
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * OpenAI MCP App deep links for `compose-preview show --link`, opening the `previews_library`
 * sidebar app at one preview.
 *
 * ```
 * codex://plugins/{pluginId}@{marketplace}/app/{toolName}?path={encodedAppRelativePath}   desktop
 * chatgpt://plugins/{pluginId}@{marketplace}/app/{toolName}?path=…                        mobile
 * https://chatgpt.com/plugins/{pluginId}/app/{toolName}?path=…                            web
 * ```
 *
 * A deliberate duplicate of compose-preview-server's `mcp/…/OpenAiDeepLinks.kt`, kept identical and
 * covered by the same tests (the CLI can't depend on the server). If a third caller appears, move
 * it to `compose-preview-contracts`.
 */
internal object OpenAiDeepLinks {
  /** The `previews_library` global entrypoint tool on the local MCP server. */
  const val LIBRARY_TOOL: String = "previews_library"

  /** Where the link is opened; decides the scheme and whether the marketplace is part of it. */
  enum class Surface {
    /** ChatGPT / Codex desktop: `codex://`. */
    DESKTOP,
    /** ChatGPT mobile: `chatgpt://`. */
    MOBILE,
    /** The ChatGPT web app: `https://chatgpt.com/plugins/…`, which carries no marketplace. */
    WEB;

    companion object {
      fun parse(value: String): Surface? =
        when (value.trim().lowercase()) {
          "",
          "desktop",
          "codex" -> DESKTOP
          "mobile",
          "chatgpt" -> MOBILE
          "web",
          "https" -> WEB
          else -> null
        }
    }
  }

  /**
   * A deep link to [toolName]'s app at [appPath] (the app-relative URL, query included). [appPath]
   * must begin with `/` and must not contain a fragment; it is percent-encoded whole as the `path`
   * query value, and `/` — the spec's default — is left out.
   */
  fun link(
    plugin: OpenAiPlugin,
    toolName: String,
    appPath: String = "/",
    surface: Surface = Surface.DESKTOP,
  ): String {
    require(toolName.isNotBlank()) { "deep link tool name must not be blank" }
    requireAppPath(appPath)
    val query = if (appPath == "/") "" else "?path=${encode(appPath)}"
    val tool = encode(toolName)
    val id = encode(plugin.pluginId)
    return when (surface) {
      Surface.WEB -> "https://chatgpt.com/plugins/$id/app/$tool$query"
      Surface.DESKTOP,
      Surface.MOBILE -> {
        val scheme = if (surface == Surface.DESKTOP) "codex" else "chatgpt"
        val at = plugin.marketplace?.let { "@${encode(it)}" }.orEmpty()
        "$scheme://plugins/$id$at/app/$tool$query"
      }
    }
  }

  /** The library app's `/preview/<uri-encoded compose-preview URI>` route. */
  fun previewPath(previewUri: String): String = "/preview/${encode(previewUri)}"

  /** A link that opens the library app at [previewUri], rendered on open. */
  fun previewLink(
    plugin: OpenAiPlugin,
    previewUri: String,
    surface: Surface = Surface.DESKTOP,
  ): String = link(plugin, LIBRARY_TOOL, previewPath(previewUri), surface)

  /** The spec's rules for an app-relative URL: begins with `/`, no fragment. */
  fun requireAppPath(appPath: String) {
    require(appPath.startsWith("/")) { "app-relative deep-link path must begin with '/': $appPath" }
    require('#' !in appPath) { "app-relative deep-link path must not contain a fragment: $appPath" }
  }

  /**
   * RFC 3986 percent-encoding of every byte outside the unreserved set (`A-Z a-z 0-9 - . _ ~`), so
   * `/`, `?`, `&`, `=`, `:` and `@` inside a value can never be read as structure. Unlike
   * [java.net.URLEncoder] a space is `%20`, not `+`.
   */
  fun encode(value: String): String = buildString {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
      val c = byte.toInt() and 0xff
      if (
        c in 'A'.code..'Z'.code ||
          c in 'a'.code..'z'.code ||
          c in '0'.code..'9'.code ||
          c == '-'.code ||
          c == '.'.code ||
          c == '_'.code ||
          c == '~'.code
      ) {
        append(c.toChar())
      } else {
        append('%')
        append(HEX[c shr 4])
        append(HEX[c and 0xf])
      }
    }
  }

  /** Inverse of [encode]; `+` stays a literal plus. Throws on a malformed escape. */
  fun decode(value: String): String {
    if ('%' !in value) return value
    val out = ByteArrayOutputStream(value.length)
    var i = 0
    while (i < value.length) {
      val c = value[i]
      if (c == '%') {
        require(i + 2 < value.length) { "truncated percent-escape in '$value'" }
        val hi = Character.digit(value[i + 1], 16)
        val lo = Character.digit(value[i + 2], 16)
        require(hi >= 0 && lo >= 0) { "malformed percent-escape in '$value'" }
        out.write((hi shl 4) or lo)
        i += 3
      } else {
        val codePoint = value.codePointAt(i)
        out.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
        i += Character.charCount(codePoint)
      }
    }
    return out.toString(Charsets.UTF_8)
  }

  private const val HEX = "0123456789ABCDEF"
}

/**
 * How the compose-preview plugin is published: its ChatGPT [pluginId] and, for a plugin installed
 * from a custom `marketplace.json`, that marketplace's `name`. Null [marketplace] is a plugin
 * published directly to ChatGPT, whose links omit `@{marketplace}`.
 */
internal data class OpenAiPlugin(val pluginId: String, val marketplace: String? = null) {
  init {
    require(pluginId.isNotBlank()) { "pluginId must not be blank" }
    require(marketplace == null || marketplace.isNotBlank()) { "marketplace must not be blank" }
  }

  companion object {
    const val PLUGIN_ID_ENV: String = "COMPOSE_PREVIEW_OPENAI_PLUGIN_ID"
    const val MARKETPLACE_ENV: String = "COMPOSE_PREVIEW_OPENAI_MARKETPLACE"

    /**
     * The plugin named by [PLUGIN_ID_ENV] (and optionally [MARKETPLACE_ENV]); null when unset, so a
     * caller that cannot name the plugin prints no link rather than a guessed one.
     */
    fun fromEnvironment(env: Map<String, String> = System.getenv()): OpenAiPlugin? =
      env[PLUGIN_ID_ENV]
        ?.takeIf { it.isNotBlank() }
        ?.let { OpenAiPlugin(it, env[MARKETPLACE_ENV]?.takeIf { m -> m.isNotBlank() }) }
  }
}

/**
 * `show --link[=desktop|mobile|web]`, plus where the plugin id comes from: `--openai-plugin-id` /
 * `--openai-marketplace`, else [OpenAiPlugin.PLUGIN_ID_ENV] / [OpenAiPlugin.MARKETPLACE_ENV]. A
 * flag beats the environment, per value.
 */
internal sealed interface ShowLinkRequest {
  /** `--link` was not passed. */
  data object Off : ShowLinkRequest

  /** `--link` was passed but cannot be honoured; [message] says why and what to set. */
  data class Unavailable(val message: String) : ShowLinkRequest

  data class Ready(val plugin: OpenAiPlugin, val surface: OpenAiDeepLinks.Surface) : ShowLinkRequest

  companion object {
    const val FLAG: String = "--link"
    const val PLUGIN_ID_FLAG: String = "--openai-plugin-id"
    const val MARKETPLACE_FLAG: String = "--openai-marketplace"

    val GUIDANCE: String =
      "compose-preview show --link: no ChatGPT/Codex plugin id is configured, so no deep link " +
        "was printed (a guessed id would open the wrong app). Pass " +
        "$PLUGIN_ID_FLAG <id> (and $MARKETPLACE_FLAG <name> for a plugin installed from a " +
        "custom marketplace), or set ${OpenAiPlugin.PLUGIN_ID_ENV} " +
        "(and ${OpenAiPlugin.MARKETPLACE_ENV})."

    fun parse(args: List<String>, env: Map<String, String> = System.getenv()): ShowLinkRequest {
      // `--link` takes its value only attached (`--link=web`): the space form would swallow the
      // next flag, since `show --link --json` is the common spelling.
      val linkArg = args.lastOrNull { it == FLAG || it.startsWith("$FLAG=") } ?: return Off
      val surfaceRaw = linkArg.substringAfter("=", "")
      val surface =
        OpenAiDeepLinks.Surface.parse(surfaceRaw)
          ?: return Unavailable(
            "compose-preview show --link: unknown surface '$surfaceRaw'; use " +
              "--link (desktop, codex://), --link=mobile (chatgpt://) or --link=web " +
              "(https://chatgpt.com)."
          )
      val envPlugin = OpenAiPlugin.fromEnvironment(env)
      val pluginId =
        args.flagValue(PLUGIN_ID_FLAG)?.takeIf { it.isNotBlank() }
          ?: envPlugin?.pluginId
          ?: return Unavailable(GUIDANCE)
      val marketplace =
        args.flagValue(MARKETPLACE_FLAG)?.takeIf { it.isNotBlank() } ?: envPlugin?.marketplace
      return Ready(OpenAiPlugin(pluginId, marketplace), surface)
    }
  }
}

/**
 * The `compose-preview://<workspace>/<module>/<previewId>` URI the MCP server would give this
 * preview (compose-preview-server's `PreviewUri.toUri()` without `config` / `overrides`), with the
 * workspace id from [WorkspaceId.derive] as for a project registered without a `rootProjectName`
 * override. Null when the id contains `/` or `?`.
 */
internal fun composePreviewUri(projectRoot: File, gradlePath: String, previewId: String): String? {
  if ('/' in previewId || '?' in previewId || previewId.isBlank()) return null
  val canonical = runCatching { projectRoot.canonicalFile }.getOrDefault(projectRoot.absoluteFile)
  val name = canonical.name.ifBlank { "workspace" }
  val workspace = WorkspaceId.derive(name, canonical).value
  val modulePath = if (gradlePath.startsWith(":")) gradlePath else ":$gradlePath"
  return "compose-preview://$workspace/${modulePath.replace(':', '_')}/$previewId"
}
