package ee.schimke.composeai.plugin

import com.android.build.api.dsl.Device
import com.android.build.api.dsl.KotlinMultiplatformAndroidDeviceTest
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import com.android.build.api.dsl.ManagedDevices
import com.android.build.api.instrumentation.StaticTestData
import com.android.build.api.instrumentation.manageddevice.DeviceSetupConfigureAction
import com.android.build.api.instrumentation.manageddevice.DeviceSetupInput
import com.android.build.api.instrumentation.manageddevice.DeviceSetupTaskAction
import com.android.build.api.instrumentation.manageddevice.DeviceTestRunConfigureAction
import com.android.build.api.instrumentation.manageddevice.DeviceTestRunInput
import com.android.build.api.instrumentation.manageddevice.DeviceTestRunParameters
import com.android.build.api.instrumentation.manageddevice.DeviceTestRunTaskAction
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.builder.testing.api.DeviceConfigProvider
import groovy.json.JsonSlurper
import java.io.File
import javax.inject.Inject
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

/**
 * Custom AGP Gradle Managed Device backed by `compose-preview android-simulator --instrument`.
 *
 * Register custom simulator devices in `android.testOptions.managedDevices.allDevices`:
 * ```kotlin
 * import ee.schimke.composeai.plugin.SimulatorManagedDevice
 *
 * android {
 *   testOptions {
 *     managedDevices {
 *       allDevices {
 *         register<SimulatorManagedDevice>("simulatorApi35") {
 *           apiLevel.set(35)
 *           device.set("phone")
 *         }
 *       }
 *     }
 *   }
 * }
 * ```
 *
 * AGP generates `<name><Variant>AndroidTest`, `<name>Check`, `allDevices<Variant>AndroidTest`,
 * `allDevicesCheck`, and device-group tasks, plus its standard HTML and JUnit XML test reports.
 *
 * By default, the plugin registers a `"simulator"` device automatically so
 * `simulator<Variant>AndroidTest` and `simulatorCheck` work with zero configuration (opt out via
 * `composePreview { simulatorManagedDevice = false }` or
 * `-PcomposePreview.simulatorManagedDevice=false`).
 */
interface SimulatorManagedDevice : Device {
  /** Android SDK API level (`--sdk`, 33+). Unset uses the CLI default. */
  val apiLevel: Property<Int>

  /**
   * Reference device preset (`--device`, e.g. `"phone"`, `"foldable"`, `"tablet"`, `"desktop"`).
   */
  val device: Property<String>

  /** Device form factor (`--form-factor`, e.g. `"phone"`, `"wear"`, `"glimmer"`, `"xr"`). */
  val formFactor: Property<String>

  /** Display qualifiers (`--qualifiers`, e.g. `"w411dp-h914dp-xxhdpi"`). */
  val qualifiers: Property<String>

  /**
   * Number of parallel simulator shards (`--shards`). Unset or 1 runs all tests in one simulator.
   */
  val shards: Property<Int>

  /** Whether to run in pre-warmed simulators from the CLI's background pool (`--pool`). */
  val pool: Property<Boolean>

  /**
   * Path or executable name of the `compose-preview` CLI. Defaults to `-PcomposePreview.cli`, else
   * `COMPOSE_PREVIEW_CLI`, else `"compose-preview"` on `PATH`.
   */
  val cliExecutable: Property<String>

  /**
   * JDK 21+ home for the simulator JVM (`--java`). Defaults to `-PcomposePreview.simulatorJava`,
   * else a JDK 21 Gradle toolchain, else CLI auto-detection.
   */
  val javaHome: Property<String>
}

/**
 * Convenience helper to register a [SimulatorManagedDevice] in
 * `android.testOptions.managedDevices`.
 */
fun ManagedDevices.simulatorDevice(
  name: String,
  configure: Action<SimulatorManagedDevice> = Action {},
): NamedDomainObjectProvider<SimulatorManagedDevice> =
  allDevices.register(name, SimulatorManagedDevice::class.java, configure)

abstract class SimulatorManagedDeviceImpl @Inject constructor(private val deviceName: String) :
  SimulatorManagedDevice {
  override fun getName(): String = deviceName
}

abstract class SimulatorSetupInput : DeviceSetupInput {
  @get:Input abstract val cliExecutable: Property<String>

  @get:Input @get:Optional abstract val javaHome: Property<String>

  @get:Input @get:Optional abstract val apiLevel: Property<Int>

  /** Module working directory for resolving relative CLI paths and spawning the process. */
  @get:Internal abstract val projectDirectoryPath: Property<String>
}

abstract class SimulatorSetupConfigureAction
@Inject
constructor(
  private val objects: ObjectFactory,
  private val providers: ProviderFactory,
  private val layout: org.gradle.api.file.ProjectLayout,
) : DeviceSetupConfigureAction<SimulatorManagedDevice, SimulatorSetupInput> {

  override fun configureTaskInput(deviceDSL: SimulatorManagedDevice): SimulatorSetupInput =
    objects.newInstance(SimulatorSetupInput::class.java).apply {
      cliExecutable.set(
        deviceDSL.cliExecutable
          .orElse(providers.gradleProperty("composePreview.cli"))
          .orElse(providers.environmentVariable("COMPOSE_PREVIEW_CLI"))
          .orElse("compose-preview"),
      )
      javaHome.set(
        deviceDSL.javaHome.orElse(providers.gradleProperty("composePreview.simulatorJava")),
      )
      apiLevel.set(
        deviceDSL.apiLevel.orElse(
          providers.gradleProperty("composePreview.simulatorSdk").map { it.toInt() },
        ),
      )
      projectDirectoryPath.set(layout.projectDirectory.asFile.absolutePath)
    }
}

open class SimulatorSetupTaskAction @Inject constructor() :
  DeviceSetupTaskAction<SimulatorSetupInput>, java.io.Serializable {

  override fun setup(setupInput: SimulatorSetupInput, outputDir: Directory) {
    val logger = Logging.getLogger(SimulatorSetupTaskAction::class.java)
    val apiLevel = setupInput.apiLevel.orNull
    if (apiLevel != null && apiLevel < SimulatorManagedDeviceSupport.MIN_SIMULATOR_API) {
      throw GradleException(
        "SimulatorManagedDevice requires apiLevel >= ${SimulatorManagedDeviceSupport.MIN_SIMULATOR_API}, " +
          "got $apiLevel.",
      )
    }

    val outDir = outputDir.asFile
    outDir.mkdirs()
    val marker = File(outDir, "simulator-setup.json")

    val workingDir = setupInput.projectDirectoryPath.orNull?.let(::File)?.takeIf { it.isDirectory }
    val resolvedCli =
      SimulatorManagedDeviceSupport.resolveExecutable(setupInput.cliExecutable.get(), workingDir)
    val command =
      SimulatorManagedDeviceSupport.buildSetupCommand(
        cliExecutable = resolvedCli,
        javaHome = setupInput.javaHome.orNull,
        apiLevel = apiLevel,
      )
    logger.info("compose-preview setup: {}", command.joinToString(" "))

    val outputLines = mutableListOf<String>()
    val exitValue =
      try {
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        if (workingDir != null) pb.directory(workingDir)
        val process = pb.start()
        process.inputStream.bufferedReader().useLines { lines ->
          lines.forEach { line ->
            outputLines += line
            logger.info(line)
          }
        }
        process.waitFor()
      } catch (e: Exception) {
        throw GradleException(
          "Could not run `${setupInput.cliExecutable.get()}` during simulator device setup " +
            "(${e.message}). Install the compose-preview CLI, or point -PcomposePreview.cli " +
            "(or COMPOSE_PREVIEW_CLI) at it.",
          e,
        )
      }

    val combined = outputLines.joinToString("\n")
    if (exitValue == 0) {
      val jsonLine = outputLines.lastOrNull { it.trim().startsWith("{") }
      marker.writeText((jsonLine ?: """{"ready":true}""") + "\n")
      return
    }

    // Older compose-preview CLIs did not support `--prepare-runtime`: degrade gracefully so device
    // setup does not break existing installs; test execution still provisions lazily.
    if (SimulatorManagedDeviceSupport.isUnsupportedPrepareRuntimeOutput(combined)) {
      logger.warn(
        "compose-preview CLI does not support `--prepare-runtime`; skipping pre-provisioning " +
          "(upgrade compose-preview CLI to pre-warm sidecar and android-all in <device>Setup).",
      )
      marker.writeText("""{"ready":true,"legacyCli":true}""" + "\n")
      return
    }

    throw GradleException(
      buildString {
        append("Simulator device setup failed (exit $exitValue).")
        if (combined.isNotBlank()) append("\n").append(combined)
      },
    )
  }

  companion object {
    private const val serialVersionUID: Long = 1L
  }
}

abstract class SimulatorTestRunInput : DeviceTestRunInput {
  @get:Input abstract val cliExecutable: Property<String>

  @get:Input @get:Optional abstract val javaHome: Property<String>

  @get:Input @get:Optional abstract val apiLevel: Property<Int>

  @get:Input @get:Optional abstract val device: Property<String>

  @get:Input @get:Optional abstract val formFactor: Property<String>

  @get:Input @get:Optional abstract val qualifiers: Property<String>

  @get:Internal abstract val shards: Property<Int>

  @get:Internal abstract val pool: Property<Boolean>

  /** Module working directory for resolving relative CLI paths and spawning the process. */
  @get:Internal abstract val projectDirectoryPath: Property<String>
}

abstract class SimulatorTestRunConfigureAction
@Inject
constructor(
  private val objects: ObjectFactory,
  private val providers: ProviderFactory,
  private val layout: org.gradle.api.file.ProjectLayout,
) : DeviceTestRunConfigureAction<SimulatorManagedDevice, SimulatorTestRunInput> {

  override fun configureTaskInput(deviceDSL: SimulatorManagedDevice): SimulatorTestRunInput =
    objects.newInstance(SimulatorTestRunInput::class.java).apply {
      cliExecutable.set(
        deviceDSL.cliExecutable
          .orElse(providers.gradleProperty("composePreview.cli"))
          .orElse(providers.environmentVariable("COMPOSE_PREVIEW_CLI"))
          .orElse("compose-preview"),
      )
      javaHome.set(
        deviceDSL.javaHome.orElse(providers.gradleProperty("composePreview.simulatorJava")),
      )
      apiLevel.set(
        deviceDSL.apiLevel.orElse(
          providers.gradleProperty("composePreview.simulatorSdk").map { it.toInt() },
        ),
      )
      device.set(
        deviceDSL.device.orElse(providers.gradleProperty("composePreview.simulatorDevice")),
      )
      formFactor.set(
        deviceDSL.formFactor.orElse(providers.gradleProperty("composePreview.simulatorFormFactor")),
      )
      qualifiers.set(
        deviceDSL.qualifiers.orElse(providers.gradleProperty("composePreview.simulatorQualifiers")),
      )
      shards.set(
        deviceDSL.shards
          .orElse(providers.gradleProperty("composePreview.simulatorShards").map { it.toInt() })
          .orElse(
            providers
              .gradleProperty("android.experimental.androidTest.numManagedDeviceShards")
              .map { it.toInt() },
          ),
      )
      pool.set(
        deviceDSL.pool.orElse(
          providers.gradleProperty("composePreview.simulatorPool").map { it.toBoolean() },
        ),
      )
      projectDirectoryPath.set(layout.projectDirectory.asFile.absolutePath)
    }
}

open class SimulatorTestRunTaskAction @Inject constructor() :
  DeviceTestRunTaskAction<SimulatorTestRunInput>, java.io.Serializable {

  override fun runTests(params: DeviceTestRunParameters<SimulatorTestRunInput>): Boolean {
    val logger = Logging.getLogger(SimulatorTestRunTaskAction::class.java)
    val input = params.deviceInput
    val runData = params.testRunData
    val testData = runData.testData

    val deviceConfig =
      SimulatorManagedDeviceSupport.deviceConfigProvider(
        apiLevel = input.apiLevel.orNull,
        minSdkVersion = testData.minSdkVersion.apiLevel,
        qualifiers = input.qualifiers.orNull,
      )
    val (appApk, testApk) = SimulatorManagedDeviceSupport.resolveApks(testData, deviceConfig)

    val outputDir = runData.outputDirectory.asFile
    outputDir.mkdirs()
    val xmlReport = File(outputDir, "TEST-${runData.deviceName}.xml")
    val jsonReport = File(outputDir, "results.json")
    xmlReport.delete()
    jsonReport.delete()

    val coverageOutDir =
      if (testData.isTestCoverageEnabled) {
        runData.coverageOutputDirectory.asFile.apply { mkdirs() }
      } else {
        null
      }
    val additionalOutputDir = runData.additionalTestOutputDir?.asFile?.apply { mkdirs() }

    val runnerArgs = testData.instrumentationRunnerArguments
    val testsFilter = runnerArgs["class"]?.trim()?.takeIf { it.isNotEmpty() }
    val effectiveShards = input.shards.orNull ?: runnerArgs["numShards"]?.trim()?.toIntOrNull()

    val workingDir = input.projectDirectoryPath.orNull?.let(::File)?.takeIf { it.isDirectory }
    val resolvedCli =
      SimulatorManagedDeviceSupport.resolveExecutable(input.cliExecutable.get(), workingDir)

    val command =
      SimulatorManagedDeviceSupport.buildCommand(
        cliExecutable = resolvedCli,
        appApk = appApk,
        testApk = testApk,
        xmlReport = xmlReport,
        jsonReport = jsonReport,
        javaHome = input.javaHome.orNull,
        apiLevel = input.apiLevel.orNull,
        device = input.device.orNull,
        formFactor = input.formFactor.orNull,
        qualifiers = input.qualifiers.orNull,
        shards = effectiveShards,
        pool = input.pool.getOrElse(false),
        testsFilter = testsFilter,
        coverageOutputDir = coverageOutDir,
        additionalTestOutputDir = additionalOutputDir,
      )

    logger.info("compose-preview: {}", command.joinToString(" "))
    val exitValue =
      try {
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        if (workingDir != null) pb.directory(workingDir)
        val process = pb.start()
        process.inputStream.bufferedReader().useLines { lines ->
          lines.forEach { logger.lifecycle(it) }
        }
        process.waitFor()
      } catch (e: Exception) {
        throw GradleException(
          "Could not run `${input.cliExecutable.get()}` (${e.message}). Install the compose-preview " +
            "CLI, or point -PcomposePreview.cli (or COMPOSE_PREVIEW_CLI) at it.",
          e,
        )
      }

    if (xmlReport.isFile) {
      val enriched =
        SimulatorManagedDeviceSupport.enrichJunitXmlForAgpReport(
          xml = xmlReport.readText(),
          deviceName = runData.deviceName,
          projectPath = runData.projectPath,
          flavorName = testData.flavorName.ifEmpty { runData.variantName },
        )
      xmlReport.writeText(enriched)
    }

    val summary = SimulatorManagedDeviceSupport.parseSummary(jsonReport)
    if (exitValue == 0) {
      summary?.text?.let { logger.lifecycle(it) }
      return true
    }

    if (summary?.error != null || !xmlReport.isFile) {
      throw GradleException(
        buildString {
          append("Simulator instrumentation tests failed (exit $exitValue).")
          summary?.text?.let { append("\n").append(it) }
          append("\nResults: ").append(outputDir)
        },
      )
    }

    summary?.text?.let { logger.error(it) }
    return false
  }

  companion object {
    private const val serialVersionUID: Long = 1L
  }
}

internal object SimulatorManagedDeviceSupport {
  /** Name of the default managed device, producing `simulator<Variant>AndroidTest`. */
  const val DEFAULT_DEVICE_NAME: String = "simulator"

  /** Minimum Android API level Robolectric's simulator supports. */
  internal const val MIN_SIMULATOR_API: Int = 33

  data class Summary(val text: String, val error: String?)

  /** Registers [SimulatorManagedDevice] with AGP's managed device registry. */
  fun registerDeviceType(androidComponents: AndroidComponentsExtension<*, *, *>) {
    androidComponents.managedDeviceRegistry.registerDeviceType(SimulatorManagedDevice::class.java) {
      dslImplementationClass = SimulatorManagedDeviceImpl::class.java
      setSetupActions(
        SimulatorSetupConfigureAction::class.java,
        SimulatorSetupTaskAction::class.java,
      )
      setTestRunActions(
        SimulatorTestRunConfigureAction::class.java,
        SimulatorTestRunTaskAction::class.java,
      )
    }
  }

  /**
   * Registers [SimulatorManagedDevice] on a KMP-Android module only when the consumer enabled
   * `withDeviceTest {}` (calling `managedDeviceRegistry` otherwise throws in AGP).
   */
  fun configureKmpDeviceTestIfEnabled(
    project: Project,
    extension: PreviewExtension,
    androidComponents: AndroidComponentsExtension<*, *, *>,
    android: KotlinMultiplatformAndroidLibraryExtension,
  ) {
    if (runCatching { androidComponents.managedDeviceRegistry }.isFailure) return
    runCatching { registerDeviceType(androidComponents) }
    val managedDevices = kmpDeviceTestManagedDevices(android) ?: return
    configureDevices(project, extension, managedDevices)
  }

  private fun kmpDeviceTestManagedDevices(
    android: KotlinMultiplatformAndroidLibraryExtension,
  ): ManagedDevices? = runCatching {
    val method =
      android.javaClass.methods.firstOrNull {
        it.name.startsWith("getAndroidTestOnDeviceOptions") && it.parameterCount == 0
      } ?: return@runCatching null
    (method.invoke(android) as? KotlinMultiplatformAndroidDeviceTest)?.managedDevices
  }
    .getOrNull()

  /**
   * Wires default conventions onto every [SimulatorManagedDevice] in [managedDevices] and registers
   * the default [DEFAULT_DEVICE_NAME] device when enabled and not already declared.
   */
  fun configureDevices(
    project: Project,
    extension: PreviewExtension,
    managedDevices: ManagedDevices,
  ) {
    val providers = project.providers
    val toolchains = project.extensions.findByType(JavaToolchainService::class.java)
    // A JDK 21 toolchain when Gradle knows one; otherwise the CLI looks for its own
    // (COMPOSE_PREVIEW_SIMULATOR_JAVA, its own JVM, JAVA_HOME).
    val toolchainJava = toolchains?.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }

    val concurrencyService = SimulatorConcurrencyService.registerIfAbsent(project)

    managedDevices.allDevices.withType(SimulatorManagedDevice::class.java).configureEach {
      val deviceName = name
      project.tasks.configureEach {
        if (SimulatorConcurrencyService.isManagedDeviceTestTaskName(name, deviceName)) {
          usesService(concurrencyService)
        }
      }
      cliExecutable.convention(
        providers
          .gradleProperty("composePreview.cli")
          .orElse(providers.environmentVariable("COMPOSE_PREVIEW_CLI"))
          .orElse("compose-preview"),
      )
      javaHome.convention(
        providers
          .gradleProperty("composePreview.simulatorJava")
          .orElse(
            providers.provider {
              try {
                toolchainJava?.get()?.metadata?.installationPath?.asFile?.absolutePath
              } catch (_: Exception) {
                null
              }
            },
          ),
      )
      apiLevel.convention(
        providers.gradleProperty("composePreview.simulatorSdk").map { it.toInt() },
      )
      device.convention(providers.gradleProperty("composePreview.simulatorDevice"))
      formFactor.convention(providers.gradleProperty("composePreview.simulatorFormFactor"))
      qualifiers.convention(providers.gradleProperty("composePreview.simulatorQualifiers"))
      shards.convention(
        providers
          .gradleProperty("composePreview.simulatorShards")
          .map { it.toInt() }
          .orElse(
            providers
              .gradleProperty("android.experimental.androidTest.numManagedDeviceShards")
              .map { it.toInt() },
          ),
      )
      pool.convention(
        providers.gradleProperty("composePreview.simulatorPool").map { it.toBoolean() },
      )
    }

    if (
      extension.enabled.get() &&
        extension.simulatorManagedDevice.get() &&
        managedDevices.allDevices.findByName(DEFAULT_DEVICE_NAME) == null
    ) {
      managedDevices.allDevices.register(DEFAULT_DEVICE_NAME, SimulatorManagedDevice::class.java)
    }
  }

  /**
   * Resolves the `(appApk, testApk)` pair from AGP's [StaticTestData]. A library module's test APK
   * instruments itself, so `testApk` is passed as both APKs.
   */
  internal fun resolveApks(
    testData: StaticTestData,
    deviceConfigProvider: DeviceConfigProvider,
  ): Pair<File, File> {
    val testApk = testData.testApk
    if (testData.isLibrary) return testApk to testApk
    val testedApks = testData.testedApkFinder(deviceConfigProvider)
    if (testedApks.isEmpty()) {
      throw GradleException(
        "Expected one app APK for ${testData.applicationId}, found none from AGP's testedApkFinder.",
      )
    }
    val appApk =
      testedApks.singleOrNull()
        ?: throw GradleException(
          "Expected one app APK for ${testData.applicationId}, found ${testedApks.size}: " +
            "${testedApks.joinToString { it.name }}. Split APKs are not supported by the simulator.",
        )
    return appApk to testApk
  }

  internal fun deviceConfigProvider(
    apiLevel: Int?,
    minSdkVersion: Int,
    qualifiers: String?,
  ): DeviceConfigProvider =
    object : DeviceConfigProvider {
      override fun getConfigFor(abi: String?): String? = null

      override fun getDensity(): Int = parseDensityFromQualifiers(qualifiers)

      override fun getLanguage(): String = "en"

      override fun getRegion(): String = "US"

      override fun getAbis(): List<String> = preferredAbis()

      override fun getApiLevel(): Int = apiLevel ?: maxOf(MIN_SIMULATOR_API, minSdkVersion)
    }

  internal fun parseDensityFromQualifiers(qualifiers: String?): Int {
    if (qualifiers.isNullOrBlank()) return 420
    val tokens = qualifiers.split('-').map { it.trim().lowercase() }
    for (token in tokens) {
      val explicit = Regex("^([0-9]+)dpi$").matchEntire(token)?.groupValues?.get(1)?.toIntOrNull()
      if (explicit != null) return explicit
      when (token) {
        "ldpi" -> return 120
        "mdpi" -> return 160
        "tvdpi" -> return 213
        "hdpi" -> return 240
        "xhdpi" -> return 320
        "xxhdpi" -> return 480
        "xxxhdpi" -> return 640
      }
    }
    return 420
  }

  private fun preferredAbis(): List<String> {
    val arch = System.getProperty("os.arch", "").lowercase()
    return if (arch == "aarch64" || arch == "arm64") {
      listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    } else {
      listOf("x86_64", "arm64-v8a", "x86", "armeabi-v7a")
    }
  }

  /**
   * Resolves a relative path CLI executable (such as `./tools/compose-preview`) against
   * [workingDir], while leaving bare command names (`compose-preview`) untouched for `PATH` lookup.
   */
  internal fun resolveExecutable(cliExecutable: String, workingDir: File?): String {
    if (workingDir == null) return cliExecutable
    if (!cliExecutable.contains('/') && !cliExecutable.contains('\\')) return cliExecutable
    val file = File(cliExecutable)
    return if (file.isAbsolute) cliExecutable else File(workingDir, cliExecutable).path
  }

  internal fun buildSetupCommand(
    cliExecutable: String,
    javaHome: String? = null,
    apiLevel: Int? = null,
  ): List<String> = buildList {
    add(cliExecutable)
    add("android-simulator")
    add("--prepare-runtime")
    javaHome
      ?.takeIf { it.isNotBlank() }
      ?.let {
        add("--java")
        add(it)
      }
    apiLevel?.let {
      add("--sdk")
      add(it.toString())
    }
  }

  internal fun isUnsupportedPrepareRuntimeOutput(output: String): Boolean {
    val lower = output.lowercase()
    return lower.contains("--prepare-runtime") &&
      (lower.contains("unknown") || lower.contains("unrecognized") || lower.contains("unexpected"))
  }

  internal fun buildCommand(
    cliExecutable: String,
    appApk: File,
    testApk: File,
    xmlReport: File,
    jsonReport: File,
    javaHome: String? = null,
    apiLevel: Int? = null,
    device: String? = null,
    formFactor: String? = null,
    qualifiers: String? = null,
    shards: Int? = null,
    pool: Boolean = false,
    testsFilter: String? = null,
    coverageOutputDir: File? = null,
    additionalTestOutputDir: File? = null,
  ): List<String> {
    if (!device.isNullOrBlank() && !qualifiers.isNullOrBlank()) {
      throw GradleException(
        "SimulatorManagedDevice cannot set both `device` ($device) and `qualifiers` ($qualifiers); " +
          "use either a device preset (`phone`, `foldable`, `tablet`, `desktop`) or explicit qualifiers.",
      )
    }
    return buildList {
      add(cliExecutable)
      add("android-simulator")
      add(appApk.absolutePath)
      add("--instrument")
      add(testApk.absolutePath)
      add("--test-report")
      add(xmlReport.absolutePath)
      add("--test-json")
      add(jsonReport.absolutePath)
      javaHome
        ?.takeIf { it.isNotBlank() }
        ?.let {
          add("--java")
          add(it)
        }
      apiLevel?.let {
        add("--sdk")
        add(it.toString())
      }
      device
        ?.takeIf { it.isNotBlank() }
        ?.let {
          add("--device")
          add(it)
        }
      formFactor
        ?.takeIf { it.isNotBlank() }
        ?.let {
          add("--form-factor")
          add(it)
        }
      qualifiers
        ?.takeIf { it.isNotBlank() }
        ?.let {
          add("--qualifiers")
          add(it)
        }
      shards
        ?.takeIf { it > 1 }
        ?.let {
          add("--shards")
          add(it.toString())
        }
      if (pool) add("--pool")
      testsFilter
        ?.takeIf { it.isNotBlank() }
        ?.let {
          add("--tests")
          add(it)
        }
      coverageOutputDir?.let {
        add("--coverage-out")
        add(it.absolutePath)
      }
      additionalTestOutputDir?.let {
        add("--additional-test-output-dir")
        add(it.absolutePath)
      }
    }
  }

  /**
   * Adds AGP's `<properties>` block (`device`, `project`, `flavor`) right after `<testsuite ...>`
   * when absent, so AGP's `TestReport` HTML generator attributes every test case to this managed
   * device and project.
   */
  internal fun enrichJunitXmlForAgpReport(
    xml: String,
    deviceName: String,
    projectPath: String,
    flavorName: String,
  ): String {
    if (xml.contains("<properties>")) return xml
    val suiteStart = xml.indexOf("<testsuite")
    if (suiteStart < 0) return xml
    val suiteTagEnd = xml.indexOf('>', suiteStart)
    if (suiteTagEnd < 0 || xml[suiteTagEnd - 1] == '/') return xml
    val properties = buildString {
      append("\n  <properties>\n")
      append("    <property name=\"device\" value=\"")
        .append(xmlEscape(deviceName))
        .append("\"/>\n")
      append("    <property name=\"project\" value=\"")
        .append(xmlEscape(projectPath))
        .append("\"/>\n")
      append("    <property name=\"flavor\" value=\"")
        .append(xmlEscape(flavorName))
        .append("\"/>\n")
      append("  </properties>")
    }
    return xml.substring(0, suiteTagEnd + 1) + properties + xml.substring(suiteTagEnd + 1)
  }

  private fun xmlEscape(value: String): String = buildString {
    for (ch in value) {
      when (ch) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(ch)
      }
    }
  }

  /** Parses `results.json` into a human-readable summary and optional top-level startup error. */
  internal fun parseSummary(jsonReport: File): Summary? {
    if (!jsonReport.isFile) return null
    @Suppress("UNCHECKED_CAST")
    val json = JsonSlurper().parse(jsonReport) as? Map<String, Any?> ?: return null
    @Suppress("UNCHECKED_CAST") val cases = json["cases"] as? List<Map<String, Any?>> ?: emptyList()
    json["error"]?.toString()?.let { error ->
      return Summary(text = "No tests ran: $error", error = error)
    }
    val text = buildString {
      append(
        "${json["tests"]} tests: ${json["passed"]} passed, ${json["failed"]} failed, " +
          "${json["skipped"]} skipped",
      )
      for (case in cases.filter { it["status"] == "failed" }) {
        append("\n  FAILED ${case["class"]}#${case["method"]}: ${case["message"]}")
      }
    }
    return Summary(text = text, error = null)
  }
}
