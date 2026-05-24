package ee.schimke.composeai.daemonlaunch

import kotlinx.serialization.json.Json

/**
 * Pure-JVM library for producing `daemon-launch.json` from pre-resolved inputs. Generic by design —
 * the Android-specific classpath layering (AGP `artifactView` resolution, R.jar appending, the
 * Robolectric-on-JDK-17 `--add-opens` set) stays in the Gradle plugin's `AndroidPreviewClasspath`;
 * this library's contract is "given these resolved jar lists + sysprops + JVM args, emit a valid
 * descriptor."
 *
 * Bazel rules and Amper tasks resolve their classpath through their own dep system
 * (`rules_jvm_external` / Amper's m2 cache) and hand the result to [build] (in-process) or to
 * [DaemonLaunchBuilderCli] via `java -cp <resolved-classpath>
 * ee.schimke.composeai.daemonlaunch.DaemonLaunchBuilderCli …` (the published JAR is slim — see the
 * CLI's KDoc for the full contract).
 */
public object DaemonLaunchBuilder {

  /**
   * Canonical JSON encoder. Pretty-printed because the descriptor is a debug surface (devs `cat` it
   * when the daemon misbehaves); `encodeDefaults = true` + `explicitNulls = true` so optional
   * fields like `javaLauncher` render explicitly as `null` rather than being omitted, removing "is
   * the field missing or is it null?" ambiguity for downstream readers.
   */
  public val json: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
  }

  /**
   * Constructs a [DaemonClasspathDescriptor] with [schemaVersion] stamped at
   * [DAEMON_DESCRIPTOR_SCHEMA_VERSION]. All other fields are passed through verbatim — the builder
   * is intentionally thin, so it stays decoupled from how a given build system resolved its
   * classpath / system properties / JVM args.
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
