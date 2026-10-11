package ee.schimke.composeai.daemonlaunch

import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import kotlinx.serialization.json.Json

/**
 * Builds `daemon-launch.json` from pre-resolved inputs, for Bazel / Amper via
 * [newDescriptorBuilder] or [DaemonLaunchBuilderCli]. The shape is the contracts'
 * [DaemonLaunchDescriptor]; what lives here is the behaviour around it: stamping the schema version
 * and the canonical encoder. Generic: Android classpath layering stays in the Gradle plugin's
 * `AndroidPreviewClasspath`.
 */
public object DaemonLaunchBuilder {

  /**
   * Pretty-printed (devs `cat` it when debugging), with explicit nulls so a field is never
   * ambiguously missing.
   */
  public val json: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
  }

  /**
   * A [DaemonLaunchDescriptor.Builder] stamped with [DaemonLaunchDescriptor.SCHEMA_VERSION]; all
   * other fields pass through verbatim. Optional fields (`javaLauncher`, `btaCompile`, …) are set
   * on the returned builder, so a field the contracts add later needs no new parameter here.
   */
  public fun newDescriptorBuilder(
    modulePath: String,
    variant: String,
    mainClass: String,
    classpath: List<String>,
    jvmArgs: List<String>,
    systemProperties: Map<String, String>,
    workingDirectory: String,
    manifestPath: String,
    enabled: Boolean = true,
  ): DaemonLaunchDescriptor.Builder =
    DaemonLaunchDescriptor.Builder(
      schemaVersion = DaemonLaunchDescriptor.SCHEMA_VERSION,
      modulePath = modulePath,
      variant = variant,
      enabled = enabled,
      mainClass = mainClass,
      classpath = classpath,
      jvmArgs = jvmArgs,
      systemProperties = systemProperties,
      workingDirectory = workingDirectory,
      manifestPath = manifestPath,
    )

  /** Serializes [descriptor] using the canonical [json] encoder. */
  public fun encode(descriptor: DaemonLaunchDescriptor): String =
    json.encodeToString(DaemonLaunchDescriptor.serializer(), descriptor)

  /**
   * Deserializes a `daemon-launch.json` payload with the canonical [json] configuration, which
   * rejects unknown keys. Readers that must tolerate a newer writer use
   * [DaemonLaunchDescriptor.parse] instead.
   */
  public fun decodeLaunchDescriptor(payload: String): DaemonLaunchDescriptor =
    json.decodeFromString(DaemonLaunchDescriptor.serializer(), payload)

  /** Deprecated: returns the legacy [DaemonClasspathDescriptor] view of [newDescriptorBuilder]. */
  @Deprecated(
    "Use newDescriptorBuilder, which returns the contracts' DaemonLaunchDescriptor.Builder.",
    ReplaceWith(
      "DaemonLaunchBuilder.newDescriptorBuilder(modulePath, variant, mainClass, classpath, " +
        "jvmArgs, systemProperties, workingDirectory, manifestPath, enabled)" +
        ".also { it.javaLauncher = javaLauncher }.build()"
    ),
  )
  @Suppress("DEPRECATION")
  public fun build(
    modulePath: String,
    variant: String,
    mainClass: String,
    classpath: List<String>,
    jvmArgs: List<String>,
    systemProperties: Map<String, String>,
    workingDirectory: String,
    manifestPath: String,
    enabled: Boolean = true,
    javaLauncher: String? = null,
  ): DaemonClasspathDescriptor =
    newDescriptorBuilder(
        modulePath = modulePath,
        variant = variant,
        mainClass = mainClass,
        classpath = classpath,
        jvmArgs = jvmArgs,
        systemProperties = systemProperties,
        workingDirectory = workingDirectory,
        manifestPath = manifestPath,
        enabled = enabled,
      )
      .also { it.javaLauncher = javaLauncher }
      .build()
      .toLegacy()

  /**
   * Deprecated: encodes the legacy type by converting it to [DaemonLaunchDescriptor], so the output
   * is the contracts' wire format (including its `jailCommand` / `hardTtlSeconds` defaults).
   */
  @Deprecated(
    "Encode the contracts' DaemonLaunchDescriptor instead.",
    ReplaceWith("encode(descriptor.toLaunchDescriptor())"),
  )
  @Suppress("DEPRECATION")
  public fun encode(descriptor: DaemonClasspathDescriptor): String =
    encode(descriptor.toLaunchDescriptor())

  /** Deprecated: decodes through [decodeLaunchDescriptor] and returns the legacy view. */
  @Deprecated(
    "Use decodeLaunchDescriptor, which returns the contracts' DaemonLaunchDescriptor.",
    ReplaceWith("DaemonLaunchBuilder.decodeLaunchDescriptor(payload)"),
  )
  @Suppress("DEPRECATION")
  public fun decode(payload: String): DaemonClasspathDescriptor =
    decodeLaunchDescriptor(payload).toLegacy()
}
