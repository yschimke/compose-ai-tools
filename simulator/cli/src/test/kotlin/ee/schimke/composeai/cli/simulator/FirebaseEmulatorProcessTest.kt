package ee.schimke.composeai.cli.simulator

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirebaseEmulatorProcessTest {

  @Test
  fun parsesFirebaseJsonAndFirebaserc() {
    val dir = Files.createTempDirectory("firebase-emu-test").toFile()
    try {
      val firebaseJson =
        File(dir, "firebase.json").apply {
          writeText(
            """
            {
              "emulators": {
                "hub": { "host": "0.0.0.0", "port": 4455 },
                "auth": { "port": 9099 },
                "firestore": { "port": 8080 }
              }
            }
            """
              .trimIndent(),
          )
        }
      File(dir, ".firebaserc").writeText("""{"projects":{"default":"my-rc-project"}}""")

      assertEquals(
        firebaseJson.absoluteFile,
        FirebaseEmulatorProcess.resolveFirebaseJsonFile(dir.path),
      )
      assertEquals(
        firebaseJson.absoluteFile,
        FirebaseEmulatorProcess.resolveFirebaseJsonFile(firebaseJson.path),
      )
      assertNull(FirebaseEmulatorProcess.resolveFirebaseJsonFile("auto"))
      assertNull(FirebaseEmulatorProcess.resolveFirebaseJsonFile("auth=9099,firestore=8080"))

      val parsed = FirebaseEmulatorProcess.parseFirebaseJson(firebaseJson)
      assertEquals("127.0.0.1", parsed.hubHost)
      assertEquals(4455, parsed.hubPort)
      assertEquals(setOf("auth", "firestore"), parsed.configuredServices)
      assertEquals(
        "my-rc-project",
        FirebaseEmulatorProcess.readDefaultProjectFromFirebaserc(firebaseJson),
      )

      val cmd = FirebaseEmulatorProcess.buildStartCommand("firebase", firebaseJson, "demo-test")
      assertEquals(
        listOf(
          "firebase",
          "emulators:start",
          "--config",
          firebaseJson.absolutePath,
          "--project",
          "demo-test",
        ),
        cmd,
      )
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun reusesAlreadyRunningEmulatorHubWithoutSpawningProcess() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/emulators") { exchange ->
      val body =
        """{"auth":{"host":"127.0.0.1","port":9099},"firestore":{"host":"127.0.0.1","port":8080}}"""
          .toByteArray(StandardCharsets.UTF_8)
      exchange.sendResponseHeaders(200, body.size.toLong())
      exchange.responseBody.write(body)
      exchange.close()
    }
    server.start()
    val dir = Files.createTempDirectory("firebase-hub-reuse").toFile()
    try {
      val port = server.address.port
      val firebaseJson =
        File(dir, "firebase.json").apply {
          writeText("""{"emulators":{"hub":{"port":$port},"auth":{"port":9099}}}""")
        }
      // Passing a non-existent binary proves resolve() reused the running Hub without spawning!
      FirebaseEmulatorProcess.resolve(
          spec = firebaseJson.absolutePath,
          projectId = "reuse-project",
          firebaseBinary = "/nonexistent/firebase-binary",
        )
        .use { resolved ->
          assertEquals("reuse-project", resolved.projectId)
          assertTrue(resolved.emulatorSpec.contains("\"auth\""))
          assertTrue(resolved.emulatorSpec.contains("9099"))
          assertTrue(resolved.emulatorSpec.contains("\"firestore\""))
        }
    } finally {
      server.stop(0)
      dir.deleteRecursively()
    }
  }
}
