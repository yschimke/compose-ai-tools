package ee.schimke.composeai.daemonlaunch

import ee.schimke.composeai.daemon.protocol.BtaCompileConfig as SharedBtaCompileConfig
import ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor
import kotlinx.serialization.Serializable

/**
 * Deprecated forwarding facade for the `daemon-launch.json` shape this module used to declare. The
 * shape is now declared once, by the contracts' [DaemonLaunchDescriptor] (with its
 * [SharedBtaCompileConfig] block), which every writer and reader shares; this class keeps the old
 * name working (source and binary) until a major release, per `docs/VERSIONING.md` § 5.
 *
 * Nothing in this repository writes through it any more. [DaemonLaunchBuilder]'s deprecated
 * overloads convert to and from [DaemonLaunchDescriptor], so a descriptor built or encoded through
 * this type is the contracts type's wire format: it gains the contracts' `jailCommand` (empty) and
 * `hardTtlSeconds` (null) defaults, which every released reader tolerates.
 */
@Deprecated(
  "The daemon-launch.json shape is ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor; " +
    "build one with DaemonLaunchBuilder.newDescriptorBuilder.",
  ReplaceWith(
    "DaemonLaunchDescriptor",
    "ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor",
  ),
)
@Serializable
public data class DaemonClasspathDescriptor(
  public val schemaVersion: Int,
  public val modulePath: String,
  public val variant: String,
  public val enabled: Boolean,
  public val mainClass: String,
  public val javaLauncher: String?,
  public val classpath: List<String>,
  public val jvmArgs: List<String>,
  public val systemProperties: Map<String, String>,
  public val workingDirectory: String,
  public val manifestPath: String,
  public val btaCompile: BtaCompileConfig? = null,
) {
  /** This descriptor as the contracts' [DaemonLaunchDescriptor], every field carried across. */
  public fun toLaunchDescriptor(): DaemonLaunchDescriptor =
    DaemonLaunchDescriptor.Builder(
        schemaVersion = schemaVersion,
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
      .also {
        it.javaLauncher = javaLauncher
        it.btaCompile = btaCompile?.toShared()
      }
      .build()
}

/** Deprecated forwarding facade for the contracts' [SharedBtaCompileConfig]. */
@Deprecated(
  "Use ee.schimke.composeai.daemon.protocol.BtaCompileConfig, built through its Builder.",
  ReplaceWith("BtaCompileConfig", "ee.schimke.composeai.daemon.protocol.BtaCompileConfig"),
)
@Serializable
public data class BtaCompileConfig(
  public val implClasspath: List<String>,
  public val compileClasspath: List<String>,
  public val compilerPlugins: List<String>,
  public val outputDir: String,
  public val moduleName: String,
  public val icWorkingDir: String,
  public val ineligibilityReason: String? = null,
)

/** Current descriptor schema version; forwards [DaemonLaunchDescriptor.SCHEMA_VERSION]. */
@Deprecated(
  "Use DaemonLaunchDescriptor.SCHEMA_VERSION.",
  ReplaceWith(
    "DaemonLaunchDescriptor.SCHEMA_VERSION",
    "ee.schimke.composeai.daemon.protocol.DaemonLaunchDescriptor",
  ),
)
public const val DAEMON_DESCRIPTOR_SCHEMA_VERSION: Int = DaemonLaunchDescriptor.SCHEMA_VERSION

@Suppress("DEPRECATION")
internal fun BtaCompileConfig.toShared(): SharedBtaCompileConfig =
  SharedBtaCompileConfig.Builder(
      implClasspath = implClasspath,
      compileClasspath = compileClasspath,
      compilerPlugins = compilerPlugins,
      outputDir = outputDir,
      moduleName = moduleName,
      icWorkingDir = icWorkingDir,
    )
    .also { it.ineligibilityReason = ineligibilityReason }
    .build()

@Suppress("DEPRECATION")
internal fun SharedBtaCompileConfig.toLegacy(): BtaCompileConfig =
  BtaCompileConfig(
    implClasspath = implClasspath,
    compileClasspath = compileClasspath,
    compilerPlugins = compilerPlugins,
    outputDir = outputDir,
    moduleName = moduleName,
    icWorkingDir = icWorkingDir,
    ineligibilityReason = ineligibilityReason,
  )

/**
 * The legacy view of a contracts descriptor. Lossy only for the contracts' sandbox fields
 * (`jailCommand`, `hardTtlSeconds`), which the legacy type never declared.
 */
@Suppress("DEPRECATION")
internal fun DaemonLaunchDescriptor.toLegacy(): DaemonClasspathDescriptor =
  DaemonClasspathDescriptor(
    schemaVersion = schemaVersion,
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
    btaCompile = btaCompile?.toLegacy(),
  )
