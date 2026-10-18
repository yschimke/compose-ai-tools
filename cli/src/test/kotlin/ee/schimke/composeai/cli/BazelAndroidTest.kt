package ee.schimke.composeai.cli

import ee.schimke.composeai.buildartifacts.protocol.AndroidBuildArtifacts
import ee.schimke.composeai.buildartifacts.protocol.AndroidLinkInputs
import ee.schimke.composeai.bundle.AndroidBundleResources
import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilder
import ee.schimke.composeai.discovery.Capture
import ee.schimke.composeai.discovery.PreviewInfo
import ee.schimke.composeai.discovery.PreviewManifest
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BazelAndroidTest {
  private fun fixture(block: (File) -> Unit) {
    val root = Files.createTempDirectory("bazel-android").toFile()
    try {
      block(root)
    } finally {
      root.deleteRecursively()
    }
  }

  private fun File.file(path: String, text: String = ""): File =
    resolve(path).apply {
      parentFile.mkdirs()
      writeText(text)
    }

  /** The shape the Android aspect writes: final-ID R classes first, then the target's jars. */
  private fun androidArtifacts(root: File): BazelArtifacts {
    listOf("bin/_compose_preview/ui/r-final.jar", "bin/ui.jar", "external/compose.jar").forEach {
      root.file(it)
    }
    root.file("bin/_compose_preview/ui/resources.ap_")
    root.file(
      "ui/AndroidManifest.xml",
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.ui">
        <application android:name="com.example.ui.App" />
      </manifest>
      """
        .trimIndent(),
    )
    return BazelArtifacts(
      "//ui",
      "bin",
      listOf("bin/ui.jar"),
      listOf("bin/_compose_preview/ui/r-final.jar", "bin/ui.jar", "external/compose.jar"),
      android =
        AndroidBuildArtifacts.Builder(
            "com.example.ui",
            "bin/_compose_preview/ui/resources.ap_",
            "ui/AndroidManifest.xml",
          )
          .apply {
            rJar = "bin/_compose_preview/ui/r-final.jar"
            compileSdk = 36
          }
          .build(),
    )
  }

  @Test
  fun `android block resolves materialized resources and keeps schema 1 Android-free`() =
    fixture { root ->
      val artifacts = androidArtifacts(root)
      val resolved = artifacts.resolve(root)
      val android = checkNotNull(resolved.android)
      assertEquals("com.example.ui", android.packageName)
      assertEquals(
        root.resolve("bin/_compose_preview/ui/resources.ap_").absoluteFile,
        android.resourceApk,
      )
      assertEquals(root.resolve("bin/_compose_preview/ui/r-final.jar").absoluteFile, android.rJar)
      assertEquals(36, android.compileSdk)
      assertNull(
        BazelArtifacts("//:app", "bin", listOf("bin/ui.jar"), listOf("bin/ui.jar"))
          .resolve(root)
          .android
      )

      // A missing linked APK is a build that did not materialize its outputs, not a JVM target.
      root.resolve("bin/_compose_preview/ui/resources.ap_").delete()
      assertTrue(
        assertFailsWith<IllegalArgumentException> { artifacts.resolve(root) }
          .message!!
          .contains("not materialized")
      )
    }

  @Test
  fun `a manifest among the link packages resolves against the execution root`() = fixture { root ->
    // rules_android reports no package for an aar_import, so the aspect passes its manifest.
    listOf("link.sh", "aapt2", "jdk/bin/javac", "android.jar").forEach { root.file(it) }
    root.file("external/widget/AndroidManifest.xml", "<manifest package=\"com.example.widget\"/>")
    root.file("root.xml", "<manifest package=\"com.example.root\"/>")
    val artifacts = androidArtifacts(root)
    val link =
      AndroidLinkInputs.Builder(
          "link.sh",
          "aapt2",
          "jdk",
          "android.jar",
          listOf(
            "com.example.ui",
            "com.example.theme",
            "external/widget/AndroidManifest.xml",
            // A root package's manifest, and a package name that merely ends in .xml.
            "root.xml",
            "com.example.xml",
          ),
          emptyList(),
        )
        .build()
    val withLink =
      artifacts
        .newBuilder()
        .apply {
          android = checkNotNull(artifacts.android).newBuilder().apply { this.link = link }.build()
        }
        .build()
    assertEquals(
      listOf(
        "com.example.ui",
        "com.example.theme",
        root.resolve("external/widget/AndroidManifest.xml").absolutePath,
        root.resolve("root.xml").absolutePath,
        "com.example.xml",
      ),
      checkNotNull(withLink.resolve(root).android?.link).packages,
    )
  }

  @Test
  fun `a link script the execution root lacks resolves from the workspace`() = fixture { root ->
    // The CLI-installed aspect's link.sh is a workspace source file under .compose-preview/;
    // some execution roots plant only the packages a build used (compose-ai-tools#5945).
    listOf("aapt2", "jdk/bin/javac", "android.jar").forEach { root.file(it) }
    val workspace = root.resolve("workspace").apply { mkdirs() }
    workspace.file(".compose-preview/bazel/android/link.sh", "#!/bin/sh\n")
    val artifacts = androidArtifacts(root)
    val link =
      AndroidLinkInputs.Builder(
          ".compose-preview/bazel/android/link.sh",
          "aapt2",
          "jdk",
          "android.jar",
          listOf("com.example.ui"),
          emptyList(),
        )
        .build()
    val withLink =
      artifacts
        .newBuilder()
        .apply {
          android = checkNotNull(artifacts.android).newBuilder().apply { this.link = link }.build()
        }
        .build()
    assertEquals(
      workspace.resolve(".compose-preview/bazel/android/link.sh").absoluteFile,
      checkNotNull(withLink.resolve(root, workspace).android?.link).script,
    )
    // Without a workspace to fall back on, the link block is dropped, as before.
    assertNull(withLink.resolve(root).android?.link)
    // A build output never resolves from the workspace: a stale checkout file must not stand in
    // for a jar the build did not materialize.
    workspace.file("bin/stale.jar")
    val stale = artifacts.newBuilder().apply { runtimeJars = runtimeJars + "bin/stale.jar" }.build()
    assertTrue(
      assertFailsWith<IllegalArgumentException> { stale.resolve(root, workspace) }
        .message!!
        .contains("not materialized")
    )
  }

  @Test
  fun `schema 1 artifacts carrying an android block are refused`() = fixture { root ->
    val encoded = bazelJson.encodeToString(androidArtifacts(root))
    assertFailsWith<IllegalArgumentException> {
      bazelJson
        .decodeFromString<BazelArtifacts>(
          encoded.replace("\"schemaVersion\": 2", "\"schemaVersion\": 1")
        )
        .resolve(root)
    }
  }

  @Test
  fun `robolectric runs offline only against an existing dependency directory`() = fixture { root ->
    assertEquals(emptyMap(), robolectricOfflineProperties(null))
    assertEquals(
      mapOf("robolectric.offline" to "true", "robolectric.dependency.dir" to root.absolutePath),
      robolectricOfflineProperties(root.path),
    )
    assertFailsWith<IllegalArgumentException> {
      robolectricOfflineProperties(root.resolve("missing").path)
    }
  }

  @Test
  fun `android launch feeds Robolectric the linked resources with final R classes first`() =
    fixture { root ->
      val artifacts = androidArtifacts(root).resolve(root)
      val sidecar = root.resolve("lib-daemon-android").apply { mkdirs() }
      sidecar.file("daemon-android.jar")
      val androidJar = root.file("sdk/platforms/android-36/android.jar")
      root.file("local.properties", "sdk.dir=${root.resolve("sdk").absolutePath}\n")
      val output = root.resolve("out").apply { mkdirs() }
      val manifestFile =
        output.resolve("previews.json").apply {
          writeText(bazelJson.encodeToString(PreviewManifest("//ui", "bin", emptyList())))
        }
      val property = DaemonSidecarProvision.sidecarProperty("lib-daemon-android")
      val previous = System.getProperty(property)
      System.setProperty(property, sidecar.absolutePath)
      val descriptor =
        try {
          DaemonLaunchBuilder.decode(
            prepareBazelDaemon(
                artifacts,
                PreparedBazelPreviews(PreviewManifest("//ui", "bin", emptyList()), manifestFile),
                root,
              )
              .readText()
          )
        } finally {
          if (previous == null) System.clearProperty(property)
          else System.setProperty(property, previous)
        }

      // Robolectric finds the APK, manifest and package through the synthesized test config,
      // which leads the daemon classpath; the Application is pinned to the safe stub.
      val testConfig = File(descriptor.classpath.first())
      val config = testConfig.resolve("com/android/tools/test_config.properties").readText()
      assertTrue(
        config.contains("android_resource_apk=${artifacts.android!!.resourceApk.absolutePath}")
      )
      assertTrue(config.contains("android_custom_package=com.example.ui"))
      val daemonManifest =
        File(config.lines().first { it.startsWith("android_merged_manifest=") }.substringAfter('='))
      assertTrue(daemonManifest.startsWith(output), "sanitized copy stays out of the build tree")
      assertTrue("com.example.ui.App" !in daemonManifest.readText())
      assertTrue(
        testConfig
          .resolve("ee/schimke/composeai/daemon/robolectric.properties")
          .readText()
          .contains("application=android.app.Application")
      )
      assertTrue(androidJar.absolutePath in descriptor.classpath)
      assertTrue(sidecar.resolve("daemon-android.jar").absolutePath in descriptor.classpath)

      val userClasses = descriptor.systemProperties.getValue("composeai.daemon.userClassDirs")
      assertEquals(
        artifacts.android!!.rJar!!.absolutePath,
        userClasses.split(File.pathSeparator).first(),
      )
      assertEquals(
        userClasses.split(File.pathSeparator).distinct(),
        userClasses.split(File.pathSeparator),
      )
      assertTrue("robolectric.offline" !in descriptor.systemProperties)
      // No clock prefixes passed (as for `"pinAppClock": false`): nothing is added to the launch.
      assertTrue(BAZEL_CLOCK_PACKAGES_PROPERTY !in descriptor.systemProperties)
      // The sandbox follows the target's compileSdk (36), lowered only on a JVM that can't run it.
      assertEquals(
        bazelAndroidSdkLevel(36, log = {}).toString(),
        descriptor.systemProperties["composeai.daemon.sandboxSdk"],
      )
      // That level was clamped against this JVM, so this JVM is the one that launches it.
      assertEquals(currentJavaLauncher(), descriptor.javaLauncher)
      assertTrue(descriptor.javaLauncher != null)
    }

  @Test
  fun `android launch prefers the android jar the target linked against`() = fixture { root ->
    val resolved = androidArtifacts(root).resolve(root)
    // The host SDK's newest platform is not the one the target was built for.
    val hostJar = root.file("sdk/platforms/android-37.0/android.jar")
    root.file("local.properties", "sdk.dir=${root.resolve("sdk").absolutePath}\n")
    val linkedJar = root.file("execroot/external/androidsdk/platforms/android-36/android.jar")
    // No link block: a merged target carries none, yet still knows its android.jar.
    val artifacts = resolved.copy(android = resolved.android!!.copy(androidJar = linkedJar))
    val sidecar = root.resolve("lib-daemon-android").apply { mkdirs() }
    sidecar.file("daemon-android.jar")
    val output = root.resolve("out").apply { mkdirs() }
    val manifestFile =
      output.resolve("previews.json").apply {
        writeText(bazelJson.encodeToString(PreviewManifest("//ui", "bin", emptyList())))
      }
    val property = DaemonSidecarProvision.sidecarProperty("lib-daemon-android")
    val previous = System.getProperty(property)
    System.setProperty(property, sidecar.absolutePath)
    val descriptor =
      try {
        DaemonLaunchBuilder.decode(
          prepareBazelDaemon(
              artifacts,
              PreparedBazelPreviews(PreviewManifest("//ui", "bin", emptyList()), manifestFile),
              root,
            )
            .readText()
        )
      } finally {
        if (previous == null) System.clearProperty(property)
        else System.setProperty(property, previous)
      }
    assertTrue(linkedJar.absolutePath in descriptor.classpath)
    assertTrue(hostJar.absolutePath !in descriptor.classpath)
  }

  @Test
  fun `pinAppClock is on by default and opts out from bazel json`() = fixture { root ->
    val android = androidArtifacts(root).resolve(root).android
    // Absent keys pin the target's own package and Wear Compose's TimeText clock.
    val defaults = bazelJson.decodeFromString<BazelBuildConfig>("""{"schemaVersion": 1}""")
    assertEquals(true, defaults.pinAppClock)
    assertNull(defaults.appClockPackages)
    assertEquals(
      listOf("com.example.ui.", "androidx.wear.compose."),
      bazelClockPackages(defaults, android),
    )
    // A JVM target has nothing to instrument.
    assertEquals(emptyList(), bazelClockPackages(defaults, null))
    // No package known: never an empty prefix that would match every class.
    assertEquals(
      listOf("androidx.wear.compose."),
      bazelClockPackages(defaults, android!!.copy(packageName = " ")),
    )

    val optedOut =
      bazelJson.decodeFromString<BazelBuildConfig>("""{"schemaVersion": 1, "pinAppClock": false}""")
    assertEquals(emptyList(), bazelClockPackages(optedOut, android))

    val explicit =
      bazelJson.decodeFromString<BazelBuildConfig>(
        """
        {"schemaVersion": 1,
         "appClockPackages": [" com.example.ui.", "com.example.shared.", "", ".", "com.example.ui."]}
        """
      )
    assertEquals(
      listOf("com.example.ui.", "com.example.shared."),
      bazelClockPackages(explicit, android),
    )
    // The opt-out wins over configured packages.
    assertEquals(emptyList(), bazelClockPackages(explicit.copy(pinAppClock = false), android))
  }

  @Test
  fun `pinAppClock reaches the android daemon launch as composeai render clockPackages`() =
    fixture { root ->
      val artifacts = androidArtifacts(root).resolve(root)
      val sidecar = root.resolve("lib-daemon-android").apply { mkdirs() }
      sidecar.file("daemon-android.jar")
      root.file("sdk/platforms/android-36/android.jar")
      root.file("local.properties", "sdk.dir=${root.resolve("sdk").absolutePath}\n")
      val output = root.resolve("out").apply { mkdirs() }
      val manifestFile =
        output.resolve("previews.json").apply {
          writeText(bazelJson.encodeToString(PreviewManifest("//ui", "bin", emptyList())))
        }
      val property = DaemonSidecarProvision.sidecarProperty("lib-daemon-android")
      val previous = System.getProperty(property)
      System.setProperty(property, sidecar.absolutePath)
      fun descriptor(sandboxCount: Int?) =
        DaemonLaunchBuilder.decode(
          prepareBazelDaemon(
              artifacts,
              PreparedBazelPreviews(PreviewManifest("//ui", "bin", emptyList()), manifestFile),
              root,
              bazelClockPackages(BazelBuildConfig(), artifacts.android),
              sandboxCount,
            )
            .readText()
        )
      val (prepared, oneShot) =
        try {
          descriptor(null) to descriptor(oneShotSandboxCount(1))
        } finally {
          if (previous == null) System.clearProperty(property)
          else System.setProperty(property, previous)
        }
      assertEquals(
        "com.example.ui.,androidx.wear.compose.",
        prepared.systemProperties["composeai.render.clockPackages"],
      )
      // A long-lived daemon keeps the daemon's own pool; a one-shot render of one preview boots
      // one sandbox rather than five (compose-ai-tools#5945).
      assertNull(prepared.systemProperties["composeai.daemon.sandboxCount"])
      assertEquals("1", oneShot.systemProperties["composeai.daemon.sandboxCount"])
    }

  @Test
  fun `a one-shot render boots a sandbox per preview, up to the warm-spare pool`() {
    assertEquals(1, oneShotSandboxCount(0))
    assertEquals(1, oneShotSandboxCount(1))
    assertEquals(3, oneShotSandboxCount(3))
    assertEquals(5, oneShotSandboxCount(40))
  }

  @Test
  fun `android bundles carry the linked resources where the bundle replay reads them`() =
    fixture { root ->
      val artifacts = androidArtifacts(root)
      fun jar(path: String, entry: String) =
        ZipOutputStream(root.resolve(path).outputStream()).use {
          it.putNextEntry(ZipEntry(entry))
          it.write(entry.toByteArray())
          it.closeEntry()
        }
      jar("bin/_compose_preview/ui/r-final.jar", "com/example/ui/R.class")
      jar("bin/ui.jar", "com/example/ui/GreetingKt.class")
      jar("external/compose.jar", "androidx/compose/ui/Modifier.class")
      root.resolve("bin/_compose_preview/ui/resources.ap_").writeText("linked")
      val id = "com.example.ui.GreetingKt.Greeting"
      val previews =
        PreviewManifest(
          "//ui",
          "bin",
          listOf(
            PreviewInfo(
              id,
              "Greeting",
              "com.example.ui.GreetingKt",
              captures = listOf(Capture(renderOutput = "greeting.png")),
            )
          ),
        )
      val previewsFile =
        root.resolve("out/previews.json").apply {
          parentFile.mkdirs()
          writeText(bazelJson.encodeToString(previews))
        }
      val bundle = root.resolve("bundle.png")
      val manifest =
        writeBazelBundle(
          artifacts.resolve(root),
          PreparedBazelPreviews(previews, previewsFile),
          setOf(id),
          emptyList(),
          bundle,
          includeRenders = false,
        )

      assertEquals("android", manifest.backend)
      assertEquals("com.example.ui", manifest.androidResources?.applicationPackage)
      // The R classes ride in the resource carriage, never twice on the user classpath.
      assertEquals(
        1,
        manifest.classpath.filterIsInstance<BundleReader.ClasspathEntry.Embedded>().size,
      )
      val extracted =
        checkNotNull(
          AndroidBundleResources.extract(
            BundleReader.extractZipBytes(bundle),
            root.resolve("replay"),
          )
        )
      assertEquals("linked", extracted.resourceApk.readText())
      assertTrue("com.example.ui.App" in extracted.mergedManifest.readText())
      assertTrue(checkNotNull(extracted.rClassesJar).isFile)

      // The one-shot `bundle render` lane restores the same carriage, R classes included.
      val carriage =
        BundleRenderer.androidResourceCarriage(
          BundleReader.extractZipBytes(bundle),
          root.resolve("one-shot"),
          manifest,
        )
      assertTrue(
        carriage
          .first()
          .resolve("com/android/tools/test_config.properties")
          .readText()
          .contains("android_custom_package=com.example.ui")
      )
      assertEquals("r-classes.jar", carriage.last().name)
    }
}
