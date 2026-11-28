package ee.schimke.composeai.simulator.launch

import java.io.File

/**
 * The simulator JVM's command line: `:simulator-host`'s `LaunchMain` on Robolectric, shaped the way
 * the simulator needs whoever launches it — `compose-preview android-simulator`, or a host that
 * launches simulators itself (`AppSimulatorLauncher` in `:render-host`). Each caller decides the
 * system properties ([baseProperties] and the `PROPERTY_*` names below); the JVM's own shape is
 * stated once, here.
 */
public object SimulatorJvm {
  public const val LAUNCH_MAIN: String = "ee.schimke.composeai.simulator.LaunchMain"

  /** The name `:simulator-host`'s jar starts with in `lib-simulator/`. */
  public const val HOST_JAR_PREFIX: String = "simulator-host"

  /**
   * Collector settings for every simulator JVM.
   *
   * Android's graphics objects — bitmaps, render nodes, canvases — are small on the Java heap and
   * own native memory that is freed only when the collector finds them unreachable. An app idling
   * on an animated screen allocates them every frame but too little heap to trigger a collection,
   * so native memory grows without bound: Confetti went from 850 MB to 2.3 GB in two idle minutes
   * with 116 MB of live heap, and to 5.4 GB in longer runs whatever its `-Xmx`. A periodic
   * collection, only when none has run for that long, keeps it flat (990 MB).
   */
  public val JVM_ARGS: List<String> = listOf("-XX:G1PeriodicGCInterval=5000")

  public const val PROPERTY_QUALIFIERS: String = "composeai.simulator.qualifiers"
  public const val PROPERTY_FORM_FACTOR: String = "composeai.simulator.formFactor"
  public const val PROPERTY_CONTROL: String = "composeai.simulator.control"
  public const val PROPERTY_NETWORK: String = "composeai.simulator.network"
  public const val PROPERTY_LAUNCHER: String = "composeai.simulator.launcher"
  public const val PROPERTY_NO_LAUNCH: String = "composeai.simulator.noLaunch"
  public const val PROPERTY_SHOW_AGENT: String = "composeai.simulator.showAgent"
  public const val PROPERTY_DATALAYER_HUB: String = "composeai.simulator.datalayer.hub"
  public const val PROPERTY_DATALAYER_AGENT: String = "composeai.simulator.datalayer.agent"
  public const val PROPERTY_DATALAYER_NODE: String = "composeai.simulator.datalayer.node"
  public const val PROPERTY_DATALAYER_NAME: String = "composeai.simulator.datalayer.name"

  /**
   * The simulator's heap when the caller sets none: an eighth of the machine, between 768 MB and 2
   * GB. The JVM's own default, a quarter of the machine, lets G1 grow far past an app's live set
   * (about 120 MB for Confetti and Now in Android) before it collects, and a pair runs two.
   */
  public fun defaultMaxHeap(physicalMemoryBytes: Long?): String {
    val mb = (physicalMemoryBytes ?: 0L) / (1L shl 20) / 8
    return "${mb.coerceIn(768L, 2048L)}m"
  }

  /** Robolectric's settings every simulator runs with, then its display and form factor. */
  public fun baseProperties(
    sdk: Int,
    qualifiers: String,
    formFactor: String,
  ): LinkedHashMap<String, String> =
    linkedMapOf(
      "robolectric.logging.enabled" to "true",
      "robolectric.logging" to "stdout",
      "robolectric.createActivityContexts" to "true",
      "robolectric.useEmbeddedViewRoot" to "true",
      "robolectric.deviceconfig.sdk" to sdk.toString(),
      PROPERTY_QUALIFIERS to qualifiers,
      PROPERTY_FORM_FACTOR to formFactor,
    )

  /** The two system properties that pair a simulator through a [DataLayerHub] on [hubPort]. */
  public fun dataLayerProperties(hubPort: Int, device: String): Map<String, String> =
    mapOf(
      PROPERTY_DATALAYER_HUB to hubPort.toString(),
      PROPERTY_DATALAYER_NODE to "$device-sim",
      PROPERTY_DATALAYER_NAME to "Simulated ${device.replaceFirstChar { it.uppercase() }}",
    )

  /**
   * The launched JVM's command line. Pure, for tests.
   *
   * @param classpath `lib-simulator/`, the Robolectric sidecar and `android.jar`; its
   *   `simulator-host` jar is also the JVM's agent, which lets a reload redefine changed classes.
   * @param classes the APK's converted classes, then any extra jars.
   * @param properties every system property, in the order they are passed.
   */
  public fun command(
    java: File,
    classpath: List<File>,
    apk: File,
    classes: List<File>,
    robolectricJvmArgs: List<String>,
    properties: Map<String, String>,
    maxHeap: String? = null,
    libraryPath: List<String> = emptyList(),
    jvmOptions: List<String> = emptyList(),
    headless: Boolean = true,
  ): List<String> = buildList {
    add(java.path)
    addAll(robolectricJvmArgs)
    maxHeap?.let { add("-Xmx$it") }
    addAll(JVM_ARGS)
    if (libraryPath.isNotEmpty()) {
      add("-Djava.library.path=${libraryPath.joinToString(File.pathSeparator)}")
    }
    addAll(jvmOptions)
    add("--enable-native-access=ALL-UNNAMED")
    classpath
      .firstOrNull { it.name.startsWith(HOST_JAR_PREFIX) }
      ?.let { add("-javaagent:${it.path}") }
    // NetworkGate wraps java.net's socket and resolver, so the simulated network decides what the
    // app's real traffic reaches.
    add("--add-opens=java.base/java.net=ALL-UNNAMED")
    if (headless) add("-Djava.awt.headless=true")
    for ((key, value) in properties) add("-D$key=$value")
    add("-cp")
    add(classpath.joinToString(File.pathSeparator) { it.path })
    add(LAUNCH_MAIN)
    add(apk.path)
    classes.forEach { add(it.path) }
  }
}
