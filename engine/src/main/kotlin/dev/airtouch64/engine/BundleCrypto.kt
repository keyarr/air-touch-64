package dev.airtouch64.engine

import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

object BundleCrypto {
    private const val MAGIC = "AT64"
    private const val VERSION = 2
    private const val MAX_HEADER = 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun readHeader(bundle: File): BundleHeader = bundle.inputStream().use { raw ->
        val input = DataInputStream(BufferedInputStream(raw))
        check(ByteArray(4).also(input::readFully).decodeToString() == MAGIC) { "Invalid AT64 magic" }
        check(input.readInt() == VERSION) { "Unsupported bundle format" }
        input.skipFully(16 + 12)
        val length = input.readInt()
        require(length in 1..MAX_HEADER) { "Invalid bundle header length" }
        val bytes = ByteArray(length).also(input::readFully)
        json.decodeFromString<BundleHeader>(bytes.decodeToString()).also {
            require(it.formatVersion == VERSION) { "Unsupported bundle header version" }
        }
    }

    fun decryptEntries(bundle: File, sourceApk: File): Pair<BundleManifest, Map<String, ByteArray>> {
        val input = DataInputStream(BufferedInputStream(bundle.inputStream()))
        try {
            check(ByteArray(4).also(input::readFully).decodeToString() == MAGIC) { "Invalid AT64 magic" }
            check(input.readInt() == VERSION) { "Unsupported bundle format" }
            val salt = ByteArray(16).also(input::readFully)
            val iv = ByteArray(12).also(input::readFully)
            val length = input.readInt()
            require(length in 1..MAX_HEADER) { "Invalid bundle header length" }
            val header = ByteArray(length).also(input::readFully)
            json.decodeFromString<BundleHeader>(header.decodeToString())
            val sourceHeader = json.decodeFromString<BundleHeader>(header.decodeToString())
            require(sourceHeader.formatVersion == VERSION) { "Unsupported bundle header version" }
            val key = deriveKey(sourceApk, salt, sourceHeader.source.swfEntry)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            val plaintext = try {
                CipherInputStream(input, cipher).use { it.readBytes() }
            } catch (error: Exception) {
                if (generateSequence(error as Throwable?) { it.cause }.any { it is AEADBadTagException }) {
                    throw IllegalArgumentException("Source APK does not match this bundle or the bundle is corrupted", error)
                }
                throw error
            }
            val entries = linkedMapOf<String, ByteArray>()
            ZipInputStream(plaintext.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                    zip.closeEntry()
                }
            }
            val manifest = entries["manifest.json"]?.let { json.decodeFromString<BundleManifest>(it.decodeToString()) }
                ?: error("Bundle has no manifest.json")
            return manifest to entries
        } catch (error: AEADBadTagException) {
            throw IllegalArgumentException("Source APK does not match this bundle or the bundle is corrupted", error)
        } finally {
            input.close()
        }
    }

    private fun deriveKey(source: File, salt: ByteArray, swfEntry: String): javax.crypto.SecretKey {
        val digest = MessageDigest.getInstance("SHA-512")
        ZipFile(source).use { zip ->
            val entries = listOf(swfEntry, "assets/CABConfig.xml", "classes.dex")
            for (name in entries) {
                val entry = zip.getEntry(name) ?: error("Source APK missing $name")
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                digest.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
                digest.update(bytes)
            }
        }
        val password = digest.digest().joinToString("") { "%02x".format(it) }.toCharArray()
        val spec = PBEKeySpec(password, salt, 120_000, 256)
        val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        password.fill('\u0000')
        return javax.crypto.spec.SecretKeySpec(encoded, "AES")
    }

    private fun DataInputStream.skipFully(count: Int) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skipBytes(remaining)
            check(skipped > 0) { "Truncated AT64 header" }
            remaining -= skipped
        }
    }
}
