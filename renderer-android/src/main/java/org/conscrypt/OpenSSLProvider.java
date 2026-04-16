package org.conscrypt;

import java.security.Provider;

public class OpenSSLProvider extends Provider {
    public OpenSSLProvider() {
        super("Conscrypt", 1.0, "Stub Conscrypt Provider");
    }
}
