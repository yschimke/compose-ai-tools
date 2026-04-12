package ee.schimke.composeai.renderer

/**
 * Standalone entry point for manual/debug invocation of the Android renderer.
 *
 * In normal operation, the Gradle plugin runs [RobolectricRenderTest] as a
 * parameterized JUnit test via a Test-type Gradle task, which inherits AGP's
 * full test infrastructure (android.jar, AAR->JAR extraction, test_config.properties).
 *
 * This main() is provided for manual testing outside of Gradle.
 *
 * Usage:
 *   java -Dcomposeai.render.manifest=path/to/previews.json \
 *        -Dcomposeai.render.outputDir=path/to/output/ \
 *        -cp <classpath> ee.schimke.composeai.renderer.AndroidRendererMainKt
 */
fun main(args: Array<String>) {
    val manifestPath = System.getProperty("composeai.render.manifest")
    val outputDir = System.getProperty("composeai.render.outputDir")

    if (manifestPath == null || outputDir == null) {
        System.err.println("Required system properties: composeai.render.manifest, composeai.render.outputDir")
        System.err.println("Normal usage is via the Gradle renderPreviews task, not this main().")
        kotlin.system.exitProcess(1)
    }

    val result = org.junit.runner.JUnitCore.runClasses(RobolectricRenderTest::class.java)

    if (!result.wasSuccessful()) {
        for (failure in result.failures) {
            System.err.println("Render failed: ${failure.message}")
            failure.exception?.printStackTrace()
        }
        kotlin.system.exitProcess(2)
    }
}
