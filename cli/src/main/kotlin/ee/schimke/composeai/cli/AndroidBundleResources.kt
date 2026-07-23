package ee.schimke.composeai.cli

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
 * The Android app-resource carriage a **packed bundle** carries under `android/`, and the wiring
 * that re-registers it with a detached Robolectric render.
 *
 * A classic `@Composable @Preview` that calls `stringResource(R.string.…)` needs the app's compiled
 * resource table (the `0x7f` package, `resources.arsc` / `apk-for-local-test.ap_`) at render time.
 * The in-Gradle render path gets it for free — AGP puts a
 * `com/android/tools/test_config.properties` on the unit-test classpath and Robolectric's
 * `RobolectricTestRunner` auto-reads it. A **detached** render (a packed bundle spawned by `bundle
 * daemon` / `bundle render`, or a `serve --catalogs` live bundle) has neither the AGP build nor
 * that config file, so without re-supplying them the sandbox has only the framework `android-all`
 * table and `R.string.…` throws `Resources$NotFoundException: String resource ID #0x7f…`.
 *
 * `BundlePreviewTask.resolveAndroidResources` packs `android/resources.ap_` +
 * `android/AndroidManifest.xml` (+ optional `android/r-classes.jar`) for any `backend == "android"`
 * bundle; this object extracts that payload and synthesizes the `test_config.properties` both the
 * `bundle daemon` ([BundleDaemonCommand]) and `serve`
 * ([ee.schimke.composeai.cli.serve.ServeBundleDaemon]) paths prepend to the Robolectric daemon's
 * `-cp`. Shared so both wire resources identically.
 */
internal object AndroidBundleResources {

  /**
   * The `android/…` payload extracted from a bundle zip: the merged resource APK, the merged
   * manifest, and (optional) the generated non-final library R classes the tile renderer links.
   */
  data class Extracted(val resourceApk: File, val mergedManifest: File, val rClassesJar: File?)

  /**
   * Extract `android/resources.ap_` + `android/AndroidManifest.xml` (+ optional
   * `android/r-classes.jar`) from [zipBytes] into [androidDir] (Zip-Slip guarded — every
   * destination is verified to live inside [androidDir]). Returns null when the bundle carries no
   * `android/` resource payload (a desktop bundle, or an Android bundle packed before this carriage
   * existed): the caller then renders without an app resource table, exactly as before.
   */
  fun extract(zipBytes: ByteArray, androidDir: File): Extracted? {
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
   * Write the Robolectric `com/android/tools/test_config.properties` under [root], pointing at the
   * extracted [resourceApk] + [mergedManifest] (binary-resources mode: `android_resource_apk`
   * carries the table; package + theme come from `android_merged_manifest`). [applicationPackage]
   * (when the pack step recorded it) is written as `android_custom_package` — the package
   * Robolectric/AGP use for the final R class. Returns [root] for the caller to put on the daemon
   * `-cp`; it's the only mechanism a detached bundle has to re-register a resource table with
   * Robolectric.
   */
  fun writeTestConfig(
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
   * Convenience for a daemon launch: extract [zipBytes]'s `android/` resources into
   * `<workDir>/android`, synthesize the `test_config.properties` under `<workDir>/test-config`, and
   * return the classpath entries (the `test-config` dir + optional `r-classes.jar`) to prepend to
   * the Robolectric daemon's `-cp`. Empty when the bundle carries no `android/` payload — the
   * render then falls back to framework resources only (unchanged pre-carriage behaviour).
   */
  fun daemonClasspath(zipBytes: ByteArray, workDir: File, applicationPackage: String?): List<File> {
    val res = extract(zipBytes, File(workDir, "android")) ?: return emptyList()
    // Neutralize the merged manifest's `<application android:name>` before handing it to
    // Robolectric — see [sanitizeManifestForDaemon]. The daemon pins `android.app.Application`
    // (SandboxHoldingRunner.buildGlobalConfig), so the consumer Application never runs; but
    // Robolectric still *resolves* the manifest-declared class name at sandbox bootstrap, and a
    // custom `Application` that the bundle doesn't pack (the common case — an app's own
    // `Application` subclass) then throws `ClassNotFoundException` and aborts the whole sandbox
    // pool, collapsing the live lane to baked PNGs. Stripping the attribute keeps bootstrap on the
    // framework `android.app.Application`, matching the config pin.
    val manifestForConfig = sanitizeManifestForDaemon(res.mergedManifest)
    val testConfigDir =
      writeTestConfig(
        File(workDir, "test-config"),
        res.resourceApk,
        manifestForConfig,
        applicationPackage,
      )
    return buildList {
      add(testConfigDir)
      res.rClassesJar?.let { add(it) }
    }
  }

  /** The Android resource namespace `android:*` attributes are qualified by. */
  private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

  /**
   * Return a manifest file for the daemon's `android_merged_manifest` whose `<application>` no
   * longer declares an `android:name`. When [mergedManifest] has no application-name attribute
   * (default `Application`, or already stripped), or when it can't be parsed, [mergedManifest] is
   * returned unchanged — so a bundle whose manifest was already daemon-safe pays no cost and a
   * malformed manifest degrades to the pre-sanitize behaviour rather than the render failing here.
   *
   * The stripped copy is written next to the original as `AndroidManifest-daemon.xml` so the raw
   * merged manifest stays on disk for diagnostics. Only `android:name` is removed; `android:theme`,
   * `android:label`, and every child element (`<activity>`, `<service>`, …) are preserved — those
   * are resolved lazily (only when launched), so they never fire at bootstrap the way the
   * `Application` does.
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
   * Remove the `android:name` attribute from every `<application>` element in [manifestXml].
   * Returns the rewritten XML when at least one attribute was removed, or `null` when there was
   * nothing to strip (no `<application android:name>`) or the document couldn't be parsed — the
   * caller treats `null` as "use the manifest as-is". Namespace-aware and XXE-safe (DOCTYPE and
   * external entities disabled), so a hostile or unusual manifest can't reach the network or blow
   * up parsing.
   */
  internal fun stripApplicationName(manifestXml: String): String? {
    val doc =
      runCatching {
          val factory =
            DocumentBuilderFactory.newInstance().apply {
              isNamespaceAware = true
              // XXE hardening: no DTDs, no external entities.
              runCatching {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
              }
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
