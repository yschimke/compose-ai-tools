package ee.schimke.composeai.renderer

import org.junit.Test
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals

class ConscryptStubTest {
    @Test
    fun testOpenSSLProviderInstantiation() {
        val provider = org.conscrypt.OpenSSLProvider()
        assertNotNull(provider)
        assertEquals("Conscrypt", provider.name)
    }

    @Test
    fun testOkHostnameVerifierInstantiation() {
        val verifier = org.conscrypt.OkHostnameVerifier.INSTANCE
        assertNotNull(verifier)
    }
}
