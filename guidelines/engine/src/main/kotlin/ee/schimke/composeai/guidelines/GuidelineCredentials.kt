package ee.schimke.composeai.guidelines

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a guidelines check finds its model key: [ENV] when set, else the user's credentials file
 * ([defaultFile], `~/.config/compose-preview/credentials.json`), written `0600` by `compose-preview
 * guidelines key set` and never by anything that also writes reports.
 *
 * The environment wins so CI, and a one-off override, need no file. The key is never taken from a
 * command line, and nothing here prints it: [Resolved.masked] is what a status line shows.
 *
 * The file sits beside the CLI's `agent-access.json` and has the same exposure: any process of the
 * same user can read it, a build script included. The CLI withholds [ENV] from the Gradle daemon,
 * but a build can still open a file, so a user who runs untrusted builds keeps to [ENV].
 *
 * ```json
 * { "schema": "compose-preview-credentials/v1", "keys": { "openrouter": "sk-or-…" },
 *   "trustedProjects": ["/home/me/src/app"] }
 * ```
 *
 * Keyed by provider, so another backend's key can sit beside OpenRouter's. `trustedProjects` lists
 * the project roots the user agreed to use the stored key for ([isTrusted]): a checkout must not
 * spend the key, sending its previews and source to a model, before its owner says so.
 */
public object GuidelineCredentials {
  /** The environment variable holding the OpenRouter key; it beats the file. */
  public const val ENV: String = "COMPOSE_PREVIEW_OPENROUTER_KEY"

  /** Overrides [defaultFile]'s location. */
  public const val FILE_ENV: String = "COMPOSE_PREVIEW_CREDENTIALS_FILE"

  public const val SCHEMA_V1: String = "compose-preview-credentials/v1"

  /** The provider id OpenRouter's key is stored under. */
  public const val OPENROUTER: String = "openrouter"

  /** A key and where it came from: [ENV], or the credentials file's path. */
  public data class Resolved(val key: String, val source: String) {
    /** The last four characters, for a status line: `…abcd`. Never the key itself. */
    public val masked: String
      get() = if (key.length <= 8) "…" else "…" + key.takeLast(4)

    /** Whether the key came from the credentials file rather than [ENV]. */
    public val stored: Boolean
      get() = source != ENV

    override fun toString(): String = "Resolved(key=$masked, source=$source)"
  }

  /**
   * The OpenRouter key from [ENV], else from [file]; null when neither holds one. A file that
   * cannot be read is reported through [warn] (without its content) and treated as holding none.
   */
  public fun openRouter(
    env: (String) -> String? = System::getenv,
    file: File? = defaultFileOrNull(env = env),
    warn: (String) -> Unit = {},
  ): Resolved? {
    env(ENV)
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.let {
        return Resolved(it, ENV)
      }
    if (file == null) return null
    return read(file, warn)
      .keys[OPENROUTER]
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.let {
        if (readableByOthers(file)) {
          warn("${file.path} is readable by other users; run `chmod 600 ${file.path}`")
        }
        Resolved(it, file.path)
      }
  }

  /**
   * Stores [key] for [provider] in [file], replacing any earlier one, through a `0600` temp file
   * and an atomic rename so the key is never world-readable, even briefly. Throws on failure.
   */
  public fun store(file: File, key: String, provider: String = OPENROUTER) {
    val trimmed = key.trim()
    require(validKey(trimmed)) { "a key is one line with no spaces" }
    write(file, forChange(file).let { it.copy(keys = it.keys + (provider to trimmed)) })
  }

  /** Removes [provider]'s key from [file]; false when there was none. Throws on failure. */
  public fun forget(file: File, provider: String = OPENROUTER): Boolean {
    val stored = forChange(file)
    if (provider !in stored.keys) return false
    write(file, stored.copy(keys = stored.keys - provider))
    return true
  }

  /**
   * Whether [project] (a Gradle root or source root) has been trusted with the stored key. Checked
   * only for a [Resolved.stored] key: [ENV] is the user's explicit choice for that shell. Exact
   * canonical paths: trusting a directory does not trust the checkouts under it.
   */
  public fun isTrusted(file: File, project: File): Boolean =
    canonical(project) in read(file) {}.trustedProjects

  /** The projects trusted with the stored key, as canonical paths. */
  public fun trustedProjects(file: File): List<String> = read(file) {}.trustedProjects

  /** Trusts [project] with the stored key. Throws on failure. */
  public fun trust(file: File, project: File) {
    val path = canonical(project)
    val stored = forChange(file)
    if (path in stored.trustedProjects) return
    write(file, stored.copy(trustedProjects = (stored.trustedProjects + path).sorted()))
  }

  /** Stops trusting [project]; false when it was not trusted. Throws on failure. */
  public fun untrust(file: File, project: File): Boolean {
    val path = canonical(project)
    val stored = forChange(file)
    if (path !in stored.trustedProjects) return false
    write(file, stored.copy(trustedProjects = stored.trustedProjects - path))
    return true
  }

  private fun canonical(project: File): String = project.absoluteFile.canonicalPath

  /** Whether [key] looks like a key at all: non-empty, one line, no spaces. */
  public fun validKey(key: String): Boolean = key.isNotEmpty() && key.none { it.isWhitespace() }

  /**
   * `$COMPOSE_PREVIEW_CREDENTIALS_FILE` when it is absolute (a relative one gives null), else
   * `$XDG_CONFIG_HOME/compose-preview/credentials.json`, else under `$HOME/.config`, else under the
   * JVM's `user.home`; null when none is an absolute path. Never the working directory, which for
   * an agent is a checkout CI archives or commits.
   */
  public fun defaultFileOrNull(
    prop: (String) -> String? = System::getProperty,
    env: (String) -> String? = System::getenv,
  ): File? {
    env(FILE_ENV)
      ?.takeIf { it.isNotBlank() }
      ?.let {
        // A relative override would put the key in the working directory: no file at all.
        return absolute(it)?.let(::File)
      }
    val configHome =
      absolute(env("XDG_CONFIG_HOME"))
        ?: absolute(env("HOME"))?.let { "$it/.config" }
        ?: absolute(prop("user.home"))?.let { "$it/.config" }
        ?: return null
    return File("$configHome/compose-preview/$FILE_NAME")
  }

  private const val FILE_NAME = "credentials.json"

  @Serializable
  internal data class Stored(
    val schema: String = SCHEMA_V1,
    val keys: Map<String, String> = emptyMap(),
    val trustedProjects: List<String> = emptyList(),
  )

  private val JSON = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
  }

  private fun read(file: File, warn: (String) -> Unit): Stored {
    if (!file.isFile) return Stored()
    return try {
      JSON.decodeFromString(Stored.serializer(), file.readText())
    } catch (_: Exception) {
      // Never the exception's message: a parser quotes the text around the error, which is a key.
      warn("could not read ${file.path}; it is not a $SCHEMA_V1 file")
      Stored()
    }
  }

  /**
   * [file]'s contents to change: empty when there is no file, and an exception rather than an empty
   * store when it cannot be read or is another schema, so a change never discards what it holds.
   */
  private fun forChange(file: File): Stored {
    if (!file.exists()) return Stored()
    val stored =
      try {
        JSON.decodeFromString(Stored.serializer(), file.readText())
      } catch (_: Exception) {
        null
      }
    check(stored != null && stored.schema == SCHEMA_V1) {
      "${file.path} is not a $SCHEMA_V1 file, so it was left as it is; fix or remove it first"
    }
    return stored
  }

  private fun write(file: File, stored: Stored) {
    val dir = file.absoluteFile.parentFile
    dir.mkdirs()
    // `createTempFile` needs a prefix of at least three characters.
    val temp = File.createTempFile(file.name.padEnd(3, '-'), ".tmp", dir)
    try {
      restrict(temp)
      temp.writeText(JSON.encodeToString(Stored.serializer(), stored) + "\n")
      Files.move(
        temp.toPath(),
        file.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (e: Exception) {
      temp.delete()
      throw e
    }
  }

  /** `0600` before anything is written, where the filesystem has POSIX permissions. */
  private fun restrict(target: File) {
    val view =
      Files.getFileAttributeView(target.toPath(), PosixFileAttributeView::class.java) ?: return
    view.setPermissions(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
  }

  private fun readableByOthers(file: File): Boolean = runCatching {
    Files.getFileAttributeView(file.toPath(), PosixFileAttributeView::class.java)
      ?.readAttributes()
      ?.permissions()
      ?.any { it == PosixFilePermission.GROUP_READ || it == PosixFilePermission.OTHERS_READ }
      ?: false
  }
    .getOrDefault(false)

  private fun absolute(candidate: String?): String? =
    candidate?.trim()?.takeIf { it.isNotEmpty() && it != "?" && File(it).isAbsolute }
}
