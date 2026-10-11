package ee.schimke.composeai.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SemanticsWorkersTest {

  @Test
  fun `no flag and no env keeps today's single daemon`() {
    assertNull(SemanticsWorkers.specFrom(emptyList(), env = { null }))
    assertEquals(
      1,
      SemanticsWorkers.resolve(spec = null, previewCount = 5000, descriptorFile = null),
    )
  }

  @Test
  fun `the flag wins over the env var`() {
    val env = mapOf(SemanticsWorkers.ENV_VAR to "auto")
    assertEquals("3", SemanticsWorkers.specFrom(listOf("--semantics-workers", "3"), env::get))
    assertEquals("auto", SemanticsWorkers.specFrom(emptyList(), env::get))
  }

  @Test
  fun `an explicit count is used as given, capped`() {
    assertEquals(3, SemanticsWorkers.resolve("3", previewCount = 10, descriptorFile = null))
    assertEquals(
      SemanticsWorkers.MAX,
      SemanticsWorkers.resolve("64", previewCount = 10, descriptorFile = null),
    )
  }

  @Test
  fun `a malformed count is a usage error, not a silent fallback`() {
    assertEquals(
      "--semantics-workers expects a positive integer or 'auto', got '0'",
      SemanticsWorkers.problemWith("0"),
    )
    assertNull(SemanticsWorkers.problemWith("auto"))
    assertNull(SemanticsWorkers.problemWith("2"))
    assertFailsWith<IllegalArgumentException> {
      SemanticsWorkers.resolve("two", previewCount = 10, descriptorFile = null)
    }
  }

  @Test
  fun `auto takes half the cores when memory and previews allow`() {
    // A standard 4-core / 16 GB GitHub runner with ~12 GB available and a 2 GB daemon heap.
    assertEquals(
      2,
      SemanticsWorkers.auto(
        cores = 4,
        availableMemoryMb = 12_000,
        heapMb = 2048,
        previewCount = 1035,
      ),
    )
  }

  @Test
  fun `auto is bounded by memory`() {
    // 6 GB available, 2 GB reserved, 2.5 GB per daemon: one fits.
    assertEquals(
      1,
      SemanticsWorkers.auto(
        cores = 16,
        availableMemoryMb = 6_000,
        heapMb = 2048,
        previewCount = 5000,
      ),
    )
    assertEquals(
      1,
      SemanticsWorkers.auto(
        cores = 16,
        availableMemoryMb = null,
        heapMb = 2048,
        previewCount = 5000,
      ),
    )
  }

  @Test
  fun `auto does not start a daemon for a handful of previews`() {
    assertEquals(
      1,
      SemanticsWorkers.auto(
        cores = 16,
        availableMemoryMb = 64_000,
        heapMb = 1024,
        previewCount = 60,
      ),
    )
    assertEquals(
      SemanticsWorkers.MAX,
      SemanticsWorkers.auto(
        cores = 64,
        availableMemoryMb = 256_000,
        heapMb = 1024,
        previewCount = 10_000,
      ),
    )
  }

  @Test
  fun `auto reads the daemon heap from the launch descriptor`() {
    val descriptor =
      File.createTempFile("daemon-launch", ".json").apply {
        deleteOnExit()
        writeText("""{"schemaVersion":2,"jvmArgs":["--add-opens=x","-Xmx3g"]}""")
      }
    assertEquals(3072L, SemanticsWorkers.heapMbOf(descriptor))
    // 3 GB heap + 512 MB per daemon: (12000 - 2048) / 3584 = 2.
    assertEquals(
      2,
      SemanticsWorkers.resolve(
        "auto",
        5000,
        descriptor,
        cores = 32,
        availableMemoryMb = { 12_000 },
      ),
    )
  }

  @Test
  fun `heap sizes parse in every unit the JVM accepts`() {
    assertEquals(4096L, SemanticsWorkers.parseHeapMb("4g"))
    assertEquals(1536L, SemanticsWorkers.parseHeapMb("1536M"))
    assertEquals(2048L, SemanticsWorkers.parseHeapMb("2097152k"))
    assertEquals(1024L, SemanticsWorkers.parseHeapMb("1073741824"))
    assertNull(SemanticsWorkers.parseHeapMb("lots"))
  }

  private fun tree(vararg files: Pair<String, String>): File =
    java.nio.file.Files.createTempDirectory("semantics-workers-mem").toFile().apply {
      deleteOnExit()
      for ((name, text) in files) File(this, name).apply { parentFile.mkdirs() }.writeText(text)
    }

  private val gib = 1024L * 1024L * 1024L

  @Test
  fun `auto memory is bounded by a cgroup v2 limit, not just MemAvailable`() {
    // A 64 GB host with plenty available, inside an 8 GB container already using 3 GB.
    val proc = tree("meminfo" to "MemTotal: 67108864 kB\nMemAvailable: 50331648 kB\n")
    val cgroup = tree("memory.max" to "${8 * gib}\n", "memory.current" to "${3 * gib}\n")

    assertEquals(5 * 1024L, SemanticsWorkers.availableMemoryMb(proc, cgroup))
  }

  @Test
  fun `an unlimited cgroup v2 leaves MemAvailable in charge`() {
    val proc = tree("meminfo" to "MemAvailable: 8388608 kB\n")
    val cgroup = tree("memory.max" to "max\n", "memory.current" to "${1 * gib}")

    assertEquals(8192L, SemanticsWorkers.availableMemoryMb(proc, cgroup))
    assertNull(SemanticsWorkers.cgroupRemainingMb(cgroup))
  }

  @Test
  fun `cgroup v1 limits apply, and its huge no-limit value counts as unlimited`() {
    val limited =
      tree(
        "memory/memory.limit_in_bytes" to "${4 * gib}",
        "memory/memory.usage_in_bytes" to "${1 * gib}",
      )
    assertEquals(3 * 1024L, SemanticsWorkers.cgroupRemainingMb(limited))

    val unlimited =
      tree(
        "memory/memory.limit_in_bytes" to "9223372036854771712",
        "memory/memory.usage_in_bytes" to "${1 * gib}",
      )
    assertNull(SemanticsWorkers.cgroupRemainingMb(unlimited))
  }

  @Test
  fun `a cgroup allowance alone is used when meminfo is unreadable`() {
    val cgroup = tree("memory.max" to "${6 * gib}", "memory.current" to "0")

    assertEquals(6 * 1024L, SemanticsWorkers.availableMemoryMb(tree(), cgroup))
  }
}
