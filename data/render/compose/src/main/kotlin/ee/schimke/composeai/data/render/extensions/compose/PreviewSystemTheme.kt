package ee.schimke.composeai.data.render.extensions.compose

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ProvidedValue
import java.lang.reflect.ParameterizedType

/** A renderer-owned system-theme value, independent of Compose's binary-incompatible enum. */
public enum class PreviewSystemTheme {
  Dark,
  Light,
  Unknown,
}

/**
 * Returns the `LocalSystemTheme` value needed by the Compose version that is actually running.
 *
 * Compose Multiplatform 1.12 changed the local's value from `androidx.compose.ui.SystemTheme` to
 * `org.jetbrains.skiko.SystemTheme` while leaving the erased JVM getter unchanged. Code compiled
 * against 1.11 therefore still linked on 1.12, but placed the old enum in the new local; the
 * comparison inside `isSystemInDarkTheme()` could then never succeed. Resolve both the local and
 * its generic value type from the runtime getter so one renderer binary works on either side of
 * that change.
 *
 * This deliberately keeps the reflection at this single compatibility boundary. Callers receive an
 * ordinary [ProvidedValue] and can pass it to `CompositionLocalProvider` with their other locals.
 */
public fun previewSystemThemeValue(theme: PreviewSystemTheme): ProvidedValue<*> {
  val binding = RuntimeSystemThemeBinding.value
  val enumValue =
    binding.themeClass.enumConstants?.firstOrNull {
      (it as Enum<*>).name.equals(theme.name, ignoreCase = true)
    }
      ?: error(
        "Compose LocalSystemTheme value type ${binding.themeClass.name} has no ${theme.name} value"
      )
  return binding.local.provides(enumValue)
}

private data class SystemThemeBinding(
  val local: ProvidableCompositionLocal<Any?>,
  val themeClass: Class<*>,
)

private object RuntimeSystemThemeBinding {
  val value: SystemThemeBinding by lazy {
    val getterClass = Class.forName("androidx.compose.ui.SystemThemeKt")
    val getter = getterClass.getMethod("getLocalSystemTheme")
    @Suppress("UNCHECKED_CAST") val local = getter.invoke(null) as ProvidableCompositionLocal<Any?>

    // Both 1.11 and 1.12 retain this generic signature even though its JVM descriptor is erased.
    // Fall back to the 1.11 type if a future bytecode transformer strips Signature attributes;
    // this preserves the oldest supported runtime instead of guessing from Skiko's presence (the
    // Skiko enum exists on both sides of the change).
    val themeClass =
      ((getter.genericReturnType as? ParameterizedType)?.actualTypeArguments?.singleOrNull()
        as? Class<*>) ?: Class.forName("androidx.compose.ui.SystemTheme")

    require(themeClass.isEnum) {
      "Compose LocalSystemTheme value type ${themeClass.name} is not an enum"
    }
    SystemThemeBinding(local = local, themeClass = themeClass)
  }
}
