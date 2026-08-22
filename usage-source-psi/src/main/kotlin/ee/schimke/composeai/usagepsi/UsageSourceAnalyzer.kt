package ee.schimke.composeai.usagepsi

import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtValueArgument

/**
 * Parses Kotlin source and reports the **structure** the usage cleaner needs, as JSON.
 *
 * ### Why JSON, and why one method
 *
 * This class runs inside an isolated classloader holding a whole Kotlin frontend; the CLI that
 * calls it holds none of those types. Returning JSON means the two sides share no classes at all,
 * so the loader can be parented to the platform loader with nothing bridged, and the reflective
 * surface stays a single `analyze(String): String`. A typed interface would need a shared package
 * visible to both loaders — more moving parts for a boundary this narrow.
 *
 * ### Parse only
 *
 * No classpath, no source roots, no resolution: [KotlinCoreEnvironment] with an empty
 * [CompilerConfiguration] is enough to build a tree, and the tree is all the rewrites need. The
 * expensive half of a frontend is resolution, which nothing here asks for. Setup costs ~0.5 s once
 * per process and parsing ~3 ms per file (`docs/design/PSI_PARSE_SPIKE.md`), against a seed path
 * that already makes a network round trip.
 *
 * ### What it deliberately does not do
 *
 * It reports facts, never decisions. Whether `ee.schimke.composeai.overrides.previewOverrideString`
 * is scaffolding to unqualify, and whether `state.metrics.counted` is somebody's receiver chain, is
 * a question about a catalog's declared rules — the same `KtDotQualifiedExpression` either way.
 * This hands over the receiver as an exact string so the caller can look it up, which is precisely
 * what the regex it replaces could not do.
 */
@OptIn(CompilerConfiguration.Internals::class, K1Deprecation::class)
class UsageSourceAnalyzer : AutoCloseable {

  private val disposable: Disposable = Disposer.newDisposable("usage-source-psi")

  private val factory: PsiFileFactory by lazy {
    val env =
      KotlinCoreEnvironment.createForProduction(
        disposable,
        CompilerConfiguration(),
        EnvironmentConfigFiles.JVM_CONFIG_FILES,
      )
    PsiFileFactory.getInstance(env.project)
  }

  /**
   * [source] → a JSON object of `calls` and `declarations`, or `{"error":"…"}` if it could not be
   * parsed at all.
   *
   * Never throws across the reflective boundary: an exception here would surface in the caller as
   * an `InvocationTargetException` carrying a frontend-loaded class the caller cannot name. A JSON
   * error field degrades to "no facts", and the cleaner's caller already knows how to fall back.
   *
   * Offsets are 0-based character indices into [source], end-exclusive, so the caller can splice
   * without re-finding anything.
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
        // The file's top-level declarations, in source order. Reported for the same reason the
        // calls are: a caller that has to say WHICH declaration a call belongs to would otherwise
        // infer the boundaries from formatting, and the blank-line rule that is safe for seeding an
        // editor buffer (over-select rather than truncate) is wrong here — over-selecting merges
        // two declarations, and every call in both is then attributed to each.
        arrayField("declarations", file.declarations) { declaration ->
          // `textRange` starts at the declaration proper. `startOffsetSkippingComments` would skip
          // the KDoc the other way; what is wanted is the widest honest span, so the KDoc and
          // annotations that precede a `fun` count as part of it rather than as a gap between
          // declarations.
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
