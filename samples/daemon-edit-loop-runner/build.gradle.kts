// `:samples:daemon-edit-loop-runner` — drives `:samples:android-daemon-bench:benchWarmEditLoop`.
//
// Launches the bench module's preview daemon, at the version this repository pins
// (`composeai-preview-daemon`, through the daemon BOM `composeai.base-conventions` applies), and
// times the warm edit -> `compileSources` loop. See `EditLoopBench.kt` for what it asserts and why
// it runs against the published daemon rather than a source build.
plugins {
  id("composeai.base-conventions")
  id("composeai.jvm-conventions")
  alias(libs.plugins.kotlin.jvm)
}

dependencies { implementation(libs.composeai.daemon.client) }
