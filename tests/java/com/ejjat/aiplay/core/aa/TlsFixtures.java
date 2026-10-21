package com.ejjat.aiplay.core.aa;

import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Test-only TLS pieces. The "phone" is a server-mode {@link SSLEngine} that <em>requires</em> a
 * client certificate, which is how Android Auto's phone side behaves toward the head unit, so a
 * head unit that fails to present its certificate fails the test.
 *
 * <p>Not named {@code *Test}, so {@code tools/test.sh} does not run it as a test class.</p>
 */
final class TlsFixtures {

    private TlsFixtures() {
    }

    static String property(String name) {
        String value = System.getProperty(name);
        if (value == null || !new File(value).isFile()) {
            fail(name + " not set — run through tools/test.sh");
        }
        return value;
    }

    static KeyStore load(String path, String password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        InputStream in = new FileInputStream(path);
        try {
            store.load(in, password.toCharArray());
        } finally {
            in.close();
        }
        return store;
    }

    /** The head unit's side: built from the real committed identity, like the receiver does. */
    static SSLContext headUnitContext() throws Exception {
        KeyStore identity = load(property("aiplay.test.identity"), "aiplay");
        return AaCryptor.clientContext(identity, "aiplay".toCharArray());
    }

    /** The phone's side: server-mode engine, certificate required from the head unit. */
    static SSLEngine phoneEngine() throws Exception {
        KeyStore keys = load(property("aiplay.test.keystore"), "aiplay");
        KeyManagerFactory factory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, "aiplay".toCharArray());
        // A real phone's CertificateRequest lists its own issuer. A self-signed head-unit certificate
        // never matches that list, so a key manager that filters by issuer sends no certificate at
        // all. Reproduce that: advertise the phone's own certificate as the only accepted issuer.
        final X509Certificate phoneCa = (X509Certificate) keys.getCertificate("aiplay");
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[]{phoneCa};
            }
        }}, null);
        SSLEngine engine = context.createSSLEngine();
        engine.setUseClientMode(false);
        engine.setNeedClientAuth(true);
        engine.setEnabledProtocols(new String[]{"TLSv1.2"});
        return engine;
    }
}
