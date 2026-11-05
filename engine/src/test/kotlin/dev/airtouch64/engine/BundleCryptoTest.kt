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
import kotlin.test.assertTrue

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

    @Test
    fun rejectsTamperedCiphertextWithClearAuthenticationError() = withFixtures { source, bundle, _ ->
        val bytes = bundle.readBytes()
        bytes[bytes.lastIndex - 1] = (bytes[bytes.lastIndex - 1].toInt() xor 1).toByte()
        bundle.writeBytes(bytes)
        val error = assertFailsWith<IllegalArgumentException> {
            BundleCrypto.decryptEntries(bundle, source)
        }
        assertEquals(true, error.message?.contains("does not match this bundle or the bundle is corrupted"))
    }

    @Test
    fun decryptsBundleProducedByPythonTool() {
        val directory = kotlin.io.path.createTempDirectory("at64-python-").toFile()
        val source = File(directory, "source.apk")
        val bundle = File(directory, "bundle.at64")
        val script = """
            import io, json, pathlib, sys, zipfile
            sys.path.insert(0, 'tools')
            from encrypt_bundle import encrypt_bytes
            root = pathlib.Path(sys.argv[1])
            source = root / 'source.apk'
            with zipfile.ZipFile(source, 'w') as z:
                z.writestr('assets/TTPixel.swf', b'swf')
                z.writestr('assets/CABConfig.xml', b'config')
                z.writestr('classes.dex', b'dex')
            manifest = {
                'formatVersion': 2, 'name': 'python-fixture', 'version': '1',
                'source': {'sha256': ['0' * 64], 'requiredEntries': ['assets/TTPixel.swf', 'assets/CABConfig.xml'], 'swfEntry': 'assets/TTPixel.swf'},
                'swf': {'patch': 'patches/swf.bdf', 'sourceSha256': 'a', 'resultSha256': 'b'}, 'steps': []
            }
            raw = io.BytesIO()
            with zipfile.ZipFile(raw, 'w') as z:
                z.writestr('manifest.json', json.dumps(manifest))
                z.writestr('patches/swf.bdf', b'python encrypted payload')
            (root / 'bundle.at64').write_bytes(encrypt_bytes(source, raw.getvalue()))
        """.trimIndent()
        try {
            val currentDir = File(System.getProperty("user.dir"))
            val projectRoot = if (File(currentDir, "tools/encrypt_bundle.py").isFile) currentDir else currentDir.parentFile
            val process = ProcessBuilder("python3", "-c", script, directory.absolutePath)
                .directory(projectRoot)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
            val header = BundleCrypto.readHeader(bundle)
            assertEquals("python-fixture", header.name)
            val (_, entries) = BundleCrypto.decryptEntries(bundle, source)
            assertTrue(entries.getValue("patches/swf.bdf").contentEquals("python encrypted payload".encodeToByteArray()))
        } finally {
            directory.deleteRecursively()
        }
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
