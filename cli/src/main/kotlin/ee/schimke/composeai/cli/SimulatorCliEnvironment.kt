package ee.schimke.composeai.cli

import ee.schimke.composeai.cli.simulator.SimulatorHostEnvironment

/**
 * Wires `:simulator-cli`'s host hooks to `:cli`'s provisioning, network policy, and flag parser.
 */
internal fun cliSimulatorHostEnvironment(): SimulatorHostEnvironment =
  SimulatorHostEnvironment(
    ensureAndroidSidecar = {
      DaemonSidecarProvision.install(DaemonSidecarProvision.Sidecar.ANDROID)
    },
    robolectricOfflineProperties = { dir ->
      if (dir == null) robolectricOfflineProperties() else robolectricOfflineProperties(dir)
    },
    provisionAndroidAll = { robolectricJars, sdk ->
      val artifact = RobolectricDepsProvision.artifactFor(robolectricJars, sdk).artifact
      val directory = RobolectricDepsProvision.defaultDir()
      RobolectricDepsProvision.ensure(
        artifact,
        directory,
        seed = RobolectricDepsProvision.mavenLocalCopy(artifact),
      )
      robolectricOfflineProperties(directory.path)
    },
    prepareSkikoNative = { rcJvmJars -> SkikoNativeProvision.prepare(rcJvmJars) },
    isOffline = { NetworkPolicy.offline },
    isNetworkDisabled = { NetworkPolicy.disabled },
    httpClientFactory = { ktorOkHttpClient() },
    firstPositional = CliFlags::firstPositional,
    positionals = CliFlags::positionals,
  )
