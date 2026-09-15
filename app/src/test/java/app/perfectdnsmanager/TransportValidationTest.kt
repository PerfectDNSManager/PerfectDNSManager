package app.perfectdnsmanager

import app.perfectdnsmanager.util.Http
import app.perfectdnsmanager.util.TlsTrust
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Date

class TransportValidationTest {
    @Test fun rejectsOversizedMetadataBeforeDecoding() {
        val body = "abcdef".toResponseBody("application/json".toMediaType())
        try { Http.readText(body, 5); fail("oversized response accepted") }
        catch (_: IllegalArgumentException) {} finally { body.close() }
    }
    @Test fun preservesUtf8Metadata() {
        assertEquals("réseau", Http.readText("réseau".toResponseBody(), 7))
    }
    @Test fun certificateNameMatchingRejectsWrongAndNestedHosts() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=ignored.example")
        val builder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0), Date(4102444800000L), name, keys.public)
        builder.addExtension(Extension.subjectAlternativeName, false,
            GeneralNames(GeneralName(GeneralName.dNSName, "*.example.test")))
        val provider = BouncyCastleProvider()
        val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(keys.private)))
        certificate.verify(keys.public)
        assertTrue(TlsTrust.hostnameMatches(certificate, "dns.example.test"))
        assertFalse(TlsTrust.hostnameMatches(certificate, "nested.dns.example.test"))
        assertFalse(TlsTrust.hostnameMatches(certificate, "example.test"))
        assertFalse(TlsTrust.hostnameMatches(certificate, "ignored.example"))
        assertFalse(TlsTrust.hostnameMatches(certificate, "evil.test"))
    }
}
