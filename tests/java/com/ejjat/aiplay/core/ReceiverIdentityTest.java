package com.ejjat.aiplay.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.util.Arrays;

/**
 * Guards the receiver's TLS identity (app/res/raw/receiver_identity.p12).
 *
 * <p>A keystore written by a current JDK's default {@code keytool} is encrypted with PBES2
 * (PBKDF2 + AES). Android before roughly API 31 cannot read it: the receiver then fails to start
 * with {@code SecretKeyFactory 1.2.840.113549.1.5.12 not available}. That happened on an API 30
 * emulator while an API 34 one worked, and aftermarket head units often run older Android.
 * Regenerate with {@code keytool -J-Dkeystore.pkcs12.legacy ...}.</p>
 */
public class ReceiverIdentityTest {

    private static final byte[] PBES2_OID = {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7,
            0x0D, 0x01, 0x05, 0x0D};

    private static File identity() {
        String path = System.getProperty("aiplay.test.identity");
        if (path == null || !new File(path).isFile()) {
            fail("aiplay.test.identity not set — run through tools/test.sh");
        }
        return new File(path);
    }

    private static byte[] read(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] data = new byte[(int) file.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            return data;
        } finally {
            in.close();
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(haystack, i, i + needle.length), needle)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void identityIsNotEncryptedWithPbes2() throws Exception {
        assertFalse("receiver_identity.p12 uses PBES2, which Android < 12 cannot read; "
                        + "regenerate with keytool -J-Dkeystore.pkcs12.legacy",
                contains(read(identity()), PBES2_OID));
    }

    @Test
    public void identityLoadsWithTheDocumentedPassword() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        FileInputStream in = new FileInputStream(identity());
        try {
            store.load(in, "aiplay".toCharArray());
        } finally {
            in.close();
        }
        assertTrue(store.isKeyEntry("aiplay"));
    }
}
