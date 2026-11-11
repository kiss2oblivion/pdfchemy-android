package com.pdfchemy.app.jail.engines

import org.bouncycastle.asn1.ASN1String
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.junit.Assert.*
import org.junit.Test

class SignerIdentitySecurityTest {
    @Test fun p12KeystoreImportExtractsPrivateKeyAndCertChain() {
        val keyInfo = AndroidPdfCryptoSigner.generateSelfSignedCertificate("Corporate Signing Authority")
        val password = "TestPassword123".toCharArray()

        // Create an in-memory PKCS#12 keystore
        val ks = java.security.KeyStore.getInstance("PKCS12")
        ks.load(null, password)
        val chain = arrayOf<java.security.cert.Certificate>(keyInfo.certificate)
        ks.setKeyEntry("client_alias", keyInfo.keyPair.private, password, chain)

        val baos = java.io.ByteArrayOutputStream()
        ks.store(baos, password)
        val p12Bytes = baos.toByteArray()

        // Load credentials via AndroidPdfCryptoSigner
        val credentials = AndroidPdfCryptoSigner.loadPkcs12Credentials(p12Bytes, password)
        assertNotNull(credentials.privateKey)
        assertNotNull(credentials.certificate)
        assertEquals("Corporate Signing Authority", (X500Name.getInstance(credentials.certificate.subjectX500Principal.encoded).getRDNs(BCStyle.CN).single().first.value as ASN1String).string)
        assertEquals(1, credentials.certificateChain.size)
    }

    @Test fun hostileNameRemainsOneLiteralCommonNameInAnEphemeralSelfSignedCertificate() {
        val supplied = "Alice, O=Injected+OU=Root\\\"\nCN=Someone Else"
        val first = AndroidPdfCryptoSigner.generateSelfSignedCertificate(supplied)
        val subject = X500Name.getInstance(first.certificate.subjectX500Principal.encoded)
        assertEquals(3, subject.rdNs.size)
        assertEquals(supplied, (subject.getRDNs(BCStyle.CN).single().first.value as ASN1String).string)
        assertEquals("PDFchemy", (subject.getRDNs(BCStyle.O).single().first.value as ASN1String).string)
        assertEquals(0, subject.getRDNs(BCStyle.OU).size)
        assertEquals(first.certificate.subjectX500Principal, first.certificate.issuerX500Principal)
        first.certificate.verify(first.keyPair.public)
        val second = AndroidPdfCryptoSigner.generateSelfSignedCertificate(supplied)
        assertFalse(first.keyPair.public.encoded.contentEquals(second.keyPair.public.encoded))
    }
}
