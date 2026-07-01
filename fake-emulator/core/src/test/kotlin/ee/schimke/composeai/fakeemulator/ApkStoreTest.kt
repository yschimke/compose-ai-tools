package ee.schimke.composeai.fakeemulator

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApkStoreTest {
  private val store = ApkStore()

  @Test
  fun `install records the parsed package and transport`() {
    val record = store.install(ApkFixtures.apk("com.example.app"), ApkStore.Transport.STREAMING)
    assertThat(record.packageName).isEqualTo("com.example.app")
    assertThat(record.transport).isEqualTo(ApkStore.Transport.STREAMING)
    assertThat(store.findByPackage("com.example.app")).isNotNull()
    assertThat(store.installed()).hasSize(1)
  }

  @Test
  fun `legacy push then take yields the pushed bytes exactly once`() {
    val apk = ApkFixtures.apk("com.example.app")
    store.putPushedFile("/data/local/tmp/x.apk", apk)
    assertThat(store.takePushedFile("/data/local/tmp/x.apk")).isEqualTo(apk)
    // Consumed — a second take is empty.
    assertThat(store.takePushedFile("/data/local/tmp/x.apk")).isNull()
  }

  @Test
  fun `session flow assembles split writes into one committed install`() {
    val apk = ApkFixtures.apk("com.example.split")
    val id = store.createSession()
    // Split the APK across two writes to prove they're concatenated.
    store.writeSession(id, apk.copyOfRange(0, 10))
    store.writeSession(id, apk.copyOfRange(10, apk.size))
    val record = store.commitSession(id)
    assertThat(record).isNotNull()
    assertThat(record!!.packageName).isEqualTo("com.example.split")
    assertThat(record.transport).isEqualTo(ApkStore.Transport.SESSION)
    // The session is consumed after commit.
    assertThat(store.commitSession(id)).isNull()
  }

  @Test
  fun `abandoned session cannot be committed`() {
    val id = store.createSession()
    store.writeSession(id, ApkFixtures.apk("com.example.app"))
    store.abandonSession(id)
    assertThat(store.commitSession(id)).isNull()
  }

  @Test
  fun `installing the same package replaces the prior record`() {
    store.install(ApkFixtures.apk("com.example.app"), ApkStore.Transport.LEGACY_PUSH)
    store.install(ApkFixtures.apk("com.example.app"), ApkStore.Transport.STREAMING)
    assertThat(store.installed()).hasSize(1)
    assertThat(store.findByPackage("com.example.app")!!.transport)
      .isEqualTo(ApkStore.Transport.STREAMING)
  }
}
