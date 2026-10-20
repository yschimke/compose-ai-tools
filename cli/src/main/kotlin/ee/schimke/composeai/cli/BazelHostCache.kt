package ee.schimke.composeai.cli

import ee.schimke.composeai.bundle.producer.bazelBundleSha
import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
 */
internal class BazelHostCache(
  val root: File,
  clock: () -> Long = System::currentTimeMillis,
) {
  /** [classifyRuntimeJar] per jar: what pruning needs to know about it. */
  val jarContents = StampedFileMemo(root.resolve("jar-contents.json"), clock)

  /** [bazelBundleSha] per file. */
  val sha256 = StampedFileMemo(root.resolve("sha256.json"), clock)

  /** [AndroidPlatformLevel.fromContents] per `android.jar`; [NONE] when it names no level. */
  val androidJarLevel = StampedFileMemo(root.resolve("android-jar-level.json"), clock)

  /** Values keyed by a digest of everything they depend on ([provisionedSdkLevel]). */
  val keyed = KeyedMemo(root.resolve("keyed.json"))

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
  fun discoveryFile(targetKey: String): File = root.resolve("discovery/$targetKey.json")

  /** Writes whichever memos changed. A failure only loses the entries; it never fails a render. */
  fun save() {
    listOf(jarContents, sha256, androidJarLevel).forEach { it.save() }
    keyed.save()
  }

  companion object {
    /** `0`, `false` or `no` turns the cache off; every value is then computed afresh. */
    const val DISABLE_ENV = "COMPOSE_PREVIEW_BAZEL_HOST_CACHE"

    const val NONE = "none"

    private val instance by lazy { BazelHostCache(composeAiCacheDir("bazel-host")) }

    /** The process-wide cache under `composeAiCacheDir("bazel-host")`, or null when disabled. */
    fun shared(env: (String) -> String? = System::getenv): BazelHostCache? =
      if (env(DISABLE_ENV)?.trim()?.lowercase() in setOf("0", "false", "no")) null else instance
  }
}

/**
 * What identifies one version of a file without reading it: size, modification time and, where the
 * filesystem has them, the inode-change time and inode. Size and mtime alone are not enough: a
 * cache restore or `cp -p` replaces a file keeping both, but cannot keep its ctime, which only the
 * kernel sets. Null for a file that is not a regular file, which is then never memoised.
 *
 * Bazel rewrites outputs in place, and on a filesystem with coarse timestamps (one or two seconds)
 * two writes in the same tick of the same size are indistinguishable. As git does for its index, a
 * value is trusted only when the file last changed [RACY_WINDOW_MS] before the value was recorded
 * ([settledBefore]); a file changed later is read again on the next run, until it has settled.
 */
internal data class FileStamp(val text: String, val changedAtMs: Long) {
  fun settledBefore(recordedAtMs: Long): Boolean = changedAtMs < recordedAtMs - RACY_WINDOW_MS

  companion object {
    /** Longer than the coarsest timestamp granularity in use (FAT's two seconds). */
    const val RACY_WINDOW_MS = 3_000L

    fun of(file: File): FileStamp? = runCatching {
      val path = file.toPath()
      val unix = runCatching {
        Files.readAttributes(path, "unix:size,lastModifiedTime,ctime,ino")
      }
        .getOrNull()
      if (unix != null) {
        if (!Files.isRegularFile(path)) return@runCatching null
        val mtime = unix["lastModifiedTime"] as FileTime
        val ctime = unix["ctime"] as FileTime
        FileStamp(
          "${unix["size"]}:${mtime.to(TimeUnit.NANOSECONDS)}:" +
            "${ctime.to(TimeUnit.NANOSECONDS)}:${unix["ino"]}",
          maxOf(mtime.toMillis(), ctime.toMillis()),
        )
      } else {
        val basic = Files.readAttributes(path, BasicFileAttributes::class.java)
        if (!basic.isRegularFile) return@runCatching null
        val mtime = basic.lastModifiedTime()
        val created = basic.creationTime()
        FileStamp(
          "${basic.size()}:${mtime.to(TimeUnit.NANOSECONDS)}:" +
            "${created.to(TimeUnit.NANOSECONDS)}:${basic.fileKey()}",
          maxOf(mtime.toMillis(), created.toMillis()),
        )
      }
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
  private val file: File,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val entries: ConcurrentHashMap<String, StampedEntry> by lazy {
    ConcurrentHashMap(
      runCatching {
          cacheJson.decodeFromString<Map<String, StampedEntry>>(file.readText(Charsets.UTF_8))
        }
        .getOrDefault(emptyMap())
    )
  }
  private val dirty = AtomicBoolean(false)

  /**
   * [target]'s memoised value, else [compute]'s, which is recorded unless it is null or [target]
   * changed while it ran.
   */
  fun get(target: File, compute: (File) -> String?): String? {
    val key = target.absolutePath
    val recordedAt = clock()
    val stamp = FileStamp.of(target) ?: return compute(target)
    entries[key]
      ?.takeIf { it.stamp == stamp.text && stamp.settledBefore(it.recordedAt) }
      ?.let {
        return it.value
      }
    val value = compute(target) ?: return null
    if (FileStamp.of(target)?.text == stamp.text) {
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
    writeAtomically(file, cacheJson.encodeToString(kept))
  }

  companion object {
    const val MAX_ENTRIES = 50_000
  }
}

/** Values keyed by a caller's digest of their inputs, kept atomically like [StampedFileMemo]. */
internal class KeyedMemo(private val file: File) {
  private val entries: ConcurrentHashMap<String, String> by lazy {
    ConcurrentHashMap(
      runCatching { cacheJson.decodeFromString<Map<String, String>>(file.readText(Charsets.UTF_8)) }
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
internal fun writeAtomically(file: File, text: String) {
  runCatching {
    file.parentFile?.mkdirs()
    val temp = Files.createTempFile(file.parentFile.toPath(), file.name, ".tmp")
    try {
      Files.writeString(temp, text)
      runCatching {
        Files.move(
          temp,
          file.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE,
        )
      }
        .getOrElse { Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    } finally {
      Files.deleteIfExists(temp)
    }
  }
}
