package ee.schimke.composeai.cli

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * An `android.link` block ([ee.schimke.composeai.buildartifacts.protocol.AndroidLinkInputs]),
 * resolved.
 */
internal data class ResolvedAndroidLink(
  val script: File,
  val aapt2: File,
  val javaHome: File,
  val androidJar: File,
  /** Every R package of the target's APK, its own first. */
  val packages: List<String>,
  /** Compiled resource archives in overlay order: every dependency before its dependents. */
  val compiledResources: List<File>,
  /** Path under `assets/` to the file or directory packed there, in order; later ones win. */
  val assets: List<Pair<String, File>>,
)

/**
 * One link over several Android targets: what `link.sh` is run with so the targets render from one
 * resource APK, the shape an `android_binary` or an AGP unit-test APK has.
 */
internal data class AndroidMergePlan(
  /** The APK's own package: the first target's, so `context.packageName` names a real library. */
  val packageName: String,
  /** Every other target's and dependency's R package; each gets an R class with the final IDs. */
  val extraPackages: List<String>,
  val compiledResources: List<File>,
  val assets: List<Pair<String, File>>,
  val compileSdk: Int?,
  /** The link script and tools: any target's would do, and they come from one configuration. */
  val tools: ResolvedAndroidLink,
  /** Each target's own final-ID R jar, whose IDs match only its own APK. */
  val replacedRJars: Set<File>,
)

/**
 * The single link [artifacts] render from, or null when any is not an Android target or its
 * producer did not report what its APK was linked from.
 */
internal fun planAndroidMerge(artifacts: List<ResolvedBazelArtifacts>): AndroidMergePlan? {
  val android = artifacts.map { it.android ?: return null }
  val links = android.map { it.link ?: return null }
  val packages = links.flatMap { it.packages }.distinct()
  // Later entries replace earlier ones at the same path, as staging does for one target.
  val assets = LinkedHashMap<String, File>()
  links.flatMap { it.assets }.forEach { (path, file) -> assets[path] = file }
  return AndroidMergePlan(
    packageName = android.first().packageName,
    extraPackages = packages - android.first().packageName,
    compiledResources = mergeOverlayOrder(links.map { it.compiledResources }),
    assets = assets.toList(),
    compileSdk = android.mapNotNull { it.compileSdk }.maxOrNull(),
    tools = links.first(),
    replacedRJars = android.mapNotNull { it.rJar }.toSet(),
  )
}

/**
 * One overlay order that keeps every target's own: each list is dependencies first, so a shared
 * dependency's archive comes before every target that depends on it. Archives no list orders
 * relative to each other (sibling libraries) keep the order they were first seen in. Two lists that
 * disagree (impossible from one build) fall back to first-seen order for what remains.
 */
internal fun <T> mergeOverlayOrder(lists: List<List<T>>): List<T> {
  val firstSeen = LinkedHashMap<T, Int>()
  lists.flatten().forEach { firstSeen.putIfAbsent(it, firstSeen.size) }
  val before = firstSeen.keys.associateWith { mutableSetOf<T>() }
  for (list in lists) {
    list.distinct().zipWithNext().forEach { (a, b) -> before.getValue(b) += a }
  }
  val remaining = firstSeen.keys.toMutableList()
  val placed = LinkedHashSet<T>()
  while (remaining.isNotEmpty()) {
    val next = remaining.firstOrNull { item -> before.getValue(item).all { it in placed } }
    if (next == null) {
      placed += remaining
      break
    }
    placed += next
    remaining -= next
  }
  return placed.toList()
}

/**
 * Runs [plan]'s link into [directory]: a synthesized manifest for the APK's package, the assets
 * listing and `link.sh` over the merged archives. Reused while its inputs are unchanged.
 */
internal fun linkAndroidMerge(
  plan: AndroidMergePlan,
  directory: File,
  timeoutSeconds: Long = 600,
): ResolvedAndroidArtifacts {
  directory.mkdirs()
  val manifest = directory.resolve("AndroidManifest.xml")
  val apk = directory.resolve("resources.ap_")
  val rJar = directory.resolve("r-final.jar")
  val assets = directory.resolve("assets.txt")
  val stamp = directory.resolve("link.fingerprint")
  val result = ResolvedAndroidArtifacts(plan.packageName, apk, manifest, rJar, plan.compileSdk)
  val fingerprint = androidMergeFingerprint(plan)
  if (apk.isFile && rJar.isFile && stamp.isFile && stamp.readText() == fingerprint) return result
  manifest.writeText(
    """
    <?xml version="1.0" encoding="utf-8"?>
    <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="${plan.packageName}">
      <application />
    </manifest>
    """
      .trimIndent() + "\n"
  )
  assets.writeText(plan.assets.joinToString("") { (path, file) -> "${file.absolutePath}\t$path\n" })
  val log = directory.resolve("link.log")
  val command =
    listOf(
      "/bin/sh",
      plan.tools.script.absolutePath,
      plan.tools.aapt2.absolutePath,
      plan.tools.javaHome.absolutePath,
      apk.absolutePath,
      rJar.absolutePath,
      manifest.absolutePath,
      plan.tools.androidJar.absolutePath,
      plan.extraPackages.joinToString(":"),
      assets.absolutePath,
    ) + plan.compiledResources.map { it.absolutePath }
  stamp.delete()
  val process =
    ProcessBuilder(command)
      .directory(directory)
      .redirectErrorStream(true)
      .redirectOutput(log)
      .start()
  check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
    process.destroyForcibly()
    "Linking the merged Android resources timed out after ${timeoutSeconds}s; see $log"
  }
  check(process.exitValue() == 0 && apk.isFile && rJar.isFile) {
    "Linking the merged Android resources failed (exit ${process.exitValue()}):\n" +
      log.readText().takeLast(4000)
  }
  stamp.writeText(fingerprint)
  return result
}

/** What a merged link's output depends on: every input's identity, size and time, in order. */
private fun androidMergeFingerprint(plan: AndroidMergePlan): String {
  val digest = MessageDigest.getInstance("SHA-256")
  fun add(text: String) = digest.update((text + "\n").toByteArray())
  fun add(file: File) = add("${file.absolutePath}\t${file.length()}\t${file.lastModified()}")
  add(plan.packageName)
  plan.extraPackages.forEach(::add)
  plan.compileSdk?.let { add(it.toString()) }
  listOf(plan.tools.script, plan.tools.aapt2, plan.tools.androidJar).forEach(::add)
  add(plan.tools.javaHome.absolutePath)
  plan.compiledResources.forEach(::add)
  plan.assets.forEach { (path, file) ->
    add(path)
    if (file.isDirectory) file.walkTopDown().filter(File::isFile).sorted().forEach(::add)
    else add(file)
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
}
