package com.pdfchemy.app.jail.engines

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface
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
 * PDFBox-Android SignatureInterface implementation using BouncyCastle.
 */
class AndroidBouncyCastleSignature(
    private val privateKey: PrivateKey,
    private val certificate: Certificate
) : SignatureInterface {
    override fun sign(content: InputStream): ByteArray {
        var tempFile: File? = null
        try {
            val certList = listOf(certificate)
            val certStore = org.bouncycastle.cert.jcajce.JcaCertStore(certList)

            val signer = CMSSignedDataGenerator()
            val signerInfoBuilder = JcaSignerInfoGeneratorBuilder(
                JcaDigestCalculatorProviderBuilder().build()
            )

            val contentSigner = JcaContentSignerBuilder("SHA256WithRSA").build(privateKey)
            signer.addSignerInfoGenerator(
                signerInfoBuilder.build(contentSigner, certificate as X509Certificate)
            )
            signer.addCertificates(certStore)

            tempFile = com.pdfchemy.app.jail.JailScratch.createTempFile("pdf_sign_", ".tmp")
            com.pdfchemy.app.jail.boundedFileOutput(tempFile).use { os ->
                content.copyTo(os)
            }

            val msg: CMSTypedData = object : CMSTypedData {
                override fun getContentType() = org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.data
                override fun getContent(): Any = requireNotNull(tempFile)
                override fun write(output: java.io.OutputStream) {
                    com.pdfchemy.app.jail.CapabilityIo.input(requireNotNull(tempFile)).use { it.copyTo(output) }
                }
            }
            val signedData = signer.generate(msg, false)

            return signedData.encoded
        } catch (e: Exception) {
            throw RuntimeException("Error signing PDF", e)
        } finally {
            tempFile?.delete()
        }
    }
}

object AndroidPdfCryptoSigner {

    data class KeyPairInfo(
        val keyPair: KeyPair,
        val certificate: X509Certificate
    )

    /**
     * Generates an ephemeral self-signed RSA-2048 certificate. The supplied name
     * is one literal CN value; this certificate establishes no trusted identity.
     */
    fun generateSelfSignedCertificate(subjectName: String): KeyPairInfo {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()

        val issuer = org.bouncycastle.asn1.x500.X500NameBuilder(org.bouncycastle.asn1.x500.style.BCStyle.INSTANCE)
            .addRDN(org.bouncycastle.asn1.x500.style.BCStyle.CN, subjectName)
            .addRDN(org.bouncycastle.asn1.x500.style.BCStyle.O, "PDFchemy")
            .addRDN(org.bouncycastle.asn1.x500.style.BCStyle.C, "US").build()
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
        com.pdfchemy.app.jail.CapabilityIo.input(sourceFile).use { PDDocument.load(it, com.pdfchemy.app.jail.JailMemory.settings()) }.use { document ->
            val signature = PDSignature()
            signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
            signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
            signature.name = keyPairInfo.certificate.subjectX500Principal.name
            signature.location = location
            signature.reason = reason
            signature.signDate = Calendar.getInstance()

            // The BouncyCastle signature provider
            val signatureInterface = AndroidBouncyCastleSignature(keyPairInfo.keyPair.private, keyPairInfo.certificate)

            // Register signature and write to destination
            document.addSignature(signature, signatureInterface)
            com.pdfchemy.app.jail.boundedFileOutput(destFile).use { os ->
                document.saveIncremental(os)
            }
        }
    }
}
