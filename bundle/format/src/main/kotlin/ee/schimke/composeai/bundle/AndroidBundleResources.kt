package ee.schimke.composeai.bundle

import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringWriter
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

/**
 * The Android app-resource carriage a packed bundle carries under `android/`, and the wiring that
 * re-registers it with a detached Robolectric render.
 *
 * A preview calling `stringResource(R.string.…)` needs the app's compiled resource table. In
 * Gradle, AGP's `com/android/tools/test_config.properties` supplies it; a detached render (`bundle
 * daemon`, `bundle render`, `serve --catalogs`) has neither, so `R.string.…` would throw
 * `Resources$NotFoundException`. This extracts the packed `android/resources.ap_`,
 * `android/AndroidManifest.xml` (+ optional `android/r-classes.jar`) and synthesizes the config the
 * [BundleDaemonCommand] and `ServeBundleDaemon` paths prepend to the daemon's `-cp`.
 */
public object AndroidBundleResources {

  /**
   * The `android/…` payload extracted from a bundle zip: the merged resource APK, the merged
   * manifest, and (optional) the generated non-final library R classes the tile renderer links.
   */
  public data class Extracted(
    val resourceApk: File,
    val mergedManifest: File,
    val rClassesJar: File?,
  )

  /**
   * Extract the `android/` payload from [zipBytes] into [androidDir] (Zip-Slip guarded). Returns
   * null when the bundle carries none; the caller then renders without an app resource table.
   */
  public fun extract(zipBytes: ByteArray, androidDir: File): Extracted? {
    androidDir.mkdirs()
    val canonical = androidDir.canonicalFile
    var apk: File? = null
    var mergedManifest: File? = null
    var rJar: File? = null
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      while (true) {
        val entry = zin.nextEntry ?: break
        val name = entry.name
        if (!entry.isDirectory && name.startsWith("android/")) {
          val dest = File(androidDir, File(name).name).canonicalFile
          if (dest.path.startsWith(canonical.path + File.separator)) {
            dest.outputStream().use { sink -> zin.copyTo(sink) }
            when (name) {
              "android/resources.ap_" -> apk = dest
              "android/AndroidManifest.xml" -> mergedManifest = dest
              "android/r-classes.jar" -> rJar = dest
            }
          }
        }
        zin.closeEntry()
      }
    }
    val resolvedApk = apk
    val resolvedManifest = mergedManifest
    return if (resolvedApk != null && resolvedManifest != null)
      Extracted(resolvedApk, resolvedManifest, rJar)
    else null
  }

  /**
   * Write Robolectric's `com/android/tools/test_config.properties` under [root], pointing at
   * [resourceApk] and [mergedManifest] (binary-resources mode). [applicationPackage], if recorded,
   * is written as `android_custom_package`. Returns [root] for the daemon `-cp`.
   */
  public fun writeTestConfig(
    root: File,
    resourceApk: File,
    mergedManifest: File,
    applicationPackage: String?,
  ): File {
    val dir = File(root, "com/android/tools").apply { mkdirs() }
    File(dir, "test_config.properties")
      .writeText(
        buildString {
          appendLine("android_resource_apk=${resourceApk.absolutePath}")
          appendLine("android_merged_manifest=${mergedManifest.absolutePath}")
          if (!applicationPackage.isNullOrBlank()) {
            appendLine("android_custom_package=$applicationPackage")
          }
        }
      )
    return root
  }

  /**
   * Extract [zipBytes]'s `android/` resources into `<workDir>/android`, synthesize the test config
   * under `<workDir>/test-config`, and return the classpath entries to prepend to the daemon's
   * `-cp` (empty when the bundle has no payload).
   *
   * [useConsumerApplication] must match the `composeai.daemon.useConsumerApplication` value passed
   * to the daemon: when false (the default), the manifest's Application name is stripped so
   * bootstrap can't crash on it; when true it is left intact.
   */
  public fun daemonClasspath(
    zipBytes: ByteArray,
    workDir: File,
    applicationPackage: String?,
    useConsumerApplication: Boolean = false,
  ): List<File> {
    val res = extract(zipBytes, File(workDir, "android")) ?: return emptyList()
    // Strip `<application android:name>` (see [sanitizeManifestForDaemon]): Robolectric resolves
    // the declared class at bootstrap, and an unpacked custom Application aborts the whole sandbox
    // pool. Left untouched when the daemon opts into the consumer Application.
    val manifestForConfig =
      if (useConsumerApplication) res.mergedManifest
      else sanitizeManifestForDaemon(res.mergedManifest)
    val testConfigDir =
      writeTestConfig(
        File(workDir, "test-config"),
        res.resourceApk,
        manifestForConfig,
        applicationPackage,
      )
    // Pin the Application via a package-scoped `robolectric.properties`, the only override
    // Robolectric 4.16 honours end-to-end (see [writeDaemonRobolectricProperties]).
    writeDaemonRobolectricProperties(testConfigDir, useConsumerApplication)
    return buildList {
      add(testConfigDir)
      res.rClassesJar?.let { add(it) }
    }
  }

  /** The package the daemon's `RobolectricHost.SandboxRunner` lives in, as a resource path. */
  private const val DAEMON_RUNNER_PACKAGE_PATH = "ee/schimke/composeai/daemon"

  /**
   * Write `ee/schimke/composeai/daemon/robolectric.properties` under [testConfigDir] so a detached
   * render pins `android.app.Application`, as the Gradle path does via
   * [ee.schimke.composeai.plugin.GenerateRobolectricPropertiesTask].
   *
   * This is the load-bearing override: Robolectric only merges `robolectric.properties` from the
   * running test class's package (`ee.schimke.composeai.daemon`), and the deprecated
   * `SandboxHoldingRunner.buildGlobalConfig` override no longer wins over the manifest in 4.16.
   * Without it, an unpacked consumer `Application` throws `ClassNotFoundException` and aborts every
   * sandbox.
   *
   * Only the `application` line belongs here (`sdk`/`graphicsMode` come from class-level
   * `@Config`). Omitted when [useConsumerApplication] is set.
   */
  private fun writeDaemonRobolectricProperties(
    testConfigDir: File,
    useConsumerApplication: Boolean,
  ) {
    val dir = File(testConfigDir, DAEMON_RUNNER_PACKAGE_PATH).apply { mkdirs() }
    val body =
      if (useConsumerApplication) {
        "# Generated by compose-ai-tools.\n" +
          "# useConsumerApplication=true — the daemon's Robolectric falls back to the\n" +
          "# manifest-declared Application.\n"
      } else {
        "# Generated by compose-ai-tools.\n" +
          "# Override the consumer's Application to a Robolectric-safe stub for daemon-driven\n" +
          "# preview rendering, so app-lifecycle init (DI, Firebase, WorkManager, …) never runs and\n" +
          "# crashes the render sandbox. Robolectric 4.16 no longer merges the deprecated\n" +
          "# buildGlobalConfig override over the manifest, so the pin must live in this package file.\n" +
          "application=android.app.Application\n"
      }
    runCatching { File(dir, "robolectric.properties").writeText(body) }
  }

  /** The Android resource namespace `android:*` attributes are qualified by. */
  private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

  /**
   * A manifest for the daemon whose `<application>` has no `android:name`, written beside the
   * original as `AndroidManifest-daemon.xml`. Returns [mergedManifest] unchanged when there's
   * nothing to strip or it can't be parsed. Other attributes and children are kept; they resolve
   * lazily.
   */
  private fun sanitizeManifestForDaemon(mergedManifest: File): File {
    val original = runCatching { mergedManifest.readText() }.getOrNull() ?: return mergedManifest
    val stripped = stripApplicationName(original) ?: return mergedManifest
    val dest = File(mergedManifest.parentFile, "AndroidManifest-daemon.xml")
    return runCatching {
        dest.writeText(stripped)
        System.err.println(
          "[android-bundle] stripped <application android:name> from the daemon manifest " +
            "(previews run on android.app.Application; the consumer Application is not bootstrapped)"
        )
        dest
      }
      .getOrDefault(mergedManifest)
  }

  /**
   * Remove `android:name` from every `<application>` in [manifestXml]. Returns the rewritten XML,
   * or `null` when nothing was stripped or parsing failed. Namespace-aware and XXE-safe.
   */
  public fun stripApplicationName(manifestXml: String): String? {
    val doc =
      runCatching {
        val factory =
          DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // XXE hardening: no DTDs, no external entities.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching {
              setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
              setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
          }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(manifestXml.toByteArray()))
      }
        .getOrNull() ?: return null

    val applications = doc.getElementsByTagName("application")
    var removedAny = false
    for (i in 0 until applications.length) {
      val app = applications.item(i) as? Element ?: continue
      if (app.hasAttributeNS(ANDROID_NS, "name")) {
        app.removeAttributeNS(ANDROID_NS, "name")
        removedAny = true
      }
    }
    if (!removedAny) return null

    return runCatching {
      val writer = StringWriter()
      TransformerFactory.newInstance()
        .newTransformer()
        .apply { setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no") }
        .transform(DOMSource(doc), StreamResult(writer))
      writer.toString()
    }
      .getOrNull()
  }
}
