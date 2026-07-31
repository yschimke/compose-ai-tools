package ee.schimke.composeai.daemon.pool

import ee.schimke.composeai.daemon.RenderRequest
import ee.schimke.composeai.daemon.RobolectricHost
import ee.schimke.composeai.daemon.UserClassLoaderHolder
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.Socket

/**
 * Out-of-process sandbox pool (issue #3072) — the child half. One worker JVM hosts exactly **one**
 * Robolectric sandbox, which is the constraint the whole pool exists to respect: Robolectric's
 * native runtime binds to a single classloader per process.
 *
 * The worker is deliberately *not* a daemon. It has no preview index, no extension registry, no
 * watch state and no JSON-RPC surface: the parent daemon resolves a `previewId` into a full spec
 * payload before it dispatches, and re-runs the host-side data products on the result it gets back.
 * All the worker owns is a `RobolectricHost(sandboxCount = 1)` — the exact same single-sandbox host
 * the daemon has always run — plus a socket loop.
 *
 * Lifecycle: dial the parent's loopback port, boot the sandbox, send [WorkerResponse.Ready] (or
 * [WorkerResponse.BootFailed]), then serve requests until [WorkerRequest.Shutdown] or EOF. EOF is a
 * shutdown too — if the parent daemon dies, its workers must not survive it and leak a JVM each.
 */
object SandboxWorkerMain {

  @JvmStatic
  fun main(args: Array<String>) {
    val port =
      System.getProperty(SandboxProcessPool.WORKER_PORT_PROP)?.toIntOrNull()
        ?: error(
          "${SandboxProcessPool.WORKER_PORT_PROP} is unset — SandboxWorkerMain is spawned by " +
            "SandboxProcessPool, not run directly"
        )
    val slot = System.getProperty(SandboxProcessPool.WORKER_SLOT_PROP)?.toIntOrNull() ?: 0
    Thread.currentThread().name = "compose-ai-sandbox-worker-$slot"

    // A worker must never outlive the daemon that spawned it. Socket EOF covers the normal case,
    // but only once the serve loop is reading — a parent that dies while this worker is still
    // inside its (minutes-long) Robolectric bootstrap would leave a whole JVM stranded, holding a
    // sandbox's worth of heap until something reaps it. Watch the parent process directly so the
    // boot window is covered too. The daemon's own test JVM aborting mid-suite (SIGABRT out of
    // `libandroid_runtime.so`) is exactly that case.
    ProcessHandle.current().parent().ifPresent { parent ->
      parent.onExit().thenRun {
        System.err.println("sandbox worker: parent process ${parent.pid()} exited; halting")
        Runtime.getRuntime().halt(0)
      }
    }

    Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
      socket.tcpNoDelay = true
      val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
      val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

      val host =
        RobolectricHost(
          sandboxCount = 1,
          // Same derivation DaemonMain uses, from the `composeai.daemon.userClassDirs` sysprop the
          // pool forwards. Unset (in-process tests, no hot-reload wiring) → null → the worker
          // resolves preview classes off its own sandbox classpath, like a single-sandbox daemon.
          userClassloaderHolderFactory =
            UserClassLoaderHolder.urlsFromSysprop().takeIf { it.isNotEmpty() }?.let { urls ->
              { sandboxClassLoader: ClassLoader ->
                UserClassLoaderHolder(urls = urls, parentSupplier = { sandboxClassLoader })
              }
            },
        )
      try {
        host.start()
      } catch (t: Throwable) {
        send(writer, WorkerResponse.BootFailed(slot = slot, diagnostic = flattenDiagnostic(t)))
        return
      }
      send(writer, WorkerResponse.Ready(slot = slot, pid = ProcessHandle.current().pid()))

      serve(host, reader, writer)
      runCatching { host.shutdown() }
    }
    // Robolectric leaves non-daemon threads (the sandbox's `SDK Main Thread`) behind, so a plain
    // return from main would not end the JVM. The parent has its result; leave deliberately.
    Runtime.getRuntime().halt(0)
  }

  private fun serve(host: RobolectricHost, reader: BufferedReader, writer: BufferedWriter) {
    while (true) {
      val line = reader.readLine() ?: return // parent closed the socket / died — exit with it
      val request =
        try {
          workerJson.decodeFromString(WorkerRequest.serializer(), line)
        } catch (t: Throwable) {
          System.err.println("sandbox worker: undecodable request '$line': $t")
          continue
        }
      when (request) {
        is WorkerRequest.Render -> {
          val response =
            try {
              val result =
                host.submit(
                  RenderRequest.Render(id = request.id, payload = request.payload),
                  timeoutMs = request.timeoutMs,
                )
              WorkerResponse.Result(RenderResultDto.of(result))
            } catch (t: Throwable) {
              WorkerResponse.Failed(id = request.id, diagnostic = flattenDiagnostic(t))
            }
          send(writer, response)
        }
        WorkerRequest.Swap -> {
          runCatching { host.swapUserClassLoaders() }
            .onFailure { System.err.println("sandbox worker: classloader swap failed: $it") }
          send(writer, WorkerResponse.Ok)
        }
        WorkerRequest.Shutdown -> {
          send(writer, WorkerResponse.Ok)
          return
        }
      }
    }
  }

  private fun send(writer: BufferedWriter, response: WorkerResponse) {
    writer.write(workerJson.encodeToString(WorkerResponse.serializer(), response))
    writer.write("\n")
    writer.flush()
  }
}
