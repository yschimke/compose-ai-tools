package ee.schimke.composeai.usagepsi

import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Parses Kotlin source and reports the structure the usage cleaner needs, as JSON.
 *
 * JSON across a single `analyze(String): String` method because this runs in an isolated
 * classloader holding a Kotlin frontend; the caller shares no classes with it.
 *
 * Parse only: [KotlinCoreEnvironment] with an empty [CompilerConfiguration] builds a tree without
 * resolution (~0.5 s setup, ~3 ms per file; `docs/design/PSI_PARSE_SPIKE.md`).
 *
 * Reports facts, never decisions: whether a qualified call is scaffolding is the caller's question,
 * answered from the exact receiver string reported here.
 */
// `KotlinCoreEnvironment.createForProduction` gained the `CoreEnvironmentDeprecation` opt-in in
// Kotlin 2.4.20; it is still the supported way to build a parse-only frontend.
@OptIn(
  CompilerConfiguration.Internals::class,
  K1Deprecation::class,
  CoreEnvironmentDeprecation::class,
  ExperimentalCompilerApi::class,
)
class UsageSourceAnalyzer : AutoCloseable {

  private val disposable: Disposable = Disposer.newDisposable("usage-source-psi")

  private val factory: PsiFileFactory by lazy {
    val env =
      KotlinCoreEnvironment.createForProduction(
        disposable,
        // Kotlin 2.4.20 reads `extensionsStorage` while wiring plugin extension points, and a bare
        // configuration has none; parsing needs no plugins, so an empty storage suffices.
        CompilerConfiguration().apply {
          extensionsStorage = CompilerPluginRegistrar.ExtensionStorage()
        },
        EnvironmentConfigFiles.JVM_CONFIG_FILES,
      )
    PsiFileFactory.getInstance(env.project)
  }

  /**
   * [source] → a JSON object of `calls` and `declarations`, or `{"error":"…"}` if unparseable.
   * Never throws across the reflective boundary. Offsets are 0-based, end-exclusive character
   * indices into [source].
   */
  fun analyze(source: String): String =
    try {
      val file =
        factory.createFileFromText("Usage.kt", KotlinFileType.INSTANCE, source) as? KtFile
          ?: return json { field("error", "not a Kotlin file") }
      json {
        arrayField("calls", PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java)) {
          call(it)
        }
        // Top-level declarations in source order, so the caller can attribute each call to its
        // declaration rather than inferring boundaries from blank lines.
        arrayField("declarations", file.declarations) { declaration ->
          // `textRange` includes preceding KDoc and annotations: the widest honest span.
          number("start", declaration.textRange.startOffset)
          number("end", declaration.textRange.endOffset)
        }
      }
    } catch (e: Throwable) {
      json { field("error", e::class.java.simpleName + ": " + (e.message ?: "")) }
    }

  override fun close() = Disposer.dispose(disposable)

  private fun JsonWriter.call(call: KtCallExpression) {
    field("callee", call.calleeExpression?.text ?: "")
    number("start", call.textRange.startOffset)
    number("end", call.textRange.endOffset)

    // The parenthesised argument list, absent entirely for `counted { }` — the shape a regex
    // requiring `(` missed, and the one most scaffolding wrappers are written in.
    val argList = call.valueArgumentList
    number("argsStart", argList?.textRange?.startOffset ?: -1)
    number("argsEnd", argList?.textRange?.endOffset ?: -1)

    val lambda = call.lambdaArguments.firstOrNull()
    number("lambdaStart", lambda?.textRange?.startOffset ?: -1)
    number("lambdaEnd", lambda?.textRange?.endOffset ?: -1)
    val body = lambda?.getLambdaExpression()?.bodyExpression
    number("lambdaBodyStart", body?.textRange?.startOffset ?: -1)
    number("lambdaBodyEnd", body?.textRange?.endOffset ?: -1)

    // The whole `receiver.callee(...)` expression when qualified, so the caller can replace or keep
    // it as one unit rather than guessing where the receiver began.
    val qualified = call.parent as? KtDotQualifiedExpression
    val isSelector = qualified?.selectorExpression === call
    field("receiver", if (isSelector) qualified.receiverExpression.text else null)
    number("qualifiedStart", if (isSelector) qualified.textRange.startOffset else -1)
    number("qualifiedEnd", if (isSelector) qualified.textRange.endOffset else -1)

    // `KtLambdaArgument` *is* a `KtValueArgument`, so a trailing lambda arrives in this list — and
    // would then take a positional slot during binding, putting `{ … }` where `default` belongs.
    // It is reported above as its own range instead.
    arrayField(
      "args",
      call.valueArguments.filterIsInstance<KtValueArgument>().filter { it !is KtLambdaArgument },
    ) { arg ->
      field("name", arg.getArgumentName()?.asName?.asString())
      val expr = arg.getArgumentExpression()
      field("text", expr?.text ?: "")
      number("start", expr?.textRange?.startOffset ?: -1)
      number("end", expr?.textRange?.endOffset ?: -1)
    }
  }
}
