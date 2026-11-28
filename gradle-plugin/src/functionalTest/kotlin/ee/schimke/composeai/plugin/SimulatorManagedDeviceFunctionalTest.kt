package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end AGP Gradle Managed Device tests for [SimulatorManagedDevice]: verifies task
 * registration (default `"simulator"` + custom prefix-named devices + groups), real APK wiring for
 * application and library modules, `instrumentationRunnerArguments.class` -> `--tests` forwarding,
 * AGP HTML report generation, failure propagation, opt-out, and Configuration Cache / Isolated
 * Projects safety.
 */
class SimulatorManagedDeviceFunctionalTest {

  @get:Rule val tempDir = TemporaryFolder()

  @Test
  fun `default simulator managed device executes androidTest on app module and generates AGP HTML report`() {
    val projectDir = createProject(includeApp = true, includeLib = false)
    val argsLog = File(projectDir, "cli-args.txt")
    val fakeCli = createFakeCli(projectDir, argsLog, mode = "pass")

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(
          ":app:simulatorDebugAndroidTest",
          "-PcomposePreview.cli=${fakeCli.absolutePath}",
          "-Pandroid.testInstrumentationRunnerArguments.class=ee.schimke.test.SmokeTest#passes",
          "--stacktrace",
        )
        .withPluginClasspath(androidPluginClasspath())
        .build()

    assertThat(result.output).contains("1 tests: 1 passed, 0 failed, 0 skipped")

    val recorded = argsLog.readLines()
    assertThat(recorded.first()).isEqualTo("android-simulator")
    val appApk = File(recorded[1])
    assertThat(appApk.isFile).isTrue()
    assertThat(appApk.name).endsWith(".apk")
    val instrumentIdx = recorded.indexOf("--instrument")
    assertThat(instrumentIdx).isGreaterThan(1)
    val testApk = File(recorded[instrumentIdx + 1])
    assertThat(testApk.isFile).isTrue()
    assertThat(testApk.name).contains("androidTest")
    assertThat(recorded).containsAtLeast("--tests", "ee.schimke.test.SmokeTest#passes").inOrder()

    val xmlReport =
      File(
        projectDir,
        "app/build/outputs/androidTest-results/managedDevice/debug/simulator/TEST-simulator.xml",
      )
    assertThat(xmlReport.isFile).isTrue()
    val xmlText = xmlReport.readText()
    assertThat(xmlText).contains("<property name=\"device\" value=\"simulator\"/>")
    assertThat(xmlText).contains("<property name=\"project\" value=\":app\"/>")

    val htmlReport =
      File(projectDir, "app/build/reports/androidTests/managedDevice/debug/simulator/index.html")
    assertThat(htmlReport.isFile).isTrue()
    assertThat(htmlReport.readText()).contains("SmokeTest")
  }

  @Test
  fun `custom prefix-named SimulatorManagedDevice forwards DSL options and fails cleanly on test failure`() {
    val projectDir =
      createProject(
        includeApp = true,
        includeLib = false,
        extraAppDsl =
          """
          android {
              testOptions {
                  managedDevices {
                      allDevices {
                          register<ee.schimke.composeai.plugin.SimulatorManagedDevice>("pixelApi35") {
                              apiLevel.set(35)
                              device.set("phone")
                              qualifiers.set("w411dp-h914dp-xxhdpi")
                              shards.set(2)
                              pool.set(true)
                          }
                      }
                      groups {
                          create("fast") {
                              targetDevices.add(allDevices.getByName("pixelApi35"))
                          }
                      }
                  }
              }
          }
          """
            .trimIndent(),
      )
    val argsLog = File(projectDir, "cli-args.txt")
    val fakeCli = createFakeCli(projectDir, argsLog, mode = "fail")

    val dryRun =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(":app:fastGroupDebugAndroidTest", ":app:pixelApi35Check", "--dry-run")
        .withPluginClasspath(androidPluginClasspath())
        .build()
    assertThat(dryRun.output).contains(":app:pixelApi35DebugAndroidTest SKIPPED")
    assertThat(dryRun.output).contains(":app:fastGroupDebugAndroidTest SKIPPED")
    assertThat(dryRun.output).contains(":app:pixelApi35Check SKIPPED")

    val failure =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(
          ":app:pixelApi35DebugAndroidTest",
          "-PcomposePreview.cli=${fakeCli.absolutePath}",
          "--stacktrace",
        )
        .withPluginClasspath(androidPluginClasspath())
        .buildAndFail()

    assertThat(failure.output).contains("FAILED ee.schimke.test.SmokeTest#fails: expected true")

    val recorded = argsLog.readLines()
    assertThat(recorded).containsAtLeast("--sdk", "35").inOrder()
    assertThat(recorded).containsAtLeast("--device", "phone").inOrder()
    assertThat(recorded).containsAtLeast("--qualifiers", "w411dp-h914dp-xxhdpi").inOrder()
    assertThat(recorded).containsAtLeast("--shards", "2").inOrder()
    assertThat(recorded).contains("--pool")

    val htmlReport =
      File(projectDir, "app/build/reports/androidTests/managedDevice/debug/pixelApi35/index.html")
    assertThat(htmlReport.isFile).isTrue()
  }

  @Test
  fun `library module instruments its own test APK through default simulator managed device`() {
    val projectDir = createProject(includeApp = false, includeLib = true)
    val argsLog = File(projectDir, "cli-args.txt")
    val fakeCli = createFakeCli(projectDir, argsLog, mode = "pass")

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(
          ":lib:simulatorDebugAndroidTest",
          "-PcomposePreview.cli=${fakeCli.absolutePath}",
          "--stacktrace",
        )
        .withPluginClasspath(androidPluginClasspath())
        .build()

    assertThat(result.output).contains("1 tests: 1 passed, 0 failed, 0 skipped")

    val recorded = argsLog.readLines()
    val appApk = File(recorded[1])
    val testApk = File(recorded[recorded.indexOf("--instrument") + 1])
    assertThat(appApk.isFile).isTrue()
    assertThat(appApk.canonicalPath).isEqualTo(testApk.canonicalPath)
  }

  @Test
  fun `opting out skips default simulator device while keeping custom SimulatorManagedDevice`() {
    val projectDir =
      createProject(
        includeApp = true,
        includeLib = false,
        extraAppDsl =
          """
          composePreview {
              simulatorManagedDevice.set(false)
          }
          android {
              testOptions {
                  managedDevices {
                      allDevices {
                          register<ee.schimke.composeai.plugin.SimulatorManagedDevice>("customSim") {
                              apiLevel.set(34)
                          }
                      }
                  }
              }
          }
          """
            .trimIndent(),
      )

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(":app:tasks", "--group=verification", "--stacktrace")
        .withPluginClasspath(androidPluginClasspath())
        .build()

    assertThat(result.output).contains("customSimDebugAndroidTest")
    assertThat(result.output).doesNotContain("simulatorDebugAndroidTest")
  }

  @Test
  fun `simulator managed device tasks configure under Isolated Projects`() {
    val projectDir = createProject(includeApp = true, includeLib = false, isolatedProjects = true)

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(":app:simulatorDebugAndroidTest", ":app:allDevicesCheck", "--dry-run")
        .withPluginClasspath(androidPluginClasspath())
        .build()

    assertThat(result.output).contains(":app:simulatorDebugAndroidTest SKIPPED")
    assertThat(result.output).contains(":app:allDevicesCheck SKIPPED")
  }

  @Test
  fun `setup task, relative CLI path, additional test outputs, and standalone android test module work end to end`() {
    val projectDir =
      createProject(
        includeApp = true,
        includeLib = false,
        includeTestModule = true,
      )
    val argsLog = File(projectDir, "cli-args.txt")
    val setupLog = File(projectDir, "setup-args.txt")
    val appToolsDir = File(projectDir, "app/tools").apply { mkdirs() }
    createFakeCli(appToolsDir, argsLog, mode = "pass", setupArgsLog = setupLog)

    val result =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(
          ":app:simulatorDebugAndroidTest",
          "-PcomposePreview.cli=./tools/fake-compose-preview.sh",
          "-Pandroid.enableAdditionalTestOutput=true",
          "--stacktrace",
        )
        .withPluginClasspath(androidPluginClasspath())
        .build()

    assertThat(result.output).contains("1 tests: 1 passed, 0 failed, 0 skipped")
    assertThat(setupLog.readLines())
      .containsAtLeast("android-simulator", "--prepare-runtime")
      .inOrder()

    val recorded = argsLog.readLines()
    val additionalIdx = recorded.indexOf("--additional-test-output-dir")
    assertThat(additionalIdx).isGreaterThan(0)
    assertThat(File(recorded[additionalIdx + 1]).isDirectory).isTrue()

    val testModuleDryRun =
      GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments(":testmod:simulatorDebugAndroidTest", "--dry-run")
        .withPluginClasspath(androidPluginClasspath())
        .build()
    assertThat(testModuleDryRun.output).contains(":testmod:simulatorSetup SKIPPED")
    assertThat(testModuleDryRun.output).contains(":testmod:simulatorDebugAndroidTest SKIPPED")
  }

  private fun androidPluginClasspath(): List<File> {
    val metadata = java.util.Properties()
    javaClass.classLoader.getResourceAsStream("plugin-under-test-metadata.properties")!!.use {
      metadata.load(it)
    }
    val plugin = metadata.getProperty("implementation-classpath").split(File.pathSeparator)
    val android =
      System.getProperty("ee.schimke.composeai.functionalTest.androidPluginClasspath", "")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
    return (plugin + android).map(::File)
  }

  private fun createFakeCli(
    projectDir: File,
    argsLog: File,
    mode: String,
    setupArgsLog: File? = null,
  ): File {
    val script = File(projectDir, "fake-compose-preview.sh")
    val xmlBody =
      if (mode == "pass") {
        """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="simulator-instrumentation" tests="1" failures="0" skipped="0" time="0.12">
  <testcase classname="ee.schimke.test.SmokeTest" name="passes" time="0.12"/>
</testsuite>"""
      } else {
        """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="simulator-instrumentation" tests="1" failures="1" skipped="0" time="0.12">
  <testcase classname="ee.schimke.test.SmokeTest" name="fails" time="0.12">
    <failure message="expected true">java.lang.AssertionError: expected true</failure>
  </testcase>
</testsuite>"""
      }
    val jsonBody =
      if (mode == "pass") {
        """{"tests":1,"passed":1,"failed":0,"skipped":0,"cases":[{"class":"ee.schimke.test.SmokeTest","method":"passes","status":"passed"}]}"""
      } else {
        """{"tests":1,"passed":0,"failed":1,"skipped":0,"cases":[{"class":"ee.schimke.test.SmokeTest","method":"fails","status":"failed","message":"expected true"}]}"""
      }
    val exitCode = if (mode == "pass") 0 else 1

    script.writeText(
      buildString {
        appendLine("#!/usr/bin/env bash")
        appendLine("set -euo pipefail")
        appendLine("for arg in \"\$@\"; do")
        appendLine("  if [[ \"\$arg\" == \"--prepare-runtime\" ]]; then")
        if (setupArgsLog != null) {
          appendLine("    printf '%s\\n' \"\$@\" > \"${setupArgsLog.absolutePath}\"")
        }
        appendLine("    echo '{\"ready\":true,\"sdk\":35}'")
        appendLine("    exit 0")
        appendLine("  fi")
        appendLine("done")
        appendLine("printf '%s\\n' \"\$@\" > \"${argsLog.absolutePath}\"")
        appendLine("XML=\"\"")
        appendLine("JSON=\"\"")
        appendLine("while [[ \$# -gt 0 ]]; do")
        appendLine("  case \"\$1\" in")
        appendLine("    --test-report) XML=\"\$2\"; shift 2 ;;")
        appendLine("    --test-json) JSON=\"\$2\"; shift 2 ;;")
        appendLine("    *) shift ;;")
        appendLine("  esac")
        appendLine("done")
        appendLine("if [[ -n \"\$XML\" ]]; then")
        appendLine("cat <<'EOF' > \"\$XML\"")
        appendLine(xmlBody)
        appendLine("EOF")
        appendLine("fi")
        appendLine("if [[ -n \"\$JSON\" ]]; then")
        appendLine("cat <<'EOF' > \"\$JSON\"")
        appendLine(jsonBody)
        appendLine("EOF")
        appendLine("fi")
        appendLine("exit $exitCode")
      },
    )
    script.setExecutable(true)
    return script
  }

  private fun createProject(
    includeApp: Boolean,
    includeLib: Boolean,
    includeTestModule: Boolean = false,
    extraAppDsl: String = "",
    isolatedProjects: Boolean = false,
  ): File {
    val androidSdkDir = System.getProperty("ee.schimke.composeai.functionalTest.androidSdkDir", "")
    assumeTrue(
      "Skipping: no Android SDK reachable via ANDROID_HOME / local.properties",
      androidSdkDir.isNotBlank() && File(androidSdkDir).isDirectory,
    )
    val projectDir = tempDir.newFolder()
    val includes = buildList {
      if (includeApp) add("include(\":app\")")
      if (includeLib) add("include(\":lib\")")
      if (includeTestModule) add("include(\":testmod\")")
    }
    File(projectDir, "settings.gradle.kts")
      .writeText(
        """
        pluginManagement {
            repositories {
                google()
                gradlePluginPortal()
                mavenCentral()
            }
        }
        dependencyResolutionManagement {
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories {
                google()
                mavenCentral()
            }
        }
        rootProject.name = "simulator-managed-device-test"
        ${includes.joinToString("\n")}
        """
          .trimIndent(),
      )
    File(projectDir, "local.properties").writeText("sdk.dir=$androidSdkDir\n")
    File(projectDir, "build.gradle.kts").writeText("")
    File(projectDir, "gradle.properties")
      .writeText(
        buildString {
          appendLine("org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8")
          appendLine("android.useAndroidX=true")
          appendLine("org.gradle.configuration-cache=true")
          if (isolatedProjects) {
            appendLine("org.gradle.unsafe.isolated-projects=true")
          }
        },
      )

    if (includeApp) {
      val appDir = File(projectDir, "app").apply { mkdirs() }
      File(appDir, "build.gradle.kts")
        .writeText(
          """
          plugins {
              id("com.android.application")
              id("ee.schimke.composeai.preview")
          }

          android {
              namespace = "ee.schimke.test.app"
              compileSdk = 36
              defaultConfig {
                  applicationId = "ee.schimke.test.app"
                  minSdk = 26
                  targetSdk = 36
              }
          }

          $extraAppDsl
          """
            .trimIndent(),
        )
      val manifestDir = File(appDir, "src/main").apply { mkdirs() }
      File(manifestDir, "AndroidManifest.xml").writeText("<manifest />\n")
      writeSampleAndroidTest(appDir, "ee.schimke.test")
    }

    if (includeLib) {
      val libDir = File(projectDir, "lib").apply { mkdirs() }
      File(libDir, "build.gradle.kts")
        .writeText(
          """
          plugins {
              id("com.android.library")
              id("ee.schimke.composeai.preview")
          }

          android {
              namespace = "ee.schimke.test.lib"
              compileSdk = 36
              defaultConfig {
                  minSdk = 26
              }
          }
          """
            .trimIndent(),
        )
      writeSampleAndroidTest(libDir, "ee.schimke.test")
    }

    if (includeTestModule) {
      val testModDir = File(projectDir, "testmod").apply { mkdirs() }
      File(testModDir, "build.gradle.kts")
        .writeText(
          """
          plugins {
              id("com.android.test")
              id("ee.schimke.composeai.preview")
          }

          android {
              namespace = "ee.schimke.test.standalone"
              compileSdk = 36
              targetProjectPath = ":app"
              defaultConfig {
                  minSdk = 26
              }
          }
          """
            .trimIndent(),
        )
      val manifestDir = File(testModDir, "src/main").apply { mkdirs() }
      File(manifestDir, "AndroidManifest.xml").writeText("<manifest />\n")
    }

    return projectDir
  }

  private fun writeSampleAndroidTest(moduleDir: File, pkg: String) {
    val dir = File(moduleDir, "src/androidTest/java/${pkg.replace('.', '/')}").apply { mkdirs() }
    File(dir, "SmokeTest.java")
      .writeText(
        """
        package $pkg;

        public class SmokeTest {
            public void passes() {}
        }
        """
          .trimIndent(),
      )
  }
}
