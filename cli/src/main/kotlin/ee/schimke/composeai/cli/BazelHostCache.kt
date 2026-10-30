package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.producer.bazelBundleSha
import ee.schimke.composeai.io.SystemFileSystem
import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Host-side memos that let a warm `compose-preview bazel render` skip work whose inputs did not
 * change (#6104): which runtime jars pruning drops, each dependency jar's SHA-256, the level an
 * `android.jar` declares, the provisioned Robolectric SDK, and a target's whole discovery result.
 *
 * Every value is keyed on a [FileStamp] of the file it was computed from, so a rebuilt, replaced or
 * restored file is read again; see [FileStamp] for what a stamp covers and why a file changed
 * moments before it was read is never trusted. A missing, unreadable or corrupt cache file is an
 * empty cache, and every file is replaced atomically, so the worst a cache can do is cost the work
 * it was meant to save.
 *
 * Applies to every bazel `list` / `render` / `show` / `bundle`, one-shot or [BazelKeepDaemon]:
 * nothing it serves can differ from a recomputation. [DISABLE_ENV] turns it off.
 *
 * [stamp] is how a file's version is identified: [FileStamper]'s unix tuple, or, where the build's
 * output tree publishes one ([DIGEST_XATTR_ENV], `digestXattr`), its content digest.
 *
 * The cache's own files under [root] go through [fileSystem]. The files it stamps stay
 * `java.io.File`s: a stamp reads the `unix` and `user` attribute views, which Okio does not expose.
 */
internal class BazelHostCache(
  val root: okio.Path,
  clock: () -> Long = System::currentTimeMillis,
  val stamp: (File) -> FileStamp? = FileStamper.UNIX::stamp,
  val fileSystem: FileSystem = SystemFileSystem,
) {
  /** [classifyRuntimeJar] per jar: what pruning needs to know about it. */
  val jarContents = StampedFileMemo(root / "jar-contents.json", clock, stamp, fileSystem)

  /** [bazelBundleSha] per file: the flat SHA-256, whichever kind of [FileStamp] keys it. */
  val sha256 = StampedFileMemo(root / "sha256.json", clock, stamp, fileSystem)

  /** [AndroidPlatformLevel.fromContents] per `android.jar`; [NONE] when it names no level. */
  val androidJarLevel = StampedFileMemo(root / "android-jar-level.json", clock, stamp, fileSystem)

  /** Values keyed by a digest of everything they depend on ([provisionedSdkLevel]). */
  val keyed = KeyedMemo(root / "keyed.json", fileSystem)

  /** [file]'s SHA-256, read again only when its [FileStamp] moved. */
  fun sha256(file: File): String = sha256.get(file) { bazelBundleSha(it) } ?: bazelBundleSha(file)

  /**
   * Fills the [sha256] memo for every regular file in [files] across cores, so a cold run (or one
   * after a dependency bump) hashes ~300 jars in parallel rather than one at a time.
   */
  fun prefetchSha256(files: Collection<File>) {
    val pending = files.filter { it.isFile }.distinct()
    if (pending.size < 2) return
    val pool =
      Executors.newFixedThreadPool(
        minOf(pending.size, Runtime.getRuntime().availableProcessors().coerceIn(1, 8))
      ) { task ->
        Thread(task, "jar-hash").apply { isDaemon = true }
      }
    try {
      pending.map { file -> pool.submit { runCatching { sha256(file) } } }.forEach { it.get() }
    } finally {
      pool.shutdownNow()
    }
  }

  /** Where a target's discovery result is kept: one file per target, replaced on each miss. */
  fun discoveryFile(targetKey: String): okio.Path = root / "discovery" / "$targetKey.json"

  /** [file]'s text, or null when it is missing or unreadable. */
  fun readText(file: okio.Path): String? = runCatching {
    fileSystem.read(file) { readUtf8() }
  }
    .getOrNull()

  /** Replaces [file] with [text] atomically ([writeAtomically]). */
  fun writeText(file: okio.Path, text: String) = writeAtomically(file, text, fileSystem)

  /** Writes whichever memos changed. A failure only loses the entries; it never fails a render. */
  fun save() {
    listOf(jarContents, sha256, androidJarLevel).forEach { it.save() }
    keyed.save()
  }

  companion object {
    /** `0`, `false` or `no` turns the cache off; every value is then computed afresh. */
    const val DISABLE_ENV = "COMPOSE_PREVIEW_BAZEL_HOST_CACHE"

    /**
     * Names the extended attribute through which the build's output filesystem publishes each
     * file's content digest (#6104), as `digestXattr` in bazel.json does; set, it wins over the
     * file, and set empty it turns a committed `digestXattr` off. See [FileStamper].
     */
    const val DIGEST_XATTR_ENV = "COMPOSE_PREVIEW_BAZEL_DIGEST_XATTR"

    const val NONE = "none"

    @Volatile private var sharedStamper: FileStamper = FileStamper.UNIX

    private val instance by lazy {
      BazelHostCache(
        composeAiCacheDir("bazel-host").toOkioPath(),
        stamp = { sharedStamper.stamp(it) },
      )
    }

    /** The process-wide cache under `composeAiCacheDir("bazel-host")`, or null when disabled. */
    fun shared(env: (String) -> String? = System::getenv): BazelHostCache? =
      if (env(DISABLE_ENV)?.trim()?.lowercase() in setOf("0", "false", "no")) null else instance

    /** The [shared] cache's stamper, as [configure] last set it. */
    val sharedFileStamper: FileStamper
      get() = sharedStamper

    /**
     * The digest attribute [config] and [env] name, and where it came from, or null when neither
     * names one. A machine's environment wins over a committed bazel.json: the attribute is a
     * property of the output filesystem mounted on that machine, not of the workspace.
     */
    fun digestAttribute(
      config: BazelBuildConfig,
      env: (String) -> String? = System::getenv,
    ): DigestAttributeSetting? {
      val fromEnv = env(DIGEST_XATTR_ENV)
      val (raw, source) =
        if (fromEnv != null) fromEnv to "\$$DIGEST_XATTR_ENV"
        else (config.digestXattr ?: return null) to "digestXattr in bazel.json"
      return FileStamper.normalizeAttribute(raw)?.let { DigestAttributeSetting(it, source) }
    }

    /** Points the [shared] cache's stamps at [config]'s digest attribute ([digestAttribute]). */
    fun configure(
      config: BazelBuildConfig,
      env: (String) -> String? = System::getenv,
    ): DigestAttributeSetting? =
      digestAttribute(config, env).also { sharedStamper = FileStamper(it?.attribute) }
  }
}

/** A digest attribute, without its `user.` namespace, and the setting that named it. */
internal data class DigestAttributeSetting(val attribute: String, val source: String)

/**
 * Stamps files for the memos. Without a digest [attribute], every stamp is [FileStamp.of]'s unix
 * tuple, exactly as before #6104's digest support.
 *
 * With one, a file whose `user.<attribute>` extended attribute reads back non-empty is stamped
 * `(size, those bytes)` instead ([FileStamp.digest]). That is for a content-addressed output tree,
 * typically a FUSE `bazel-out` that remounts on every build and so hands every file a fresh mtime,
 * ctime and inode although its bytes did not change: the unix tuple then misses for every jar, the
 * digest does not. The attribute is read by name only, never listed (such filesystems often list
 * none), and its bytes are opaque: an ASCII or binary, flat or chunked digest of any algorithm is
 * equally good as an identity, and is never taken for the file's SHA-256.
 *
 * Any file the attribute cannot be read from (a workspace or external jar outside the mounted
 * subtree, `ENODATA`; a filesystem without user attributes) falls back to the unix tuple. The
 * attribute is never guessed: one that is not derived from the file's content would serve stale
 * values, so it must be configured, and must name what the filesystem derives from content (the
 * attribute Bazel's `--unix_digest_hash_attribute_name` reads).
 */
internal class FileStamper(
  attribute: String?,
  readDigest: ((Path) -> ByteArray?)? = null,
  private val readUnix: (Path) -> Map<String, Any?>? = { FileStamp.unixAttributes(it) },
) {
  val attribute: String? = normalizeAttribute(attribute)
  private val readDigest: ((Path) -> ByteArray?)? =
    this.attribute?.let { name -> readDigest ?: { path -> readUserAttribute(path, name) } }

  /** How many stamps were digest-keyed, and how many fell back to the unix tuple. */
  val digestStamps = AtomicInteger()
  val unixStamps = AtomicInteger()

  fun stamp(file: File): FileStamp? {
    val read = readDigest ?: return FileStamp.of(file, readUnix)
    val digest =
      try {
        read(file.toPath())
      } catch (_: IOException) {
        null
      } catch (_: UnsupportedOperationException) {
        null
      } catch (_: IllegalArgumentException) {
        null
      }
    if (digest != null && digest.isNotEmpty()) {
      FileStamp.digest(file, attribute!!, digest)?.let {
        digestStamps.incrementAndGet()
        return it
      }
    }
    return FileStamp.of(file, readUnix).also { if (it != null) unixStamps.incrementAndGet() }
  }

  companion object {
    /** Stamps by the unix tuple alone. */
    val UNIX = FileStamper(null)

    /** [raw] trimmed and without a leading `user.`; null when that leaves nothing. */
    fun normalizeAttribute(raw: String?): String? =
      raw?.trim()?.removePrefix("user.")?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * `user.<name>` of [path], read by exact name through the `user` attribute view (Linux xattrs,
     * macOS extended attributes, NTFS alternate data streams); null when the view exists but yields
     * no bytes. Throws as `Files.getAttribute` does when it cannot be read.
     */
    fun readUserAttribute(path: Path, name: String): ByteArray? =
      Files.getAttribute(path, "user:$name") as? ByteArray
  }
}

/**
 * What identifies one version of a file without reading it: size, modification time, inode-change
 * time and inode ([of]), or, where the filesystem publishes one, size and content digest
 * ([digest]).
 *
 * The two kinds can never compare equal: a digest stamp's [text] starts `digest:`, a unix one with
 * the size's digits, so an entry recorded under one kind (including every entry written before
 * digest stamps existed, which are unix) is a miss for the other, and the JSON shape is unchanged.
 *
 * Unix stamps: size and mtime alone are not enough: a cache restore or `cp -p` replaces a file
 * keeping both, but cannot keep its ctime, which only the kernel sets. Null for a file that is not
 * a regular file, or on a filesystem without a `unix` attribute view (Windows), where no attribute
 * changes with an in-place rewrite: such a file is never memoised.
 *
 * Bazel rewrites outputs in place, and on a filesystem with coarse timestamps (one or two seconds)
 * two writes in the same tick of the same size are indistinguishable. As git does for its index, a
 * value is trusted only when the file last changed [RACY_WINDOW_MS] before the value was recorded
 * ([settledBefore]); a file changed later is read again on the next run, until it has settled.
 */
internal data class FileStamp(
  val text: String,
  val changedAtMs: Long,
  /**
   * A [digest] stamp. It names the content itself, so no rewrite can keep it while changing the
   * bytes, and the racy window, which only covers timestamps too coarse to see a rewrite, does not
   * apply: such a value is trusted as soon as it is recorded.
   */
  val contentAddressed: Boolean = false,
) {
  fun settledBefore(recordedAtMs: Long): Boolean =
    contentAddressed || changedAtMs < recordedAtMs - RACY_WINDOW_MS

  companion object {
    /** Longer than the coarsest timestamp granularity in use (FAT's two seconds). */
    const val RACY_WINDOW_MS = 3_000L

    const val DIGEST_PREFIX = "digest:"

    /**
     * [file]'s stamp from the content digest [digest] its filesystem published under `user.`
     * [attribute]: the size and the digest's bytes in hex, which no attribute other than those
     * moves. Needs no `unix` view, so it memoises on Windows too. Null for a non-regular file.
     */
    fun digest(file: File, attribute: String, digest: ByteArray): FileStamp? = runCatching {
      val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
      if (!attributes.isRegularFile) return@runCatching null
      FileStamp(
        "$DIGEST_PREFIX$attribute:${attributes.size()}:" +
          digest.joinToString("") { "%02x".format(it) },
        changedAtMs = 0L,
        contentAddressed = true,
      )
    }
      .getOrNull()

    /** The `unix` view's attributes, or null where the filesystem has none (Windows). */
    internal fun unixAttributes(path: Path): Map<String, Any?>? = runCatching {
      Files.readAttributes(path, "unix:size,lastModifiedTime,ctime,ino")
    }
      .getOrNull()

    fun of(
      file: File,
      readUnix: (Path) -> Map<String, Any?>? = { unixAttributes(it) },
    ): FileStamp? = runCatching {
      val path = file.toPath()
      val unix = readUnix(path)
      // Without an inode-change time (Windows, FAT) nothing identifies an in-place rewrite that
      // keeps size and mtime, so such a file is never memoised.
      if (unix == null || !Files.isRegularFile(path)) return@runCatching null
      val mtime = unix["lastModifiedTime"] as FileTime
      val ctime = unix["ctime"] as FileTime
      FileStamp(
        "${unix["size"]}:${mtime.to(TimeUnit.NANOSECONDS)}:" +
          "${ctime.to(TimeUnit.NANOSECONDS)}:${unix["ino"]}",
        maxOf(mtime.toMillis(), ctime.toMillis()),
      )
    }
      .getOrNull()
  }
}

@Serializable
internal data class StampedEntry(val stamp: String, val recordedAt: Long, val value: String)

private val cacheJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
}

/**
 * One value per file, by absolute path, valid while the file keeps its [FileStamp] and that stamp
 * had settled when the value was recorded. Safe across threads; [save] writes it atomically.
 */
internal class StampedFileMemo(
  private val file: okio.Path,
  private val clock: () -> Long = System::currentTimeMillis,
  private val stampOf: (File) -> FileStamp? = { FileStamp.of(it) },
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  private val entries: ConcurrentHashMap<String, StampedEntry> by lazy {
    ConcurrentHashMap(
      runCatching {
          cacheJson.decodeFromString<Map<String, StampedEntry>>(
            fileSystem.read(file) { readUtf8() }
          )
        }
        .getOrDefault(emptyMap())
    )
  }
  private val dirty = AtomicBoolean(false)

  /** Lookups served from the memo, and lookups that computed (a miss, or an unstampable file). */
  val hits = AtomicInteger()
  val misses = AtomicInteger()

  /**
   * [target]'s memoised value, else [compute]'s, which is recorded unless it is null or [target]
   * changed while it ran.
   */
  fun get(target: File, compute: (File) -> String?): String? {
    val key = target.absolutePath
    val recordedAt = clock()
    val stamp =
      stampOf(target)
        ?: run {
          misses.incrementAndGet()
          return compute(target)
        }
    entries[key]
      ?.takeIf { it.stamp == stamp.text && stamp.settledBefore(it.recordedAt) }
      ?.let {
        hits.incrementAndGet()
        return it.value
      }
    misses.incrementAndGet()
    val value = compute(target) ?: return null
    if (stampOf(target)?.text == stamp.text) {
      entries[key] = StampedEntry(stamp.text, recordedAt, value)
      dirty.set(true)
    }
    return value
  }

  fun save() {
    if (!dirty.compareAndSet(true, false)) return
    // Bounded: the entries recorded longest ago go first, so a cache shared by many workspaces
    // cannot grow without limit.
    val kept =
      entries.entries
        .sortedByDescending { it.value.recordedAt }
        .take(MAX_ENTRIES)
        .associate { it.key to it.value }
    writeAtomically(file, cacheJson.encodeToString(kept), fileSystem)
  }

  companion object {
    const val MAX_ENTRIES = 50_000
  }
}

/** Values keyed by a caller's digest of their inputs, kept atomically like [StampedFileMemo]. */
internal class KeyedMemo(
  private val file: okio.Path,
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  private val entries: ConcurrentHashMap<String, String> by lazy {
    ConcurrentHashMap(
      runCatching {
          cacheJson.decodeFromString<Map<String, String>>(fileSystem.read(file) { readUtf8() })
        }
        .getOrDefault(emptyMap())
    )
  }
  private val dirty = AtomicBoolean(false)

  operator fun get(key: String): String? = entries[key]

  operator fun set(key: String, value: String) {
    if (entries.put(key, value) != value) dirty.set(true)
  }

  fun save() {
    if (!dirty.compareAndSet(true, false)) return
    writeAtomically(
      file,
      cacheJson.encodeToString(
        entries.entries.toList().takeLast(1_000).associate { it.key to it.value }
      ),
      fileSystem,
    )
  }
}

/** SHA-256 of [parts], one per line: a memo key. */
internal fun cacheKey(parts: List<String>): String {
  val digest = MessageDigest.getInstance("SHA-256")
  parts.forEach { digest.update((it + "\n").toByteArray()) }
  return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Replaces [file] with [text] through a sibling temporary file and a rename, so a reader sees the
 * old contents or the new, never a torn write. Failures are swallowed: a cache that cannot be
 * written is only a cache that misses.
 */
internal fun writeAtomically(
  file: okio.Path,
  text: String,
  fileSystem: FileSystem = SystemFileSystem,
) {
  runCatching {
    val parent = file.parent
    if (parent != null) fileSystem.createDirectories(parent)
    val temp = (parent ?: file).resolve("${file.name}.${UUID.randomUUID()}.tmp")
    try {
      fileSystem.write(temp) { writeUtf8(text) }
      fileSystem.atomicMove(temp, file)
    } finally {
      fileSystem.delete(temp, mustExist = false)
    }
  }
}
