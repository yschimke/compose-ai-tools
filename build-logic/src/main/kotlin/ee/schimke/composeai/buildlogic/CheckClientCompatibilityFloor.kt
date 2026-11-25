package ee.schimke.composeai.buildlogic

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

/**
 * Enforces published client-compatibility floors on a module's resolved classpaths:
 * 1. JVM bytecode floor (class file major <= [maxJavaMajor] + 44, e.g. Java 17 = 61) across
 *    production runtime JARs, catching Java 21+/25+ dependencies such as ClassGraph 6.
 * 2. Module-version ceilings ([versionCeilings]) across resolved compile/runtime classpaths,
 *    preventing Renovate or manual bumps from silently raising published consumer compatibility
 *    floors (such as `compose-bom-compat`).
 * 3. Lockstep version pairs ([lockstepPairs]) where two coordinates on the same configuration must
 *    resolve to the same version (such as `org.slf4j:slf4j-nop` matching `org.slf4j:slf4j-api` from
 *    `gradle-tooling-api`).
 */
abstract class CheckClientCompatibilityFloor : DefaultTask() {

  @get:Input abstract val maxJavaMajor: Property<Int>

  @get:Classpath @get:Optional abstract val runtimeClasspath: ConfigurableFileCollection

  /** `<configuration>\t<group>:<module>\t<version>` entries from resolved classpaths. */
  @get:Input abstract val resolvedVersions: ListProperty<String>

  /** `<groupPrefixOrCoordinate>\t<maxMajor>.<maxMinor>\t<reason>` ceiling rules. */
  @get:Input abstract val versionCeilings: ListProperty<String>

  /** `<leftCoordinate>\t<rightCoordinate>\t<reason>` lockstep rules. */
  @get:Input abstract val lockstepPairs: ListProperty<String>

  @TaskAction
  fun checkFloor() {
    val maxJava = maxJavaMajor.get()
    val maxClassMajor = maxJava + 44
    val bytecodeOffenders =
      ClientCompatibilityFloor.findBytecodeOffenders(runtimeClasspath.files, maxClassMajor)

    val ceilings = versionCeilings.get().map(ClientCompatibilityFloor.Ceiling::parse)
    val pairs = lockstepPairs.get().map(ClientCompatibilityFloor.LockstepPair::parse)
    val resolved = resolvedVersions.get().map(ClientCompatibilityFloor.ResolvedModule::parse)
    val versionOffenders = ClientCompatibilityFloor.findVersionOffenders(resolved, ceilings)
    val lockstepOffenders = ClientCompatibilityFloor.findLockstepOffenders(resolved, pairs)

    val problems = buildList {
      if (bytecodeOffenders.isNotEmpty()) {
        add(
          "Production runtime bytecode exceeds Java $maxJava (class file major $maxClassMajor): " +
            bytecodeOffenders.take(10).joinToString(", ") +
            if (bytecodeOffenders.size > 10) ", … (${bytecodeOffenders.size} total)" else ""
        )
      }
      versionOffenders.forEach(::add)
      lockstepOffenders.forEach(::add)
    }

    check(problems.isEmpty()) {
      "Client compatibility floor violated in ${path.substringBeforeLast(':')}:\n" +
        problems.joinToString("\n") { "  - $it" } +
        "\nSee docs/SDK_COMPATIBILITY.md before intentionally raising a published floor."
    }
  }
}

internal object ClientCompatibilityFloor {

  data class ResolvedModule(val configuration: String, val coordinate: String, val version: String) {
    companion object {
      fun parse(encoded: String): ResolvedModule {
        val parts = encoded.split('\t')
        require(parts.size == 3) { "Malformed resolved module entry: $encoded" }
        return ResolvedModule(parts[0], parts[1], parts[2])
      }
    }
  }

  data class Ceiling(
    val coordinatePrefix: String,
    val maxMajor: Int,
    val maxMinor: Int,
    val reason: String,
  ) {
    fun matches(coordinate: String): Boolean =
      if (coordinatePrefix.endsWith(":")) {
        coordinate.startsWith(coordinatePrefix)
      } else {
        coordinate == coordinatePrefix
      }

    fun encode(): String = "$coordinatePrefix\t$maxMajor.$maxMinor\t$reason"

    companion object {
      fun parse(encoded: String): Ceiling {
        val parts = encoded.split('\t')
        require(parts.size == 3) { "Malformed ceiling entry: $encoded" }
        val (major, minor) = parts[1].split('.').map(String::toInt)
        return Ceiling(parts[0], major, minor, parts[2])
      }
    }
  }

  data class LockstepPair(
    val leftCoordinate: String,
    val rightCoordinate: String,
    val reason: String,
  ) {
    fun encode(): String = "$leftCoordinate\t$rightCoordinate\t$reason"

    companion object {
      fun parse(encoded: String): LockstepPair {
        val parts = encoded.split('\t')
        require(parts.size == 3) { "Malformed lockstep pair entry: $encoded" }
        return LockstepPair(parts[0], parts[1], parts[2])
      }
    }
  }

  fun findVersionOffenders(
    resolved: List<ResolvedModule>,
    ceilings: List<Ceiling>,
  ): List<String> {
    if (ceilings.isEmpty()) return emptyList()
    val offenders = mutableListOf<String>()
    for (entry in resolved) {
      val parsed = parseMajorMinor(entry.version) ?: continue
      for (ceiling in ceilings) {
        if (!ceiling.matches(entry.coordinate)) continue
        if (parsed.first > ceiling.maxMajor ||
            (parsed.first == ceiling.maxMajor && parsed.second > ceiling.maxMinor)
        ) {
          offenders +=
            "${entry.coordinate}:${entry.version} [${entry.configuration}] exceeds " +
              "${ceiling.maxMajor}.${ceiling.maxMinor}.x floor (${ceiling.reason})"
        }
      }
    }
    return offenders.distinct().sorted()
  }

  fun findLockstepOffenders(
    resolved: List<ResolvedModule>,
    pairs: List<LockstepPair>,
  ): List<String> {
    if (pairs.isEmpty()) return emptyList()
    val byConfig = resolved.groupBy { it.configuration }
    val offenders = mutableListOf<String>()
    for ((config, modules) in byConfig) {
      val versions = modules.associate { it.coordinate to it.version }
      for (pair in pairs) {
        val left = versions[pair.leftCoordinate] ?: continue
        val right = versions[pair.rightCoordinate] ?: continue
        if (left != right) {
          offenders +=
            "${pair.leftCoordinate}:$left != ${pair.rightCoordinate}:$right [$config] (${pair.reason})"
        }
      }
    }
    return offenders.distinct().sorted()
  }

  fun findBytecodeOffenders(files: Iterable<File>, maxClassMajor: Int): List<String> {
    val offenders = mutableListOf<String>()
    for (entry in files) {
      if (!entry.exists()) continue
      if (entry.isDirectory) {
        entry
          .walkTopDown()
          .filter { it.isFile && it.extension == "class" && it.name != "module-info.class" }
          .forEach { classFile ->
            classFile.inputStream().use { stream ->
              majorVersion(stream)?.let { major ->
                if (major > maxClassMajor) offenders += "${entry.name}!${classFile.name} (major $major)"
              }
            }
          }
      } else if (entry.name.endsWith(".jar")) {
        ZipFile(entry).use { jar ->
          jar
            .entries()
            .asSequence()
            .filter { it.name.endsWith(".class") && !it.isDirectory }
            .filterNot {
              it.name.startsWith("META-INF/versions/") || it.name.endsWith("module-info.class")
            }
            .forEach { zipEntry ->
              jar.getInputStream(zipEntry).use { stream ->
                majorVersion(stream)?.let { major ->
                  if (major > maxClassMajor) offenders += "${entry.name}!${zipEntry.name} (major $major)"
                }
              }
            }
        }
      }
    }
    return offenders.distinct().sorted()
  }

  internal fun parseMajorMinor(version: String): Pair<Int, Int>? {
    val core = version.substringBefore('-').substringBefore('+')
    val parts = core.split('.')
    if (parts.size < 2) return null
    val major = parts[0].toIntOrNull() ?: return null
    val minor = parts[1].toIntOrNull() ?: return null
    return major to minor
  }

  internal fun majorVersion(stream: InputStream): Int? {
    val header = ByteArray(8)
    var read = 0
    while (read < header.size) {
      val next = stream.read(header, read, header.size - read)
      if (next < 0) return null
      read += next
    }
    val magic =
      ((header[0].toInt() and 0xff) shl 24) or
        ((header[1].toInt() and 0xff) shl 16) or
        ((header[2].toInt() and 0xff) shl 8) or
        (header[3].toInt() and 0xff)
    if (magic != -0x35014542) return null
    return ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
  }

  fun isProductionRuntimeClasspath(name: String): Boolean {
    if (name == "runtimeClasspath" || name == "jvmRuntimeClasspath") return true
    if (!name.endsWith("RuntimeClasspath")) return false
    val lower = name.lowercase()
    return listOf("test", "androidtest", "unittest", "lint", "screenshot").none(lower::contains)
  }

  fun isProductionCompileClasspath(name: String): Boolean {
    if (name == "compileClasspath" || name == "jvmCompileClasspath") return true
    if (!name.endsWith("CompileClasspath")) return false
    val lower = name.lowercase()
    return listOf("test", "androidtest", "unittest", "lint", "screenshot").none(lower::contains)
  }
}
