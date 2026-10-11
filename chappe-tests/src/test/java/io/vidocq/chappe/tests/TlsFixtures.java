/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.chappe.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * Test certificates generated at test time with the JDK {@code keytool} (as {@code TlsTest}
 * does; the JDK has no public API to issue a certificate), plus in-memory trust stores and
 * {@link SSLContext}s built from them. Nothing is committed to the repository.
 */
final class TlsFixtures {

    static final char[] PASSWORD = "changeit".toCharArray();

    /** Upper bound on one keytool run, so a stalled child cannot hang the test fork. */
    private static final long KEYTOOL_TIMEOUT_SECONDS = 60;

    private TlsFixtures() {}

    /**
     * A PKCS12 key store holding one self-signed RSA key pair under {@code alias}.
     *
     * @param san the subjectAltName extension value (for example {@code "ip:127.0.0.1"}), or
     *     {@code null} for none
     */
    static KeyStore selfSigned(String alias, String dname, String san) throws Exception {
        Path file = Files.createTempFile("chappe-" + alias + "-", ".p12");
        Files.delete(file); // keytool refuses to overwrite an existing file
        try {
            // The keytool of the JDK running the tests, not whichever one PATH resolves.
            String keytool =
                    Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            var command = new ArrayList<>(List.of(
                    keytool,
                    "-genkeypair",
                    "-alias",
                    alias,
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-validity",
                    "1",
                    "-dname",
                    dname,
                    "-storetype",
                    "PKCS12",
                    "-keystore",
                    file.toString(),
                    "-storepass",
                    "changeit",
                    "-keypass",
                    "changeit"));
            if (san != null) {
                command.add("-ext");
                command.add("san=" + san);
            }
            // stdin from the null device: any unexpected prompt reads EOF instead of blocking.
            var nullDevice = new File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
            var process = new ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.from(nullDevice))
                    .redirectErrorStream(true)
                    .start();
            // keytool prints little; reading after the exit cannot fill the pipe and stall it.
            if (!process.waitFor(KEYTOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("keytool did not finish within " + KEYTOOL_TIMEOUT_SECONDS + " s");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), "keytool failed: " + output);

            var keyStore = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(file)) {
                keyStore.load(in, PASSWORD);
            }
            return keyStore;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    static X509Certificate certificate(KeyStore keyStore, String alias) throws Exception {
        return (X509Certificate) keyStore.getCertificate(alias);
    }

    /** An in-memory trust store holding exactly {@code certificates}. */
    static KeyStore trustStore(X509Certificate... certificates) throws Exception {
        var trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        for (int i = 0; i < certificates.length; i++) {
            trustStore.setCertificateEntry("trusted-" + i, certificates[i]);
        }
        return trustStore;
    }

    /**
     * A TLS context presenting the key pair of {@code keys} ({@code null}: present none) and
     * trusting exactly the certificates of {@code trust}.
     */
    static SSLContext context(KeyStore keys, KeyStore trust) throws Exception {
        KeyManager[] keyManagers = null;
        if (keys != null) {
            var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keys, PASSWORD);
            keyManagers = kmf.getKeyManagers();
        }
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        var context = SSLContext.getInstance("TLS");
        context.init(keyManagers, tmf.getTrustManagers(), null);
        return context;
    }

    /**
     * A TLS client context that always presents the key pair {@code alias} of {@code keys}, even
     * when the server's CertificateRequest does not list its issuer (the JDK key manager would
     * then send no certificate). Used to put an untrusted certificate on the wire.
     */
    static SSLContext contextAlwaysOffering(KeyStore keys, String alias, KeyStore trust) throws Exception {
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, PASSWORD);
        var delegate = (X509ExtendedKeyManager) kmf.getKeyManagers()[0];
        var forcing = new X509ExtendedKeyManager() {
            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return new String[] {alias};
            }

            @Override
            public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                return alias;
            }

            @Override
            public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
                return alias;
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return delegate.getServerAliases(keyType, issuers);
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return delegate.chooseServerAlias(keyType, issuers, socket);
            }

            @Override
            public X509Certificate[] getCertificateChain(String name) {
                return delegate.getCertificateChain(name);
            }

            @Override
            public PrivateKey getPrivateKey(String name) {
                return delegate.getPrivateKey(name);
            }
        };
        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        var context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[] {forcing}, tmf.getTrustManagers(), null);
        return context;
    }
}
