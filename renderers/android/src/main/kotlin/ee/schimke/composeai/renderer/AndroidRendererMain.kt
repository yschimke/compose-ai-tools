package ee.schimke.composeai.renderer

/**
 * Standalone entry point for the Android renderer — manual/debug invocation, and the detached
 * bundle render `BundleRenderer.spawnAndroidRenderer` launches.
 *
 * In normal operation, the Gradle plugin runs [RobolectricRenderTest] as a parameterized JUnit test
 * via a Test-type Gradle task, which inherits AGP's full test infrastructure (android.jar, AAR->JAR
 * extraction, test_config.properties).
 *
 * **Both render lanes run here.** [RobolectricRenderTest] claims the composable half of the
 * manifest and [AppTourRobolectricRenderTest] the `kind=ACTIVITY` / `kind=APP_TOUR` half — they are
 * two classes because Robolectric resolves the Application per test class, and each drops what the
 * other owns (see [PreviewManifestLoader.Lane]). Running only the first would render the whole
 * manifest MINUS its app-level previews and still exit 0, so a detached bundle render would report
 * every activity and tour as failed with no diagnostic. The Gradle task includes both classes for
 * the same reason.
 *
 * Usage: java -Dcomposeai.render.manifest=path/to/previews.json \
 * -Dcomposeai.render.outputDir=path/to/output/ \ -cp <classpath>
 * ee.schimke.composeai.renderer.AndroidRendererMainKt
 */
fun main(args: Array<String>) {
  val manifestPath = System.getProperty("composeai.render.manifest")
  val outputDir = System.getProperty("composeai.render.outputDir")

  if (manifestPath == null || outputDir == null) {
    System.err.println(
      "Required system properties: composeai.render.manifest, composeai.render.outputDir"
    )
    System.err.println("Normal usage is via the Gradle composePreviewRender task, not this main().")
    kotlin.system.exitProcess(1)
  }

  val result =
    org.junit.runner.JUnitCore.runClasses(
      RobolectricRenderTest::class.java,
      ee.schimke.composeai.apptour.AppTourRobolectricRenderTest::class.java,
    )

  if (!result.wasSuccessful()) {
    for (failure in result.failures) {
      System.err.println("Render failed: ${failure.message}")
      failure.exception?.printStackTrace()
    }
    kotlin.system.exitProcess(2)
  }
}
