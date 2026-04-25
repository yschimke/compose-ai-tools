package ee.schimke.composeai.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.reflect.ComposableMethod
import androidx.compose.runtime.reflect.getDeclaredComposableMethod
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.exitProcess
import org.jetbrains.skia.EncodedImageFormat

/**
 * Standalone entry point for rendering Compose Desktop previews to PNG.
 *
 * Args: className functionName widthPx heightPx density showBackground backgroundColor outputFile
 * [wrapperClassName] [wrapWidth] [wrapHeight] [previewParameterProviderFqn] [previewParameterLimit]
 *
 * The optional 9th argument is the FQN of a `PreviewWrapperProvider` (Compose 1.11+); pass an empty
 * string or omit to skip wrapping.
 *
 * Args 10 and 11 are AS-parity wrap flags. When an axis wraps, widthPx/heightPx are treated as a
 * sandbox dimension — the renderer wraps the composable, measures its intrinsic size, and crops the
 * final PNG to that size on the wrapped axis. Defaults to `false` when omitted so older callers
 * keep the full-frame behaviour.
 *
 * Args 12 and 13 are the `@PreviewParameter` provider FQN + limit. When arg 12 is non-empty the
 * renderer instantiates the provider, iterates its `values.take(limit)`, and writes one file per
 * value using `outputFile` as a template (`_PARAM_<idx>` inserted before the extension). Looping
 * inside a single JVM — instead of spawning one subprocess per value — avoids N× Compose cold-start
 * cost; the same `ImageComposeScene` is reused across values.
 */
fun main(args: Array<String>) {
  if (args.size < 8) {
    System.err.println(
      "Usage: DesktopRendererMain <className> <functionName> <widthPx> <heightPx> <density> <showBackground> <backgroundColor> <outputFile> [wrapperClassName] [wrapWidth] [wrapHeight] [previewParameterProviderFqn] [previewParameterLimit]"
    )
    exitProcess(1)
  }

  val className = args[0]
  val functionName = args[1]
  val widthPx =
    args[2].toIntOrNull()
      ?: run {
        System.err.println("Invalid widthPx: '${args[2]}' (expected integer)")
        exitProcess(1)
      }
  val heightPx =
    args[3].toIntOrNull()
      ?: run {
        System.err.println("Invalid heightPx: '${args[3]}' (expected integer)")
        exitProcess(1)
      }
  val density =
    args[4].toFloatOrNull()
      ?: run {
        System.err.println("Invalid density: '${args[4]}' (expected float)")
        exitProcess(1)
      }
  val showBackground = args[5].toBoolean()
  val backgroundColor =
    args[6].toLongOrNull()
      ?: run {
        System.err.println("Invalid backgroundColor: '${args[6]}' (expected long)")
        exitProcess(1)
      }
  val outputFile = File(args[7])
  val wrapperClassName = args.getOrNull(8)?.takeIf { it.isNotBlank() }
  val wrapWidth = args.getOrNull(9)?.toBoolean() ?: false
  val wrapHeight = args.getOrNull(10)?.toBoolean() ?: false
  val previewParameterProviderFqn = args.getOrNull(11)?.takeIf { it.isNotBlank() }
  val previewParameterLimit = args.getOrNull(12)?.toIntOrNull()?.coerceAtLeast(0) ?: Int.MAX_VALUE

  try {
    val values: List<Any?> =
      if (previewParameterProviderFqn != null && previewParameterLimit > 0) {
        loadProviderValues(previewParameterProviderFqn, previewParameterLimit).also { vs ->
          if (vs.isEmpty()) {
            System.err.println(
              "@PreviewParameter(provider = $previewParameterProviderFqn) on $functionName produced no values — skipping."
            )
          }
        }
      } else {
        listOf(NO_PARAM)
      }

    val suffixes: List<String> =
      if (values.size == 1 && values[0] === NO_PARAM) {
        listOf("")
      } else {
        PreviewParameterLabels.suffixesFor(values)
      }
    val targetFiles = values.mapIndexed { idx, value ->
      if (value === NO_PARAM) outputFile
      else File(insertBeforeExtension(outputFile.path, suffixes[idx]))
    }
    // Renderer is authoritative about the fan-out — delete any
    // `<stem>_*<ext>` files from prior runs that aren't in this run's
    // expected output. Guards against provider renames and the
    // `_PARAM_<idx>` → `_<label>` migration leaving stale PNGs behind.
    if (values.any { it !== NO_PARAM }) {
      deleteStaleFanoutFiles(outputFile, targetFiles.map { it.name }.toSet())
    }
    for ((idx, value) in values.withIndex()) {
      val targetFile = targetFiles[idx]
      val previewArgs = if (value === NO_PARAM) emptyList() else listOf(value)
      renderPreview(
        className,
        functionName,
        widthPx,
        heightPx,
        density,
        showBackground,
        backgroundColor,
        targetFile,
        wrapperClassName,
        wrapWidth,
        wrapHeight,
        previewArgs,
      )
    }
  } catch (e: Exception) {
    System.err.println("Render failed for $className.$functionName: ${e.message}")
    e.printStackTrace()
    exitProcess(2)
  }
}

// Sentinel: distinguishes "no @PreviewParameter fan-out" from "provider yielded null".
// A null value from the provider is a legitimate case we want to render; NO_PARAM
// short-circuits the file-path suffix logic instead.
private val NO_PARAM = Any()

private fun deleteStaleFanoutFiles(template: File, expectedNames: Set<String>) {
  val dir = template.parentFile ?: return
  if (!dir.isDirectory) return
  val stem = template.nameWithoutExtension
  val ext = ".${template.extension}"
  val prefix = stem + "_"
  dir
    .listFiles()
    ?.filter { it.name.startsWith(prefix) && it.name.endsWith(ext) && it.name !in expectedNames }
    ?.forEach { f ->
      if (!f.delete()) {
        System.err.println("Failed to delete stale fan-out file: ${f.absolutePath}")
      }
    }
}

private fun insertBeforeExtension(path: String, suffix: String): String {
  if (path.isEmpty()) return path
  val dot = path.lastIndexOf('.')
  val slash = path.lastIndexOf(File.separatorChar).coerceAtLeast(path.lastIndexOf('/'))
  return if (dot > slash) path.substring(0, dot) + suffix + path.substring(dot) else path + suffix
}

/**
 * Loads and enumerates a `PreviewParameterProvider` reflectively — same strategy the Android
 * renderer uses in `PreviewManifestLoader.loadProviderValues`. Keeping this renderer-local avoids a
 * shared module dependency and the lookup stays limited to the method shapes the interface
 * guarantees (`getValues(): Sequence`).
 */
private fun loadProviderValues(providerFqn: String, limit: Int): List<Any?> {
  val clazz =
    try {
      Class.forName(providerFqn)
    } catch (e: ClassNotFoundException) {
      System.err.println("@PreviewParameter: provider class $providerFqn not found — skipping.")
      return emptyList()
    }
  val instance =
    runCatching {
        val ctor = clazz.getDeclaredConstructor()
        ctor.isAccessible = true
        ctor.newInstance()
      }
      .getOrElse { e ->
        System.err.println(
          "@PreviewParameter: couldn't instantiate $providerFqn via nullary ctor: ${e.message}"
        )
        return emptyList()
      }
  val getValues =
    runCatching { clazz.getMethod("getValues") }
      .getOrElse {
        System.err.println(
          "@PreviewParameter: $providerFqn has no getValues() — not a PreviewParameterProvider?"
        )
        return emptyList()
      }
  @Suppress("UNCHECKED_CAST")
  val sequence = getValues.invoke(instance) as? Sequence<Any?> ?: return emptyList()
  // `Sequence.take(Int)` is lazy and `.toList()` drives it — bounds the
  // enumeration for infinite providers without requiring an explicit
  // counter. Calling through the typed Kotlin API avoids reflective
  // access into `kotlin.jvm.internal.ArrayIterator`, whose visibility is
  // package-private and throws `IllegalAccessException` from outside the
  // stdlib's own module.
  return sequence.take(limit).toList()
}

private fun renderPreview(
  className: String,
  functionName: String,
  widthPx: Int,
  heightPx: Int,
  density: Float,
  showBackground: Boolean,
  backgroundColor: Long,
  outputFile: File,
  wrapperClassName: String?,
  wrapWidth: Boolean,
  wrapHeight: Boolean,
  previewArgs: List<Any?>,
) {
  val clazz = Class.forName(className)
  val composableMethod =
    if (previewArgs.isEmpty()) {
      clazz.getDeclaredComposableMethod(functionName)
    } else {
      findComposableMethodWithArgs(clazz, functionName, previewArgs)
    }

  val scene = ImageComposeScene(width = widthPx, height = heightPx, density = Density(density))

  // Measured content size in pixels, captured from the wrapping Box via
  // onGloballyPositioned. Only read when at least one axis wraps.
  var measured: IntSize? = null

  scene.setContent {
    CompositionLocalProvider(LocalInspectionMode provides true) {
      val bgColor =
        when {
          backgroundColor != 0L -> Color(backgroundColor.toInt())
          showBackground -> Color.White
          else -> Color.Transparent
        }
      val body: @Composable () -> Unit = {
        if (wrapWidth || wrapHeight) {
          // AS-parity wrap: measure the composable with unbounded
          // constraints on wrapped axes (keep the sandbox constraint
          // on fixed axes), capture the child's pixel size, then
          // size the outer Box to exactly that. The .layout modifier
          // lets us both observe and bound the child's size in a
          // single measurement pass — more reliable under
          // ImageComposeScene than onGloballyPositioned, which is
          // tied to a post-layout effect pass the scene doesn't
          // always flush.
          // Bounded sandbox constraints (not Infinity) — matches
          // Android Studio's preview pane. `fillMaxWidth` / LazyColumn
          // / etc. require bounded constraints; they'd throw from
          // `InlineClassHelper` under an Infinity max. Small
          // composables (`Modifier.size(100.dp)`) still measure at
          // their intrinsic size and get cropped to that below;
          // `fillMax*` composables measure at the sandbox size and
          // no crop happens on that axis.
          Box(
            modifier =
              Modifier.layout { measurable, constraints ->
                  // Relax the min constraint on wrapped axes so
                  // small composables can shrink below the
                  // sandbox; keep the max bounded (the parent's
                  // maxWidth/maxHeight) so `fillMax*` / LazyColumn
                  // still have a finite viewport.
                  val wrappedConstraints =
                    Constraints(
                      minWidth = if (wrapWidth) 0 else constraints.minWidth,
                      maxWidth = constraints.maxWidth,
                      minHeight = if (wrapHeight) 0 else constraints.minHeight,
                      maxHeight = constraints.maxHeight,
                    )
                  val placeable = measurable.measure(wrappedConstraints)
                  measured = IntSize(placeable.width, placeable.height)
                  layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .background(bgColor)
          ) {
            InvokeComposable(composableMethod, null, previewArgs)
          }
        } else {
          Box(modifier = Modifier.fillMaxSize().background(bgColor)) {
            InvokeComposable(composableMethod, null, previewArgs)
          }
        }
      }
      // `@PreviewWrapper(Provider::class)` — instantiate the provider reflectively
      // so the renderer stays compatible with apps on stable Compose (no
      // `PreviewWrapperProvider` on classpath).
      if (wrapperClassName != null) {
        InvokeWrappedComposable(wrapperClassName, body)
      } else {
        body()
      }
    }
  }

  // Render two frames for animations/effects to settle
  scene.render()
  val image = scene.render()

  val pngData =
    image.encodeToData(EncodedImageFormat.PNG)
      ?: throw IllegalStateException("Failed to encode image to PNG")

  outputFile.parentFile?.mkdirs()

  // Crop to the measured content bounds on wrapped axes. `measured` is
  // populated during the Modifier.layout measure pass in the wrap branch
  // above — if it somehow wasn't set (shouldn't happen, but defensive),
  // fall back to the sandbox dimensions and write the uncropped PNG.
  if ((wrapWidth || wrapHeight) && measured != null) {
    val m = measured!!
    val cropW = (if (wrapWidth) m.width else widthPx).coerceIn(1, widthPx)
    val cropH = (if (wrapHeight) m.height else heightPx).coerceIn(1, heightPx)
    val decoded = ByteArrayInputStream(pngData.bytes).use { ImageIO.read(it) }
    if (decoded != null && (cropW < decoded.width || cropH < decoded.height)) {
      val sub =
        decoded.getSubimage(
          0,
          0,
          cropW.coerceAtMost(decoded.width),
          cropH.coerceAtMost(decoded.height),
        )
      ImageIO.write(sub, "PNG", outputFile)
    } else {
      outputFile.writeBytes(pngData.bytes)
    }
  } else {
    outputFile.writeBytes(pngData.bytes)
  }

  scene.close()
}

@Composable
private fun InvokeComposable(
  composableMethod: ComposableMethod,
  instance: Any?,
  previewArgs: List<Any?>,
) {
  composableMethod.invoke(currentComposer, instance, *previewArgs.toTypedArray())
}

/**
 * Desktop mirror of the Android renderer's lookup for `@PreviewParameter` functions — see
 * [ee.schimke.composeai.renderer.findComposableMethodWithArgs] for the full commentary. Kept local
 * (not shared via a common module) so the two renderer artefacts stay independently buildable.
 */
private fun findComposableMethodWithArgs(
  clazz: Class<*>,
  name: String,
  previewArgs: List<Any?>,
): ComposableMethod {
  val argCount = previewArgs.size
  val candidate =
    clazz.declaredMethods.firstOrNull { m ->
      m.name == name && m.parameterCount >= argCount + 2 && argsMatch(m, previewArgs)
    }
      ?: throw NoSuchMethodException(
        "Couldn't find composable method $name on ${clazz.name} taking $argCount parameter(s); " +
          "check that the @PreviewParameter provider's value type matches the preview's parameter type."
      )
  val declaredTypes = candidate.parameterTypes.take(argCount).toTypedArray()
  return clazz.getDeclaredComposableMethod(name, *declaredTypes)
}

private fun argsMatch(method: java.lang.reflect.Method, previewArgs: List<Any?>): Boolean {
  for ((i, arg) in previewArgs.withIndex()) {
    val expected = method.parameterTypes[i]
    if (arg == null) {
      if (expected.isPrimitive) return false
      continue
    }
    val actual = arg.javaClass
    if (expected.isAssignableFrom(actual)) continue
    if (expected.kotlin.javaObjectType.isAssignableFrom(actual)) continue
    return false
  }
  return true
}

/**
 * Reflectively instantiates the `PreviewWrapperProvider` identified by [wrapperFqn] and invokes its
 * `Wrap(content)` composable around [body].
 *
 * See [RobolectricRenderTest.resolveWrapper] — same lookup strategy, same caveats.
 */
@Composable
private fun InvokeWrappedComposable(wrapperFqn: String, body: @Composable () -> Unit) {
  val resolved = remember(wrapperFqn) { resolveWrapper(wrapperFqn) }
  resolved.first.invoke(currentComposer, resolved.second, body)
}

private fun resolveWrapper(wrapperFqn: String): Pair<ComposableMethod, Any> {
  val cls = Class.forName(wrapperFqn)
  val instance = cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
  // PreviewWrapperProvider.Wrap(content: @Composable () -> Unit) compiles to
  // Wrap(Function2, Composer, int) at the bytecode level.
  val method = cls.getDeclaredComposableMethod("Wrap", Function2::class.java)
  return method to instance
}
