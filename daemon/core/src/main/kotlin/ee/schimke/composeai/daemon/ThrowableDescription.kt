package ee.schimke.composeai.daemon

/**
 * Render [t] for a daemon log line or a JSON-RPC error message, **including its cause chain**.
 *
 * The daemon used to spell these as `"${t.javaClass.simpleName}: ${t.message}"`. That reads fine
 * for the throwables which carry their own explanation, and loses the whole failure for the ones
 * that do not:
 * ```
 * recording/start: ExceptionInInitializerError: null
 * ```
 *
 * `ExceptionInInitializerError` has a **null message** by construction — the class that failed to
 * initialise and the reason it failed both live in its `cause`. So does
 * `InvocationTargetException`, and so does every wrapper a reflective preview invocation travels
 * through. The line above names neither the class nor the reason, and the client sees the same
 * string, so a failure that is perfectly diagnosable inside the JVM arrives as a dead end at both
 * ends of the wire.
 *
 * The chain is what makes it diagnosable, so the chain is what this renders:
 * ```
 * ExceptionInInitializerError ← caused by NoClassDefFoundError: kotlinx/coroutines/GlobalScope
 * ```
 *
 * Depth is capped at [MAX_CAUSE_DEPTH] and self-referential chains terminate: a message that lands
 * in a log line and a protocol field has to stay bounded whatever the JVM hands us.
 */
internal fun describeThrowable(t: Throwable): String {
  val out = StringBuilder()
  var current: Throwable? = t
  var depth = 0
  // Identity, not equality: a cause chain can legitimately repeat an equal-but-distinct throwable,
  // and only a cycle through the *same* instance would spin here.
  val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
  while (current != null && depth <= MAX_CAUSE_DEPTH) {
    if (!seen.add(current)) {
      out.append(" ← caused by (cycle)")
      break
    }
    if (depth > 0) out.append(" ← caused by ")
    out.append(describeOne(current))
    val next = current.cause
    if (next != null && depth == MAX_CAUSE_DEPTH) {
      out.append(" ← caused by …")
      break
    }
    current = next
    depth++
  }
  return out.toString()
}

/** `SimpleName: message`, or the bare name when there is no message worth printing. */
private fun describeOne(t: Throwable): String {
  // `simpleName` is empty for an anonymous class; fall back to the binary name so the line still
  // names something.
  val name = t.javaClass.simpleName.ifEmpty { t.javaClass.name }
  val message = t.message?.trim()
  return if (message.isNullOrEmpty()) name else "$name: $message"
}

/**
 * How many causes deep to walk. Four links past the head covers the wrapper stacks this daemon
 * actually produces (reflection → class init → linkage) without letting a pathological chain turn
 * one log line into a page.
 */
private const val MAX_CAUSE_DEPTH = 4
