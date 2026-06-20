package ee.schimke.composeai.clients

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionLinkTest {

  @Test
  fun parsesCustomSchemePreview() {
    val link =
      SessionLink.parse(
        "composeai://session?host=192.168.1.5&port=7341&preview=com.x.Foo&token=abc123"
      )
    assertThat(link)
      .isEqualTo(SessionLink("192.168.1.5", 7341, "abc123", SessionTarget.Preview("com.x.Foo")))
  }

  @Test
  fun parsesCustomSchemeBundle() {
    val link =
      SessionLink.parse(
        "composeai://session?host=h&port=7341&token=t&bundle=https%3A%2F%2Fcdn%2Fa.bundle&preview=Foo"
      )
    assertThat(link!!.target)
      .isEqualTo(SessionTarget.Bundle("https://cdn/a.bundle", previewId = "Foo"))
  }

  @Test
  fun customSchemeHonoursSecureFlagAndDefaultPort() {
    val link = SessionLink.parse("composeai://session?host=h&preview=p&token=t&secure=true")
    assertThat(link!!.secure).isTrue()
    assertThat(link.port).isEqualTo(443)
  }

  @Test
  fun parsesServeViewerUrlAsPreview() {
    val link = SessionLink.parse("http://10.0.0.2:7341/p/com.x.Foo%24Bar?token=tok")
    assertThat(link)
      .isEqualTo(SessionLink("10.0.0.2", 7341, "tok", SessionTarget.Preview("com.x.Foo\$Bar")))
  }

  @Test
  fun parsesHttpsViewerAsSecure() {
    val link = SessionLink.parse("https://host/p/Foo?token=tok")
    assertThat(link!!.secure).isTrue()
    assertThat(link.port).isEqualTo(443)
  }

  @Test
  fun parsesRawWebSocketUrlPreview() {
    val link = SessionLink.parse("ws://host:9000/ws/Foo?token=tok")
    assertThat(link).isEqualTo(SessionLink("host", 9000, "tok", SessionTarget.Preview("Foo")))
  }

  @Test
  fun parsesRawWebSocketBundleUrl() {
    val link = SessionLink.parse("ws://host:9000/ws/bundle?src=file%3A%2F%2Fb.zip&token=tok")
    assertThat(link!!.target).isEqualTo(SessionTarget.Bundle("file://b.zip"))
  }

  @Test
  fun parseOpenReadsHostlessBundleRequest() {
    val open =
      SessionLink.parseOpen(
        "composeai://open?bundle=https%3A%2F%2Fcdn%2Fx.bundle&token=tok&preview=P"
      )
    assertThat(open)
      .isEqualTo(SessionLink.OpenRequest(SessionTarget.Bundle("https://cdn/x.bundle", "P"), "tok"))
    // A host-bearing link is NOT an open request.
    assertThat(SessionLink.parseOpen("composeai://session?host=h&preview=p&token=t")).isNull()
    // ...and an open link is not a fully-addressed link.
    assertThat(SessionLink.parse("composeai://open?bundle=x&token=t")).isNull()
  }

  @Test
  fun forBundleBuildsAServerPinnedBundleLink() {
    val link = SessionLink.forBundle("srv", 8080, "tok", "file://b.zip", previewId = "P")
    assertThat(link.target).isEqualTo(SessionTarget.Bundle("file://b.zip", "P"))
    assertThat(link.host).isEqualTo("srv")
  }

  @Test
  fun rejectsLinksMissingAToken() {
    assertThat(SessionLink.parse("composeai://session?host=h&preview=p")).isNull()
    assertThat(SessionLink.parse("http://host/p/Foo")).isNull()
  }

  @Test
  fun rejectsLinksWithNeitherPreviewNorBundle() {
    assertThat(SessionLink.parse("composeai://session?host=h&token=t")).isNull()
  }

  @Test
  fun rejectsUnknownSchemesAndGarbage() {
    assertThat(SessionLink.parse("ftp://host/p/Foo?token=t")).isNull()
    assertThat(SessionLink.parse("not a uri at all")).isNull()
    assertThat(SessionLink.parse("")).isNull()
    assertThat(SessionLink.parse(null)).isNull()
  }

  @Test
  fun webSocketUrlPreviewPercentEncodesPreviewAndToken() {
    val link = SessionLink("host", 7341, "a/b+c", SessionTarget.Preview("com.x.Foo\$Bar"))
    assertThat(link.webSocketUrl()).isEqualTo("ws://host:7341/ws/com.x.Foo%24Bar?token=a%2Fb%2Bc")
  }

  @Test
  fun webSocketUrlBundleUsesBundleEntrypoint() {
    val link = SessionLink("host", 7341, "tok", SessionTarget.Bundle("https://cdn/a.bundle", "Foo"))
    assertThat(link.webSocketUrl())
      .isEqualTo("ws://host:7341/ws/bundle?src=https%3A%2F%2Fcdn%2Fa.bundle&token=tok&preview=Foo")
  }

  @Test
  fun secureWebSocketUrlUsesWss() {
    assertThat(SessionLink("h", 443, "t", SessionTarget.Preview("P"), secure = true).webSocketUrl())
      .isEqualTo("wss://h:443/ws/P?token=t")
  }

  @Test
  fun toUriRoundTripsPreviewAndBundle() {
    val preview =
      SessionLink("h", 7341, "tok+en", SessionTarget.Preview("com.x.Foo\$Bar"), secure = true)
    assertThat(SessionLink.parse(preview.toUri())).isEqualTo(preview)

    val bundle = SessionLink("h", 7341, "t", SessionTarget.Bundle("https://cdn/a.bundle", "P"))
    assertThat(SessionLink.parse(bundle.toUri())).isEqualTo(bundle)
  }

  @Test
  fun constructorRejectsInvalidFields() {
    assertThat(runCatching { SessionLink("", 7341, "t", SessionTarget.Preview("p")) }.isFailure)
      .isTrue()
    assertThat(runCatching { SessionLink("h", 0, "t", SessionTarget.Preview("p")) }.isFailure)
      .isTrue()
    assertThat(runCatching { SessionTarget.Preview("") }.isFailure).isTrue()
    assertThat(runCatching { SessionTarget.Bundle("") }.isFailure).isTrue()
  }
}
