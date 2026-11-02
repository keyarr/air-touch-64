package dev.airtouch64.engine

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BundleCryptoTest {
    private val json = Json

    @Test
    fun decryptsBundleUsingSourceContents() = withFixtures { source, bundle, header ->
        assertEquals(header, BundleCrypto.readHeader(bundle))
        val (manifest, entries) = BundleCrypto.decryptEntries(bundle, source)
        assertEquals("sample", manifest.name)
        assertEquals("secret patch", entries.getValue("patches/swf.bdf").decodeToString())
    }

    @Test
    fun rejectsBundleWithWrongSource() = withFixtures { _, bundle, _ ->
        val wrong = createSource(byteArrayOf(9, 8, 7))
        try {
            val error = assertFailsWith<Exception> { BundleCrypto.decryptEntries(bundle, wrong) }
            assert(error.message.orEmpty().contains("does not match"))
        } finally { wrong.delete() }
    }

    private fun withFixtures(block: (File, File, BundleHeader) -> Unit) {
        val source = createSource(byteArrayOf(1, 2, 3, 4))
        val bundle = File.createTempFile("sample", ".at64")
        val sourceSpec = SourceSpec(listOf("unused"), listOf("assets/TTPixel.swf", "assets/CABConfig.xml"), "assets/TTPixel.swf")
        val header = BundleHeader(2, "sample", "1", sourceSpec)
        val manifest = BundleManifest(1, "sample", "1", sourceSpec,
            SwfPatch("patches/swf.bdf", "input", "output"))
        val zipBytes = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(json.encodeToString(manifest).encodeToByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("patches/swf.bdf")); zip.write("secret patch".encodeToByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        val salt = ByteArray(16) { it.toByte() }; val iv = ByteArray(12) { (it + 16).toByte() }
        val headerBytes = json.encodeToString(header).encodeToByteArray()
        val digest = MessageDigest.getInstance("SHA-512")
        java.util.zip.ZipFile(source).use { archive ->
            for (name in listOf("assets/TTPixel.swf", "assets/CABConfig.xml", "classes.dex")) {
                val data = archive.getInputStream(archive.getEntry(name)).use { it.readBytes() }
                digest.update(java.nio.ByteBuffer.allocate(8).putLong(data.size.toLong()).array()); digest.update(data)
            }
        }
        val spec = PBEKeySpec(digest.digest().joinToString("") { "%02x".format(it) }.toCharArray(), salt, 120_000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); cipher.updateAAD(headerBytes)
        DataOutputStream(bundle.outputStream()).use { out ->
            out.write("AT64".encodeToByteArray()); out.writeInt(2); out.write(salt); out.write(iv); out.writeInt(headerBytes.size)
            out.write(headerBytes); out.write(cipher.doFinal(zipBytes))
        }
        try { block(source, bundle, header) } finally { source.delete(); bundle.delete() }
    }

    private fun createSource(swf: ByteArray): File = File.createTempFile("source", ".apk").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            listOf("assets/TTPixel.swf" to swf, "assets/CABConfig.xml" to byteArrayOf(5), "classes.dex" to byteArrayOf(6)).forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry()
            }
        }
    }
}
