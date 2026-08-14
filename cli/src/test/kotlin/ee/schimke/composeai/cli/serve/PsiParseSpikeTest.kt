package ee.schimke.composeai.cli.serve

import java.io.File
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * **Spike** for replacing [PlaygroundSourceCleaner]'s text passes with a real parse.
 *
 * ### What it is trying to find out
 *
 * The cleaner scans text because the Kotlin frontend is deliberately kept off the CLI's runtime
 * classpath (`cli/build.gradle.kts` — it is staged into `lib-bta/` and loaded in an isolated
 * classloader only for an actual compile). That decision has a measurable cost: nearly every defect
 * found by the snippet corpus's review rounds was *parser-shaped* — named-argument binding, a
 * receiver chain mistaken for a package qualifier, a trailing-lambda call with no parentheses, a
 * qualified call that no pass could see. A parse gets all four right for nothing.
 *
 * Three questions, and this answers all three before anything is committed to:
 * 1. Does **parse-only** PSI work with no analysis, no classpath, no resolution?
 * 2. What does it cost — environment setup once, and per file?
 * 3. Does the tree actually carry what the cleaner needs (call names, argument names, qualifiers)?
 *
 * ### Why a test-only dependency
 *
 * `testImplementation` here does not put the frontend on the CLI's runtime classpath, so the
 * constraint this spike is questioning stays intact while the spike runs. If the numbers say yes,
 * the real change loads the same jars through the **existing** `lib-bta/` classloader
 * ([PlaygroundBtaCompiler.installJars]) rather than adding a dependency.
 *
 * Reported via `println` rather than asserted: the timings are the product, and pinning a
 * millisecond budget in CI would be a flaky test about somebody's machine.
 */
@OptIn(CompilerConfiguration.Internals::class, K1Deprecation::class)
class PsiParseSpikeTest {

  private fun sampleSources(): List<Pair<String, String>> {
    val corpus = File("build/usage-corpus")
    val generated =
      corpus
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .map { it.name to it.readText() }
        .toList()
    // The fixture stands in when no corpus has been generated on this machine, so the spike still
    // reports something rather than silently measuring nothing.
    return generated.ifEmpty { listOf("Fixture.kt" to FIXTURE) }
  }

  @Test
  fun `parse-only PSI is available, and this is what it costs`() {
    val disposable = Disposer.newDisposable("psi-parse-spike")
    try {
      lateinit var factory: PsiFileFactory
      val setup = measureTime {
        // No classpath, no roots, no analysis: a parser needs none of it. This is the whole claim
        // being tested — the expensive part of the frontend is resolution, not parsing.
        val env =
          KotlinCoreEnvironment.createForProduction(
            disposable,
            CompilerConfiguration(),
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
          )
        factory = PsiFileFactory.getInstance(env.project)
      }

      val sources = sampleSources()
      var files = 0
      var functions = 0
      var calls = 0
      var namedArgs = 0
      var qualified = 0
      var bytes = 0L

      // Parse everything once to warm up, then measure — otherwise the first file carries the
      // parser's own class loading and the per-file number is nonsense.
      for ((name, text) in sources) {
        factory.createFileFromText(name, KotlinFileType.INSTANCE, text)
      }
      val parsing = measureTime {
        for ((name, text) in sources) {
          val ktFile =
            factory.createFileFromText(name, KotlinFileType.INSTANCE, text) as? KtFile ?: continue
          files++
          bytes += text.length
          functions += ktFile.collectDescendantsOfType<KtNamedFunction>().size
          val callNodes = ktFile.collectDescendantsOfType<KtCallExpression>()
          calls += callNodes.size
          namedArgs += callNodes.sumOf { call ->
            call.valueArguments.count { (it as? KtValueArgument)?.getArgumentName() != null }
          }
          qualified += ktFile.collectDescendantsOfType<KtDotQualifiedExpression>().size
        }
      }

      println(
        """
        |
        |=== parse-only PSI spike ===
        |  environment setup : $setup   (once per process)
        |  parsed            : $files files, $bytes chars, in $parsing
        |  per file          : ${if (files > 0) parsing / files else parsing}
        |
        |  what the tree carried, which the text passes each had to guess at:
        |    named functions        : $functions
        |    call expressions       : $calls
        |    named arguments        : $namedArgs   (argument binding, no `params` list needed)
        |    qualified expressions  : $qualified   (receiver vs package, structurally)
        """
          .trimMargin()
      )

      assertTrue(files > 0, "no sources parsed")
      assertTrue(functions > 0, "parsed but found no functions — the tree is not usable")
    } finally {
      Disposer.dispose(disposable)
    }
  }

  /**
   * Every shape the corpus review rounds got wrong, in one file. A parse has to distinguish all of
   * them without a rules file telling it how.
   */
  @Test
  fun `the tree distinguishes the shapes the text passes could not`() {
    val disposable = Disposer.newDisposable("psi-parse-spike-shapes")
    try {
      val env =
        KotlinCoreEnvironment.createForProduction(
          disposable,
          CompilerConfiguration(),
          EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
      val ktFile =
        PsiFileFactory.getInstance(env.project)
          .createFileFromText("Shapes.kt", KotlinFileType.INSTANCE, FIXTURE) as KtFile

      val calls = ktFile.collectDescendantsOfType<KtCallExpression>()
      val byName = calls.groupBy { it.calleeExpression?.text }

      // 1. Named arguments resolve by name, in any order — no `params` declaration required.
      val named = byName["previewOverrideString"].orEmpty()
      val defaults = named.mapNotNull { call ->
        call.valueArguments
          .firstOrNull {
            (it as? KtValueArgument)?.getArgumentName()?.asName?.asString() == "default"
          }
          ?.getArgumentExpression()
          ?.text
      }
      println("previewOverrideString defaults, bound by name: $defaults")
      assertTrue(defaults.contains("\"Shopping\""), "named-argument binding failed: $defaults")

      // 2. A trailing-lambda call is a call, parentheses or not.
      val tally = byName["counted"].orEmpty()
      println("counted call sites: ${tally.size} (trailing-lambda forms included)")
      assertTrue(tally.size >= 2, "trailing-lambda call not seen as a call: ${tally.size}")

      // 3. A qualified call knows its own receiver text, so package vs receiver chain is a
      //    structural question rather than a regex guess.
      val qualifiers =
        ktFile.collectDescendantsOfType<KtDotQualifiedExpression>().mapNotNull { dq ->
          val callee = (dq.selectorExpression as? KtCallExpression)?.calleeExpression?.text
          if (callee == null) null else dq.receiverExpression.text to callee
        }
      println("qualified calls (receiver → callee): $qualifiers")
      assertTrue(
        qualifiers.any { it.first == "ee.schimke.composeai.overrides" },
        "package-qualified call not distinguishable: $qualifiers",
      )
      assertTrue(
        qualifiers.any { it.first == "state.metrics" },
        "receiver chain not distinguishable: $qualifiers",
      )

      // 4. Destructuring — the `toggleable` / `editable` gap the rules file still records as open.
      val destructured = ktFile.text.contains("val (checked, onCheckedChange)")
      println("destructuring declaration present in fixture: $destructured")
    } finally {
      Disposer.dispose(disposable)
    }
  }

  /**
   * The deployment route, not the convenience one: load the parser from the CLI install's staged
   * `lib-bta/` through an **isolated** classloader, exactly as `PlaygroundBtaCompiler` already
   * loads the compiler. This is what makes the spike actionable — it shows the frontend never has
   * to reach the CLI's own runtime classpath, which is the constraint the text passes exist to
   * respect.
   *
   * Skipped when `lib-bta/` has not been staged (`./gradlew :cli:installDist`), like the compiler
   * route it mirrors.
   */
  @Test
  fun `the parser loads from the isolated lib-bta classloader`() {
    val libBta = File("build/install/compose-preview/lib-bta")
    val jars = libBta.listFiles()?.filter { it.extension == "jar" }.orEmpty()
    if (jars.isEmpty()) {
      println("lib-bta not staged; skipping (run :cli:installDist to exercise this)")
      return
    }

    // Platform parent, so nothing resolves against the CLI's own classpath: if this works, the
    // frontend is reachable without ever being a dependency of the serve host.
    val loader =
      URLClassLoader(
        jars.map { it.toURI().toURL() }.toTypedArray(),
        ClassLoader.getPlatformClassLoader(),
      )
    var text = ""
    val elapsed = measureTime {
      val disposer = loader.loadClass("org.jetbrains.kotlin.com.intellij.openapi.util.Disposer")
      val disposable =
        disposer.getMethod("newDisposable", String::class.java).invoke(null, "lib-bta-spike")
      val envClass = loader.loadClass("org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment")
      val companion = envClass.getField("Companion").get(null)
      val config =
        loader
          .loadClass("org.jetbrains.kotlin.config.CompilerConfiguration")
          .getDeclaredConstructor()
          .newInstance()
      val jvmConfigFiles =
        loader
          .loadClass("org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles")
          .getField("JVM_CONFIG_FILES")
          .get(null)
      // By exact signature, never `methods.first { … }`: `Class.getMethods()` has no specified
      // order, so a predicate can select a different overload from run to run. That is precisely
      // how this spike first failed — intermittently, and while looking convincingly like JVM-state
      // interference from a prior in-process compile.
      val create =
        companion.javaClass.getMethod(
          "createForProduction",
          loader.loadClass("org.jetbrains.kotlin.com.intellij.openapi.Disposable"),
          loader.loadClass("org.jetbrains.kotlin.config.CompilerConfiguration"),
          loader.loadClass("org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles"),
        )
      val env = create.invoke(companion, disposable, config, jvmConfigFiles)
      val project = env.javaClass.getMethod("getProject").invoke(env)

      val fileTypeClass = loader.loadClass("org.jetbrains.kotlin.idea.KotlinFileType")
      val fileType = fileTypeClass.getField("INSTANCE").get(null)
      val factoryClass = loader.loadClass("org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory")
      val factory =
        factoryClass
          .getMethod(
            "getInstance",
            loader.loadClass("org.jetbrains.kotlin.com.intellij.openapi.project.Project"),
          )
          .invoke(null, project)
      val createFile =
        factoryClass.getMethod(
          "createFileFromText",
          String::class.java,
          loader.loadClass("org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType"),
          CharSequence::class.java,
        )
      val psi = createFile.invoke(factory, "Shapes.kt", fileType, FIXTURE)
      text = psi.javaClass.getMethod("getText").invoke(psi) as String

      disposer
        .getMethod(
          "dispose",
          loader.loadClass("org.jetbrains.kotlin.com.intellij.openapi.Disposable"),
        )
        .invoke(null, disposable)
    }

    println("lib-bta isolated load + parse: $elapsed over ${jars.size} jars")
    assertTrue(text.contains("previewOverrideString"), "parsed nothing through the isolated loader")
  }

  private companion object {
    /** Not a tidy sample: every one of these lines is a defect the corpus found. */
    val FIXTURE =
      """
      package ee.schimke.demo

      import androidx.compose.material3.Text
      import androidx.compose.runtime.Composable
      import ee.schimke.composeai.preview.previewOverrideString

      @Composable
      fun Shapes() {
        Text(previewOverrideString(key = "title", default = "Shopping"))
        Text(previewOverrideString("subtitle", "Basket"))
        counted { }
        counted("label")
        ee.schimke.composeai.overrides.previewOverrideString("k", "v")
        state.metrics.counted { }
        val (checked, onCheckedChange) = toggleable("on", true)
      }
      """
        .trimIndent()
  }
}
