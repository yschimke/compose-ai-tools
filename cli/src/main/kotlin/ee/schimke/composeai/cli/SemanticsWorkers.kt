package ee.schimke.composeai.cli

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How many daemons `bundle pack --with-semantics` drives in parallel (`--semantics-workers`).
 *
 * Every daemon is a separate JVM that loads the module's full classpath and previews.json, so the
 * cost is memory as much as cores. The default stays 1 (today's behaviour); `auto` sizes it from
 * the host.
 */
internal object SemanticsWorkers {
  const val FLAG = "--semantics-workers"

  /** Env fallback, so a CI workflow can set it once instead of on every `bundle pack` line. */
  const val ENV_VAR = "COMPOSE_PREVIEW_SEMANTICS_WORKERS"

  /** Upper bound for `auto` and for an explicit value. */
  const val MAX = 8

  /** Below this many previews per daemon, its JVM boot and warm-up outweigh what it renders. */
  const val MIN_PREVIEWS_PER_WORKER = 50

  /** Heap assumed when the launch descriptor carries no `-Xmx`. */
  private const val DEFAULT_HEAP_MB = 1024L

  /** Per-daemon memory beyond its heap: metaspace, code cache, Skia / Robolectric native memory. */
  private const val NON_HEAP_OVERHEAD_MB = 512L

  /** Left for the CLI, the Gradle daemon and the OS before any render daemon is counted. */
  private const val RESERVED_MB = 2048L

  /** The raw `--semantics-workers` value, else [ENV_VAR]; null means the default of one daemon. */
  fun specFrom(args: List<String>, env: (String) -> String? = System::getenv): String? =
    args.flagValue(FLAG)?.trim()?.takeIf { it.isNotEmpty() }
      ?: env(ENV_VAR)?.trim()?.takeIf { it.isNotEmpty() }

  /**
   * The usage error for a malformed [spec], or null when it is absent, `auto` or a positive int.
   */
  fun problemWith(spec: String?): String? =
    if (spec == null || spec.equals("auto", ignoreCase = true) || (spec.toIntOrNull() ?: 0) >= 1)
      null
    else "$FLAG expects a positive integer or 'auto', got '$spec'"

  /**
   * Resolves [spec] (`null`, a positive integer, or `auto`) to a worker count for [previewCount]
   * previews. Throws [IllegalArgumentException] for anything else, so a typo isn't silently serial.
   */
  fun resolve(
    spec: String?,
    previewCount: Int,
    descriptorFile: File?,
    cores: Int = Runtime.getRuntime().availableProcessors(),
    availableMemoryMb: () -> Long? = { SemanticsWorkers.availableMemoryMb() },
  ): Int {
    if (spec == null) return 1
    if (spec.equals("auto", ignoreCase = true)) {
      return auto(
        cores = cores,
        availableMemoryMb = availableMemoryMb(),
        heapMb = descriptorFile?.let(::heapMbOf) ?: DEFAULT_HEAP_MB,
        previewCount = previewCount,
      )
    }
    problemWith(spec)?.let { throw IllegalArgumentException(it) }
    return spec.toInt().coerceAtMost(MAX)
  }

  /**
   * The `auto` policy: half the cores (each daemon keeps a render thread, the GC and Skia busy),
   * bounded by what memory fits after [RESERVED_MB], by one daemon per [MIN_PREVIEWS_PER_WORKER]
   * previews, and by [MAX]. Never below one. A standard 4-core / 16 GB CI runner gets 2.
   */
  fun auto(cores: Int, availableMemoryMb: Long?, heapMb: Long, previewCount: Int): Int {
    val byCpu = (cores / 2).coerceAtLeast(1)
    val perDaemonMb = heapMb + NON_HEAP_OVERHEAD_MB
    val byMemory =
      if (availableMemoryMb == null) 1
      else ((availableMemoryMb - RESERVED_MB) / perDaemonMb).toInt().coerceAtLeast(1)
    val byPreviews = (previewCount / MIN_PREVIEWS_PER_WORKER).coerceAtLeast(1)
    return minOf(byCpu, byMemory, byPreviews, MAX)
  }

  /** The `-Xmx` in the launch descriptor's `jvmArgs`, in MB; null when absent or unreadable. */
  fun heapMbOf(descriptorFile: File): Long? = runCatching {
    Json.parseToJsonElement(descriptorFile.readText())
      .jsonObject["jvmArgs"]
      ?.jsonArray
      ?.map { it.jsonPrimitive.content }
      ?.lastOrNull { it.startsWith("-Xmx") }
      ?.let { parseHeapMb(it.removePrefix("-Xmx")) }
  }
    .getOrNull()

  /** `4g` → 4096, `1536m` → 1536, `2097152k` → 2048; null for anything else. */
  fun parseHeapMb(value: String): Long? {
    val v = value.trim().lowercase()
    val digits = v.takeWhile(Char::isDigit)
    val n = digits.toLongOrNull() ?: return null
    return when (v.removePrefix(digits)) {
      "g" -> n * 1024
      "m" -> n
      "k" -> n / 1024
      "" -> n / (1024 * 1024)
      else -> null
    }
  }

  /**
   * Memory a new daemon could actually get: the smaller of the host's `MemAvailable` and what the
   * enclosing cgroup still allows (a container's limit, which `/proc/meminfo` doesn't reflect).
   * Falls back to half the physical memory when neither is readable. [procRoot] and [cgroupRoot]
   * are injectable for tests.
   */
  fun availableMemoryMb(
    procRoot: File = File("/proc"),
    cgroupRoot: File = File("/sys/fs/cgroup"),
  ): Long? {
    val host = memAvailableMb(procRoot)
    val cgroup = cgroupRemainingMb(cgroupRoot)
    return listOfNotNull(host, cgroup).minOrNull()
      ?: runCatching {
        val os =
          java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            as com.sun.management.OperatingSystemMXBean
        os.totalMemorySize / MB / 2
      }
        .getOrNull()
  }

  /** `MemAvailable` from `<procRoot>/meminfo`, which counts reclaimable page cache. */
  private fun memAvailableMb(procRoot: File): Long? = runCatching {
    File(procRoot, "meminfo")
      .takeIf { it.isFile }
      ?.useLines { lines -> lines.firstOrNull { it.startsWith("MemAvailable:") } }
      ?.split(Regex("\\s+"))
      ?.getOrNull(1)
      ?.toLongOrNull()
      ?.let { kb -> kb / 1024 }
  }
    .getOrNull()

  /**
   * The cgroup's remaining allowance: v2 `memory.max - memory.current` (`max` is unlimited), else
   * v1 `memory/memory.limit_in_bytes - memory/memory.usage_in_bytes` (v1 reports "unlimited" as a
   * huge page-rounded value, so anything above [CGROUP_V1_UNLIMITED] counts as unlimited). Null
   * when unlimited or unreadable.
   */
  fun cgroupRemainingMb(cgroupRoot: File): Long? {
    fun read(name: String): String? {
      val file = File(cgroupRoot, name)
      return if (file.isFile) runCatching { file.readText().trim() }.getOrNull() else null
    }
    val v2Max = read("memory.max")
    if (v2Max != null) {
      if (v2Max == "max") return null
      val limit = v2Max.toLongOrNull() ?: return null
      val used = read("memory.current")?.toLongOrNull() ?: 0L
      return ((limit - used) / MB).coerceAtLeast(0)
    }
    val v1Limit = read("memory/memory.limit_in_bytes")?.toLongOrNull() ?: return null
    if (v1Limit >= CGROUP_V1_UNLIMITED) return null
    val used = read("memory/memory.usage_in_bytes")?.toLongOrNull() ?: 0L
    return ((v1Limit - used) / MB).coerceAtLeast(0)
  }

  private const val MB = 1024L * 1024L

  /** cgroup v1's "no limit" is `LONG_MAX` rounded to a page; nothing real comes near 2^60. */
  private const val CGROUP_V1_UNLIMITED = 1L shl 60
}
