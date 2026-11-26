package com.radarviewer.ld2450

import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * TLS for the MQTT connection, trusting only the bundled root certificates
 * (res/raw/radar_root_cas.pem: DigiCert Global Root CA / G2 / G3 and ISRG Root X1, the same
 * set the ESP32 uses). EMQX Cloud Serverless is signed by DigiCert Global Root CA.
 */
object Tls {
    fun socketFactory(rootCasPem: InputStream): SSLSocketFactory {
        val certs = rootCasPem.use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
        require(certs.isNotEmpty()) { "no root certificates" }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certs.forEachIndexed { i, cert -> setCertificateEntry("root$i", cert) }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
    }

    fun certificateCount(rootCasPem: InputStream): Int =
        rootCasPem.use { CertificateFactory.getInstance("X.509").generateCertificates(it).size }
}
