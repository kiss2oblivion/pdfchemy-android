package com.pdfchemy.desktop.jail

import com.pdfchemy.desktop.engine.*

import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.CMSTypedData
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

/**
 * PDFBox SignatureInterface implementation using BouncyCastle.
 */
class BouncyCastleSignature(
    private val privateKey: PrivateKey,
    private val certificate: Certificate
) : SignatureInterface {
    override fun sign(content: InputStream): ByteArray {
        try {
            val certList = listOf(certificate)
            val certStore = org.bouncycastle.util.Store { certList }

            val signer = CMSSignedDataGenerator()
            val signerInfoBuilder = JcaSignerInfoGeneratorBuilder(
                JcaDigestCalculatorProviderBuilder().build()
            )
            
            val contentSigner = JcaContentSignerBuilder("SHA256WithRSA").build(privateKey)
            signer.addSignerInfoGenerator(
                signerInfoBuilder.build(contentSigner, certificate as X509Certificate)
            )
            signer.addCertificates(certStore)

            val msg: CMSTypedData = CMSProcessableByteArray(content.readBytes())
            val signedData = signer.generate(msg, false)
            
            return signedData.encoded
        } catch (e: Exception) {
            throw RuntimeException("Error signing PDF", e)
        }
    }
}

object PdfCryptoSigner {

    data class KeyPairInfo(
        val keyPair: KeyPair,
        val certificate: X509Certificate
    )

    /**
     * Generates a self-signed RSA-2048 certificate for the given subject (e.g. "CN=John Doe, O=PDFchemy").
     */
    fun generateSelfSignedCertificate(subjectName: String): KeyPairInfo {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()

        val issuer = X500Name(subjectName)
        val subject = issuer
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000) // 1 year validity

        val pubKeyInfo = SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        
        val certBuilder = X509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, subject, pubKeyInfo
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        val certHolder = certBuilder.build(signer)
        val certificate = JcaX509CertificateConverter().getCertificate(certHolder)

        return KeyPairInfo(keyPair, certificate)
    }

    /**
     * Digitally signs the PDF file using the provided private key and certificate.
     */
    fun signPdf(sourceFile: File, destFile: File, keyPairInfo: KeyPairInfo, reason: String = "Signed by PDFchemy", location: String = "Local Device") {
        PDDocument.load(sourceFile, org.apache.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()).use { document ->
            val signature = PDSignature()
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
            signature.name = keyPairInfo.certificate.subjectX500Principal.name
            signature.location = location
            signature.reason = reason
            signature.signDate = Calendar.getInstance()

            // The BouncyCastle signature provider
            val signatureInterface = BouncyCastleSignature(keyPairInfo.keyPair.private, keyPairInfo.certificate)

            // Register signature and write to destination
            document.addSignature(signature, signatureInterface)
            destFile.outputStream().use { os ->
                document.saveIncremental(os)
            }
        }
    }
}
