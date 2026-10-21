package ee.schimke.composeai.bundle.coordinates

import java.lang.reflect.Proxy
import java.security.cert.Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.CertificatePinner.Companion.sha256Hash
import okhttp3.tls.HeldCertificate

class ForbiddenCertificatesHostnameVerifierTest {
  private val root = HeldCertificate.Builder().certificateAuthority(0).build()
  private val leaf =
    HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("example.com").build()
  private val session = sessionWith(leaf.certificate, root.certificate)
  private val acceptAll = HostnameVerifier { _, _ -> true }

  @Test
  fun `a chain with no forbidden certificate passes`() {
    assertTrue(
      ForbiddenCertificatesHostnameVerifier(delegate = acceptAll).verify("example.com", session)
    )
  }

  @Test
  fun `a forbidden certificate anywhere in the chain is refused`() {
    val verifier =
      ForbiddenCertificatesHostnameVerifier(setOf(root.certificate.sha256Hash()), acceptAll)
    assertFalse(verifier.verify("example.com", session))
  }

  @Test
  fun `the hostname check still applies`() {
    assertFalse(ForbiddenCertificatesHostnameVerifier().verify("other.example", session))
    assertTrue(ForbiddenCertificatesHostnameVerifier().verify("example.com", session))
  }

  @Test
  fun `the CVE-2026-111943 denylist holds all eight intermediates`() {
    assertEquals(8, ForbiddenCertificatesHostnameVerifier.CVE_2026_111943.size)
    assertTrue(ForbiddenCertificatesHostnameVerifier.CVE_2026_111943.all { it.size == 32 })
  }

  private fun sessionWith(vararg chain: Certificate): SSLSession =
    Proxy.newProxyInstance(SSLSession::class.java.classLoader, arrayOf(SSLSession::class.java)) {
      _,
      method,
      _ ->
      when (method.name) {
        "getPeerCertificates" -> arrayOf(*chain)
        else -> throw UnsupportedOperationException(method.name)
      }
    } as SSLSession
}
