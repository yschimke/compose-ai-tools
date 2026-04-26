package ee.schimke.composeai.daemon

/**
 * Entry point for the preview daemon JVM — see docs/daemon/DESIGN.md § 4.
 *
 * For B1.1 this is just a smoke-test main: it prints a hello string and exits.
 * The real lifecycle (initialise → render loop → shutdown) lands in B1.5 once
 * the JSON-RPC server and DaemonHost are wired together. The sandbox-holder
 * pattern is exercised by the unit test in `DaemonHostTest`, not by this
 * main(), so that B1.3 can be verified independently of stdio plumbing.
 */
fun main(args: Array<String>) {
  println("compose-ai-tools daemon: hello")
}
