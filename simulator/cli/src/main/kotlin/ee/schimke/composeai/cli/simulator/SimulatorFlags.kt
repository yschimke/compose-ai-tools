package ee.schimke.composeai.cli.simulator

/** Extracts the first value for [flag] from `--flag value` or `--flag=value`. */
internal fun List<String>.flagValue(flag: String): String? {
  val prefix = "$flag="
  for (i in indices) {
    val arg = this[i]
    if (arg == flag && i + 1 < size) return this[i + 1]
    if (arg.startsWith(prefix)) return arg.removePrefix(prefix)
  }
  return null
}

/** Extracts every value for a repeatable [flag] from `--flag value` or `--flag=value`. */
internal fun List<String>.flagValuesAll(flag: String): List<String> = buildList {
  val prefix = "$flag="
  var i = 0
  while (i < this@flagValuesAll.size) {
    val arg = this@flagValuesAll[i]
    when {
      arg == flag && i + 1 < this@flagValuesAll.size -> {
        add(this@flagValuesAll[i + 1])
        i += 2
      }
      arg.startsWith(prefix) -> {
        add(arg.removePrefix(prefix))
        i++
      }
      else -> i++
    }
  }
}

/** Positional arguments of `dwf-runtime`, skipping its value-consuming flags. */
internal fun dwfRuntimePositionals(args: List<String>): List<String> {
  val valueFlags = setOf("--apk", "--serial", "--output", "-o")
  return buildList {
    var i = 0
    while (i < args.size) {
      val arg = args[i]
      when {
        arg in valueFlags -> i += 2
        arg.startsWith("-") -> i++
        else -> {
          add(arg)
          i++
        }
      }
    }
  }
}

/** First positional argument of `android-simulator`, skipping known value-consuming flags. */
internal fun firstSimulatorPositional(args: List<String>): String? {
  var i = 0
  while (i < args.size) {
    val arg = args[i]
    when {
      arg in SIMULATOR_VALUE_FLAGS -> i += 2
      arg.startsWith("-") -> i++
      else -> return arg
    }
  }
  return null
}

private val SIMULATOR_VALUE_FLAGS: Set<String> =
  setOf(
    "--adb-port",
    "--additional-test-output-dir",
    "--android-jar",
    "--apk",
    "--build-dir",
    "--control",
    "--coverage-out",
    "--data-dir",
    "--data-layer",
    "--device",
    "--display",
    "--dwf-renderer-apk",
    "--fingerprint",
    "--font-scale",
    "--form-factor",
    "--gradle-arg",
    "--grpc-port",
    "--install",
    "--instrument",
    "--jar",
    "--java",
    "--jvm-opt",
    "--max-heap",
    "--module",
    "--network",
    "--output",
    "-o",
    "--pair",
    "--pair-qualifiers",
    "--play-services",
    "--project",
    "--qualifiers",
    "--scenario",
    "--sdk",
    "--serial",
    "--shards",
    "--test-json",
    "--test-report",
    "--tests",
    "--tile",
    "--variant",
    "--watchface",
    "--widget",
  )
