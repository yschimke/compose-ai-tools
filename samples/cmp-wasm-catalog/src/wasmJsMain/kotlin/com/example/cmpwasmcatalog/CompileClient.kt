@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.example.cmpwasmcatalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ee.schimke.composeai.screen.CompileCheck
import ee.schimke.composeai.screen.CompileOutcome
import ee.schimke.composeai.screen.CompileTarget
import ee.schimke.composeai.screen.StaleGuard
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * What the builder's compile pane is showing.
 *
 * There is no "off" member on purpose: with no `?compileHost=` the caller gets **null** and renders
 * nothing at all. An empty pane would tell a user the check ran and found nothing, when in fact the
 * feature was never switched on — and the browser-only loop is the thing that works today and must
 * keep working with no server anywhere near it.
 */
internal sealed interface CompilePaneState {
  /** Asking the host what it can compile. */
  data object Discovering : CompilePaneState

  /** The host cannot compile this screen, and says why rather than failing every check. */
  data class Unavailable(val reason: String) : CompilePaneState

  data class Ready(
    val target: CompileTarget,
    val checking: Boolean = false,
    val outcome: CompileOutcome? = null,
  ) : CompilePaneState
}

/**
 * How long the source must stop changing before it is posted.
 *
 * Every keystroke regenerates the source. Posting each one would hammer the host and mint a
 * compiled-snippet token per character. A pause this long is well inside "I stopped typing" and
 * well outside the gap between two keys.
 */
private const val DEBOUNCE_MS = 600L

/** A compile is a real compile — Gradle-less, but not instant. */
private const val COMPILE_TIMEOUT_MS = 60_000

private const val DISCOVER_TIMEOUT_MS = 10_000

/**
 * Runs the compile check against [host] whenever [source] settles, and reports what came back.
 *
 * ### Staleness
 *
 * Two mechanisms, and both are wanted. Keying the effect on [source] means a new edit **cancels**
 * the in-flight check, so its continuation never resumes; the [StaleGuard] sequence then makes that
 * guarantee explicit at the point the result is applied. Without either, a slow answer to an older
 * edit lands last and paints errors over source the user has already fixed.
 *
 * ### Not blocking the editor
 *
 * Nothing here is awaited by the render or code panes. A host that takes a minute — or never
 * answers — costs this pane and nothing else.
 */
@Composable
internal fun rememberCompileCheck(host: String, source: String): CompilePaneState {
  var state by remember(host) { mutableStateOf<CompilePaneState>(CompilePaneState.Discovering) }
  val guard = remember(host) { StaleGuard() }

  LaunchedEffect(host) {
    state =
      try {
        val catalogs = CompileCheck.parseCatalogs(httpGetText(CompileCheck.catalogsUrl(host)))
        val target = CompileCheck.targetFor(catalogs)
        when {
          target != null -> CompilePaneState.Ready(target)
          catalogs.isEmpty() -> CompilePaneState.Unavailable("$host offers no compile catalogs")
          else ->
            // The classpath question, answered by the host instead of assumed. The generated file
            // imports `androidx.compose.material3.*`; only the `compose-m3` catalog's classpath
            // carries it. Naming what the host *does* offer turns "it says errors" into "this host
            // cannot compile M3", which is a different bug report.
            CompilePaneState.Unavailable(
              "no '${CompileCheck.M3_CATALOG_SYSTEM}' catalog here — this host compiles " +
                catalogs.joinToString(", ") { it.servedSystem } +
                ", none of which carries androidx.compose.material3"
            )
        }
      } catch (e: Exception) {
        CompilePaneState.Unavailable("could not reach $host: ${e.message}")
      }
  }

  val ready = state as? CompilePaneState.Ready
  val target = ready?.target
  LaunchedEffect(target, source) {
    if (target == null) return@LaunchedEffect
    delay(DEBOUNCE_MS)
    val sequence = guard.issue()
    state = CompilePaneState.Ready(target, checking = true, outcome = ready.outcome)
    val outcome =
      try {
        CompileCheck.readResponse(
          httpPostJson(CompileCheck.runUrl(host), CompileCheck.requestBody(source, target))
        )
      } catch (e: Exception) {
        CompileOutcome.Failed(e.message ?: "the compile request failed")
      }
    if (guard.isCurrent(sequence)) {
      state = CompilePaneState.Ready(target, checking = false, outcome = outcome)
    }
  }

  return state
}

private fun getTextPromise(url: String, timeoutMs: Int): Promise<JsString> =
  js(
    """fetch(url, { signal: AbortSignal.timeout(timeoutMs) })
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.text(); })"""
  )

/**
 * The response **body is returned even on a non-2xx**, because the server answers a rejected
 * compile with a JSON body that says why. Throwing away that body and reporting the status code
 * would turn a readable "catalog not available" into "HTTP 400".
 */
private fun postJsonPromise(url: String, body: String, timeoutMs: Int): Promise<JsString> =
  js(
    """fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: body,
        signal: AbortSignal.timeout(timeoutMs)
      }).then(function (r) { return r.text(); })"""
  )

/** Cancellable, so an edit that supersedes a check genuinely abandons it. */
private suspend fun httpGetText(url: String): String =
  await(getTextPromise(url, DISCOVER_TIMEOUT_MS))

private suspend fun httpPostJson(url: String, body: String): String =
  await(postJsonPromise(url, body, COMPILE_TIMEOUT_MS))

private suspend fun await(promise: Promise<JsString>): String =
  suspendCancellableCoroutine { cont ->
    promise
      .then { s ->
        if (cont.isActive) cont.resume(s.toString())
        null
      }
      .catch { e ->
        if (cont.isActive) {
          cont.resumeWithException(IllegalStateException(e?.toString() ?: "the request failed"))
        }
        null
      }
  }

/** Opens the live preview in a new tab. `noopener` because the token URL is a capability. */
internal fun openInNewTab(url: String) {
  openUrl(url)
}

private fun openUrl(url: String): Unit = js("{ window.open(url, '_blank', 'noopener'); }")
