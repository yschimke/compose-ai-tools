package ee.schimke.composeai.simulator.launch

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLClassLoader

/**
 * Which `android-all` jar the Robolectric in a sidecar needs for an SDK level, as its
 * `LocalDependencyResolver` names the file in `robolectric.dependency.dir`. A launcher that points
 * Robolectric at a directory offline must check the level's own jar is there: a directory holding
 * another level's jar leaves the simulator unable to start.
 *
 * Asked of Robolectric itself (its `DefaultSdkProvider`, given a resolver that resolves nothing),
 * the way `compose-preview`'s provisioning asks it, so the name always matches the Robolectric that
 * will look for it.
 */
public object RobolectricAndroidAll {
  /**
   * The file name for [apiLevel], or null when [robolectricJars] hold no Robolectric that knows the
   * level.
   */
  public fun fileName(robolectricJars: List<File>, apiLevel: Int): String? {
    val jars = robolectricJars.filter { it.isFile && it.extension == "jar" }
    if (jars.isEmpty()) return null
    return runCatching {
      URLClassLoader(
          jars.map { it.toURI().toURL() }.toTypedArray(),
          ClassLoader.getPlatformClassLoader(),
        )
        .use { fileName(it, apiLevel) }
    }
      .getOrNull()
  }

  private fun fileName(loader: ClassLoader, apiLevel: Int): String? {
    val sdk = defaultSdk(loader, apiLevel) ?: return null
    val dependency =
      try {
        sdk.javaClass.getDeclaredMethod("getAndroidSdkDependency").let {
          it.isAccessible = true
          it.invoke(sdk)
        }
      } catch (e: InvocationTargetException) {
        // A level that needs a newer JDK than this one: Robolectric refuses to name it, so the
        // name is rebuilt from the same fields.
        if (e.cause !is UnsupportedClassVersionError) throw e.cause ?: e
        null
      }
    if (dependency == null) return preinstrumentedName(sdk)
    fun get(name: String) = dependency.javaClass.getMethod(name).invoke(dependency) as String?
    val classifier = get("getClassifier")?.let { "-$it" }.orEmpty()
    return "${get("getArtifactId")}-${get("getVersion")}$classifier.${get("getType") ?: "jar"}"
  }

  /** `android-all-instrumented-<androidVersion>-robolectric-<robolectricVersion>-i<N>.jar`. */
  private fun preinstrumentedName(sdk: Any): String {
    val instrumentation =
      sdk.javaClass.enclosingClass.getDeclaredField("PREINSTRUMENTED_VERSION").let {
        it.isAccessible = true
        it.getInt(null)
      }
    return "android-all-instrumented-${sdk.field("androidVersion")}-robolectric-" +
      "${sdk.field("robolectricVersion")}-i$instrumentation.jar"
  }

  private fun defaultSdk(loader: ClassLoader, apiLevel: Int): Any? {
    val resolverType = loader.loadClass("org.robolectric.internal.dependency.DependencyResolver")
    val resolver =
      Proxy.newProxyInstance(loader, arrayOf(resolverType)) { proxy, method, args ->
        when (method.name) {
          "toString" -> "simulator-launch"
          "hashCode" -> System.identityHashCode(proxy)
          "equals" -> proxy === args?.firstOrNull()
          else -> error("naming an artifact resolves nothing (${method.name})")
        }
      }
    val providerType = loader.loadClass("org.robolectric.plugins.DefaultSdkProvider")
    val provider = providerType.getConstructor(resolverType).newInstance(resolver)
    val sdks = providerType.getMethod("getSdks").invoke(provider) as Collection<*>
    return sdks.filterNotNull().firstOrNull {
      it.javaClass.getMethod("getApiLevel").invoke(it) == apiLevel
    }
  }

  private fun Any.field(name: String): Any? =
    javaClass.getDeclaredField(name).let {
      it.isAccessible = true
      it.get(this)
    }
}
