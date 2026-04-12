package ee.schimke.composeai.renderer

import java.io.File
import kotlin.system.exitProcess

/**
 * Standalone entry point for rendering Android Compose previews to PNG via Robolectric.
 *
 * Args: className functionName widthPx heightPx density showBackground backgroundColor outputFile
 *
 * Delegates to JUnitCore which runs [RobolectricRenderTest]. The test runner bootstraps
 * the Robolectric sandbox (which provides Android framework classes) before the test
 * class is loaded, avoiding ClassNotFoundException for Android types.
 *
 * Render parameters are passed via system properties.
 */
fun main(args: Array<String>) {
    if (args.size < 8) {
        System.err.println("Usage: AndroidRendererMain <className> <functionName> <widthPx> <heightPx> <density> <showBackground> <backgroundColor> <outputFile>")
        exitProcess(1)
    }

    System.setProperty("composeai.render.className", args[0])
    System.setProperty("composeai.render.functionName", args[1])
    System.setProperty("composeai.render.widthPx", args[2])
    System.setProperty("composeai.render.heightPx", args[3])
    System.setProperty("composeai.render.density", args[4])
    System.setProperty("composeai.render.showBackground", args[5])
    System.setProperty("composeai.render.backgroundColor", args[6])
    System.setProperty("composeai.render.outputFile", args[7])

    // Load JUnitCore and RobolectricRenderTest by name to avoid touching Android classes
    // before Robolectric has a chance to initialize its sandbox classloader.
    val junitCoreClass = Class.forName("org.junit.runner.JUnitCore")
    @Suppress("UNCHECKED_CAST")
    val classArrayType = java.lang.reflect.Array.newInstance(Class::class.java, 0).javaClass as Class<Array<Class<*>>>
    val runClassesMethod = junitCoreClass.getMethod("runClasses", classArrayType)
    val testClass = Class.forName("ee.schimke.composeai.renderer.RobolectricRenderTest")

    val result = runClassesMethod.invoke(null, arrayOf(testClass))

    val wasSuccessful = result.javaClass.getMethod("wasSuccessful").invoke(result) as Boolean
    if (!wasSuccessful) {
        val failures = result.javaClass.getMethod("getFailures").invoke(result) as List<*>
        for (failure in failures) {
            val message = failure!!.javaClass.getMethod("getMessage").invoke(failure)
            System.err.println("Render failed: $message")
            val exception = failure.javaClass.getMethod("getException").invoke(failure) as? Throwable
            exception?.printStackTrace()
        }
        exitProcess(2)
    }

    val outputFile = File(args[7])
    if (!outputFile.exists()) {
        System.err.println("Render produced no output file: ${outputFile.absolutePath}")
        exitProcess(2)
    }
}
