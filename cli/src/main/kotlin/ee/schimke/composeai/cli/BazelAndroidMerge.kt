package ee.schimke.composeai.cli

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

/**
 * An `android.link` block ([ee.schimke.composeai.buildartifacts.protocol.AndroidLinkInputs]),
 * resolved.
 */
internal data class ResolvedAndroidLink(
  val script: File,
  val aapt2: File,
  val javaHome: File,
  val androidJar: File,
  /**
   * Every R package of the target's APK, its own first. An absolute path is a library manifest,
   * standing for the package it declares.
   */
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
  /** Each target's package and the manifest its own APK was linked against, in selection order. */
  val manifests: List<Pair<String, File>> = emptyList(),
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
    manifests = android.map { it.packageName to it.manifest },
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
 * Runs [plan]'s link into [directory]: the assets listing and `link.sh` over the merged archives,
 * against a bare manifest for the APK's package. Reused while its inputs are unchanged.
 *
 * The result's manifest is [mergeAndroidManifests] over the targets' own, so preview discovery
 * (activity previews, Wear detection) and Robolectric see what each target declares, as they do for
 * one target. Only the link uses the bare one: aapt2 needs nothing else from it, and a library's
 * manifest can carry what only its own link resolved.
 */
internal fun linkAndroidMerge(
  plan: AndroidMergePlan,
  directory: File,
  timeoutSeconds: Long = 600,
): ResolvedAndroidArtifacts {
  directory.mkdirs()
  val linkManifest = directory.resolve("AndroidManifest.link.xml")
  val manifest = directory.resolve("AndroidManifest.xml")
  val apk = directory.resolve("resources.ap_")
  val rJar = directory.resolve("r-final.jar")
  val assets = directory.resolve("assets.txt")
  val stamp = directory.resolve("link.fingerprint")
  val result =
    ResolvedAndroidArtifacts(
      plan.packageName,
      apk,
      manifest,
      rJar,
      plan.compileSdk,
      compileSdkSource = plan.compileSdk?.let { "the highest of the merged targets" },
      androidJar = plan.tools.androidJar,
    )
  val fingerprint = androidMergeFingerprint(plan)
  if (
    apk.isFile && rJar.isFile && manifest.isFile && stamp.isFile && stamp.readText() == fingerprint
  )
    return result
  manifest.writeText(mergeAndroidManifests(plan.packageName, plan.manifests))
  linkManifest.writeText(
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
      linkManifest.absolutePath,
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
  if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
    // aapt2 and javac run under the shell; stop them too, or they keep writing into the outputs.
    val tree = process.descendants().toList() + process.toHandle()
    tree.forEach { it.destroyForcibly() }
    tree.forEach { runCatching { it.onExit().get(10, TimeUnit.SECONDS) } }
    error("Linking the merged Android resources timed out after ${timeoutSeconds}s; see $log")
  }
  check(process.exitValue() == 0 && apk.isFile && rJar.isFile) {
    "Linking the merged Android resources failed (exit ${process.exitValue()}):\n" +
      log.readText().takeLast(4000)
  }
  stamp.writeText(fingerprint)
  return result
}

/**
 * What a merged link's output depends on: every input by content, in order. A cache restore can
 * replace a file with different bytes at the same path, size and time, as
 * [bazelArtifactsFingerprint] also allows for.
 */
private fun androidMergeFingerprint(plan: AndroidMergePlan): String {
  val digest = MessageDigest.getInstance("SHA-256")
  fun add(text: String) = digest.update((text + "\n").toByteArray())
  fun add(file: File) {
    add(file.absolutePath)
    file.inputStream().use { input ->
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
  }
  add(plan.packageName)
  plan.extraPackages.forEach { if ('/' in it) add(File(it)) else add(it) }
  plan.compileSdk?.let { add(it.toString()) }
  listOf(plan.tools.script, plan.tools.aapt2, plan.tools.androidJar).forEach(::add)
  add(plan.tools.javaHome.absolutePath)
  plan.manifests.forEach { (pkg, manifest) ->
    add(pkg)
    add(manifest)
  }
  plan.compiledResources.forEach(::add)
  plan.assets.forEach { (path, file) ->
    add(path)
    if (file.isDirectory) file.walkTopDown().filter(File::isFile).sorted().forEach(::add)
    else add(file)
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
}

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/** Components whose `android:name` (and `targetActivity`) name a class. */
private val COMPONENT_TAGS = setOf("activity", "activity-alias", "service", "receiver", "provider")

/**
 * One manifest for [packageName] carrying what every target's own declares: each top-level element
 * once (permissions, features, queries), the first target's `<application>` attributes, and every
 * `<application>` child once by tag and name, the first target winning. A class name written
 * relative to its own package (`.Main`) is made absolute, since the merged package is another
 * target's. A manifest that does not parse is left out, so a merge never fails on it.
 */
internal fun mergeAndroidManifests(
  packageName: String,
  manifests: List<Pair<String, File>>,
): String {
  val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
  val out = factory.newDocumentBuilder().newDocument()
  val root = out.createElement("manifest").also(out::appendChild)
  root.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:android", ANDROID_NS)
  root.setAttribute("package", packageName)
  val application = out.createElement("application")
  val topLevel = LinkedHashSet<String>()
  val components = LinkedHashSet<String>()
  fun absolute(pkg: String, name: String) =
    when {
      name.startsWith(".") -> pkg + name
      '.' !in name -> "$pkg.$name"
      else -> name
    }
  for ((pkg, file) in manifests) {
    val source =
      runCatching { factory.newDocumentBuilder().parse(file).documentElement }
        .onFailure {
          System.err.println("[bazel] manifest $file left out of the merge: ${it.message}")
        }
        .getOrNull() ?: continue
    val own = source.getAttribute("package").ifBlank { pkg }
    for (child in source.childNodes.elements()) {
      if (child.localName != "application") {
        val imported = out.importNode(child, true) as Element
        if (topLevel.add(imported.serialized())) root.appendChild(imported)
        continue
      }
      val attributes = child.attributes
      for (i in 0 until attributes.length) {
        val attribute = attributes.item(i)
        if (attribute.namespaceURI == "http://www.w3.org/2000/xmlns/") continue
        if (!application.hasAttributeNS(attribute.namespaceURI, attribute.localName)) {
          val value =
            if (attribute.namespaceURI == ANDROID_NS && attribute.localName == "name")
              absolute(own, attribute.nodeValue)
            else attribute.nodeValue
          application.setAttributeNS(attribute.namespaceURI, attribute.nodeName, value)
        }
      }
      for (component in child.childNodes.elements()) {
        val imported = out.importNode(component, true) as Element
        if (imported.localName in COMPONENT_TAGS) {
          for (attribute in listOf("name", "targetActivity")) {
            imported
              .getAttributeNS(ANDROID_NS, attribute)
              .takeIf { it.isNotEmpty() }
              ?.let { imported.setAttributeNS(ANDROID_NS, "android:$attribute", absolute(own, it)) }
          }
        }
        val key = "${imported.localName}\u0000${imported.getAttributeNS(ANDROID_NS, "name")}"
        if (imported.getAttributeNS(ANDROID_NS, "name").isEmpty() || components.add(key)) {
          application.appendChild(imported)
        }
      }
    }
  }
  root.appendChild(application)
  return out.serialized()
}

private fun org.w3c.dom.NodeList.elements(): List<Element> =
  (0 until length).mapNotNull { item(it) as? Element }

private fun org.w3c.dom.Node.serialized(): String {
  val writer = java.io.StringWriter()
  TransformerFactory.newInstance()
    .newTransformer()
    .apply {
      setOutputProperty(OutputKeys.INDENT, "yes")
      setOutputProperty(
        OutputKeys.OMIT_XML_DECLARATION,
        if (this@serialized is Element) "yes" else "no",
      )
    }
    .transform(DOMSource(this), StreamResult(writer))
  return writer.toString()
}
