/*
 * Copyright © 2013 – 2015 Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package com.etesync.syncadapter;

import android.support.annotation.NonNull;
import android.text.TextUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

public class SSLSocketFactoryCompat extends SSLSocketFactory {

    private SSLSocketFactory delegate;

    // Android 5.0+ (API level21) provides reasonable default settings
    // but it still allows SSLv3
    // https://developer.android.com/reference/javax/net/ssl/SSLSocket.html
    static String protocols[] = null, cipherSuites[] = null;
    static {
        try {
            SSLSocket socket = (SSLSocket)SSLSocketFactory.getDefault().createSocket();
            if (socket != null) {
                /* set reasonable protocol versions */
                // - enable all supported protocols (enables TLSv1.1 and TLSv1.2 on Android <5.0)
                // - remove all SSL versions (especially SSLv3) because they're insecure now
                List<String> protocols = new LinkedList<>();
                for (String protocol : socket.getSupportedProtocols())
                    if (!protocol.toUpperCase(Locale.US).contains("SSL"))
                        protocols.add(protocol);
                App.log.info("Setting allowed TLS protocols: " + TextUtils.join(", ", protocols));
                SSLSocketFactoryCompat.protocols = protocols.toArray(new String[protocols.size()]);

                /* set up reasonable cipher suites */
                // choose known secure cipher suites
                List<String> allowedCiphers = Arrays.asList(
                        // first priority
                        "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
                        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
                        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
                        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                        // second priority
                        "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
                        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
                        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
                        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
                        // compat
                        "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
                        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
                        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
                        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA"
                );
                List<String> availableCiphers = Arrays.asList(socket.getSupportedCipherSuites());
                App.log.info("Available cipher suites: " + TextUtils.join(", ", availableCiphers));

                /* For maximum security, preferredCiphers should *replace* enabled ciphers (thus
                 * disabling ciphers which are enabled by default, but have become unsecure), but for
                 * the security level of DAVdroid and maximum compatibility, disabling of insecure
                 * ciphers should be a server-side task */

                // for the final set of enabled ciphers, take the ciphers enabled by default, ...
                HashSet<String> enabledCiphers = new HashSet<>(Arrays.asList(socket.getEnabledCipherSuites()));
                App.log.info("Cipher suites enabled by default: " + TextUtils.join(", ", enabledCiphers));
                // ... add explicitly allowed ciphers ...
                enabledCiphers.addAll(allowedCiphers);
                // ... and keep only those which are actually available
                enabledCiphers.retainAll(availableCiphers);

                App.log.info("Enabling (only) those TLS ciphers: " + TextUtils.join(", ", enabledCiphers));
                SSLSocketFactoryCompat.cipherSuites = enabledCiphers.toArray(new String[enabledCiphers.size()]);
                socket.close();
            }
        } catch (IOException e) {
            App.log.severe("Couldn't determine default TLS settings");
        }
    }

    public SSLSocketFactoryCompat(@NonNull X509TrustManager trustManager) {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new X509TrustManager[] { trustManager }, null);
            delegate = sslContext.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new AssertionError(); // The system has no TLS. Just give up.
        }
    }

    private void upgradeTLS(SSLSocket ssl) {
        if (protocols != null)
            ssl.setEnabledProtocols(protocols);

        if (cipherSuites != null)
            ssl.setEnabledCipherSuites(cipherSuites);
    }


    @Override
    public String[] getDefaultCipherSuites() {
        return cipherSuites;
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return cipherSuites;
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        Socket ssl = delegate.createSocket(s, host, port, autoClose);
        if (ssl instanceof SSLSocket)
            upgradeTLS((SSLSocket)ssl);
        return ssl;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket ssl = delegate.createSocket(host, port);
        if (ssl instanceof SSLSocket)
            upgradeTLS((SSLSocket)ssl);
        return ssl;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        Socket ssl = delegate.createSocket(host, port, localHost, localPort);
        if (ssl instanceof SSLSocket)
            upgradeTLS((SSLSocket)ssl);
        return ssl;
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        Socket ssl = delegate.createSocket(host, port);
        if (ssl instanceof SSLSocket)
            upgradeTLS((SSLSocket)ssl);
        return ssl;
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        Socket ssl = delegate.createSocket(address, port, localAddress, localPort);
        if (ssl instanceof SSLSocket)
            upgradeTLS((SSLSocket)ssl);
        return ssl;
    }

}
