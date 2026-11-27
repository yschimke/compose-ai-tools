package ee.schimke.composeai.plugin

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SimulatorManagedDeviceTest {

  @get:Rule val tempDir = TemporaryFolder()

  @Test
  fun `buildCommand emits minimal arguments when optional settings are unset`() {
    val appApk = File("/tmp/app-debug.apk")
    val testApk = File("/tmp/app-debug-androidTest.apk")
    val xmlReport = File("/tmp/TEST-simulator-debug.xml")
    val jsonReport = File("/tmp/results.json")

    val cmd =
      SimulatorManagedDeviceSupport.buildCommand(
        cliExecutable = "compose-preview",
        appApk = appApk,
        testApk = testApk,
        xmlReport = xmlReport,
        jsonReport = jsonReport,
      )

    assertThat(cmd)
      .containsExactly(
        "compose-preview",
        "android-simulator",
        appApk.absolutePath,
        "--instrument",
        testApk.absolutePath,
        "--test-report",
        xmlReport.absolutePath,
        "--test-json",
        jsonReport.absolutePath,
      )
      .inOrder()
  }

  @Test
  fun `buildCommand forwards all simulator options and filters`() {
    val appApk = File("/tmp/app-debug.apk")
    val testApk = File("/tmp/app-debug-androidTest.apk")
    val xmlReport = File("/tmp/TEST-pixelApi35-debug.xml")
    val jsonReport = File("/tmp/results.json")

    val cmd =
      SimulatorManagedDeviceSupport.buildCommand(
        cliExecutable = "/usr/local/bin/compose-preview",
        appApk = appApk,
        testApk = testApk,
        xmlReport = xmlReport,
        jsonReport = jsonReport,
        javaHome = "/opt/jdk-21",
        apiLevel = 35,
        device = "phone",
        formFactor = "wear",
        qualifiers = "w411dp-h914dp-xxhdpi",
        shards = 3,
        pool = true,
        testsFilter = "com.example.LoginTest,com.example.CartTest#checkout",
      )

    assertThat(cmd)
      .containsExactly(
        "/usr/local/bin/compose-preview",
        "android-simulator",
        appApk.absolutePath,
        "--instrument",
        testApk.absolutePath,
        "--test-report",
        xmlReport.absolutePath,
        "--test-json",
        jsonReport.absolutePath,
        "--java",
        "/opt/jdk-21",
        "--sdk",
        "35",
        "--device",
        "phone",
        "--form-factor",
        "wear",
        "--qualifiers",
        "w411dp-h914dp-xxhdpi",
        "--shards",
        "3",
        "--pool",
        "--tests",
        "com.example.LoginTest,com.example.CartTest#checkout",
      )
      .inOrder()
  }

  @Test
  fun `buildCommand omits single shard and blank optional values`() {
    val cmd =
      SimulatorManagedDeviceSupport.buildCommand(
        cliExecutable = "compose-preview",
        appApk = File("/tmp/a.apk"),
        testApk = File("/tmp/t.apk"),
        xmlReport = File("/tmp/r.xml"),
        jsonReport = File("/tmp/r.json"),
        javaHome = "  ",
        device = "",
        formFactor = "",
        qualifiers = "",
        shards = 1,
        pool = false,
        testsFilter = " ",
      )

    assertThat(cmd).doesNotContain("--java")
    assertThat(cmd).doesNotContain("--device")
    assertThat(cmd).doesNotContain("--form-factor")
    assertThat(cmd).doesNotContain("--qualifiers")
    assertThat(cmd).doesNotContain("--shards")
    assertThat(cmd).doesNotContain("--pool")
    assertThat(cmd).doesNotContain("--tests")
  }

  @Test
  fun `parseDensityFromQualifiers handles buckets, explicit dpi, and defaults`() {
    assertThat(SimulatorManagedDeviceSupport.parseDensityFromQualifiers(null)).isEqualTo(420)
    assertThat(SimulatorManagedDeviceSupport.parseDensityFromQualifiers("")).isEqualTo(420)
    assertThat(SimulatorManagedDeviceSupport.parseDensityFromQualifiers("w360dp-h640dp-hdpi"))
      .isEqualTo(240)
    assertThat(
        SimulatorManagedDeviceSupport.parseDensityFromQualifiers("w227dp-h227dp-round-xhdpi"),
      )
      .isEqualTo(320)
    assertThat(SimulatorManagedDeviceSupport.parseDensityFromQualifiers("w411dp-h914dp-xxhdpi"))
      .isEqualTo(480)
    assertThat(SimulatorManagedDeviceSupport.parseDensityFromQualifiers("w411dp-h891dp-440dpi"))
      .isEqualTo(440)
    assertThat(SimulatorManagedDeviceSupport.parseDensityFromQualifiers("w960dp-h720dp-mdpi"))
      .isEqualTo(160)
  }

  @Test
  fun `enrichJunitXmlForAgpReport inserts AGP properties block once`() {
    val original =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <testsuite name="simulator-instrumentation" tests="1" failures="0" skipped="0" time="0.5">
        <testcase classname="com.example.SmokeTest" name="ok" time="0.5"/>
      </testsuite>
      """
        .trimIndent()

    val enriched =
      SimulatorManagedDeviceSupport.enrichJunitXmlForAgpReport(
        xml = original,
        deviceName = "simulator",
        projectPath = ":app",
        flavorName = "debug",
      )

    assertThat(enriched).contains("<properties>")
    assertThat(enriched).contains("<property name=\"device\" value=\"simulator\"/>")
    assertThat(enriched).contains("<property name=\"project\" value=\":app\"/>")
    assertThat(enriched).contains("<property name=\"flavor\" value=\"debug\"/>")
    assertThat(enriched).contains("<testcase classname=\"com.example.SmokeTest\"")

    // Idempotent when called a second time.
    val enrichedAgain =
      SimulatorManagedDeviceSupport.enrichJunitXmlForAgpReport(
        xml = enriched,
        deviceName = "simulator",
        projectPath = ":app",
        flavorName = "debug",
      )
    assertThat(enrichedAgain).isEqualTo(enriched)
  }

  @Test
  fun `parseSummary formats passed, failed, and error reports`() {
    val passFile = tempDir.newFile("pass.json")
    passFile.writeText(
      """{"tests":2,"passed":2,"failed":0,"skipped":0,"cases":[]}""",
    )
    val pass = SimulatorManagedDeviceSupport.parseSummary(passFile)!!
    assertThat(pass.error).isNull()
    assertThat(pass.text).isEqualTo("2 tests: 2 passed, 0 failed, 0 skipped")

    val failFile = tempDir.newFile("fail.json")
    failFile.writeText(
      """
      {
        "tests": 2, "passed": 1, "failed": 1, "skipped": 0,
        "cases": [
          {"class": "com.example.CartTest", "method": "checkout", "status": "failed", "message": "boom"}
        ]
      }
      """
        .trimIndent(),
    )
    val fail = SimulatorManagedDeviceSupport.parseSummary(failFile)!!
    assertThat(fail.error).isNull()
    assertThat(fail.text).contains("2 tests: 1 passed, 1 failed, 0 skipped")
    assertThat(fail.text).contains("FAILED com.example.CartTest#checkout: boom")

    val errFile = tempDir.newFile("err.json")
    errFile.writeText("""{"tests":0,"passed":0,"failed":0,"skipped":0,"error":"bad sdk"}""")
    val err = SimulatorManagedDeviceSupport.parseSummary(errFile)!!
    assertThat(err.error).isEqualTo("bad sdk")
    assertThat(err.text).isEqualTo("No tests ran: bad sdk")
  }
}
