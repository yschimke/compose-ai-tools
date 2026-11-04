// A SurfaceView app for the simulator's SurfaceView surfaces (#6428): it draws into its surface
// with lockCanvas and logs each SurfaceHolder callback under the `SurfaceViewSample` tag, so a
// headless `compose-preview launch` shows whether the surface was created, sized and drawn.
plugins {
  id("composeai.base-conventions")
  id("composeai.android-conventions")
  alias(libs.plugins.android.application)
}

android {
  namespace = "ee.schimke.composeai.samples.surfaceview"
  defaultConfig {
    applicationId = "ee.schimke.composeai.samples.surfaceview"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }
}
