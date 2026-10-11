package ee.schimke.composeai.daemonlaunch

import kotlinx.serialization.json.Json

/**
 * Builds `daemon-launch.json` from pre-resolved inputs, for Bazel / Amper via [build] or
 * [DaemonLaunchBuilderCli]. Generic: Android classpath layering stays in the Gradle plugin's
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
   * A [DaemonClasspathDescriptor] stamped with [DAEMON_DESCRIPTOR_SCHEMA_VERSION]; all other fields
   * pass through verbatim.
   */
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
    DaemonClasspathDescriptor(
      schemaVersion = DAEMON_DESCRIPTOR_SCHEMA_VERSION,
      modulePath = modulePath,
      variant = variant,
      enabled = enabled,
      mainClass = mainClass,
      javaLauncher = javaLauncher,
      classpath = classpath,
      jvmArgs = jvmArgs,
      systemProperties = systemProperties,
      workingDirectory = workingDirectory,
      manifestPath = manifestPath,
    )

  /** Serializes [descriptor] using the canonical [json] encoder. */
  public fun encode(descriptor: DaemonClasspathDescriptor): String =
    json.encodeToString(DaemonClasspathDescriptor.serializer(), descriptor)

  /** Deserializes a `daemon-launch.json` payload back to a [DaemonClasspathDescriptor]. */
  public fun decode(payload: String): DaemonClasspathDescriptor =
    json.decodeFromString(DaemonClasspathDescriptor.serializer(), payload)
}
