package dev.kptmanager.engine

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.MessageDigest
import java.util.Base64
import java.util.Date

class SigningIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    val sha256Fingerprint: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it) }
}

/** Persistent self signed key, created on first use and reused for every patch. */
object SigningStore {
    private const val ALIAS = "kptmanager"
    private const val KEYSTORE = "signing.p12"
    private const val PASSWORD = "password"

    fun loadOrCreate(directory: File): SigningIdentity {
        directory.mkdirs()
        val keyStoreFile = File(directory, KEYSTORE)
        val password = passwordFile(directory)
        if (!keyStoreFile.isFile || !password.isFile) {
            return create(directory, keyStoreFile, password)
        }
        val chars = Base64.getDecoder().decode(password.readText()).toString(Charsets.UTF_8).toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStoreFile.inputStream().use { keyStore.load(it, chars) }
        val key = keyStore.getKey(ALIAS, chars) as? PrivateKey
            ?: throw IllegalStateException("Signing key missing from $keyStoreFile")
        val certificate = keyStore.getCertificate(ALIAS) as? X509Certificate
            ?: throw IllegalStateException("Signing certificate missing from $keyStoreFile")
        return SigningIdentity(key, certificate)
    }

    private fun passwordFile(directory: File) = File(directory, "signing.pass")

    private fun create(directory: File, keyStoreFile: File, passwordFile: File): SigningIdentity {
        val passwordChars = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .let { Base64.getEncoder().encodeToString(it) }.toCharArray()
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=PS Touch Manager, O=kptmanager")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            Date(now - 86_400_000L),
            Date(now + 30L * 365 * 86_400_000L),
            subject,
            keyPair.public,
        )
        val certificate = JcaX509CertificateConverter().getCertificate(
            builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        )
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry("kptmanager", keyPair.private, passwordChars, arrayOf(certificate))
        keyStoreFile.outputStream().use { keyStore.store(it, passwordChars) }
        passwordFile.writeText(
            Base64.getEncoder().encodeToString(String(passwordChars).toByteArray(Charsets.UTF_8))
        )
        return SigningIdentity(keyPair.private, certificate)
    }
}

object ApkSigning {
    fun sign(input: File, output: File, identity: SigningIdentity) {
        val signer = ApkSigner.SignerConfig.Builder(
            "kptmanager",
            identity.privateKey,
            listOf(identity.certificate),
        ).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(26)
            .setAlignmentPreserved(true)
            .build()
            .sign()
    }

    fun verify(apk: File): List<X509Certificate> {
        val result = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(26).build().verify()
        check(result.isVerified) {
            "APK verification failed: " + result.errors.joinToString("; ") { it.toString() }
        }
        return result.signerCertificates
    }
}
