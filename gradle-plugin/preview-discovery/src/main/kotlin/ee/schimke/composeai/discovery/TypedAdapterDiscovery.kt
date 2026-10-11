package ee.schimke.composeai.discovery

import io.github.classgraph.ClassGraph
import java.io.File

/** Metadata-only discovery for explicitly selected top-level Kotlin adapter entry points. */
object TypedAdapterDiscovery {
  /** JVM file-facade owner and method name, for example `com.acme.ButtonKt` / `BrandButton`. */
  data class Callable(val owner: String, val name: String)

  /**
   * Scan compiled code without loading or executing application classes. A null classpath scans the
   * export process's runtime classpath; build integrations can supply their compiled outputs. This
   * selects entry points rather than following every call made by an app's previews.
   */
  fun discover(
    module: String,
    variant: String,
    callables: List<Callable>,
    classpath: List<File>? = null,
  ): ComponentRecordFile {
    require(module.isNotBlank() && variant.isNotBlank()) { "module and variant must be non-blank" }
    require(callables.isNotEmpty() && callables.distinct().size == callables.size) {
      "declare distinct callable entry points"
    }
    require(callables.all { it.owner.contains('.') && it.name.isNotBlank() }) {
      "callables require a qualified owner and a method name"
    }
    val graph =
      ClassGraph()
        .enableClassInfo()
        .enableMethodInfo()
        .enableAnnotationInfo()
        .acceptClasses(*callables.map { it.owner }.distinct().toTypedArray())
    if (classpath != null) graph.overrideClasspath(classpath)
    return graph.scan().use { scan ->
      val components =
        callables
          .map { selected ->
            val owner =
              requireNotNull(scan.getClassInfo(selected.owner)) {
                "missing compiled owner ${selected.owner}"
              }
            val methods = owner.methodInfo.filter { it.name == selected.name }
            require(methods.size == 1) {
              "${selected.owner}.${selected.name}: expected one callable, found ${methods.size}"
            }
            val method = methods.single()
            require(method.isStatic && ComposableSignature.isTopLevel(owner)) {
              "${selected.owner}.${selected.name}: use an ordinary top-level Kotlin export wrapper"
            }
            val signature =
              requireNotNull(ComposableSignature.signatureOf(owner, method, scan)) {
                "${selected.owner}.${selected.name}: signature metadata missing"
              }
            val record =
              ComponentRecord.Builder(
                  "$module/${selected.owner}.${signature.name}",
                  ComponentSymbol.Builder(
                      selected.owner,
                      selected.owner.substringBeforeLast('.') + "." + signature.name,
                      signature.name,
                      ComponentOrigin.PROJECT,
                    )
                    .also {
                      it.descriptor = method.typeDescriptorStr
                      it.jvmName = selected.name
                      it.receiver = signature.receiver
                    }
                    .build(),
                )
                .also {
                  it.signatureKnown = true
                  it.parameters = signature.parameters
                  it.slots = ComponentRecords.slotsOf(signature.parameters)
                  it.callableFromAnotherFile = signature.callableFromAnotherFile
                  it.hasTypeParameters = signature.hasTypeParameters
                  it.hasContextReceivers = signature.hasContextReceivers
                  it.requiredOptIns = signature.requiredOptIns
                  it.androidxOptIns = signature.androidxOptIns
                }
                .build()
            record.newBuilder().also { it.code = ComponentSnippets.codeFor(record) }.build()
          }
          .sortedBy { it.canonicalId }
      ComponentRecordFile.Builder(module, variant, components).build()
    }
  }

  /**
   * The factories a typed definition's export projection calls — `ButtonDefaults` members such as
   * `filledTonalButtonColors` — with every overload's parameters and return type, so
   * [TypedComponentAdapter.validateAgainst] can check a `call(…)` by argument name and type rather
   * than trusting a string. [Callable.owner] is the object's class (`…material3.ButtonDefaults`),
   * not a file facade. Metadata only: no class is loaded.
   */
  fun discoverFactories(
    factories: List<Callable>,
    classpath: List<File>? = null,
  ): List<FactorySignature> {
    require(factories.isNotEmpty() && factories.distinct().size == factories.size) {
      "declare distinct factories"
    }
    val graph =
      ClassGraph()
        .enableClassInfo()
        .enableMethodInfo()
        .acceptClasses(*factories.map { it.owner }.distinct().toTypedArray())
    if (classpath != null) graph.overrideClasspath(classpath)
    return graph.scan().use { scan ->
      factories.flatMap { factory ->
        val owner =
          requireNotNull(scan.getClassInfo(factory.owner)) {
            "missing compiled owner ${factory.owner}"
          }
        ComposableSignature.factorySignatures(owner, factory.name).also {
          require(it.isNotEmpty()) {
            "${factory.owner}.${factory.name}: no public member of an object or companion with readable metadata"
          }
        }
      }
    }
  }
}
