package ee.schimke.composeai.simulator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.junit.Before;
import org.junit.Test;

public class AndroidKeyStoreProviderTest {

  /** The shape of {@code KeyGenParameterSpec} the provider reads, without the framework. */
  public static final class Spec implements AlgorithmParameterSpec {
    private final String alias;
    private final int size;

    Spec(String alias, int size) {
      this.alias = alias;
      this.size = size;
    }

    public String getKeystoreAlias() {
      return alias;
    }

    public int getKeySize() {
      return size;
    }

    public AlgorithmParameterSpec getAlgorithmParameterSpec() {
      return null;
    }
  }

  @Before
  public void install() {
    AndroidKeyStoreProvider.install();
    AndroidKeyStoreProvider.forgetAll();
  }

  @Test
  public void anAesKeyIsKeptUnderItsAliasAndEncrypts() throws Exception {
    KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
    generator.init(new Spec("master", 256));
    SecretKey key = generator.generateKey();
    assertEquals("AES", key.getAlgorithm());
    assertEquals(32, key.getEncoded().length);

    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    assertTrue(store.containsAlias("master"));
    SecretKey stored = (SecretKey) store.getKey("master", null);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, stored);
    byte[] sealed = cipher.doFinal("secret".getBytes(StandardCharsets.UTF_8));
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, cipher.getIV()));
    assertArrayEquals("secret".getBytes(StandardCharsets.UTF_8), cipher.doFinal(sealed));

    store.deleteEntry("master");
    assertFalse(store.containsAlias("master"));
  }

  @Test
  public void anHmacKeyIsAnHmacKey() throws Exception {
    KeyGenerator generator = KeyGenerator.getInstance("HmacSHA256", "AndroidKeyStore");
    generator.init(new Spec("mac", -1));
    SecretKey key = generator.generateKey();
    assertEquals("HmacSHA256", key.getAlgorithm());
    Mac.getInstance("HmacSHA256").init(key);
  }

  @Test
  public void anEcPairGetsASelfSignedCertificate() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
    generator.initialize(new Spec("signing", -1));
    KeyPair pair = generator.generateKeyPair();

    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    X509Certificate certificate = (X509Certificate) store.getCertificate("signing");
    assertNotNull(certificate);
    assertEquals("CN=fake", certificate.getSubjectX500Principal().getName());
    assertEquals(pair.getPublic(), certificate.getPublicKey());
    certificate.verify(pair.getPublic());
    KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) store.getEntry("signing", null);
    PrivateKey key = entry.getPrivateKey();

    Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(key);
    signer.update(new byte[] {1, 2, 3});
    byte[] signature = signer.sign();
    Signature verifier = Signature.getInstance("SHA256withECDSA");
    verifier.initVerify(certificate);
    verifier.update(new byte[] {1, 2, 3});
    assertTrue(verifier.verify(signature));
  }

  @Test
  public void anRsaPairHasItsSizeAndCertificate() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
    generator.initialize(new Spec("rsa", 2048));
    KeyPair pair = generator.generateKeyPair();
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    X509Certificate certificate = (X509Certificate) store.getCertificate("rsa");
    certificate.verify(pair.getPublic());
    assertEquals(2048, ((java.security.interfaces.RSAPublicKey) pair.getPublic()).getModulus().bitLength());
  }
}
